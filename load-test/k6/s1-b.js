import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate } from 'k6/metrics';

// S1-b (17.3 2계층, 17.5 고정 부하): 200 VU × 각 5회 = 정확히 1,000번 시도.
// VU는 두 앱 중 하나에 고정 배정한다. 세션 기반 인증에서 LB가 없어도 sticky-session을 재현하고,
// 각 인스턴스가 정확히 100 VU / 500 주문 시도를 받게 한다.
const attempts = new Counter('order_attempts');
const successes = new Counter('order_successes');
const soldOut = new Counter('order_sold_out');
const app1Attempts = new Counter('order_app1_attempts');
const app2Attempts = new Counter('order_app2_attempts');
const serverErrors = new Rate('order_server_errors');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  setupTimeout: '2m',
  scenarios: {
    s1b: {
      executor: 'per-vu-iterations',
      vus: 200,
      iterations: 5,
      // 17.5: 주문 1,000건은 짧은 구간(30초) 안에 유입해야 한다.
      maxDuration: '30s',
    },
  },
  thresholds: {
    order_attempts: ['count==1000'],
    order_app1_attempts: ['count==500'],
    order_app2_attempts: ['count==500'],
    order_successes: ['count==100'],
    order_sold_out: ['count==900'],
    order_server_errors: ['rate<0.01'],
  },
};

const app1Url = __ENV.APP1_URL || 'http://localhost:58102';
const app2Url = __ENV.APP2_URL || 'http://localhost:58103';
const campaignId = __ENV.CAMPAIGN_ID;
const productSkuId = __ENV.PRODUCT_SKU_ID;
const password = __ENV.BUYER_PASSWORD || 'groupdrop123!';

function instanceForVu(vu) {
  return (vu % 2 === 1) ? { name: 'app1', url: app1Url } : { name: 'app2', url: app2Url };
}

export function setup() {
  if (!campaignId || !productSkuId) {
    fail('CAMPAIGN_ID and PRODUCT_SKU_ID are required');
  }
  if (app1Url === app2Url) {
    fail('APP1_URL and APP2_URL must point to different app instances');
  }

  const sessions = [];
  const jar = http.cookieJar();
  for (let vu = 1; vu <= 200; vu += 1) {
    const instance = instanceForVu(vu);
    const email = `buyer${String(vu).padStart(3, '0')}@groupdrop.test`;
    jar.clear(instance.url);
    const response = http.post(`${instance.url}/api/auth/login`, JSON.stringify({ email, password }), {
      headers: { 'Content-Type': 'application/json' },
    });
    const cookie = response.cookies.JSESSIONID && response.cookies.JSESSIONID[0];
    if (response.status !== 200 || !cookie) {
      fail(`login failed for ${email} on ${instance.name}: status=${response.status}`);
    }
    sessions.push(`JSESSIONID=${cookie.value}`);
  }
  return { sessions };
}

export default function (data) {
  const instance = instanceForVu(__VU);
  const payload = JSON.stringify({ items: [{ productSkuId: Number(productSkuId), quantity: 1 }] });
  const response = http.post(`${instance.url}/api/campaigns/${campaignId}/orders`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'Cookie': data.sessions[__VU - 1],
      'Idempotency-Key': `s1b-${campaignId}-${__VU}-${__ITER}`,
    },
    responseCallback: http.expectedStatuses(201, 409),
  });

  attempts.add(1);
  if (instance.name === 'app1') app1Attempts.add(1); else app2Attempts.add(1);

  const isSuccess = response.status === 201;
  const isServerError = response.status >= 500;
  let isSoldOut = false;
  if (response.status === 409) {
    try {
      isSoldOut = response.json('code') === 'INVENTORY_SOLD_OUT';
    } catch (_) {
      isSoldOut = false;
    }
  }
  if (isSuccess) successes.add(1);
  if (isSoldOut) soldOut.add(1);
  serverErrors.add(isServerError);

  check(response, {
    '201 success or inventory sold out': () => isSuccess || isSoldOut,
  });
}
