import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const attempts = new Counter('order_attempts');
const successes = new Counter('order_successes');
const soldOut = new Counter('order_sold_out');
const serverErrors = new Rate('order_server_errors');

export const options = {
  setupTimeout: '2m',
  scenarios: {
    s1a: {
      executor: 'per-vu-iterations',
      vus: 200,
      iterations: 5,
      maxDuration: '2m',
    },
  },
  thresholds: {
    order_attempts: ['count==1000'],
    order_successes: ['count==100'],
    order_sold_out: ['count==900'],
    order_server_errors: ['rate<0.01'],
  },
};

const appUrl = __ENV.APP_URL || 'http://localhost:58080';
const campaignId = __ENV.CAMPAIGN_ID;
const productSkuId = __ENV.PRODUCT_SKU_ID;
const password = __ENV.BUYER_PASSWORD || 'groupdrop123!';

export function setup() {
  if (!campaignId || !productSkuId) {
    fail('CAMPAIGN_ID and PRODUCT_SKU_ID are required');
  }
  const sessions = [];
  const jar = http.cookieJar();
  for (let i = 1; i <= 200; i += 1) {
    jar.clear(appUrl);
    const email = `buyer${String(i).padStart(3, '0')}@groupdrop.test`;
    const response = http.post(`${appUrl}/api/auth/login`, JSON.stringify({ email, password }), {
      headers: { 'Content-Type': 'application/json' },
    });
    const cookie = response.cookies.JSESSIONID && response.cookies.JSESSIONID[0];
    if (response.status !== 200 || !cookie) {
      fail(`login failed for ${email}: status=${response.status}`);
    }
    sessions.push(`JSESSIONID=${cookie.value}`);
  }
  return { sessions };
}

export default function (data) {
  const payload = JSON.stringify({ items: [{ productSkuId: Number(productSkuId), quantity: 1 }] });
  const response = http.post(`${appUrl}/api/campaigns/${campaignId}/orders`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'Cookie': data.sessions[__VU - 1],
      'Idempotency-Key': `s1a-${campaignId}-${__VU}-${__ITER}`,
    },
    responseCallback: http.expectedStatuses(201, 409),
  });

  attempts.add(1);
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
