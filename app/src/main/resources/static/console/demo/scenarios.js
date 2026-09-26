// scenarios.js — 장애 주입 시나리오 4종의 흐름·판정 로직 (프론트엔드 계획 §7.4, §8 6단계)
//
// 이 모듈은 DOM을 건드리지 않는다. 호출부(demo.js)가 ctx 객체로 다음을 주입한다:
//   ctx.api            — common/api.js의 api() (앱 API, 같은 출처)
//   ctx.pg              — demo/pg.js 모듈 전체 (mock-pg 전용, §7.2)
//   ctx.log(step, status, detail) — 타임라인에 한 줄 추가. status: 'progress'|'success'|'failure'|'warning'
//   ctx.checkAbort()    — "중단" 버튼이 눌렸으면 ScenarioAbort를 던진다. 단계 사이마다 호출한다 (§7.4).
//   ctx.snapshotCounters / ctx.diffCounters — metrics.js 재노출
//   ctx.options         — 시나리오별 입력 (아래 SCENARIO_DEFS 및 각 runX 참고)
//
// 판정은 네 가지: 'pass'(통과) | 'warn'(경고) | 'fail'(실패) | 'aborted'(중단). Verdict가 pass→warn→fail
// 순으로 최악값만 남기고, 판정 근거(기대 vs 실제)를 reasons[]에 문자열로 쌓는다.
//
// 카운터는 전역이라 다른 탭 활동이 섞일 수 있다 — 핵심 불변식(예: 주문당 결제 레코드 수)은 항상
// 리소스 조회로 판정하고, 카운터 Δ는 기대값과 같으면 pass, 크면 warn("다른 활동이 섞였을 수 있음"),
// 작으면 fail로 판정한다(시나리오별 예외는 각 함수 주석에 명시). 이 규칙이 judgeDeltaText()다.

import { uuid } from '../common/api.js';
import { messageFor } from '../common/codes.js';
import { fetchAppCounter } from './metrics.js';

/** "중단" 버튼 또는 되돌릴 수 없는 조작 전 confirm() 취소로 시나리오를 빠져나갈 때 던진다. */
export class ScenarioAbort extends Error {}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// --- 판정 누적기 ---------------------------------------------------------------------------

const LEVEL_ORDER = { pass: 0, warn: 1, fail: 2 };

class Verdict {
  constructor() {
    this.level = 'pass';
    this.reasons = [];
  }

  add(level, text) {
    this.reasons.push(text);
    if (LEVEL_ORDER[level] > LEVEL_ORDER[this.level]) this.level = level;
  }

  pass(text) { this.add('pass', text); }
  warn(text) { this.add('warn', text); }
  fail(text) { this.add('fail', text); }
  /** 판정 레벨은 바꾸지 않고 근거만 덧붙인다 — 실패·중단 뒤의 "다음 조치" 안내 등. */
  note(text) { this.reasons.push(text); }

  result(extra) {
    return { result: this.level, reasons: this.reasons, ...extra };
  }
}

/** 공통 Δ 판정 규칙: 같으면 pass, 크면 warn(다른 활동 혼입 가능성), 작으면(null 포함 취급 없음) fail.
 * 값을 못 읽었으면(actual === null) 판정을 내릴 수 없으므로 warn으로 접어 실행 자체를 막지 않는다. */
function judgeDeltaText(label, expected, actual) {
  if (actual === null || actual === undefined) {
    return { level: 'warn', text: `${label} Δ 확인 불가(카운터 조회 실패) — 기대 Δ${expected}` };
  }
  if (actual === expected) return { level: 'pass', text: `${label} Δ${actual} (기대 Δ${expected})` };
  if (actual > expected) return { level: 'warn', text: `${label} Δ${actual} (기대 Δ${expected} — 다른 활동이 섞였을 수 있음)` };
  return { level: 'fail', text: `${label} Δ${actual} (기대 Δ${expected} 미만)` };
}

function addDeltaJudgement(verdict, label, expected, actual) {
  const j = judgeDeltaText(label, expected, actual);
  verdict.add(j.level, j.text);
}

// --- 공용 폴링 헬퍼 -------------------------------------------------------------------------

/**
 * until(result)가 true가 될 때까지 intervalMs 간격으로 fn()을 반복한다. 매 tick 전에 중단을 확인한다.
 * failIf(result)가 있고 true를 돌려주면(예: 기대와 다른 최종 상태에 이미 도달) 타임아웃을 기다리지
 * 않고 즉시 { failedFast: true }로 빠진다 — 더 기다려도 결과가 바뀌지 않는 확정적 실패다.
 */
async function pollFor(ctx, { fn, until, failIf, intervalMs, timeoutMs }) {
  const start = Date.now();
  for (;;) {
    ctx.checkAbort();
    const result = await fn();
    if (until(result)) return { result, timedOut: false, failedFast: false };
    if (failIf && failIf(result)) return { result, timedOut: false, failedFast: true };
    if (Date.now() - start >= timeoutMs) return { result, timedOut: true, failedFast: false };
    await sleep(intervalMs);
  }
}

/** 값이 baseline 대비 expected 이상 증가할 때까지 최대 timeoutMs 폴링하고, 마지막으로 관측한 Δ를
 * 돌려준다(타임아웃이어도 마지막 값을 반환 — 판정은 호출부가 judgeDeltaText로 한다). */
async function pollDeltaAtLeast(ctx, { fetchValue, baseline, expected, timeoutMs = 15000, intervalMs = 1000 }) {
  const start = Date.now();
  let lastDelta = null;
  for (;;) {
    ctx.checkAbort();
    const current = await fetchValue();
    lastDelta = current === null || baseline === null || baseline === undefined ? null : current - baseline;
    if (lastDelta !== null && lastDelta >= expected) return lastDelta;
    if (Date.now() - start >= timeoutMs) return lastDelta;
    await sleep(intervalMs);
  }
}

// --- 결제 시나리오 공용: 주문 생성 (§7.4) ---------------------------------------------------

/**
 * 선택한 캠페인(OPEN)의 첫 SKU로 수량 1 주문을 만든다. 실패·비정상 상태는 verdict에 fail을 기록하고
 * null을 돌려준다(호출부는 null이면 즉시 verdict.result()로 빠진다) — 예외를 던지지 않는다, 주문
 * 생성 실패는 "판정"의 일부이지 스캐폴딩 오류가 아니기 때문이다.
 */
async function createOrderForScenario(ctx, campaignId, verdict) {
  ctx.log('주문 생성', 'progress');
  let order;
  try {
    const { data: campaign } = await ctx.api(`/api/campaigns/${campaignId}`);
    const sku = campaign.skus?.[0];
    if (!sku) throw new Error('선택한 캠페인에 SKU가 없습니다.');
    const { data } = await ctx.api(`/api/campaigns/${campaignId}/orders`, {
      method: 'POST',
      body: { items: [{ productSkuId: sku.productSkuId, quantity: 1 }] },
      idempotencyKey: uuid(),
    });
    order = data;
  } catch (err) {
    // §7.4: 409 PURCHASE_LIMIT_EXCEEDED는 전용 안내, INVENTORY_SOLD_OUT·그 외는 messageFor로.
    const message = err.code === 'PURCHASE_LIMIT_EXCEEDED'
      ? '인당 한도를 다 썼습니다 — buyer2로 로그인하거나 다른 캠페인을 고르세요'
      : messageFor(err);
    ctx.log('주문 생성', 'failure', message);
    verdict.fail(`주문 생성 실패: ${message}`);
    return null;
  }
  if (order.status !== 'PENDING_PAYMENT') {
    ctx.log('주문 생성', 'failure', `주문 상태 ${order.status} (기대 PENDING_PAYMENT)`);
    verdict.fail(`생성된 주문 상태 ${order.status} (기대 PENDING_PAYMENT)`);
    return null;
  }
  ctx.log('주문 생성', 'success', `주문 #${order.id}, 총액 ${order.totalAmount}원`);
  return order;
}

function payOnce(ctx, orderId, amount, idempotencyKey) {
  return ctx.api(`/api/orders/${orderId}/payments`, { method: 'POST', body: { amount }, idempotencyKey });
}

// --- 1. UNKNOWN 복구 (S4-a) — BUYER --------------------------------------------------------
//
// 202 응답의 providerPaymentId는 null이라 merchantPaymentId("mpay_" + payment.id)로 재발사 대상을
// 지정한다 — PaymentService#prepare("mpay_" + paymentId)에 결합된 서버 내부 규칙이다. 이 결합이
// 깨지면(접두사가 바뀌면) 이 시나리오도 바뀌어야 한다 (결정 로그 D-056).
async function runS4a(ctx) {
  const verdict = new Verdict();
  const { campaignId, useTimeoutDelay } = ctx.options;
  const delayMs = useTimeoutDelay ? 3500 : 0;
  let payment = null; // 202 응답을 받은 뒤부터 채워진다.
  // "결제가 UNKNOWN을 벗어났는지" — payment 존재 여부가 아니라 이 플래그가 안내 여부를 결정한다.
  // 202 UNKNOWN 확인 직후 true, 이후 어떤 경로로든(재확인·확정 대기에서) UNKNOWN을 벗어나면 false로
  // 되돌린다 — 이미 SUCCEEDED·FAILED로 해소된 뒤라면 "재발사 대상" 안내는 틀린 안내다.
  let stillUnknown = false;
  let guidanceAdded = false;

  const order = await createOrderForScenario(ctx, campaignId, verdict);
  if (!order) return verdict.result();

  /** 결제가 여전히 UNKNOWN으로 남은 채 실패·중단됐을 때만, 수동 복구 경로와 정산 영향을 한 번만 남긴다. */
  const addUnknownGuidanceIfNeeded = () => {
    if (!stillUnknown || guidanceAdded || !payment) return;
    guidanceAdded = true;
    verdict.note(`보류 웹훅 재발사 대상: merchantPaymentId=mpay_${payment.id} (아래 "수동 조작 → 웹훅 재발사"에서 시도할 수 있습니다)`);
    verdict.note('UNKNOWN 결제가 남아 있으면 이 캠페인의 정산이 막힙니다.');
  };

  try {
    ctx.checkAbort();

    ctx.log('장애 모드 적용', 'progress', `SUCCEED_BUT_TIMEOUT, blockWebhook=true, delayMs=${delayMs}`);
    await ctx.pg.setFailureMode({ mode: 'SUCCEED_BUT_TIMEOUT', blockWebhook: true, delayMs });
    ctx.log('장애 모드 적용', 'success');
    ctx.checkAbort();

    ctx.log('결제 요청', 'progress');
    let meta;
    try {
      const res = await payOnce(ctx, order.id, order.totalAmount, uuid());
      payment = res.data;
      meta = res.meta;
    } catch (err) {
      ctx.log('결제 요청', 'failure', messageFor(err));
      verdict.fail(`결제 요청이 예외로 실패했습니다: ${messageFor(err)}`);
      return verdict.result();
    }
    if (meta.status === 202 && payment.status === 'UNKNOWN') {
      ctx.log('결제 요청', 'success', `202 UNKNOWN, 결제 #${payment.id}`);
      stillUnknown = true;
    } else if (meta.status === 200 && payment.status === 'SUCCEEDED') {
      ctx.log('결제 요청', 'failure', '모드가 적용되지 않았습니다 — 다른 탭이 모드를 바꿨을 수 있습니다');
      verdict.fail('기대 202 UNKNOWN, 실제 200 SUCCEEDED — 모드가 적용되지 않았을 수 있습니다(다른 탭 간섭 의심)');
      return verdict.result();
    } else {
      ctx.log('결제 요청', 'failure', `기대와 다른 응답: ${meta.status} ${payment.status}`);
      verdict.fail(`기대 202 UNKNOWN, 실제 ${meta.status} ${payment.status}`);
      return verdict.result();
    }

    // §7.4: 모드를 바꾼 시나리오는 필요한 결제 호출이 끝나는 즉시 NORMAL로 되돌린다(오염 창 최소화).
    ctx.log('NORMAL 복구 (조기)', 'progress');
    await ctx.pg.resetNormal();
    ctx.log('NORMAL 복구 (조기)', 'success');
    ctx.checkAbort();

    ctx.log('증거 확인 — Δ', 'progress');
    const afterAttempt = await ctx.snapshotCounters();
    const diff = ctx.diffCounters(ctx.startSnapshot, afterAttempt);
    addDeltaJudgement(verdict, 'stats.webhookPendingCount', 1, diff?.pg?.webhookPendingCount ?? null);
    addDeltaJudgement(verdict, 'payment.unknown', 1, diff?.app?.['payment.unknown'] ?? null);
    ctx.log('증거 확인 — Δ', 'success');
    ctx.checkAbort();

    // 재발사 직전에 실제 상태를 다시 확인한다 — sync·대사 등 조회 경로가 먼저 확정해도 보류 큐는
    // 남아 있어 replayedCount=1이 나올 수 있다(=허위 통과). "재발사가 해소했다"고 주장하려면
    // 재발사 직전 상태가 여전히 UNKNOWN이어야 한다.
    ctx.log('재발사 전 상태 재확인', 'progress');
    const { data: preReplay } = await ctx.api(`/api/payments/${payment.id}`);
    if (preReplay.status !== 'UNKNOWN') {
      stillUnknown = false; // 이미 다른 경로로 벗어났다 — 재발사 안내는 무의미하다.
      ctx.log('재발사 전 상태 재확인', 'failure', `이미 ${preReplay.status}`);
      verdict.fail(`재발사 전에 이미 ${preReplay.status} — 조회·대사 경로로 해소됨(S4-a 아님, 운영자 탭에서 sync·대사를 누르지 않았는지 확인하세요)`);
      return verdict.result();
    }
    ctx.log('재발사 전 상태 재확인', 'success', 'UNKNOWN 유지');
    ctx.checkAbort();

    ctx.log('웹훅 재발사', 'progress', `merchantPaymentId=mpay_${payment.id}`);
    let replay;
    try {
      replay = await ctx.pg.replayWebhooks({ merchantPaymentId: `mpay_${payment.id}` });
    } catch (err) {
      ctx.log('웹훅 재발사', 'failure', err.message);
      verdict.fail(`웹훅 재발사 실패: ${err.message}`);
      return verdict.result();
    }
    if (replay.replayedCount === 1) {
      ctx.log('웹훅 재발사', 'success', 'replayedCount=1');
    } else if (replay.replayedCount === 0) {
      ctx.log('웹훅 재발사', 'failure', '보류 웹훅이 없습니다 — 이미 다른 경로(sync·대사)로 해소됐을 수 있습니다');
      verdict.fail('replayedCount=0 — 보류 웹훅이 없습니다(다른 경로로 이미 해소됐을 수 있음)');
      return verdict.result();
    } else {
      ctx.log('웹훅 재발사', 'warning', `replayedCount=${replay.replayedCount}`);
      verdict.warn(`replayedCount=${replay.replayedCount} (기대 1, 2건 이상은 경고)`);
    }
    ctx.checkAbort();

    // 진행 로그와 완료 로그가 같은 step 이름을 써야 타임라인이 제자리 갱신된다(item 1). 결제 확정
    // 서브이벤트는 이 단계를 끝내지 않고 detail만 더해 같은 행을 계속 갱신하는 대신, 완료로 한 번
    // 닫고 곧바로 같은 이름으로 다음 단계(주문 PAID 대기)를 다시 연다 — 어느 쪽도 progress로 남지
    // 않는다.
    const STEP = '결제·주문 확정 대기';
    ctx.log(STEP, 'progress', '최대 30초 (inbox+outbox 순차 — 명세상 최대 약 10초)');
    const start = Date.now();
    const timeoutMs = 30000;
    let paymentConfirmed = false;
    for (;;) {
      ctx.checkAbort();
      if (!paymentConfirmed) {
        const { data: p } = await ctx.api(`/api/payments/${payment.id}`);
        if (p.status !== 'UNKNOWN') {
          stillUnknown = false;
          if (p.status !== 'SUCCEEDED') {
            ctx.log(STEP, 'failure', `결제 최종 상태 ${p.status} (기대 SUCCEEDED)`);
            verdict.fail(`결제 최종 상태 ${p.status} (기대 SUCCEEDED)`);
            return verdict.result();
          }
          paymentConfirmed = true;
          ctx.log(STEP, 'success', 'SUCCEEDED — 주문 PAID 대기로 이어감');
          ctx.log(STEP, 'progress', '주문 PAID 대기 중');
        }
      } else {
        const { data: o } = await ctx.api(`/api/orders/${order.id}`);
        if (o.status === 'PAID') {
          ctx.log(STEP, 'success', '주문 PAID 확인');
          verdict.pass('결제 SUCCEEDED, 주문 PAID — UNKNOWN이 웹훅 재발사로 정상 복구되었습니다');
          return verdict.result();
        }
      }
      if (Date.now() - start >= timeoutMs) {
        ctx.log(STEP, 'failure',
          paymentConfirmed ? '주문이 30초 안에 PAID가 되지 않았습니다' : '결제가 30초 안에 확정되지 않았습니다');
        verdict.fail('30초 타임아웃 — 결제·주문 확정을 확인하지 못했습니다');
        return verdict.result();
      }
      await sleep(1000);
    }
  } catch (err) {
    if (err instanceof ScenarioAbort) {
      ctx.log('중단', 'warning', err.message || '사용자가 중단했습니다.');
      verdict.note(err.message || '사용자가 중단했습니다.');
      addUnknownGuidanceIfNeeded();
      return { result: 'aborted', reasons: verdict.reasons };
    }
    // 예기치 않은 예외 — 다시 던지면 runScenario가 새 reasons 배열로 감싸 지금까지 쌓인 안내가
    // 사라진다. 여기서 직접 fail로 접어 verdict.reasons(안내 포함)를 보존한다.
    ctx.log('예외', 'failure', err.message ?? String(err));
    verdict.fail(`예기치 않은 오류: ${err.message ?? String(err)}`);
    addUnknownGuidanceIfNeeded();
    return verdict.result();
  } finally {
    // try 안의 모든 명시적 실패 return(위에서 throw 없이 verdict.fail 후 return한 지점들)에도
    // 안내가 붙게 하는 안전망 — addUnknownGuidanceIfNeeded 자체가 stillUnknown·guidanceAdded로
    // 중복·오적용을 막으므로 조건 없이 불러도 안전하다.
    addUnknownGuidanceIfNeeded();
  }
}

// --- 2. 웹훅 중복·역순 (S3) — BUYER ---------------------------------------------------------
async function runS3(ctx) {
  const verdict = new Verdict();
  const { campaignId } = ctx.options;

  const order = await createOrderForScenario(ctx, campaignId, verdict);
  if (!order) return verdict.result();
  ctx.checkAbort();

  ctx.log('장애 모드 적용', 'progress', 'NORMAL + webhookDuplicateCount=3, webhookReverseOrder=true');
  await ctx.pg.setFailureMode({ mode: 'NORMAL', webhookDuplicateCount: 3, webhookReverseOrder: true });
  ctx.log('장애 모드 적용', 'success');
  ctx.checkAbort();

  ctx.log('결제 요청', 'progress');
  let payment;
  let meta;
  try {
    const res = await payOnce(ctx, order.id, order.totalAmount, uuid());
    payment = res.data;
    meta = res.meta;
  } catch (err) {
    ctx.log('결제 요청', 'failure', messageFor(err));
    verdict.fail(`결제 요청 실패: ${messageFor(err)}`);
    return verdict.result();
  }
  if (!(meta.status === 200 && payment.status === 'SUCCEEDED')) {
    ctx.log('결제 요청', 'failure', `기대 200 SUCCEEDED, 실제 ${meta.status} ${payment.status}`);
    verdict.fail(`기대 200 SUCCEEDED, 실제 ${meta.status} ${payment.status}`);
    return verdict.result();
  }
  ctx.log('결제 요청', 'success', `200 SUCCEEDED, 결제 #${payment.id}`);

  ctx.log('NORMAL 복구 (조기)', 'progress');
  await ctx.pg.resetNormal();
  ctx.log('NORMAL 복구 (조기)', 'success');
  ctx.checkAbort();

  ctx.log('주문 PAID 대기', 'progress', '최대 30초');
  const paidPoll = await pollFor(ctx, {
    fn: () => ctx.api(`/api/orders/${order.id}`),
    until: (r) => r.data.status === 'PAID',
    intervalMs: 1000,
    timeoutMs: 30000,
  });
  if (paidPoll.timedOut) {
    ctx.log('주문 PAID 대기', 'failure', `30초 타임아웃 (마지막 상태 ${paidPoll.result.data.status})`);
    verdict.fail('주문이 30초 안에 PAID가 되지 않았습니다');
    return verdict.result();
  }
  ctx.log('주문 PAID 대기', 'success', 'PAID 확인');
  ctx.checkAbort();

  // WebhookSender.java: send()는 @Async 없이 동기 RestClient 호출로 deliverNow()→notifyPaymentDecision()
  // 체인 안에서 직접 실행된다(WebhookSender.java:70-101) — 즉 mock-pg가 confirm 응답을 돌려주기 전에
  // 이미 웹훅 HTTP 호출까지 끝난다(응답 전 동기). 그래도 앱 쪽 webhook.duplicate 집계와의 미세한 시차에
  // 대비해 최대 15초 폴링 후 판정한다(§7.4 지시).
  ctx.log('증거 확인 — 웹훅 발송 Δ', 'progress', '최대 15초 폴링 (WebhookSender.java 확인 — 응답 전 동기 발송)');
  const startPg = ctx.startSnapshot?.pg;
  const sentDelta = await pollDeltaAtLeast(ctx, {
    fetchValue: async () => {
      try {
        return (await ctx.pg.stats()).webhookSentCount;
      } catch {
        return null;
      }
    },
    baseline: startPg?.webhookSentCount ?? null,
    expected: 4,
  });
  addDeltaJudgement(verdict, 'stats.webhookSentCount', 4, sentDelta);
  ctx.log('증거 확인 — 웹훅 발송 Δ', 'success', `Δ${sentDelta ?? '확인 불가'}`);
  ctx.checkAbort();

  ctx.log('증거 확인 — webhook.duplicate Δ', 'progress', '최대 15초 폴링');
  const startApp = ctx.startSnapshot?.app;
  const dupDelta = await pollDeltaAtLeast(ctx, {
    fetchValue: () => fetchAppCounter('webhook.duplicate'),
    baseline: startApp?.['webhook.duplicate'] ?? null,
    expected: 2,
  });
  addDeltaJudgement(verdict, 'webhook.duplicate', 2, dupDelta);
  ctx.log('증거 확인 — webhook.duplicate Δ', 'success', `Δ${dupDelta ?? '확인 불가'}`);
  ctx.checkAbort();

  ctx.log('불변식 확인', 'progress', 'GET /api/orders/{id}/payments');
  const { data: payments } = await ctx.api(`/api/orders/${order.id}/payments`);
  if (payments.length !== 1) {
    ctx.log('불변식 확인', 'failure', `결제 레코드 ${payments.length}건 (기대 정확히 1건)`);
    verdict.fail(`결제 레코드 ${payments.length}건 (기대 정확히 1건)`);
    return verdict.result();
  }
  if (payments[0].status !== 'SUCCEEDED') {
    ctx.log('불변식 확인', 'failure', `결제 상태 ${payments[0].status} (기대 SUCCEEDED)`);
    verdict.fail(`결제 상태 ${payments[0].status} (기대 SUCCEEDED)`);
    return verdict.result();
  }
  ctx.log('불변식 확인', 'success', '결제 레코드 1건, SUCCEEDED');
  ctx.checkAbort();

  // 역순 웹훅(과거 시각 PROCESSING, 다른 eventId)이 나중에 도착해 상태를 되돌리지 않는지 확인한다.
  // inbox 폴링 주기가 5초이므로 그보다 약간 더(6초) 기다린 뒤 다시 읽는다.
  ctx.log('역순 웹훅 안정성 재확인', 'progress', 'inbox 폴링 주기(5초)보다 약간 더(6초) 대기 후 재조회');
  await sleep(6000);
  ctx.checkAbort();
  const { data: paymentAfterWait } = await ctx.api(`/api/payments/${payments[0].id}`);
  if (paymentAfterWait.status !== 'SUCCEEDED') {
    ctx.log('역순 웹훅 안정성 재확인', 'failure', `6초 후 상태 ${paymentAfterWait.status} (기대 SUCCEEDED 유지)`);
    verdict.fail(`6초 대기 후 결제 상태 ${paymentAfterWait.status} — 역순 PROCESSING 웹훅이 상태를 되돌렸을 수 있습니다(기대 SUCCEEDED 유지)`);
    return verdict.result();
  }
  ctx.log('역순 웹훅 안정성 재확인', 'success', 'SUCCEEDED 유지');
  verdict.pass('웹훅 중복·역순이 무해하게 무시되고 결제는 1건 SUCCEEDED로 유지되었습니다');

  const port = location.port ? `:${location.port}` : '';
  const opBase = `${location.protocol}//127.0.0.1${port}`;
  return verdict.result({
    links: [
      { label: '운영자 탭 — 이벤트 (inbox IGNORED 보통 2건 — 워커가 요청 경로보다 먼저 처리하면 1건)', href: `${opBase}/console/admin/events.html` },
      { label: '운영자 탭 — 원장 (이 주문의 PAYMENT 거래 1건 확인)', href: `${opBase}/console/admin/ledger.html?orderId=${order.id}` },
    ],
  });
}

// --- 3. 중복 결제 차단 (S2) — BUYER ----------------------------------------------------------

function describeSettled(settled) {
  if (settled.status === 'fulfilled') {
    const { data, meta } = settled.value;
    return { kind: 'success', httpStatus: meta.status, paymentId: data.id, paymentStatus: data.status,
      summary: `${meta.status} ${data.status} (id=${data.id})` };
  }
  const err = settled.reason;
  const code = err?.code ?? 'UNKNOWN_ERROR';
  const httpStatus = typeof err?.status === 'number' ? err.status : null;
  return { kind: 'error', httpStatus, code, summary: `${httpStatus ?? '?'} ${code}` };
}

/** 기대하는 두 패턴 모두 "성공 쪽"이 정확히 200 SUCCEEDED여야 한다 — 202 UNKNOWN·409 SUPERSEDED
 * 바디처럼 api()가 던지지 않는 다른 응답까지 "성공"으로 뭉뚱그리면 잘못된 결과를 통과시킬 수 있다. */
function isExpectedSuccess(r) {
  return r.kind === 'success' && r.httpStatus === 200 && r.paymentStatus === 'SUCCEEDED';
}

function classifyDuplicateResults([a, b]) {
  const successes = [a, b].filter(isExpectedSuccess);
  const errors = [a, b].filter((r) => r.kind === 'error');
  if (successes.length === 1 && errors.length === 1 && errors[0].code === 'IDEMPOTENCY_REQUEST_IN_PROGRESS') {
    return { matched: 'in-progress-pair' };
  }
  if (successes.length === 2 && successes[0].paymentId === successes[1].paymentId) {
    return { matched: 'replayed-pair', paymentId: successes[0].paymentId };
  }
  return { matched: 'unexpected' };
}

async function runS2(ctx) {
  const verdict = new Verdict();
  const { campaignId } = ctx.options;

  const order = await createOrderForScenario(ctx, campaignId, verdict);
  if (!order) return verdict.result();
  ctx.checkAbort();

  ctx.log('장애 모드 적용', 'progress', 'DELAY delayMs=1500');
  await ctx.pg.setFailureMode({ mode: 'DELAY', delayMs: 1500 });
  ctx.log('장애 모드 적용', 'success');
  ctx.checkAbort();

  const key = uuid();
  ctx.log('동시 결제 2회', 'progress', '같은 Idempotency-Key로 Promise.allSettled');
  const settled = await Promise.allSettled([
    payOnce(ctx, order.id, order.totalAmount, key),
    payOnce(ctx, order.id, order.totalAmount, key),
  ]);

  ctx.log('NORMAL 복구 (조기)', 'progress');
  await ctx.pg.resetNormal();
  ctx.log('NORMAL 복구 (조기)', 'success');

  const results = settled.map(describeSettled);
  ctx.log('동시 결제 2회', 'success', results.map((r) => r.summary).join(' / '));
  ctx.checkAbort();

  const classification = classifyDuplicateResults(results);
  if (classification.matched === 'in-progress-pair') {
    verdict.pass(`기대 결과: 200 SUCCEEDED 1건 + 409 IDEMPOTENCY_REQUEST_IN_PROGRESS 1건 (${results.map((r) => r.summary).join(', ')})`);
  } else if (classification.matched === 'replayed-pair') {
    verdict.pass(`지연이 모자라 재생 응답 — 200 2건, 같은 payment.id=${classification.paymentId} (정상)`);
  } else {
    ctx.log('결과 분류', 'failure', results.map((r) => r.summary).join(', '));
    verdict.fail(`예상 밖 결과: ${results.map((r) => r.summary).join(', ')}`);
    if (results.some((r) => r.kind === 'success' && r.paymentStatus === 'UNKNOWN')) {
      verdict.note('결과에 202 UNKNOWN이 섞여 있습니다 — 다른 탭이 mock-pg 모드를 바꿨을 수 있습니다.');
    }
    return verdict.result();
  }
  ctx.checkAbort();

  ctx.log('불변식 확인', 'progress', 'GET /api/orders/{id}/payments');
  const { data: payments } = await ctx.api(`/api/orders/${order.id}/payments`);
  if (payments.length !== 1) {
    ctx.log('불변식 확인', 'failure', `결제 레코드 ${payments.length}건 (기대 정확히 1건)`);
    verdict.fail(`결제 레코드 ${payments.length}건 (기대 정확히 1건)`);
    return verdict.result();
  }
  ctx.log('불변식 확인', 'success', '결제 레코드 1건');
  ctx.checkAbort();

  ctx.log('증거 확인 — Δ', 'progress');
  const after = await ctx.snapshotCounters();
  const diff = ctx.diffCounters(ctx.startSnapshot, after);
  addDeltaJudgement(verdict, 'stats.confirmRequestCount', 1, diff?.pg?.confirmRequestCount ?? null);
  addDeltaJudgement(verdict, 'payment.duplicate.prevented', 1, diff?.app?.['payment.duplicate.prevented'] ?? null);
  ctx.log('증거 확인 — Δ', 'success');
  ctx.checkAbort();

  ctx.log('참고: 주문 PAID 대기', 'progress', '최대 30초 (참고용 — 판정에 직접 반영하지 않음)');
  const paidPoll = await pollFor(ctx, {
    fn: () => ctx.api(`/api/orders/${order.id}`),
    until: (r) => r.data.status === 'PAID',
    intervalMs: 1000,
    timeoutMs: 30000,
  });
  if (paidPoll.timedOut) {
    ctx.log('참고: 주문 PAID 대기', 'warning', '30초 타임아웃 (참고용)');
    verdict.warn('주문이 30초 안에 PAID가 되지 않았습니다(참고용, 판정에 직접 반영하지 않음)');
  } else {
    ctx.log('참고: 주문 PAID 대기', 'success', 'PAID 확인');
  }
  return verdict.result();
}

// --- 4. 대사 불일치 → 정산 보류 (S7, SET-02) — ADMIN ----------------------------------------

function summarizeBatches(batches) {
  return batches.map((b) => `${b.payeeType}#${b.id}:${b.status}`).join(', ') || '(없음)';
}

async function runS7(ctx) {
  const verdict = new Verdict();
  const { campaignId, campaignName, orderId, orderAmount } = ctx.options;
  let campaignClosed = false; // ③ 성공 이후에만 true — 실패·중단 안내(item 9)가 이 값을 본다.

  /** ③ 이후(되돌릴 수 없는 조작 이후) 실패·중단이면 운영자 화면으로 이어지는 안내·링크를 덧붙인다. */
  const postCloseExtra = () => {
    if (!campaignClosed) return {};
    verdict.note('운영자 대사·정산 화면에서 남은 불일치를 해결하고 배치를 해제하세요.');
    return {
      links: [
        { label: '운영자 탭 — 대사 (남은 불일치 해결)', href: '/console/admin/reconciliation.html' },
        { label: '운영자 탭 — 정산 (배치 상태 확인·해제)', href: `/console/admin/settlements.html?campaignId=${campaignId}` },
      ],
    };
  };

  try {
    // 시작 전 경고: minAgeMinutes=0 대사가 UNKNOWN 결제를 조회 경로로 먼저 확정해버릴 수 있다.
    let opsSummary = null;
    try {
      const { data } = await ctx.api('/api/admin/ops/summary');
      opsSummary = data;
    } catch (err) {
      ctx.log('사전 확인 (운영 요약)', 'warning', `운영 요약을 불러오지 못했습니다: ${messageFor(err)} — 확인 없이 진행합니다`);
    }
    if (opsSummary && opsSummary.unknownPaymentCount > 0) {
      const proceedOps = window.confirm(
        `minAgeMinutes 0 대사가 UNKNOWN 결제(${opsSummary.unknownPaymentCount}건)를 조회 경로로 먼저 확정합니다. 계속할까요?`);
      if (!proceedOps) throw new ScenarioAbort('사전 확인(UNKNOWN 결제 존재)에서 사용자가 취소했습니다.');
    }

    // 되돌릴 수 없는 조작(주입·강제 종료) 전 확인.
    const proceed = window.confirm(
      `캠페인 "${campaignName}"(#${campaignId})의 주문 #${orderId}(금액 ${orderAmount}원)에 불일치 거래를 주입하고 강제 종료합니다.\n` +
      '되돌릴 수 없습니다(주입 거래는 mock-pg 메모리에 남고 삭제 API가 없습니다). 계속할까요?');
    if (!proceed) throw new ScenarioAbort('실행 확인에서 사용자가 취소했습니다.');
    ctx.checkAbort();

    ctx.log('① 불일치 거래 주입', 'progress', `orderId=${orderId}, amount=${orderAmount}, status=SUCCEEDED`);
    let injected;
    try {
      injected = await ctx.pg.injectTransaction({ orderId, amount: orderAmount, status: 'SUCCEEDED' });
    } catch (err) {
      ctx.log('① 불일치 거래 주입', 'failure', err.message);
      verdict.fail(`거래 주입 실패: ${err.message}`);
      return verdict.result(postCloseExtra());
    }
    ctx.log('① 불일치 거래 주입', 'success', `providerPaymentId=${injected.providerPaymentId}`);
    ctx.checkAbort();

    ctx.log('② 대사 실행', 'progress', 'minAgeMinutes=0');
    let run;
    try {
      const { data } = await ctx.api('/api/admin/reconciliations', { method: 'POST', body: { minAgeMinutes: 0 } });
      run = data;
    } catch (err) {
      ctx.log('② 대사 실행', 'failure', messageFor(err));
      verdict.fail(err.code === 'RECONCILIATION_ALREADY_RUNNING'
        ? '409 RECONCILIATION_ALREADY_RUNNING — 이미 실행 중인 대사가 있습니다'
        : `대사 실행 실패: ${messageFor(err)}`);
      return verdict.result(postCloseExtra());
    }
    ctx.log('② 대사 실행', 'success', `run #${run.id}, mismatchCount=${run.mismatchCount}`);
    ctx.checkAbort();

    ctx.log('② 불일치 유형 확인', 'progress');
    const { data: openDiscrepancies1 } = await ctx.api('/api/admin/reconciliation-discrepancies?status=OPEN');
    const targetDiscrepancies = openDiscrepancies1.filter((d) => d.orderId === orderId);
    const types = new Set(targetDiscrepancies.map((d) => d.type));
    const expectedTypes = ['MISSING_INTERNAL', 'DUPLICATE_PAYMENT'];
    const typesMatch = types.size === expectedTypes.length && expectedTypes.every((t) => types.has(t));
    if (!typesMatch) {
      const actualTypes = [...types].join(', ') || '(없음)';
      ctx.log('② 불일치 유형 확인', 'failure', `실제 유형: {${actualTypes}}`);
      verdict.fail(`불일치 유형 기대 {MISSING_INTERNAL, DUPLICATE_PAYMENT}, 실제 {${actualTypes}}`);
      return verdict.result(postCloseExtra());
    }
    ctx.log('② 불일치 유형 확인', 'success', `${targetDiscrepancies.length}건: {${[...types].join(', ')}}`);
    ctx.checkAbort();

    ctx.log('③ 캠페인 강제 종료', 'progress');
    let closedCampaign;
    try {
      const { data } = await ctx.api(`/api/admin/campaigns/${campaignId}/cancel`, { method: 'POST' });
      closedCampaign = data;
    } catch (err) {
      ctx.log('③ 캠페인 강제 종료', 'failure', messageFor(err));
      verdict.fail(`캠페인 강제 종료 실패: ${messageFor(err)}`);
      return verdict.result(postCloseExtra());
    }
    if (closedCampaign.status !== 'CLOSED') {
      ctx.log('③ 캠페인 강제 종료', 'failure', `상태 ${closedCampaign.status} (기대 CLOSED)`);
      verdict.fail(`캠페인 상태 ${closedCampaign.status} (기대 CLOSED)`);
      return verdict.result(postCloseExtra());
    }
    campaignClosed = true; // 이 시점부터 되돌릴 수 없다 — 이후 실패·중단은 운영자 화면 안내를 덧붙인다.
    ctx.log('③ 캠페인 강제 종료', 'success', 'CLOSED');
    ctx.checkAbort();

    ctx.log('④ 배치 HELD 대기', 'progress', '최대 60초 — SUPPLIER·INFLUENCER 배치 2개');
    const NON_HELD_FINAL = new Set(['COMPLETED', 'FAILED']);
    const heldPoll = await pollFor(ctx, {
      fn: () => ctx.api(`/api/admin/settlements?campaignId=${campaignId}`),
      until: (r) => {
        const s = r.data.find((b) => b.payeeType === 'SUPPLIER');
        const i = r.data.find((b) => b.payeeType === 'INFLUENCER');
        return !!s && !!i && s.status === 'HELD' && i.status === 'HELD';
      },
      // HELD가 아닌 최종 상태(COMPLETED·FAILED)로 이미 가버렸다면 더 기다려도 HELD가 되지 않는다 —
      // 타임아웃까지 기다리지 않고 즉시 실패로 빠진다.
      failIf: (r) => r.data.some((b) => (b.payeeType === 'SUPPLIER' || b.payeeType === 'INFLUENCER') && NON_HELD_FINAL.has(b.status)),
      intervalMs: 2000,
      timeoutMs: 60000,
    });
    const latestBatches = heldPoll.result.data;
    if (heldPoll.failedFast) {
      ctx.log('④ 배치 HELD 대기', 'failure', `보류 없이 최종 상태로 진행됨 — ${summarizeBatches(latestBatches)}`);
      verdict.fail(`배치가 보류(HELD) 없이 최종 상태로 넘어갔습니다(보류가 걸리지 않음): ${summarizeBatches(latestBatches)}`);
      return verdict.result(postCloseExtra());
    }
    if (heldPoll.timedOut) {
      ctx.log('④ 배치 HELD 대기', 'failure', `60초 타임아웃 — 현재 ${summarizeBatches(latestBatches)}`);
      verdict.fail(`60초 안에 두 배치 모두 HELD가 되지 않았습니다: ${summarizeBatches(latestBatches)}`);
      return verdict.result(postCloseExtra());
    }
    const supplierBatch = latestBatches.find((b) => b.payeeType === 'SUPPLIER');
    const influencerBatch = latestBatches.find((b) => b.payeeType === 'INFLUENCER');

    // holdReason이 대사 불일치 때문인지 확인한다 — 다른 사유(예: 운영자가 수동으로 건 보류)로 HELD가
    // 됐다면 이 시나리오가 만든 보류가 아니다.
    const HOLD_REASON_PREFIX = '미해결 대사 불일치';
    const badHoldReasonBatch = [supplierBatch, influencerBatch]
      .find((b) => !(b.holdReason ?? '').startsWith(HOLD_REASON_PREFIX));
    if (badHoldReasonBatch) {
      ctx.log('④ 배치 HELD 대기', 'failure',
        `${badHoldReasonBatch.payeeType}#${badHoldReasonBatch.id} holdReason="${badHoldReasonBatch.holdReason ?? '-'}" (기대 접두 "${HOLD_REASON_PREFIX}")`);
      verdict.fail(`배치 holdReason이 "${HOLD_REASON_PREFIX}"로 시작하지 않습니다 — `
        + `${badHoldReasonBatch.payeeType}#${badHoldReasonBatch.id}: "${badHoldReasonBatch.holdReason ?? '-'}"`);
      return verdict.result(postCloseExtra());
    }
    ctx.log('④ 배치 HELD 대기', 'success',
      `HELD 확인 — SUPPLIER #${supplierBatch.id} (${supplierBatch.holdReason ?? '-'}), INFLUENCER #${influencerBatch.id} (${influencerBatch.holdReason ?? '-'})`);
    ctx.checkAbort();

    // holdReason에 적힌 불일치 건수("미해결 대사 불일치 N건")를 뽑아, 이후 ⑤에서 실제로 찾은 해결
    // 대상 건수와 대조한다(다르면 경고 — 결제 없는 주문의 불일치는 결제 목록 기반 집합에서 빠질 수 있다).
    const holdReasonMatch = /(\d+)\s*건/.exec(supplierBatch.holdReason ?? '');
    const holdReasonCount = holdReasonMatch ? Number(holdReasonMatch[1]) : null;

    ctx.log('⑤ 불일치 일괄 해결', 'progress');
    const allStatuses = 'READY,PROCESSING,SUCCEEDED,FAILED,UNKNOWN,SUPERSEDED,REFUNDING,REFUNDED';
    const { data: campaignPayments } = await ctx.api(`/api/admin/payments?campaignId=${campaignId}&status=${allStatuses}`);
    const campaignOrderIds = new Set(campaignPayments.map((p) => p.orderId));
    const { data: openDiscrepancies2 } = await ctx.api('/api/admin/reconciliation-discrepancies?status=OPEN');
    const toResolve = openDiscrepancies2.filter((d) => d.orderId != null && campaignOrderIds.has(d.orderId));

    if (holdReasonCount !== null && holdReasonCount !== toResolve.length) {
      verdict.warn(`holdReason의 불일치 건수(${holdReasonCount}건)와 실제로 찾은 해결 대상(${toResolve.length}건)이 다릅니다 — `
        + '결제 없는 주문의 불일치는 결제 목록 기반 집합에서 빠질 수 있습니다');
    }

    let resolvedCount = 0;
    let alreadyResolvedCount = 0;
    for (const d of toResolve) {
      ctx.checkAbort();
      try {
        // eslint-disable-next-line no-await-in-loop -- 해결은 건별 순차 처리, 실패해도 나머지는 계속한다.
        await ctx.api(`/api/admin/reconciliation-discrepancies/${d.id}/resolve`, {
          method: 'POST',
          body: { note: '장애 주입 데모: 주입 거래 확인 후 해소' },
        });
        resolvedCount += 1;
      } catch (err) {
        // 409 DISCREPANCY_NOT_OPEN(ReconciliationOpsService.java:148) — 다른 경로(재처리 등)로 이미
        // OPEN을 벗어났다는 뜻이다. 이 시나리오가 막을 일이 아니므로 "이미 해결됨"으로 간주하고
        // 진행 건수에 포함한다.
        if (err.code === 'DISCREPANCY_NOT_OPEN') {
          resolvedCount += 1;
          alreadyResolvedCount += 1;
        } else {
          ctx.log('⑤ 불일치 일괄 해결', 'warning', `#${d.id} 해결 실패: ${messageFor(err)}`);
        }
      }
    }
    if (alreadyResolvedCount > 0) {
      verdict.note(`불일치 ${alreadyResolvedCount}건은 이미 OPEN이 아니었습니다(409 DISCREPANCY_NOT_OPEN) — 이미 해결된 것으로 간주했습니다`);
    }
    if (toResolve.length === 0) {
      ctx.log('⑤ 불일치 일괄 해결', 'failure', '해결할 OPEN 불일치를 찾지 못했습니다(캠페인 주문 집합과 겹치는 불일치 없음)');
      verdict.fail('해결할 OPEN 불일치가 없습니다 — ②에서 만든 불일치가 이 캠페인의 결제와 연결되지 않았을 수 있습니다');
      return verdict.result(postCloseExtra());
    }
    // 일부라도 해결에 실패했으면 해제 전에 멈춘다 — 순서 규칙(모두 해결 → 그다음 해제)을 지키기 위해서다.
    if (resolvedCount < toResolve.length) {
      ctx.log('⑤ 불일치 일괄 해결', 'failure', `${resolvedCount}/${toResolve.length}건만 해결됨 — 해제를 진행하지 않습니다`);
      verdict.fail(`불일치 ${toResolve.length}건 중 ${resolvedCount}건만 해결되어 배치 해제를 진행하지 않습니다`);
      return verdict.result(postCloseExtra());
    }
    ctx.log('⑤ 불일치 일괄 해결', 'success', `${resolvedCount}/${toResolve.length}건 해결`);
    ctx.checkAbort();

    // 순서가 중요하다: 모든 불일치 해결 → 그다음 해제 (한 건만 해결하고 해제하면 재검증에서 다시 HELD).
    ctx.log('⑥ 배치 일괄 해제', 'progress');
    const heldBatches = [supplierBatch, influencerBatch].filter((b) => b.status === 'HELD');
    let releasedCount = 0;
    for (const b of heldBatches) {
      ctx.checkAbort();
      try {
        // eslint-disable-next-line no-await-in-loop -- 배치 2개뿐, 순차 처리로 충분하다.
        const { data } = await ctx.api(`/api/admin/settlements/${b.id}/release`, { method: 'POST' });
        if (data.status === 'PENDING') releasedCount += 1;
        else ctx.log('⑥ 배치 일괄 해제', 'warning', `#${b.id} 해제 후 상태 ${data.status} (기대 PENDING)`);
      } catch (err) {
        ctx.log('⑥ 배치 일괄 해제', 'warning', `#${b.id} 해제 실패: ${messageFor(err)}`);
      }
    }
    ctx.log('⑥ 배치 일괄 해제', releasedCount === heldBatches.length ? 'success' : 'warning',
      `${releasedCount}/${heldBatches.length}건 PENDING 전환`);
    if (releasedCount < heldBatches.length) {
      verdict.fail('일부 배치를 해제하지 못했습니다(HELD → PENDING 실패)');
      return verdict.result(postCloseExtra());
    }
    ctx.checkAbort();

    ctx.log('⑦ 완료 대기', 'progress', '최대 90초 — 배치 2건 COMPLETED + 캠페인 SETTLED');
    const completedPoll = await pollFor(ctx, {
      fn: async () => {
        const [batchesRes, campaignRes] = await Promise.all([
          ctx.api(`/api/admin/settlements?campaignId=${campaignId}`),
          ctx.api(`/api/campaigns/${campaignId}`),
        ]);
        return { batches: batchesRes.data, campaign: campaignRes.data };
      },
      until: (r) => {
        const s = r.batches.find((b) => b.payeeType === 'SUPPLIER');
        const i = r.batches.find((b) => b.payeeType === 'INFLUENCER');
        return !!s && !!i && s.status === 'COMPLETED' && i.status === 'COMPLETED' && r.campaign.status === 'SETTLED';
      },
      // 재검증에서 다시 HELD가 되면 더 기다려도 소용없다 — 즉시 실패로 빠진다(90초를 기다리지 않는다).
      failIf: (r) => r.batches.some((b) => b.status === 'HELD'),
      intervalMs: 3000,
      timeoutMs: 90000,
    });
    if (completedPoll.failedFast) {
      const { batches } = completedPoll.result;
      ctx.log('⑦ 완료 대기', 'failure', `재검증에서 다시 HELD: ${summarizeBatches(batches)}`);
      verdict.fail(`재검증에서 배치가 다시 HELD로 돌아갔습니다 — 남은 불일치를 확인하세요: ${summarizeBatches(batches)}`);
      return verdict.result(postCloseExtra());
    }
    if (completedPoll.timedOut) {
      const { batches, campaign } = completedPoll.result;
      ctx.log('⑦ 완료 대기', 'failure', `90초 타임아웃 — 배치 ${summarizeBatches(batches)}, 캠페인 ${campaign.status}`);
      verdict.fail(`90초 타임아웃 — 배치 ${summarizeBatches(batches)}, 캠페인 상태 ${campaign.status}`);
      return verdict.result(postCloseExtra());
    }
    ctx.log('⑦ 완료 대기', 'success', '완료 확인 — 배치 2건 COMPLETED, 캠페인 SETTLED');
    verdict.pass('불일치 2건이 해소되고 배치 2개가 COMPLETED, 캠페인이 SETTLED로 완료되었습니다');
    return verdict.result();
  } catch (err) {
    if (err instanceof ScenarioAbort) {
      ctx.log('중단', 'warning', err.message || '사용자가 중단했습니다.');
      verdict.note(err.message || '사용자가 중단했습니다.');
      return { result: 'aborted', reasons: verdict.reasons, ...postCloseExtra() };
    }
    // 예기치 않은 예외를 다시 던지면 runScenario가 새 reasons 배열로 감싸 postCloseExtra 안내(운영자
    // 대사·정산 화면 링크)가 사라진다. 여기서 직접 fail로 접어 verdict.reasons를 보존한다(item 2와
    // 같은 패턴).
    ctx.log('예외', 'failure', err.message ?? String(err));
    verdict.fail(`예기치 않은 오류: ${err.message ?? String(err)}`);
    return verdict.result(postCloseExtra());
  }
}

// --- 등록·디스패치 --------------------------------------------------------------------------

export const SCENARIO_DEFS = [
  {
    key: 's4a',
    title: 'UNKNOWN 복구 (S4-a)',
    role: 'BUYER',
    summary: '결제가 202 UNKNOWN으로 확정 보류된 뒤, 웹훅 재발사로 SUCCEEDED·PAID까지 복구됩니다.',
    warningNote: '실행 중 운영자 탭에서 sync·대사를 누르지 마세요 — 조회 경로로 먼저 해소되면 이 시나리오(웹훅 재발사 경로)를 '
      + '관찰할 수 없습니다. 완료되면 운영자 탭 요약 헤더의 "확정 대기 결제"가 0으로 돌아옵니다.',
    hasTimeoutOption: true,
  },
  {
    key: 's3',
    title: '웹훅 중복·역순 (S3)',
    role: 'BUYER',
    summary: '같은 결제에 중복(3회)·역순(과거 시각 PROCESSING) 웹훅이 더 와도 결제는 1건으로만 확정됩니다.',
  },
  {
    key: 's2',
    title: '중복 결제 차단 (S2)',
    role: 'BUYER',
    summary: '같은 Idempotency-Key로 결제 2회를 동시에 보내도 실제 PG 승인은 1건만 나갑니다.',
  },
  {
    key: 's7',
    title: '대사 불일치 → 정산 보류 (S7, SET-02)',
    role: 'ADMIN',
    summary: '불일치 거래 주입 → 대사(2건 분류) → 캠페인 강제 종료 → 배치 2개 HELD → 일괄 해소·해제 → 완료(COMPLETED·SETTLED).',
    warningNote: '전제: 시연 스택(정산 유예 0s), seed-demo.sh가 만든 캠페인 B의 PAID 주문 1건, '
      + '공급 단가·커미션이 0이 아닌 캠페인(배치 2개 전제 — 수령 주체 합계가 0이면 정산 배치 자체가 만들어지지 않습니다). '
      + '①~④는 30분 안에 끝내세요 — 주입 거래는 mock-pg 메모리에 남아 30분 주기 자동 대사가 새 OPEN 불일치로 다시 등록합니다'
      + '(시연 후 되살아나는 것은 정상). 초기화는 down -v 후 재기동뿐입니다.',
    irreversible: true,
  },
];

const RUNNERS = { s4a: runS4a, s3: runS3, s2: runS2, s7: runS7 };

/**
 * 공통 규칙(§7.4)을 감싼 진입점: 시작 전 스냅숏·NORMAL 적용·보류 웹훅 경고, 종료 시 성공·실패·중단
 * 모두 NORMAL 복구(try/finally). ctx.options는 시나리오별 입력을 담아 그대로 전달한다.
 */
export async function runScenario(key, ctx) {
  if (!RUNNERS[key]) throw new Error(`알 수 없는 시나리오: ${key}`);

  ctx.log('사전 준비', 'progress', '카운터 스냅숏 → NORMAL 적용');
  let startSnapshot = null;
  try {
    startSnapshot = await ctx.snapshotCounters();
  } catch (err) {
    ctx.log('사전 준비', 'warning', `카운터 스냅숏 실패: ${err.message ?? err}`);
  }

  try {
    await ctx.pg.resetNormal();
  } catch (err) {
    ctx.log('사전 준비', 'failure', `NORMAL 적용 실패: ${err.message}`);
    return { result: 'fail', reasons: [`시작 전 NORMAL 적용 실패 — mock-pg 연결을 확인하세요: ${err.message}`] };
  }

  if (startSnapshot?.pg && startSnapshot.pg.webhookPendingCount > 0) {
    ctx.log('사전 준비', 'warning', `이전 실행의 보류 웹훅이 있습니다 (webhookPendingCount=${startSnapshot.pg.webhookPendingCount})`);
  }
  ctx.log('사전 준비', 'success');
  // demo.js가 증거 카운터 패널에 같은 스냅숏을 기준으로 실시간 Δ를 보여줄 수 있게 알린다(선택 훅 —
  // 중복 스냅숏 호출을 피한다).
  ctx.onStartSnapshot?.(startSnapshot);

  const scenarioCtx = { ...ctx, startSnapshot };
  let outcome;
  try {
    outcome = await RUNNERS[key](scenarioCtx);
  } catch (err) {
    if (err instanceof ScenarioAbort) {
      ctx.log('중단', 'warning', err.message || '사용자가 중단했습니다.');
      outcome = { result: 'aborted', reasons: [err.message || '사용자가 중단했습니다.'] };
    } else {
      ctx.log('예외', 'failure', err.message ?? String(err));
      outcome = { result: 'fail', reasons: [`예기치 않은 오류: ${err.message ?? String(err)}`] };
    }
  } finally {
    ctx.log('종료 정리 — NORMAL 복구', 'progress');
    try {
      await ctx.pg.resetNormal();
      ctx.log('종료 정리 — NORMAL 복구', 'success');
    } catch (err) {
      ctx.log('종료 정리 — NORMAL 복구', 'failure',
        `NORMAL 복구 실패 — 다른 탭의 결제가 오염될 수 있습니다: ${err.message}`);
      // §7.4 finally는 안전망이지 결과 자체를 바꾸는 로직은 아니지만, mock-pg가 NORMAL이 아닌 채로
      // 남는 것은 다른 탭에 실제 위험이 있는 사건이다 — 판정을 최소 warn으로 올리고(이미 fail·aborted면
      // 그대로 둔다) 근거에 남긴다.
      outcome.reasons = [...(outcome.reasons ?? []), 'mock-pg 모드가 NORMAL이 아닐 수 있습니다 — 다른 탭 결제 오염 위험'];
      if (outcome.result === 'pass') outcome.result = 'warn';
    }
  }
  return outcome;
}
