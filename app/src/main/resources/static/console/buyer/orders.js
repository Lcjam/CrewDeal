// orders.js — 구매자 내 주문 화면 (ORD-03, 프론트엔드 계획 §6 "구매자", §5.5 "주문" 폴링 행)
//
// GET /api/orders는 buyer_id로 필터되고 created_at DESC, id DESC로 온다 (OrderRepository:158-165,
// LIMIT 없음 — 표시용 안내 문구가 필요 없다). 이 화면의 핵심은 PENDING_PAYMENT 주문이 카운트다운 끝에
// EXPIRED로 바뀌고 재고가 돌아오는 순간까지 화면이 저절로 따라가는 것이다.
//
// 목록은 행을 재사용한다(DOM을 통째로 교체하지 않는다) — 그래야 "주문 취소" 버튼이 처리 중일 때
// 다음 폴링 틱이 그 버튼을 되살리지 않는다. 캠페인 이름·SKU 옵션명은 refcache로 캠페인당 한 번만
// 가져와 캐시하고, 실패하면 ID로 대체한다 (§5.6, §5.8).

import { api } from '../common/api.js';
import { messageFor } from '../common/codes.js';
import { html, raw } from '../common/html.js';
import { formatMoney } from '../common/format.js';
import { poll, renderContinueWatching } from '../common/poll.js';
import { campaignName as fetchCampaignName, skuOptionName as fetchSkuOptionName } from '../common/refcache.js';

let user = null;
let orders = [];
let listPollHandle = null;
let plannedDeadline = null; // ms epoch — 지금 예약된 poll이 커버하는 만료 시각(+10s)

// orderId -> { tr, campaignCell, itemsCell, remarksCell, actionCell }
const rowRefs = new Map();
// orderId -> { loaded, campaignName, campaignNameFailed, labelByItemId: Map<itemId,label> }
const orderDisplay = new Map();
// orderId -> 마지막으로 관찰한 상태. PENDING_PAYMENT -> EXPIRED 전이를 감지하는 데 쓴다.
const lastKnownStatus = new Map();
// orderId -> Map<productSkuId, availableQuantity> — PENDING일 때 한 번 찍어 둔 "만료 전" 재고.
const beforeSnapshots = new Map();
// orderId -> Map<productSkuId, availableQuantity> — EXPIRED 전이를 본 직후 찍은 "현재" 재고.
const afterSnapshots = new Map();
// 지금 취소 요청이 진행 중인 orderId — 이 동안은 폴링이 그 행의 조작 칸을 다시 그리지 않는다.
const cancellingIds = new Set();

export async function initOrdersPage(bootedUser) {
  user = bootedUser;
  wireRowEvents();
  await refreshOnce();
}

// --- 알림 -------------------------------------------------------------

function showNotice(message, level = 'success') {
  const el = document.getElementById('notice');
  if (!el) return;
  el.className = `notice notice--${level}`;
  el.textContent = message;
}

/**
 * 목록 조회(최초 로드·폴링 틱) 실패 전용 알림 — 취소 결과 알림(#notice)과 분리한다. 그래야
 * 오류 뒤에 다음 틱이 성공했을 때 이 알림만 지우면 되고, 취소 성공/실패 메시지가 그 사이에
 * #notice에 떠 있어도 건드리지 않는다. "다시 불러오기" 버튼으로 최초 로드 실패도 재시도할 수 있다.
 */
function showPollError(err) {
  const el = document.getElementById('poll-error');
  if (!el) return;
  el.innerHTML = html`
    <div class="notice notice--danger">
      주문 목록을 불러오지 못했습니다: ${messageFor(err)} (요청 ID ${err.requestId ?? '-'})
      <button type="button" class="btn btn-secondary btn-sm" id="poll-error-retry">다시 불러오기</button>
    </div>
  `;
  const retryBtn = document.getElementById('poll-error-retry');
  if (retryBtn) retryBtn.addEventListener('click', () => { refreshOnce(); });
}

function clearPollError() {
  const el = document.getElementById('poll-error');
  if (el) el.innerHTML = '';
}

// --- 최초 로드 + 목록 갱신 처리 (초기 호출과 폴링 틱이 공유) --------------------

async function refreshOnce() {
  try {
    const { data } = await api('/api/orders');
    handleFetchedOrders(data);
  } catch (err) {
    showPollError(err);
  }
}

function handleFetchedOrders(newOrders) {
  clearPollError(); // 이 목록 조회는 성공했다 — 이전 실패 알림이 남아 있었다면 지운다.
  orders = newOrders;
  reconcileTable(orders);
  trackTransitions(orders);
  maybeStartOrRestartPolling(orders);
}

// --- 폴링: PENDING_PAYMENT가 있는 동안만, 없으면 멈춘다 (§5.5 "주문" 행) --------

function computeTimeoutMs(currentOrders, nowMs) {
  const pending = currentOrders.filter((o) => o.status === 'PENDING_PAYMENT');
  if (pending.length === 0) return null;
  const maxExpiry = Math.max(...pending.map((o) => new Date(o.expiresAt).getTime()));
  // §5.5: 종료 조건은 PENDING_PAYMENT가 아닐 때, 타임아웃은 expiresAt + 10초(만료 워커 5s 감안).
  return Math.max(1000, maxExpiry + 10000 - nowMs);
}

/**
 * 지금 PENDING_PAYMENT 주문이 있으면 그중 가장 늦게 만료되는 것 기준으로 폴링을 유지·시작하고,
 * 없으면 멈춘다. 이미 그 시각을 커버하는 폴링이 돌고 있으면 다시 만들지 않는다(매 틱 재시작 방지).
 */
function maybeStartOrRestartPolling(currentOrders) {
  const requiredTimeoutMs = computeTimeoutMs(currentOrders, Date.now());
  if (requiredTimeoutMs === null) {
    if (listPollHandle) { listPollHandle.stop(); listPollHandle = null; }
    plannedDeadline = null;
    hideGraceNotice();
    return;
  }
  const requiredDeadline = Date.now() + requiredTimeoutMs;
  if (listPollHandle && plannedDeadline && requiredDeadline <= plannedDeadline + 1000) {
    return; // 지금 폴링이 이미 이 시각까지 커버한다
  }
  if (listPollHandle) listPollHandle.stop();
  plannedDeadline = requiredDeadline;
  listPollHandle = poll({
    fn: () => api('/api/orders'),
    until: (result) => !result.data.some((o) => o.status === 'PENDING_PAYMENT'),
    intervalMs: 2000,
    maxIntervalMs: 5000,
    timeoutMs: requiredTimeoutMs,
    onTick: (result, error) => {
      if (error) {
        showPollError(error);
        return;
      }
      handleFetchedOrders(result.data);
    },
    onTimeout: () => {
      // poll.js가 이미 이 핸들을 'stopped'로 끝냈다 — 참조를 놓아야 다음 maybeStartOrRestartPolling
      // 호출(취소 조작 등)이 "이미 커버 중"으로 착각해 새 폴링을 건너뛰지 않는다.
      listPollHandle = null;
      plannedDeadline = null;
      showGraceNotice();
    },
  });
}

/** §5.5 타임아웃 안내: 결제가 PROCESSING·UNKNOWN·SUCCEEDED면 만료가 유예된다(OrderRepository:183-206). */
function showGraceNotice() {
  const container = document.getElementById('grace-notice');
  if (!container) return;
  container.innerHTML = html`
    <div class="notice notice--warning">
      예상한 시각이 지나도 아직 PENDING_PAYMENT인 주문이 있습니다.
      결제 확정 대기(PROCESSING·UNKNOWN·SUCCEEDED)로 만료가 유예되었을 수 있습니다 — 결제 내역에서 확인해 보세요.
    </div>
    <div id="grace-continue" class="continue-watching"></div>
  `;
  const continueEl = document.getElementById('grace-continue');
  renderContinueWatching(continueEl, {
    stop: () => { if (listPollHandle) listPollHandle.stop(); },
    restart: () => {
      hideGraceNotice();
      listPollHandle = null;
      plannedDeadline = null;
      maybeStartOrRestartPolling(orders);
    },
  });
}

function hideGraceNotice() {
  const container = document.getElementById('grace-notice');
  if (container) container.innerHTML = '';
}

// --- 만료 전/후 재고 스냅숏 (ORD-03 재고 복귀 관찰) -----------------------------

function trackTransitions(currentOrders) {
  currentOrders.forEach((order) => {
    const prevStatus = lastKnownStatus.get(order.id);
    if (order.status === 'PENDING_PAYMENT' && !beforeSnapshots.has(order.id)) {
      captureSnapshot(order, beforeSnapshots);
    }
    if (prevStatus === 'PENDING_PAYMENT' && order.status === 'EXPIRED' && !afterSnapshots.has(order.id)) {
      captureSnapshot(order, afterSnapshots, () => {
        const refs = rowRefs.get(order.id);
        if (refs) refs.remarksCell.innerHTML = remarksHtml(order);
      });
    }
    lastKnownStatus.set(order.id, order.status);
  });
}

/** 최선의 노력으로만 시도한다 — 실패해도 목록 표시 자체는 막지 않는다. */
async function captureSnapshot(order, store, onDone) {
  try {
    const { data } = await api(`/api/campaigns/${order.campaignId}/status`);
    const bySku = new Map(data.skus.map((s) => [s.productSkuId, s.availableQuantity]));
    const snapshot = new Map();
    order.items.forEach((item) => {
      if (bySku.has(item.productSkuId)) snapshot.set(item.productSkuId, bySku.get(item.productSkuId));
    });
    store.set(order.id, snapshot);
  } catch {
    // 조회 실패 — "재고 복귀 확인" 링크만으로 충분하다, 조용히 건너뛴다.
  } finally {
    if (onDone) onDone();
  }
}

// --- 표시용 데이터 로드 (캠페인 이름 · SKU 옵션명, §5.8 refcache) ----------------

async function loadOrderDisplay(order) {
  let campaignName = null;
  let campaignNameFailed = false;
  try {
    campaignName = await fetchCampaignName(order.campaignId);
  } catch {
    campaignNameFailed = true;
  }
  const labelByItemId = new Map();
  await Promise.all(order.items.map(async (item) => {
    let label;
    try {
      label = await fetchSkuOptionName(order.campaignId, item.productSkuId);
    } catch {
      label = `SKU #${item.productSkuId}`;
    }
    labelByItemId.set(item.id, label);
  }));
  orderDisplay.set(order.id, { loaded: true, campaignName, campaignNameFailed, labelByItemId });
  const refs = rowRefs.get(order.id);
  const current = orders.find((o) => o.id === order.id);
  if (refs && current) {
    refs.campaignCell.innerHTML = campaignCellHtml(current);
    refs.itemsCell.innerHTML = itemsHtml(current);
  }
}

// --- 렌더링: 행 재사용 (진행 중인 취소 조작을 깨지 않는다) ------------------------

function reconcileTable(currentOrders) {
  const tbody = document.getElementById('orders-tbody');
  if (!tbody) return;

  if (currentOrders.length === 0) {
    rowRefs.clear();
    tbody.innerHTML = html`
      <tr><td colspan="8">
        <div class="empty-state">
          아직 주문이 없습니다. <a href="/console/buyer/campaigns.html">공구 목록</a>에서 담아보세요.
        </div>
      </td></tr>
    `;
    return;
  }
  // 아직 실제 행을 하나도 안 만들었으면(첫 로드의 "불러오는 중" 자리표시자, 또는 방금 전까지
  // "주문 없음" 안내였던 경우) tbody를 비우고 시작한다.
  if (rowRefs.size === 0) {
    tbody.innerHTML = '';
  }

  let anchor = null;
  currentOrders.forEach((order) => {
    let refs = rowRefs.get(order.id);
    if (!refs) {
      refs = createRow(order);
      rowRefs.set(order.id, refs);
      loadOrderDisplay(order);
    } else {
      updateRowVariableParts(refs, order);
    }
    const expected = anchor ? anchor.nextSibling : tbody.firstChild;
    if (expected !== refs.tr) tbody.insertBefore(refs.tr, expected);
    anchor = refs.tr;
  });
}

function createRow(order) {
  const container = document.createElement('tbody');
  container.innerHTML = renderRowMarkup(order);
  const tr = container.querySelector('tr[data-order-id]');
  const refs = {
    tr,
    campaignCell: tr.querySelector('[data-field="campaign"]'),
    itemsCell: tr.querySelector('[data-field="items"]'),
    remarksCell: tr.querySelector('[data-field="remarks"]'),
    actionCell: tr.querySelector('[data-field="actions"]'),
  };
  return refs;
}

function renderRowMarkup(order) {
  return html`
    <tr data-order-id="${order.id}">
      <td>${order.id}</td>
      <td data-field="campaign">${campaignCellHtml(order)}</td>
      <td data-field="items">${itemsHtml(order)}</td>
      <td>${order.totalQuantity}</td>
      <td>${formatMoney(order.totalAmount)}</td>
      <td><status-badge domain="order" value="${order.status}"></status-badge></td>
      <td data-field="remarks">${remarksHtml(order)}</td>
      <td data-field="actions">${actionHtml(order)}</td>
    </tr>
  `;
}

/** 요약 칸만 고쳐 쓴다 — 조작 칸은 취소가 진행 중이면 건드리지 않는다. */
function updateRowVariableParts(refs, order) {
  refs.campaignCell.innerHTML = campaignCellHtml(order);
  refs.itemsCell.innerHTML = itemsHtml(order);
  const badge = refs.tr.querySelector('status-badge[domain="order"]');
  if (badge) badge.setAttribute('value', order.status);
  refs.remarksCell.innerHTML = remarksHtml(order);
  if (!cancellingIds.has(order.id)) {
    refs.actionCell.innerHTML = actionHtml(order);
  }
}

function campaignCellHtml(order) {
  const entry = orderDisplay.get(order.id);
  const href = `/console/buyer/campaign.html?id=${order.campaignId}`;
  if (!entry || !entry.loaded) {
    return html`<a href="${href}">#${order.campaignId}</a> <span class="muted">(불러오는 중…)</span>`;
  }
  const label = entry.campaignNameFailed || !entry.campaignName ? `#${order.campaignId}` : entry.campaignName;
  return html`<a href="${href}">${label}</a>`;
}

function itemsHtml(order) {
  const entry = orderDisplay.get(order.id);
  const lines = order.items.map((item) => {
    const label = entry?.loaded ? (entry.labelByItemId.get(item.id) ?? `SKU #${item.productSkuId}`) : `SKU #${item.productSkuId}`;
    return html`<div class="order-item-line">${label} × ${item.quantity}
      <status-badge domain="reservation" value="${item.reservationStatus}"></status-badge></div>`;
  });
  return raw(lines.join(''));
}

function remarksHtml(order) {
  if (order.status === 'PENDING_PAYMENT') {
    return html`<countdown-timer expires-at="${order.expiresAt}"></countdown-timer>`;
  }
  if (order.status === 'EXPIRED') {
    const href = `/console/buyer/campaign.html?id=${order.campaignId}`;
    const before = beforeSnapshots.get(order.id);
    const after = afterSnapshots.get(order.id);
    let diff = '';
    if (before && after) {
      const entry = orderDisplay.get(order.id);
      const parts = order.items
        .map((item) => {
          const b = before.get(item.productSkuId);
          const a = after.get(item.productSkuId);
          if (b === undefined || a === undefined) return null;
          const label = entry?.loaded ? (entry.labelByItemId.get(item.id) ?? `SKU #${item.productSkuId}`) : `SKU #${item.productSkuId}`;
          return `${label}: 만료 전 ${b} → 현재 ${a}`;
        })
        .filter((line) => line !== null);
      if (parts.length > 0) {
        diff = html`<div class="stock-diff">${parts.join(', ')}</div>`;
      }
    }
    return html`<a href="${href}">재고 복귀 확인</a>${diff}`;
  }
  return '-';
}

function actionHtml(order) {
  if (order.status === 'PENDING_PAYMENT') {
    return html`
      <a class="btn btn-primary btn-sm" href="/console/buyer/payment.html?orderId=${order.id}">결제하기</a>
      <button type="button" class="btn btn-danger btn-sm" data-action="cancel" data-id="${order.id}">주문 취소</button>
    `;
  }
  return html`<a class="btn btn-secondary btn-sm" href="/console/buyer/payment.html?orderId=${order.id}">결제 내역</a>`;
}

// --- 행 이벤트 위임 -----------------------------------------------------------

function wireRowEvents() {
  const tbody = document.getElementById('orders-tbody');
  if (!tbody) return;
  tbody.addEventListener('click', onRowClick);
}

function onRowClick(event) {
  const btn = event.target.closest('button[data-action="cancel"]');
  if (!btn) return;
  const id = Number(btn.dataset.id);
  handleCancelClick(id);
}

// --- 주문 취소 (반복 호출 안전, §5.4 주문 경로는 키 만료 검사가 없다) ---------------

async function handleCancelClick(orderId) {
  const confirmed = window.confirm('이 주문을 취소하시겠습니까? 예약된 재고가 해제됩니다.');
  if (!confirmed) return;

  cancellingIds.add(orderId);
  const refs = rowRefs.get(orderId);
  if (refs) {
    refs.actionCell.innerHTML = '<button type="button" class="btn btn-danger btn-sm" disabled>취소 처리 중…</button>';
  }
  // 주문 취소는 멱등 키가 없는 POST다 — 서버 쪽 조건부 UPDATE가 반복 호출을 안전하게 만든다
  // (OrderRepository#cancelOrder, 이미 CANCELLED·EXPIRED면 그 상태를 그대로 돌려준다).
  try {
    const { data, meta } = await api(`/api/orders/${orderId}/cancel`, { method: 'POST' });
    showNotice(`주문 ${orderId} 취소 처리 완료 (요청 ID ${meta.requestId})`, 'success');
    // 조작 칸을 다시 그리기 전에 플래그부터 지운다 — applyOrderUpdate가 updateRowVariableParts를
    // 부르는데, cancellingIds에 아직 남아 있으면 "취소 처리 중…" 버튼이 갱신되지 않고 그대로 남는다.
    cancellingIds.delete(orderId);
    applyOrderUpdate(data);
  } catch (err) {
    cancellingIds.delete(orderId);
    showNotice(`주문 ${orderId} 취소 실패: ${messageFor(err)} (요청 ID ${err.requestId ?? '-'})`, 'danger');
    const p = orders.find((o) => o.id === orderId);
    const refs2 = rowRefs.get(orderId);
    if (p && refs2) refs2.actionCell.innerHTML = actionHtml(p);
  }
}

/** 취소 응답을 폴링을 기다리지 않고 즉시 반영한다. */
function applyOrderUpdate(updated) {
  const idx = orders.findIndex((o) => o.id === updated.id);
  if (idx >= 0) orders[idx] = updated; else orders = [updated, ...orders];
  const refs = rowRefs.get(updated.id);
  if (refs) updateRowVariableParts(refs, updated);
  trackTransitions(orders);
  maybeStartOrRestartPolling(orders);
}
