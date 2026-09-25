// campaign.js — 구매자 공구 상세·주문 (프론트엔드 계획 §6 "구매자", §5.4 멱등 키 표, ORD-04)
//
// GET /api/campaigns/{id}는 이름·딜가·인당 한도·기간·SKU 옵션명처럼 판매 중 바뀌지 않는 값이라
// 한 번만 불러온다. 잔여 재고(availableQuantity)와 인당 잔여 구매 가능 수량(remainingPurchaseQuantity)은
// GET /api/campaigns/{id}/status로 1초 폴링한다 — 폼(입력 요소)은 renderForm()에서 한 번만 그리고,
// 폴링 tick은 잔여 수치·배지·disabled만 갱신한다. 그래야 사용자가 입력 중인 수량이 폴링 재렌더로
// 지워지지 않는다.
//
// 주문 가능 상태(OPEN·SOLD_OUT)는 OrderRepository#lockOrderableCampaign(:61)과 campaign/AGENTS.md:25
// ("SOLD_OUT을 판매 가능 여부의 정합성 게이트로 사용하지 않는다")를 그대로 따른다 — SOLD_OUT은 전체
// 캠페인이 아니라 개별 SKU 재고 소진을 뜻할 수 있어 그 상태에서도 다른 SKU는 주문 가능하다.

import { api, idempotencyKeyFor, rotateIdempotencyKey, orderCreateScope } from '../common/api.js';
import { html, raw, idParam } from '../common/html.js';
import { formatMoney, formatDateTime } from '../common/format.js';
import { messageFor } from '../common/codes.js';
import { poll } from '../common/poll.js';

// 서버 게이트(OrderRepository#lockOrderableCampaign)와 같은 집합.
const ORDERABLE_STATUSES = new Set(['OPEN', 'SOLD_OUT']);

const NOT_ORDERABLE_REASONS = {
  DRAFT: '아직 준비 중인 캠페인입니다.',
  REVIEWING: '심사 중인 캠페인입니다.',
  SCHEDULED: '아직 판매가 시작되지 않았습니다.',
  CLOSED: '판매가 종료되었습니다.',
  CANCELLED: '취소된 캠페인입니다.',
  SETTLING: '판매가 종료되어 정산 중입니다.',
  SETTLED: '판매가 종료되어 정산이 완료되었습니다.',
};

let user = null;
let campaignId = null;
let campaign = null; // CampaignResponse — 한 번만 불러온다
let latestStatus = null; // CampaignStatusResponse — 1초마다 갱신
let submitting = false;

export async function initCampaignPage(bootedUser) {
  user = bootedUser;
  campaignId = idParam('id');
  if (campaignId === null) {
    showFatalMessage('잘못된 캠페인 ID입니다.');
    return; // §html.js 규칙: id가 없으면 API를 호출하지 않는다
  }

  try {
    const { data } = await api(`/api/campaigns/${campaignId}`);
    campaign = data;
  } catch (err) {
    showFatalMessage(html`캠페인을 불러오지 못했습니다: ${messageFor(err)} (요청 ID ${err.requestId ?? '-'})`);
    return;
  }

  document.getElementById('camp-body').hidden = false;
  renderStatic();
  renderForm();
  wireForm();
  startStatusPolling();
}

function showFatalMessage(message) {
  const el = document.getElementById('camp-error');
  if (el) el.innerHTML = html`<div class="notice notice--danger">${message}</div>`;
}

function isOrderable(status) {
  return ORDERABLE_STATUSES.has(status);
}

function reasonForStatus(status) {
  return NOT_ORDERABLE_REASONS[status] ?? '지금은 주문할 수 없습니다.';
}

// --- 정적 정보 (한 번만 렌더) --------------------------------------------

function renderStatic() {
  document.getElementById('camp-name').textContent = campaign.name;
  document.getElementById('camp-price').textContent = formatMoney(campaign.dealPrice);
  document.getElementById('camp-limit').textContent = `${campaign.perUserPurchaseLimit}개`;
  document.getElementById('camp-period').textContent =
    `${formatDateTime(campaign.startsAt)} ~ ${formatDateTime(campaign.endsAt)}`;
}

function renderForm() {
  const container = document.getElementById('camp-sku-list');
  const skus = campaign.skus ?? [];
  container.innerHTML = skus.map((s) => html`
    <div class="camp-sku-row" data-sku-id="${s.productSkuId}">
      <label for="camp-qty-${s.productSkuId}">${s.optionName}</label>
      <input type="number" id="camp-qty-${s.productSkuId}" data-sku-id="${s.productSkuId}" min="0" step="1" value="0" inputmode="numeric">
      <span class="camp-sku-row__available" data-field="available">확인 중…</span>
    </div>
  `).join('');
}

function wireForm() {
  const form = document.getElementById('camp-order-form');
  form.addEventListener('submit', onSubmit);
  form.addEventListener('input', (event) => {
    if (event.target.matches('input[data-sku-id]')) updateSubmitState();
  });
}

// --- 잔여 재고·인당 한도 폴링 (1초, 타임아웃 없음 — §5.5) ------------------

function startStatusPolling() {
  poll({
    fn: () => api(`/api/campaigns/${campaignId}/status`),
    until: () => false,
    intervalMs: 1000,
    maxIntervalMs: 2000,
    timeoutMs: Infinity,
    onTick: (result, error) => {
      if (error) {
        setPollError(error);
        return;
      }
      setPollError(null);
      latestStatus = result.data;
      applyStatus(latestStatus);
    },
  });
}

function setPollError(error) {
  const el = document.getElementById('camp-poll-error');
  if (!el) return;
  if (!error) {
    el.innerHTML = '';
    return;
  }
  el.innerHTML = html`<div class="notice notice--danger">갱신 실패 — 마지막 값을 표시 중입니다: ${messageFor(error)} (요청 ID ${error.requestId ?? '-'})</div>`;
}

/** 폴링 tick마다 호출 — 배지·잔여 수치·SKU별 잔여만 갱신한다. 입력 요소 자체는 절대 다시 그리지 않는다. */
function applyStatus(status) {
  const badge = document.getElementById('camp-status-badge');
  if (badge) badge.setAttribute('value', status.status);

  const remainingEl = document.getElementById('camp-remaining');
  if (remainingEl) remainingEl.textContent = `${status.remainingPurchaseQuantity}개`;

  const orderable = isOrderable(status.status);
  (status.skus ?? []).forEach((s) => {
    const row = document.querySelector(`.camp-sku-row[data-sku-id="${s.productSkuId}"]`);
    if (!row) return;
    const soldOut = s.availableQuantity <= 0;
    const availEl = row.querySelector('[data-field="available"]');
    if (availEl) availEl.textContent = soldOut ? '품절' : `잔여 ${s.availableQuantity}개`;
    const input = row.querySelector('input[data-sku-id]');
    if (input) {
      input.max = String(Math.max(0, s.availableQuantity));
      input.disabled = !orderable || soldOut;
    }
  });

  const reasonEl = document.getElementById('camp-status-reason');
  if (reasonEl) {
    reasonEl.innerHTML = orderable
      ? ''
      : html`<div class="notice notice--warning">${reasonForStatus(status.status)}</div>`;
  }

  updateSubmitState();
}

// --- 주문 입력·제출 -------------------------------------------------------

function collectItems() {
  const inputs = document.querySelectorAll('#camp-sku-list input[data-sku-id]');
  const items = [];
  inputs.forEach((input) => {
    if (input.disabled) return; // 품절·주문불가 상태의 SKU는 값이 남아 있어도 제외한다
    const qty = Math.max(0, Math.trunc(Number(input.value) || 0));
    if (qty > 0) items.push({ productSkuId: Number(input.dataset.skuId), quantity: qty });
  });
  return items;
}

function updateSubmitState() {
  const btn = document.getElementById('camp-submit-btn');
  const summaryEl = document.getElementById('camp-order-summary');
  if (!btn) return;

  const items = collectItems();
  const sum = items.reduce((total, item) => total + item.quantity, 0);
  const orderable = latestStatus ? isOrderable(latestStatus.status) : false;
  const remaining = latestStatus ? latestStatus.remainingPurchaseQuantity : 0;
  const overLimit = sum > remaining;

  btn.disabled = submitting || !orderable || sum === 0 || overLimit;

  if (summaryEl) {
    if (sum === 0) {
      summaryEl.textContent = '';
      summaryEl.classList.remove('camp-order-summary--over');
    } else if (overLimit) {
      summaryEl.textContent = `선택 수량 합계 ${sum}개 — 인당 잔여 구매 가능 수량(${remaining}개)을 초과했습니다.`;
      summaryEl.classList.add('camp-order-summary--over');
    } else {
      summaryEl.textContent = `선택 수량 합계: ${sum}개 (잔여 구매 가능 ${remaining}개)`;
      summaryEl.classList.remove('camp-order-summary--over');
    }
  }
}

async function onSubmit(event) {
  event.preventDefault();
  if (submitting) return;
  const items = collectItems();
  if (items.length === 0) return;

  submitting = true;
  updateSubmitState();
  setOrderNotice(null);

  const scope = orderCreateScope(user.id, campaignId);
  const key = idempotencyKeyFor(scope);

  try {
    const { data } = await api(`/api/campaigns/${campaignId}/orders`, {
      method: 'POST',
      body: { items },
      idempotencyKey: key,
    });
    rotateIdempotencyKey(scope); // §5.4: 주문 201 → rotate
    location.href = `payment.html?orderId=${data.id}`;
    return; // 페이지를 떠나므로 아래 finally에서 버튼을 되살릴 필요가 없다
  } catch (err) {
    handleOrderError(err, scope);
  }

  submitting = false;
  updateSubmitState();
}

/**
 * §5.4 멱등 키 표를 그대로 따른다.
 * - 409 IDEMPOTENCY_KEY_REUSED → rotate (이 키로 이미 주문이 커밋됨) → orders.html 안내
 * - 네트워크 오류·5xx → 키 유지, 같은 키로 재전송 가능한 버튼 제공
 * - 그 외 4xx problem(INVENTORY_SOLD_OUT, PURCHASE_LIMIT_EXCEEDED, CAMPAIGN_NOT_ORDERABLE 등) → 키 유지해도 무방, 메시지만 표시
 */
function handleOrderError(err, scope) {
  if (err.code === 'IDEMPOTENCY_KEY_REUSED') {
    rotateIdempotencyKey(scope);
    setOrderNotice({
      message: '이 요청으로 주문이 이미 생성되었습니다.',
      level: 'warning',
      showOrdersLink: true,
    });
    return;
  }
  const isNetworkOrServerError = err.code === 'NETWORK_ERROR' || (typeof err.status === 'number' && err.status >= 500);
  if (isNetworkOrServerError) {
    setOrderNotice({
      message: '결과를 확인하지 못했습니다 — 같은 요청으로 다시 시도해 주세요.',
      level: 'danger',
      showRetry: true,
    });
    return;
  }
  // IDEMPOTENCY_KEY_EXPIRED 등도 여기로 오면 사용자가 "다시 시도"로 새 제출을 하면 서버가 안내한다.
  setOrderNotice({
    message: html`${messageFor(err)} (요청 ID ${err.requestId ?? '-'})`,
    level: 'danger',
  });
}

function setOrderNotice(opts) {
  const el = document.getElementById('camp-order-notice');
  if (!el) return;
  if (!opts) {
    el.innerHTML = '';
    return;
  }
  let extra = '';
  if (opts.showOrdersLink) {
    extra = html`<div><a href="orders.html">내 주문 목록에서 확인</a></div>`;
  } else if (opts.showRetry) {
    extra = html`<div><button type="button" class="btn btn-secondary btn-sm" id="camp-retry-btn">같은 요청으로 다시 시도</button></div>`;
  }
  el.innerHTML = html`<div class="notice notice--${opts.level}">${opts.message}</div>${raw(extra)}`;
  if (opts.showRetry) {
    const retryBtn = document.getElementById('camp-retry-btn');
    if (retryBtn) {
      retryBtn.addEventListener('click', () => {
        document.getElementById('camp-order-form').requestSubmit();
      });
    }
  }
}
