// payment.js — 구매자 결제 화면 (프론트엔드 계획 §3 결제 응답·멱등 키, §5.4 전체, §5.5 "결제(구매자)",
// §6 "구매자 — payment.html", "결제 화면의 응답 처리")
//
// 이 파일이 하는 일은 세 가지뿐이다.
//   1) 주문 요약을 보여준다 (GET /api/orders/{orderId}).
//   2) 결제를 요청하고, §5.4 표 그대로 응답별로 멱등 키를 rotate하거나 유지한다.
//   3) "확정 관찰 규칙"(§5.4 마지막 문단)을 적용하되, **그 관찰이 정말 "이번 요청"의 결과인지**를
//      항상 먼저 따진다.
//
// ## B1 — "최신 결제"만 보고 rotate하면 이중 승인이 난다
//
// 예전 구현은 "이 주문의 최신 결제(attempts[0])가 확정이면 rotate"라는 규칙을 응답 경로 전체에
// 그대로 적용했다. 그런데 "최신"은 새로고침 시점·오류 재조회 시점의 스냅숏일 뿐, **내가 방금 보낸
// 요청**의 결과라는 보장이 없다. 재현 경로:
//   1) 결제 #1이 FAILED → rotate → 새 키 K2로 결제 #2 시도.
//   2) 캠페인 락 대기 등으로 서버가 #2를 처리하는 동안 fetch가 reject되거나 5xx가 온다(NETWORK_ERROR).
//   3) 예전 코드는 즉시 loadAttempts()를 다시 불러 attempts[0]을 본다 — 그런데 #2는 아직 커밋 전이라
//      attempts[0]은 여전히 #1(FAILED)이다. 예전 코드는 이걸 "확정 관찰"로 오인해 K2를 rotate해버린다.
//   4) 사용자가 다시 누르면 K3로 결제 #3이 나간다. 그런데 서버에서는 #2가 사실 SUCCEEDED였고, outbox가
//      주문을 PAID로 바꾸기 전(≤5초)이라 `hasNonFinalPayment`가 SUCCEEDED를 비최종으로 보지 않아
//      (PaymentRepository:54) #3도 PG 승인까지 간다 → 부분 유니크가 패자를 SUPERSEDED로 보상한다.
//      즉, 사용자 모르게 같은 주문에 PG 승인이 두 번 나간다.
//
// 원칙: **"최신 결제" 대신 "baseline(제출 직전까지 관측된 최대 결제 id) 이후에 새로 생긴 결제"만
// "이번 요청의 결과"로 본다.** baselineMaxId는 handlePayClick이 매 제출 직전에 attempts[0]?.id로
// 찍는다 — 그 뒤 새로 생기는 결제는 id가 반드시 baseline보다 커야 하고(PAY-01: 주문당 비최종 결제는
// 1건뿐이라 새로 생기는 행도 많아야 1건), 그 행이 없으면 "아직 내 요청의 결과가 안 보인다"는 뜻이라
// 키를 유지한 채 같은 키 재전송만 허용한다 (resolveAfterInconclusiveSubmit).
//
// 새로고침으로 재진입할 때도 같은 문제가 생긴다: 이력의 최신이 FAILED라도, 지금 sessionStorage에
// 남은 키가 "그 FAILED를 만든 키"인지 "아직 서버에 도달 못 했거나 처리 중인 더 최신 요청의 키"인지
// 구분할 수 없다. 그래서 resumeFromHistory는 latest.status==='FAILED'일 때 **rotate하지 않는다** —
// 저장된 키 그대로 "다시 결제"를 허용해 서버 판정에 맡긴다(§5.4의 replay 규약이 알아서 해소한다).
//
// (m-f) 이 baseline 방어에는 알려진 한계가 있다: baseline은 "내가 마지막으로 목록을 조회한
// 시점"의 스냅숏일 뿐이라, 같은 사용자가 다른 탭·다른 기기로 그 사이에 이 주문에 새 결제를
// 만들었다면 그 결제를 "내 요청의 결과"로 착각할 수 있다(코드를 고치지 않고 문서화만 한다 —
// 이 화면은 단일 세션 단일 탭 시연 전제다). 이 착각이 실제로 위험해지려면 그 다른 탭도 동시에
// PG 승인까지 가야 하는데, 서버의 `hasNonFinalPayment`(PaymentRepository:54)가 비최종 결제
// 동시 생성을 막고, 그래도 경쟁이 뚫리면 부분 유니크가 패자를 SUPERSEDED로 보상한다 — 즉
// 클라이언트의 baseline 판정이 틀려도 최종 방어는 항상 서버 쪽에 있다.
//
// rotateIdempotencyKey를 부르는 곳은 정확히 넷이고 각각 이유가 다르다:
//   - handleConfirmedPayment: "확정 관찰"의 유일한 정상 경로. 호출처는 handlePaymentResponse(baseline
//     검증을 통과한 새 결제), startPaymentConfirmationPoll·startOrderPaymentsPoll의 onTick(폴링 대상은
//     이미 "내 것"으로 알려진 id), resolveAfterInconclusiveSubmit(baseline보다 큰, 즉 내 요청의 결과),
//     resumeFromHistory(latest가 FAILED가 아닌 확정 상태일 때만 — FAILED는 위 이유로 제외).
//   - handleReplayOfOldPayment 안에서 handleConfirmedPayment를 통해: 서버가 예전에 완료된 요청의
//     저장된 응답을 재생했다고 판단되면(data.id <= baseline) 그 키는 이미 소비가 끝난 게 확실하므로
//     rotate해도 안전하다.
//   - IDEMPOTENCY_KEY_EXPIRED: 서버가 명시적으로 "이 키는 죽었다"고 알려준 경우 — §5.4 표 그대로.
//   - IDEMPOTENCY_KEY_REUSED(m8): 결제 경로에서는 이론상 나오지 않지만(우리가 보내는 request-hash는
//     orderId+amount로 고정) 방어적으로 "커밋됐다"고 보고 rotate한다.
//
// 이 화면에 텍스트 입력은 없다(금액은 주문 총액을 그대로 보낸다) — 그래서 admin/payments.js처럼
// DOM 행을 재사용해 포커스를 보존할 필요가 없다. render()는 매번 #page-content 전체를 다시 그린다.

import { api, idempotencyKeyFor, rotateIdempotencyKey, paymentScope } from '../common/api.js';
import { messageFor } from '../common/codes.js';
import { html, raw, idParam } from '../common/html.js';
import { formatMoney, formatDateTime } from '../common/format.js';
import { poll, renderContinueWatching } from '../common/poll.js';
import { campaignName, skuOptionName } from '../common/refcache.js';

// 결제 상태 (V3 CHECK, format.js STATUS_TABLES.payment): READY PROCESSING SUCCEEDED FAILED UNKNOWN
// SUPERSEDED REFUNDING REFUNDED.

// §5.4: 결제의 확정은 SUCCEEDED·FAILED·SUPERSEDED다. 202(UNKNOWN)는 확정이 아니다.
const CONFIRMED_STATUSES = new Set(['SUCCEEDED', 'FAILED', 'SUPERSEDED']);
// REFUNDING·REFUNDED는 §5.4 표에는 없지만 SUCCEEDED를 반드시 거쳐야만 도달하는 상태다
// (PaymentRepository#markRefunding은 'SUCCEEDED'에서만 REFUNDING으로 전이한다, V3 CHECK).
// 폴링 간격 사이에 SUCCEEDED 자체를 놓치고 곧장 REFUNDING/REFUNDED를 관찰하더라도, 이미 SUCCEEDED를
// 거쳤다는 뜻이므로 안전하게 rotate해도 된다.
//
// (m9) §5.5 "결제(구매자)" 폴링 표는 REFUNDING을 "환불 폴링으로 넘긴다"고 적지만, 이 화면(구매자
// 결제 화면)에는 환불 조작·환불 폴링이 없다 — 그래서 여기서는 의도적으로 REFUNDING도 REFUNDED와
// 같이 "종료로 보고 안내"하는 쪽으로 편차를 둔다(작업 명세 지시). §5.5와 다른 의도적 편차임을
// 명시한다.
const ROTATE_SAFE_EXTRA = new Set(['REFUNDING', 'REFUNDED']);

function isConfirmed(status) {
  return CONFIRMED_STATUSES.has(status) || ROTATE_SAFE_EXTRA.has(status);
}

/** m6: 4xx(401 제외 — api.js가 401은 로그인 화면으로 리다이렉트한다)면 폴링을 멈출 대상 오류. */
function isStoppableClientError(err) {
  return typeof err?.status === 'number' && err.status >= 400 && err.status < 500 && err.status !== 401;
}

let user = null;
let orderId = null;
let scope = null; // paymentScope(user.id, orderId)

let order = null;
let orderLoadError = null;
let campaignDisplayName = null;
// productSkuId -> optionName. order.items 자체는 orderStatusPollHandle이 매 틱마다 새로
// 받아오므로(예: PAID 후 reservationStatus가 ACTIVE→CONFIRMED로 바뀐다) 항목 배열을 통째로
// 캐시하지 않고 이름만 따로 캐시한다 — 그래야 렌더링이 항상 최신 order.items를 반영한다.
let optionNameBySku = new Map();

let attempts = []; // GET /api/orders/{orderId}/payments — 서버가 id DESC로 내려준다 (PaymentRepository#findByOrderId)
let attemptsError = null;
let lastAttemptsRequestId = null;
let attemptsRequestSeq = 0; // (m3) 겹쳐 불린 loadAttempts() 중 가장 최근 호출의 응답만 반영한다.

// 'invalid-id' | 'order-error' | 'history-unavailable' | 'ready' | 'not-payable' | 'unknown' |
// 'succeeded' | 'failed' | 'stale-key-replayed' | 'superseded' | 'refunding' | 'in-progress' |
// 'order-not-payable' | 'key-expired' | 'key-reused' | 'network-error' | 'other-error' |
// 'confirm-poll-error'
let uiMode = 'ready';
let submitting = false;
let submitError = null;
let failureInfo = null; // PaymentResponse — FAILED 사유 표시용
// (m-g) true면 직전에 rotate돼 다음 클릭이 새 키로 나간다("다시 결제 (새 요청)"), false면
// resumeFromHistory에서 rotate 없이 진입해 다음 클릭이 저장된 같은 키로 나간다("다시 결제").
let failedIsFreshKey = true;
let supersededInfo = null; // PaymentResponse — SUPERSEDED 안내용
let replayedInfo = null; // PaymentResponse — "이전 응답이 재생됨" 안내용 (B1)

let confirmPollHandle = null; // GET /api/payments/{id} — §5.5 "결제(구매자)" 행, 202 UNKNOWN 관찰
let confirmPaymentId = null; // 지금 confirmPollHandle이 지켜보는 결제 id ("다시 확인" 재시작용)
let confirmTimedOut = false;
let listPollHandle = null; // GET /api/orders/{id}/payments — IN_PROGRESS류에서 확정 관찰 규칙 적용
let listTimedOut = false;
let orderStatusPollHandle = null; // GET /api/orders/{id} — SUCCEEDED·SUPERSEDED 확정 후 PAID 반영 대기
let orderTimedOut = false;
let pollErrorKind = null; // (m6) 'confirm' | 'list' — confirm-poll-error에서 "다시 확인"이 재시작할 대상

// 타임라인 — "서버에 이력 API가 없으므로 클라이언트가 관찰한 변화" (§6). {time, status, requestId}[]
let timeline = [];

export async function initPaymentPage(bootedUser) {
  user = bootedUser;
  orderId = idParam('orderId');
  if (orderId === null) {
    uiMode = 'invalid-id';
    render();
    return;
  }
  scope = paymentScope(user.id, orderId);
  wireEvents();

  await loadOrder();
  if (orderLoadError) {
    uiMode = 'order-error';
    render();
    return;
  }
  await loadRefNames();
  await loadAttempts();
  if (attemptsError) {
    // (M1) 이력을 못 읽으면 최신 결제가 UNKNOWN인지 알 방법이 없다 — 'ready'로 흘려보내
    // 결제 버튼을 노출하면 오래된 202를 재전송할 위험이 있다. 결제를 완전히 막는다.
    uiMode = 'history-unavailable';
    render();
    return;
  }
  // 새로고침(페이지 재진입) 포함 — 최신 결제가 미확정이면 재전송 금지·폴링 재개, 확정이면
  // 확정 관찰 규칙을 즉시 적용한다 (작업 명세 "페이지 진입 시" 절, B1 예외는 resumeFromHistory 참고).
  resumeFromHistory();
  render();
}

// --- 데이터 로딩 -------------------------------------------------------------

async function loadOrder() {
  try {
    const { data } = await api(`/api/orders/${orderId}`);
    order = data;
    orderLoadError = null;
  } catch (err) {
    order = null;
    orderLoadError = err;
  }
}

/** (M2) 제출 후 부가 확인용 — 실패해도 이미 그려둔 order 스냅숏을 null로 지우지 않는다. */
async function refreshOrderQuiet() {
  try {
    const { data } = await api(`/api/orders/${orderId}`);
    order = data;
  } catch {
    // 조용히 무시한다 — 이 갱신은 버튼 조건을 더 정확하게 만드는 보강일 뿐, 실패해도 화면을 깨면 안 된다.
  }
}

async function loadRefNames() {
  if (!order) return;
  try {
    campaignDisplayName = await campaignName(order.campaignId);
  } catch {
    campaignDisplayName = null;
  }
  await Promise.all(order.items.map(async (item) => {
    let optionName = `SKU #${item.productSkuId}`;
    try {
      optionName = await skuOptionName(order.campaignId, item.productSkuId);
    } catch {
      // refcache 실패 — 폴백 표시로 충분하다, 화면을 막지 않는다.
    }
    optionNameBySku.set(item.productSkuId, optionName);
  }));
}

/** (m3) 겹쳐 불려도 가장 최근 호출의 응답만 attempts에 반영한다(느린 응답이 나중에 도착해 더
 * 최신 결과를 덮어쓰는 것을 막는다). */
async function loadAttempts() {
  const seq = ++attemptsRequestSeq;
  try {
    const { data, meta } = await api(`/api/orders/${orderId}/payments`);
    if (seq !== attemptsRequestSeq) return; // 더 최근 호출이 이미 있었다 — 이 응답은 버린다.
    attempts = data;
    attemptsError = null;
    lastAttemptsRequestId = meta.requestId;
  } catch (err) {
    if (seq !== attemptsRequestSeq) return;
    attempts = [];
    attemptsError = err;
    lastAttemptsRequestId = err.requestId ?? null;
  }
}

/** (m1) "이력 다시 불러오기"·"결제 시도 이력 새로고침" 버튼 공통 처리 — 항상 resumeFromHistory와
 * 같은 판정 함수를 거친다(B1 baseline 규칙이 적용되는 판정 경로는 이거 하나뿐이다). */
async function reloadHistoryAndResume() {
  await loadAttempts();
  if (attemptsError) {
    // (m-c) 'history-unavailable'은 초기 진입 전용이다. 여기서 실패해도 uiMode를 덮지 않는다 —
    // 이미 다른 화면(예: 'in-progress' 폴링 중)을 보여주고 있었다면 그 상태와 진행 중인 폴링을
    // 그대로 유지하고, 오류는 이력 카드 안에서만 보여준다(renderAttempts가 attemptsError를 읽는다).
    render();
    return;
  }
  resumeFromHistory();
  render();
}

// --- 타임라인 -----------------------------------------------------------------

/** 상태가 바뀔 때만 한 줄 추가한다 (연속된 같은 상태의 폴링 틱은 쌓지 않는다). */
function recordTimeline(status, requestId) {
  const last = timeline[timeline.length - 1];
  if (last && last.status === status) return;
  timeline.push({ time: new Date(), status, requestId: requestId ?? '-' });
}

// --- 폴링 정지 ------------------------------------------------------------------

/** 상태를 처음부터 다시 판정하기 전에 진행 중이던 확인용 폴링을 정리한다. orderStatusPollHandle은
 * 건드리지 않는다 — "결제 확정 후 PAID 대기"는 이 판정과 독립적인 목적이라 계속 지켜봐도 된다. */
function stopConfirmationPolls() {
  if (confirmPollHandle) { confirmPollHandle.stop(); confirmPollHandle = null; confirmTimedOut = false; }
  if (listPollHandle) { listPollHandle.stop(); listPollHandle = null; listTimedOut = false; }
}

// --- 확정 관찰 (파일 상단 주석의 원칙을 그대로 구현) ------------------------------

function handleConfirmedPayment(paymentLike, requestId) {
  recordTimeline(paymentLike.status, requestId);
  rotateIdempotencyKey(scope);
  if (paymentLike.status === 'FAILED') {
    uiMode = 'failed';
    failureInfo = paymentLike;
    // (m-g) 이 경로는 방금 rotate했으니 다음 클릭은 반드시 새 키로 나간다 — 버튼 문구에
    // "(새 요청)"을 붙여 구분한다. resumeFromHistory의 FAILED 분기(rotate 안 함, 저장된 키
    // 그대로 재전송)는 이 값을 false로 둔다.
    failedIsFreshKey = true;
  } else if (paymentLike.status === 'SUPERSEDED') {
    uiMode = 'superseded';
    supersededInfo = paymentLike;
    // (m5) 내 시도는 졌지만 같은 주문의 다른 시도가 이겼다는 뜻 — 그 시도도 곧 PAID로 반영된다.
    if (order && order.status === 'PENDING_PAYMENT') startOrderStatusPoll();
  } else if (paymentLike.status === 'REFUNDING' || paymentLike.status === 'REFUNDED') {
    uiMode = 'refunding';
  } else if (paymentLike.status === 'SUCCEEDED') {
    uiMode = 'succeeded';
    failureInfo = null;
    // §6 "성공 표시(주문 PAID 반영은 outbox로 최대 ~5초 — 주문 상태 폴링으로 PAID까지 보여줌)".
    if (order && order.status === 'PENDING_PAYMENT') startOrderStatusPoll();
  }
}

/**
 * (m4, m-d) 목록에서 "지금 보여줄 대표 결제"를 고른다. id DESC 최신이 FAILED이더라도, 같은
 * 목록에 SUCCEEDED·REFUNDING·REFUNDED가 하나라도 있으면 그걸 우선한다 — outbox가 주문을 PAID로
 * 바꾸기 전(최대 ~5초) 창에서는 SUCCEEDED가 `hasNonFinalPayment`의 비최종 집합에 없어
 * (PaymentRepository:54) 새 결제 시도가 하나 더 만들어져 독자적으로 FAILED가 될 수 있다 —
 * 그러면 "최신"만 보고는 이미 성공한 주문을 "실패"로 잘못 보여주게 된다. READY/PROCESSING/
 * UNKNOWN이 최신인 경우는 건드리지 않는다 — 그 상태는 이미 폴링으로 안전하게 처리되고(버튼이
 * 없다), 여기서 재정의할 이유가 없다.
 *
 * (m-d) "모드 결정"의 공통 지점이다 — resumeFromHistory·startOrderPaymentsPoll·
 * startPaymentConfirmationPoll(loadAttempts 이후)·resolveAfterInconclusiveSubmit 넷 다 이
 * 함수로 고른 결제를 기준으로 확정 여부를 판단한다. rotate 규칙(handleConfirmedPayment의
 * 상태별 분기) 자체는 바꾸지 않는다 — 이 함수는 그 규칙을 적용할 대상만 고른다.
 */
function pickEffectiveLatest(list) {
  const latest = list[0] ?? null;
  if (latest && latest.status === 'FAILED') {
    const winner = list.find((p) => p.status === 'SUCCEEDED' || p.status === 'REFUNDING' || p.status === 'REFUNDED');
    if (winner) return winner;
  }
  return latest;
}

/** 페이지 진입(새로고침 포함)·이력 새로고침의 시작 상태를 결정한다. */
function resumeFromHistory() {
  stopConfirmationPolls();
  const effective = pickEffectiveLatest(attempts);
  if (!effective) {
    uiMode = order.status === 'PENDING_PAYMENT' ? 'ready' : 'not-payable';
    return;
  }
  if (effective.status === 'FAILED') {
    // B1 — 지금 sessionStorage에 남은 키가 "이 FAILED를 만든 키"인지, "아직 서버에 도달하지
    // 못했거나 처리 중인 더 최신 요청의 키"인지 이 시점에는 구분할 수 없다. 함부로 rotate하면
    // 후자일 때 진행 중인 요청을 무시하고 새 키로 병행 결제를 만들어 이중 승인으로 이어진다.
    // 그래서 여기서는 절대 rotate하지 않는다 — 저장된 키 그대로 "다시 결제"를 허용해 서버
    // 판정에 맡긴다: 키가 이미 이 FAILED로 완료됐다면 서버가 같은 응답을 재생하고, 우리는 그
    // 응답의 id가 baseline(=지금 이 FAILED의 id) 이하임을 handlePaymentResponse에서 감지해
    // "이전 실패 결과가 재생되었습니다"로 안내한다(그때 rotate한다). 키가 아직 처리 중인 더
    // 최신 요청의 것이라면 IDEMPOTENCY_REQUEST_IN_PROGRESS로 안전하게 막혀 목록 폴링으로
    // 전환된다.
    recordTimeline(effective.status, lastAttemptsRequestId);
    uiMode = 'failed';
    failureInfo = effective;
    failedIsFreshKey = false; // (m-g) rotate 안 함 — 다음 클릭은 저장된 같은 키로 재전송된다.
    return;
  }
  if (isConfirmed(effective.status)) {
    // SUCCEEDED·SUPERSEDED(+ROTATE_SAFE_EXTRA) — 새 결제가 필요 없는 상태이므로 관찰 즉시
    // rotate해도 안전하다.
    handleConfirmedPayment(effective, lastAttemptsRequestId);
    return;
  }
  // READY·PROCESSING·UNKNOWN — 재전송 금지, 폴링 재개 (새로고침으로 낡은 202를 재생하지 않는다).
  recordTimeline(effective.status, lastAttemptsRequestId);
  startPaymentConfirmationPoll(effective.id);
}

// --- 결제 요청 -----------------------------------------------------------------

function canSubmitNewPayment() {
  if (!order || order.status !== 'PENDING_PAYMENT') return false;
  if (submitting) return false;
  // 화이트리스트로 막는다 — 새 uiMode를 추가할 때 이 가드를 깜빡 잊어도 기본값이 "금지" 쪽이 되게
  // (블랙리스트였다면 재전송 금지 상태를 빠뜨렸을 때 조용히 뚫린다).
  return uiMode === 'ready' || uiMode === 'failed' || uiMode === 'stale-key-replayed'
    || uiMode === 'key-expired' || uiMode === 'key-reused'
    || uiMode === 'network-error' || uiMode === 'other-error';
}

async function handlePayClick() {
  if (!canSubmitNewPayment()) return;
  // 이전 시도의 폴링이 남아 있으면 정리한다 (정상 흐름에서는 canSubmitNewPayment()가 이미 막지만
  // 방어적으로 한 번 더 멈춘다).
  stopConfirmationPolls();

  // B1 — 이 값 이후에 새로 생기는 결제만 "이번 요청의 결과"로 본다. attempts는 id DESC이므로
  // attempts[0]이 지금까지 본 가장 큰 id다.
  const baselineMaxId = attempts[0]?.id ?? 0;

  submitting = true;
  submitError = null;
  render();

  const key = idempotencyKeyFor(scope);
  try {
    const { data, meta } = await api(`/api/orders/${orderId}/payments`, {
      method: 'POST',
      body: { amount: order.totalAmount },
      idempotencyKey: key,
    });
    handlePaymentResponse(data, meta, baselineMaxId);
  } catch (err) {
    handlePaymentError(err);
  }
  submitting = false;

  await loadAttempts();
  // §6 "409 problem ORDER_NOT_PAYABLE → 오류로 그리지 않는다. 실제 결과를 표시하고 확정이면 rotate"
  // §5.4 "네트워크 오류·5xx → 키 유지 + 먼저 목록으로 결과를 확인"
  // (M2) 요약 카드·버튼 조건도 최신 주문 상태로 맞춘다.
  if (uiMode === 'order-not-payable' || uiMode === 'network-error') {
    await refreshOrderQuiet();
    resolveAfterInconclusiveSubmit(baselineMaxId);
  }
  render();
}

/**
 * ORDER_NOT_PAYABLE·네트워크 오류처럼 "내 요청이 어떻게 됐는지 응답만으로는 알 수 없는" 경우,
 * 방금 새로 고친 attempts에서 baseline보다 큰 id가 있는지 본다. attempts는 id DESC이고 주문당
 * 비최종 결제는 최대 1건(PAY-01)이므로, 있다면 그것은 attempts[0]이고 그게 전부다.
 *
 * (m-d) 다만 baseline을 보기 전에 먼저 pickEffectiveLatest로 "이미 확정된 승자가 목록 어딘가에
 * 있는지"부터 본다 — 이건 baseline과 무관하게 안전하다: pickEffectiveLatest는 raw 최신이
 * FAILED이고 그 밖에 SUCCEEDED/REFUNDING/REFUNDED가 실제로 있을 때만 그 승자로 "override"하고,
 * override가 안 일어나면 그냥 raw 최신을 그대로 돌려준다(effective === latest). override가
 * 일어난 경우에만 그 결과를 즉시 반영한다 — override가 안 일어난 raw 최신을 baseline 검사 없이
 * 신뢰하면 B1이 그대로 재현된다(오래된 FAILED를 내 요청의 결과로 오인).
 */
function resolveAfterInconclusiveSubmit(baselineMaxId) {
  const latest = attempts[0] ?? null;
  const effective = pickEffectiveLatest(attempts);
  if (effective && latest && effective !== latest) {
    // 이미 확정된 승자가 다른 결제로 존재한다 — 내 이번 요청이 무엇을 했든 주문은 이미
    // 해소됐다는 뜻이라 baseline과 무관하게 이 결과를 보여준다.
    handleConfirmedPayment(effective, lastAttemptsRequestId);
    return;
  }
  const mine = latest && latest.id > baselineMaxId ? latest : null;
  if (!mine) {
    // 내 요청의 결과가 아직 보이지 않는다.
    // (m-b) 그런데 재조회한 주문이 이미 PENDING_PAYMENT가 아니라면(예: 다른 경로로 이미
    // 종결됨) 키를 유지한 채 재전송을 계속 허용하는 것도 의미가 없다 — 결제할 수 없는 상태로
    // 명확히 전환한다.
    if (order && order.status !== 'PENDING_PAYMENT') {
      uiMode = 'not-payable';
    }
    return;
  }
  if (isConfirmed(mine.status)) {
    handleConfirmedPayment(mine, lastAttemptsRequestId);
  } else {
    // READY/PROCESSING/UNKNOWN — 내 요청이 서버에 실제로 살아있다. 재전송 대신 단건 폴링으로
    // 전환한다 (M3).
    recordTimeline(mine.status, lastAttemptsRequestId);
    startPaymentConfirmationPoll(mine.id);
  }
}

function handlePaymentResponse(data, meta, baselineMaxId) {
  if (data.id != null && data.id <= baselineMaxId) {
    // 같은 키가 예전에 이미 끝난 요청의 저장된 응답을 그대로 재생한 것이다(§5.4) — 이번 클릭은
    // 새 시도를 만들지 못했다.
    handleReplayOfOldPayment(data, meta.requestId);
    return;
  }
  if (!isConfirmed(data.status)) {
    // 정상 경로는 202 UNKNOWN이지만, (m7) 어떤 경로로든 미확정 상태가 오면 항상 폴링으로
    // 넘긴다 — 절대 확정으로 오해하지 않는다.
    recordTimeline(data.status, meta.requestId);
    startPaymentConfirmationPoll(data.id);
    return;
  }
  handleConfirmedPayment(data, meta.requestId);
}

/**
 * baseline 이하 id로 온 응답 — "저장된 스냅숏의 재생"이다. 최종 상태(SUCCEEDED·FAILED·SUPERSEDED
 * 등)는 재생돼도 값이 바뀌지 않으므로 handleConfirmedPayment를 그대로 재사용해도 안전하다. 다만
 * FAILED라면 "방금 클릭이 새 시도를 만들지 못했다"는 걸 명시적으로 안내해야 한다(그렇지 않으면
 * 사용자가 "다시 결제했다"고 착각한 채 넘어간다).
 *
 * READY/PROCESSING/UNKNOWN으로 재생된 경우는 신뢰하지 않는다 — `PaymentService#settle`의
 * `idempotency.complete`는 그 순간의 PaymentResponse를 JSON으로 얼려서 저장하므로, 그 뒤 웹훅·
 * 대사가 실제 결제를 이미 확정했더라도 재생 응답의 status는 계속 옛 스냅숏(UNKNOWN 등)을 보여준다.
 * 그래서 이 경우는 그 결제 id를 실제로 폴링해 지금의 진짜 상태를 확인한다.
 */
function handleReplayOfOldPayment(data, requestId) {
  if (isConfirmed(data.status)) {
    handleConfirmedPayment(data, requestId);
    if (data.status === 'FAILED') {
      uiMode = 'stale-key-replayed';
    }
    replayedInfo = data;
    return;
  }
  recordTimeline(data.status, requestId);
  startPaymentConfirmationPoll(data.id);
}

function handlePaymentError(err) {
  const code = err.code;
  if (code === 'NETWORK_ERROR' || (typeof err.status === 'number' && err.status >= 500)) {
    // §5.4: 키 유지, 같은 키로 재전송 (PAY-02 시연 대상).
    uiMode = 'network-error';
    submitError = err;
    return;
  }
  if (code === 'ORDER_NOT_PAYABLE') {
    // §6: 오류로 그리지 않는다 — 결제 시도 이력으로 실제 결과를 보여준다 (handlePayClick 뒤에서 처리).
    uiMode = 'order-not-payable';
    submitError = err;
    return;
  }
  if (code === 'IDEMPOTENCY_REQUEST_IN_PROGRESS' || code === 'PAYMENT_ALREADY_IN_PROGRESS') {
    // §5.4: 키 유지, 재전송 루프 금지 — 결제 시도 이력(목록) 폴링으로 전환.
    uiMode = 'in-progress';
    submitError = err;
    startOrderPaymentsPoll();
    return;
  }
  if (code === 'IDEMPOTENCY_KEY_EXPIRED') {
    rotateIdempotencyKey(scope);
    uiMode = 'key-expired';
    submitError = err;
    return;
  }
  if (code === 'IDEMPOTENCY_KEY_REUSED') {
    // (m8) 결제 경로에서는 이론상 나오지 않아야 한다(요청 해시가 orderId+amount로 고정되고 이
    // scope의 키는 이 결제 의도에만 쓰인다) — 그래도 방어적으로 "커밋됐다" 취급을 따른다.
    rotateIdempotencyKey(scope);
    uiMode = 'key-reused';
    submitError = err;
    return;
  }
  // 그 밖의 4xx problem — 키 유지해도 무방 (§5.4 마지막 행).
  uiMode = 'other-error';
  submitError = err;
}

// --- 폴링 ----------------------------------------------------------------------

/** §5.5 "결제(구매자)": GET /api/payments/{id}, 종료 SUCCEEDED·FAILED·SUPERSEDED·REFUNDED(+REFUNDING), 60초. */
function startPaymentConfirmationPoll(paymentId) {
  if (confirmPollHandle) confirmPollHandle.stop();
  uiMode = 'unknown';
  confirmPaymentId = paymentId;
  confirmTimedOut = false;
  confirmPollHandle = poll({
    fn: () => api(`/api/payments/${paymentId}`),
    until: (result) => isConfirmed(result.data.status),
    onTick: (result, err) => {
      if (err) {
        // (m6) 4xx(401 제외)는 일시적 장애가 아니라 대상이 잘못됐다는 신호일 가능성이 높다 —
        // 폴링을 멈추고 오류를 보여준다. 5xx·네트워크 오류는 poll.js의 백오프에 맡기고 계속한다.
        if (isStoppableClientError(err)) {
          confirmPollHandle.stop();
          confirmTimedOut = false;
          pollErrorKind = 'confirm';
          uiMode = 'confirm-poll-error';
          submitError = err;
          render();
        }
        return;
      }
      recordTimeline(result.data.status, result.meta.requestId);
      if (isConfirmed(result.data.status)) {
        handleConfirmedPayment(result.data, result.meta.requestId);
      }
      loadAttempts().then(() => {
        // (m-d) 지금 개별 폴링 중인 id 말고, 목록 전체에서 m4 우선순위로 고른 대표 결제가 이미
        // 확정돼 있으면 그것도 반영한다 — outbox 창에서 독자적으로 생긴 다른 시도가 이 id보다
        // 먼저 확정될 수 있다. handleConfirmedPayment는 이미 rotate된 키를 다시 rotate해도
        // 안전하다(sessionStorage.removeItem은 멱등).
        const effective = pickEffectiveLatest(attempts);
        if (effective && isConfirmed(effective.status)) {
          handleConfirmedPayment(effective, lastAttemptsRequestId);
        }
        render();
      });
    },
    onTimeout: () => {
      confirmTimedOut = true;
      render();
    },
  });
}

/** IDEMPOTENCY_REQUEST_IN_PROGRESS·PAYMENT_ALREADY_IN_PROGRESS 이후: 목록에서 확정을 기다린다.
 * "최신"을 그대로 믿어도 안전하다 — 여기서 기다리는 건 방금 내 요청이 걸어 둔 바로 그 키의 claim이
 * 결제 INSERT와 같은 트랜잭션으로 커밋된 뒤에만 나오는 상태이기 때문이다(오퍼스 검토로 확인됨). */
function startOrderPaymentsPoll() {
  if (listPollHandle) listPollHandle.stop();
  // (m-e) 'confirm-poll-error'에서 "다시 확인"으로 이 폴링을 재시작하는 경로도 있다 — 그때
  // uiMode가 'confirm-poll-error'에 머무르지 않고 원래의 'in-progress' 화면(안내문·
  // continue-watching-list 컨테이너)으로 돌아오게 한다.
  uiMode = 'in-progress';
  pollErrorKind = 'list';
  listTimedOut = false;
  listPollHandle = poll({
    fn: () => api(`/api/orders/${orderId}/payments`),
    // (m-d) m4 우선순위를 여기도 적용한다 — list[0]이 아니라 pickEffectiveLatest로 고른 대표
    // 결제를 기준으로 종료를 판정한다(opus가 vetting한 "list[0]이 곧 내 것"이라는 전제는
    // pickEffectiveLatest가 override를 안 할 때는 그대로 유지되고, override가 일어나는 경우는
    // 이미 확정된 승자가 다른 곳에 있다는 뜻이라 어차피 더 빨리 끝내는 게 맞다).
    until: (result) => {
      const effective = pickEffectiveLatest(result.data);
      return !!effective && isConfirmed(effective.status);
    },
    onTick: (result, err) => {
      if (err) {
        if (isStoppableClientError(err)) {
          listPollHandle.stop();
          listTimedOut = false;
          pollErrorKind = 'list';
          uiMode = 'confirm-poll-error';
          submitError = err;
          render();
        }
        return;
      }
      attempts = result.data;
      attemptsError = null; // (m2)
      lastAttemptsRequestId = result.meta.requestId; // (m2)
      const effective = pickEffectiveLatest(attempts);
      if (effective) {
        recordTimeline(effective.status, result.meta.requestId);
        if (isConfirmed(effective.status)) {
          handleConfirmedPayment(effective, result.meta.requestId);
        }
      }
      render();
    },
    onTimeout: () => {
      listTimedOut = true;
      render();
    },
  });
}

/** SUCCEEDED·SUPERSEDED 확정 후 outbox의 주문 PAID 반영(최대 약 5초)을 기다린다. */
function startOrderStatusPoll() {
  if (orderStatusPollHandle) orderStatusPollHandle.stop();
  orderTimedOut = false;
  orderStatusPollHandle = poll({
    fn: () => api(`/api/orders/${orderId}`),
    until: (result) => result.data.status !== 'PENDING_PAYMENT',
    intervalMs: 1000,
    maxIntervalMs: 2000,
    timeoutMs: 15000,
    onTick: (result, err) => {
      if (err) {
        // 이 폴링은 이미 보여준 "성공/대체됨" 결과 위에 얹는 부가 확인일 뿐이다 — 4xx가 나도
        // 그 결과 화면을 오류로 덮지 않고 조용히 멈춘다(다시 지켜보기 버튼도 띄우지 않는다).
        if (isStoppableClientError(err)) { orderStatusPollHandle.stop(); orderTimedOut = false; }
        return;
      }
      order = result.data;
      render();
    },
    onTimeout: () => {
      orderTimedOut = true;
      render();
    },
  });
}

function handleRetryConfirmClick() {
  if (pollErrorKind === 'list') {
    startOrderPaymentsPoll();
  } else if (confirmPaymentId != null) {
    startPaymentConfirmationPoll(confirmPaymentId);
  }
  render();
}

// --- 렌더링 ----------------------------------------------------------------------

function wireEvents() {
  const root = document.getElementById('page-content');
  root.addEventListener('click', (event) => {
    const btn = event.target.closest('button[data-action]');
    if (!btn) return;
    // (m-a) 제출 중에는 다른 액션(이력 새로고침·다시 확인 등)도 무시한다 — 그렇지 않으면 그
    // 액션이 resumeFromHistory로 uiMode·폴링을 다시 판정하는 동안 원래 POST의 응답(특히
    // NETWORK_ERROR)이 그 판정을 덮어써 폴링은 살아있는데 재전송 버튼이 노출되는 경로가 생긴다.
    if (submitting) return;
    const action = btn.dataset.action;
    if (action === 'pay') handlePayClick();
    else if (action === 'reload-attempts' || action === 'retry-history') reloadHistoryAndResume();
    else if (action === 'retry-confirm') handleRetryConfirmClick();
  });
}

function render() {
  const root = document.getElementById('page-content');
  root.innerHTML = renderPage();
  if (confirmTimedOut) attachContinueWatching('continue-watching-confirm', confirmPollHandle, () => {
    confirmTimedOut = false;
    confirmPollHandle.restart();
    render();
  });
  if (listTimedOut) attachContinueWatching('continue-watching-list', listPollHandle, () => {
    listTimedOut = false;
    listPollHandle.restart();
    render();
  });
  if (orderTimedOut) attachContinueWatching('continue-watching-order', orderStatusPollHandle, () => {
    orderTimedOut = false;
    orderStatusPollHandle.restart();
    render();
  });
}

function attachContinueWatching(containerId, handle, restart) {
  const el = document.getElementById(containerId);
  if (!el || !handle) return;
  renderContinueWatching(el, { stop: handle.stop, restart });
}

function renderPage() {
  if (uiMode === 'invalid-id') {
    return html`<div class="notice notice--danger">
      <p>주문 ID가 올바르지 않습니다.</p>
      <p><a href="/console/buyer/orders.html">내 주문</a>에서 다시 선택해 주세요.</p>
    </div>`;
  }
  if (uiMode === 'order-error') {
    return html`<div class="notice notice--danger">
      <p>주문을 불러오지 못했습니다: ${messageFor(orderLoadError)}</p>
      <p class="mono">요청 ID: ${orderLoadError?.requestId ?? '-'}</p>
      <p><a href="/console/buyer/orders.html">내 주문</a>으로 돌아가기</p>
    </div>`;
  }
  return html`
    <div class="crumb"><a href="/console/buyer/orders.html">← 내 주문</a></div>
    ${raw(renderOrderSummary())}
    ${raw(renderPaymentAction())}
    ${raw(renderTimeline())}
    ${raw(renderAttempts())}
  `;
}

function renderOrderSummary() {
  const rows = order.items.map((item) => html`
    <tr>
      <td>${optionNameBySku.get(item.productSkuId) ?? `SKU #${item.productSkuId}`}</td>
      <td>${item.quantity}</td>
      <td>${formatMoney(item.unitPrice)}</td>
      <td>${formatMoney(item.lineAmount)}</td>
      <td><status-badge domain="reservation" value="${item.reservationStatus}"></status-badge></td>
    </tr>`).join('');

  let countdown = '';
  if (order.status === 'PENDING_PAYMENT') {
    const expiresAtMs = new Date(order.expiresAt).getTime();
    const countdownExpired = !Number.isNaN(expiresAtMs) && expiresAtMs <= Date.now();
    // (m5) 결제가 이미 확정된 뒤라면 만료 스윕이 유예된다(OrderRepository:183-206 — PROCESSING·
    // UNKNOWN·SUCCEEDED 결제가 있으면 만료 워커가 건너뛴다). 카운트다운이 "만료"로 보여도
    // 실제로는 곧 PAID가 될 상태라는 걸 명확히 안내한다.
    const showGraceNote = countdownExpired && (uiMode === 'succeeded' || uiMode === 'superseded');
    const note = showGraceNote
      ? '— 결제가 확정되어 만료 처리가 유예됩니다. 주문이 곧 PAID로 바뀝니다.'
      : '— 서버 만료 처리까지 최대 5초 더 걸릴 수 있습니다. 만료되면 새로고침해 확인하세요.';
    countdown = html`<p>남은 시간: <countdown-timer expires-at="${order.expiresAt}"></countdown-timer>
        <span class="muted">${note}</span></p>`;
  }

  return html`
    <section class="card">
      <h1>주문 #${order.id} 결제</h1>
      <p>
        캠페인: ${campaignDisplayName
          ? html`<a href="/console/buyer/campaign.html?id=${order.campaignId}">${campaignDisplayName}</a>`
          : html`<a href="/console/buyer/campaign.html?id=${order.campaignId}">#${order.campaignId}</a>`}
        &nbsp;·&nbsp; 상태: <status-badge domain="order" value="${order.status}"></status-badge>
      </p>
      <table>
        <thead><tr><th>옵션</th><th>수량</th><th>단가</th><th>금액</th><th>예약</th></tr></thead>
        <tbody>${raw(rows)}</tbody>
      </table>
      <p class="total-line">총액: <strong>${formatMoney(order.totalAmount)}</strong></p>
      ${raw(countdown)}
    </section>
  `;
}

function renderPaymentAction() {
  const payButton = (label) => html`
    <button type="button" class="btn btn-primary" data-action="pay" ${submitting ? 'disabled' : ''}>
      ${submitting ? '요청 중…' : label}
    </button>`;

  switch (uiMode) {
    case 'history-unavailable':
      return html`<section class="card">
        <h2>결제</h2>
        <div class="notice notice--danger">결제 시도 이력을 불러오지 못해 지금 상태를 안전하게
          판단할 수 없습니다: ${messageFor(attemptsError)}
          <span class="mono">(요청 ID ${attemptsError?.requestId ?? '-'})</span></div>
        <p class="muted">이 상태에서 결제 버튼을 열어두면 오래된 202 응답을 재전송할 위험이 있어
          막아 둡니다.</p>
        <button type="button" class="btn btn-secondary" data-action="retry-history" ${submitting ? 'disabled' : ''}>이력 다시 불러오기</button>
      </section>`;

    case 'ready':
      return html`<section class="card">
        <h2>결제</h2>
        <p class="muted">주문 총액 ${formatMoney(order.totalAmount)}을(를) 결제합니다.</p>
        ${payButton('결제하기')}
      </section>`;

    case 'not-payable':
      return html`<section class="card">
        <h2>결제</h2>
        <p class="muted">이 주문은 결제 대기(PENDING_PAYMENT) 상태가 아니라 결제할 수 없습니다.
          현재 상태: <status-badge domain="order" value="${order.status}"></status-badge></p>
      </section>`;

    case 'unknown':
      return html`<section class="card">
        <h2>결제 — <span class="status-badge status-badge--yellow">확정 대기</span></h2>
        <p class="muted">결제 결과가 아직 확정되지 않았습니다(PAY-03 UNKNOWN). 같은 요청을 다시 보내지
          않습니다 — 낡은 202 응답을 재생할 뿐입니다. 자동으로 확인 중입니다…</p>
        <div id="continue-watching-confirm" class="continue-watching"></div>
      </section>`;

    case 'confirm-poll-error':
      return html`<section class="card">
        <h2>결제</h2>
        <div class="notice notice--danger">결제 상태 확인 요청이 실패했습니다: ${messageFor(submitError)}
          <span class="mono">(요청 ID ${submitError?.requestId ?? '-'})</span></div>
        <button type="button" class="btn btn-secondary" data-action="retry-confirm" ${submitting ? 'disabled' : ''}>다시 확인</button>
      </section>`;

    case 'succeeded':
      return html`<section class="card">
        <h2>결제 — <span class="status-badge status-badge--green">성공</span></h2>
        ${order.status === 'PENDING_PAYMENT'
          ? html`<p class="muted">결제가 확정되었습니다. 주문이 PAID로 바뀌는 것을 최대 약 5초까지
              자동으로 확인합니다(outbox 반영).</p>
            <div id="continue-watching-order" class="continue-watching"></div>`
          : html`<p class="muted">결제와 주문이 모두 확정되었습니다. 위 주문 상태를 확인하세요.</p>`}
      </section>`;

    case 'failed':
      return html`<section class="card">
        <h2>결제 — <span class="status-badge status-badge--red">실패</span></h2>
        ${failureInfo?.failureReason ? html`<p>사유: ${failureInfo.failureReason}</p>` : ''}
        ${failureInfo?.failureCode ? html`<p class="mono">코드: ${failureInfo.failureCode}</p>` : ''}
        ${order.status === 'PENDING_PAYMENT'
          ? payButton(failedIsFreshKey ? '다시 결제 (새 요청)' : '다시 결제')
          : html`<p class="muted">이 주문은 더 이상 결제 대기 상태가 아니라 재시도할 수 없습니다.</p>`}
      </section>`;

    case 'stale-key-replayed':
      return html`<section class="card">
        <h2>결제 — <span class="status-badge status-badge--red">실패 (이전 응답 재생됨)</span></h2>
        <div class="notice notice--warning">방금 요청은 새 시도를 만들지 못했습니다 — 예전에 이미
          끝난 실패 결과가 그대로 재생됐을 뿐입니다(B1). 새 키로 다시 결제해야 실제로 새 시도가
          나갑니다.</div>
        ${failureInfo?.failureReason ? html`<p>사유: ${failureInfo.failureReason}</p>` : ''}
        ${order.status === 'PENDING_PAYMENT' ? payButton('새 요청으로 다시 결제') : ''}
      </section>`;

    case 'superseded':
      return html`<section class="card">
        <h2>결제</h2>
        <div class="notice notice--info">다른 결제 시도가 유효 결제로 확정되었습니다.</div>
        ${supersededInfo ? html`<p class="mono">대체된 결제 ID: ${supersededInfo.id}</p>` : ''}
        <p class="muted">아래 결제 시도 이력에서 실제로 확정된 결제를 확인하세요.</p>
        ${order.status === 'PENDING_PAYMENT'
          ? html`<p class="muted">주문이 PAID로 바뀌는 것을 최대 약 5초까지 자동으로 확인합니다.</p>
            <div id="continue-watching-order" class="continue-watching"></div>`
          : ''}
      </section>`;

    case 'refunding':
      return html`<section class="card">
        <h2>결제</h2>
        <div class="notice notice--info">이 결제는 성공한 뒤 환불이 진행 중이거나 완료되었습니다.</div>
      </section>`;

    case 'in-progress':
      return html`<section class="card">
        <h2>결제</h2>
        <div class="notice notice--warning">${messageFor(submitError)}
          <span class="mono">(요청 ID ${submitError?.requestId ?? '-'})</span></div>
        <p class="muted">같은 요청을 다시 보내지 않고, 이 주문의 결제 시도 이력을 자동으로 확인합니다.
          확정되면(성공·실패·대체됨) 자동으로 다시 결제할 수 있게 됩니다.</p>
        <div id="continue-watching-list" class="continue-watching"></div>
      </section>`;

    case 'order-not-payable':
      return html`<section class="card">
        <h2>결제</h2>
        <div class="notice notice--info">이 요청은 결제할 수 없는 상태로 거부되었습니다 — 이전 결제
          시도가 이미 이 주문을 처리했을 수 있습니다. 아래 결제 시도 이력에서 실제 결과를 확인하세요.</div>
      </section>`;

    case 'key-expired':
      return html`<section class="card">
        <h2>결제</h2>
        <div class="notice notice--warning">${messageFor(submitError)}
          <span class="mono">(요청 ID ${submitError?.requestId ?? '-'})</span></div>
        ${order.status === 'PENDING_PAYMENT' ? payButton('새 키로 다시 시도') : ''}
      </section>`;

    case 'key-reused':
      return html`<section class="card">
        <h2>결제</h2>
        <div class="notice notice--warning">${messageFor(submitError)}
          <span class="mono">(요청 ID ${submitError?.requestId ?? '-'})</span></div>
        <p class="muted">이 키는 이미 다른 요청에 쓰였습니다.</p>
        ${order.status === 'PENDING_PAYMENT' ? payButton('새 키로 다시 결제') : ''}
      </section>`;

    case 'network-error':
      return html`<section class="card">
        <h2>결제</h2>
        <div class="notice notice--danger">${messageFor(submitError)}
          <span class="mono">(요청 ID ${submitError?.requestId ?? '-'})</span></div>
        <p class="muted">응답을 받지 못했을 뿐 서버에서는 처리되었을 수 있습니다. 아래 결제 시도
          이력을 먼저 확인하세요. 그래도 결과가 없다면 같은 요청(같은 Idempotency-Key)으로 다시
          시도할 수 있습니다 — 서버가 중복 처리를 막습니다 (PAY-02).</p>
        ${order.status === 'PENDING_PAYMENT' ? payButton('같은 요청으로 다시 시도') : ''}
      </section>`;

    case 'other-error':
      return html`<section class="card">
        <h2>결제</h2>
        <div class="notice notice--danger">${messageFor(submitError)}
          <span class="mono">(요청 ID ${submitError?.requestId ?? '-'})</span></div>
        ${order.status === 'PENDING_PAYMENT' ? payButton('다시 시도') : ''}
      </section>`;

    default:
      return '';
  }
}

function renderTimeline() {
  if (timeline.length === 0) return '';
  const rows = timeline.map((entry) => html`
    <li>${formatDateTime(entry.time)} | <status-badge domain="payment" value="${entry.status}"></status-badge> | 요청 ID <span class="mono">${entry.requestId}</span></li>
  `).join('');
  return html`
    <section class="card">
      <h2>상태 타임라인</h2>
      <p class="muted">서버에 결제 이력 API가 없으므로, 이 타임라인은 이 브라우저 탭이 관찰한 변화입니다.</p>
      <ul class="timeline">${raw(rows)}</ul>
    </section>
  `;
}

function renderAttempts() {
  let body;
  if (attemptsError) {
    body = html`<div class="notice notice--danger">결제 시도 이력을 불러오지 못했습니다: ${messageFor(attemptsError)}
      <span class="mono">(요청 ID ${attemptsError.requestId ?? '-'})</span></div>`;
  } else if (attempts.length === 0) {
    body = html`<p class="muted">결제 시도 이력이 없습니다.</p>`;
  } else {
    const rows = attempts.map((p) => html`
      <tr>
        <td>${p.id}</td>
        <td><status-badge domain="payment" value="${p.status}"></status-badge></td>
        <td>${formatMoney(p.amount)}</td>
        <td class="mono">${p.providerPaymentId ?? '-'}</td>
        <td>${formatDateTime(p.approvedAt)}</td>
        <td>${p.failureCode ?? '-'}</td>
        <td>${p.failureReason ?? '-'}</td>
      </tr>`).join('');
    body = html`<table>
      <thead><tr><th>ID</th><th>상태</th><th>금액</th><th>PG 결제ID</th><th>승인시각</th><th>실패코드</th><th>실패사유</th></tr></thead>
      <tbody>${raw(rows)}</tbody>
    </table>`;
  }
  return html`
    <section class="card">
      <h2>결제 시도 이력
        <button type="button" class="btn btn-secondary btn-sm" data-action="reload-attempts" ${submitting ? 'disabled' : ''}>새로고침</button>
      </h2>
      <p class="muted">재시도할 때마다 새 결제 레코드가 쌓입니다(id DESC로 최신 순 표시) — 이 목록이
        재시도의 실제 기록입니다.</p>
      ${raw(body)}
    </section>
  `;
}
