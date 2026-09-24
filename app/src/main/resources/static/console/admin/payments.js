// payments.js — 운영자 결제 화면 (REC-02, REF-02, 프론트엔드 계획 §6 "운영자", §5.4, §5.5)
//
// 결제 목록은 3초마다 자동 새로고침되지만, 그 새로고침은 각 행의 "요약 칸"(상태 배지·금액·
// 시각)만 제자리에서 고쳐 쓰고 행을 재사용한다 — DOM 노드 자체(그리고 포커스)를 절대
// 파괴하지 않는다. 펼친 행의 상세 패널(결제 시도 이력 / PG 동기화 / 환불)은 한 번 만들어지면
// 그 자리에 남고, 오직 그 패널에 관련된 사용자 조작이나 그 조작이 시작한 폴링만 그 패널의
// 내용을 다시 그린다. 3초 목록 폴링은 상세 패널에 손대지 않는다 — 그래서 환불 사유를 타이핑
// 중이거나 버튼을 막 누른 순간에 새로고침이 끼어들어도 입력·클릭이 끊기지 않는다.

import { api, idempotencyKeyFor, rotateIdempotencyKey, refundScope } from '../common/api.js';
import { messageFor } from '../common/codes.js';
import { html, raw, idParam } from '../common/html.js';
import { formatMoney, formatDateTime, statusOf } from '../common/format.js';
import { poll, renderContinueWatching } from '../common/poll.js';
import { currentSummary } from './admin.js';

const ALL_STATUSES = ['READY', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'SUPERSEDED', 'REFUNDING', 'REFUNDED'];
// sync 버튼 대상 — §4 표 "READY/PROCESSING/UNKNOWN" 그대로. 오래된 순 정렬 조건과도 같은 집합.
const SYNCABLE_STATUSES = new Set(['READY', 'PROCESSING', 'UNKNOWN']);
// 운영자 결제 폴링 종료 조건 (§5.5 "결제 (운영자)" 행)
const PAYMENT_FINAL_STATUSES = new Set(['SUCCEEDED', 'FAILED', 'SUPERSEDED', 'REFUNDED']);

let user = null;
let payments = [];
let campaigns = [];
let selectedStatuses = new Set(['UNKNOWN']);
let selectedCampaignId = idParam('campaignId');
let listPollHandle = null;
let latestSummary = null;

// 다른 화면(대사 불일치 등)이 payments.html?orderId=로 넘어올 때 쓰는 목표 주문. 기본 필터
// (UNKNOWN만)에 없으면 전체 상태로 넓혀서 찾고, 찾으면 그 행을 펼쳐 눈에 띄게 한다. 한 번
// 처리하면 더 건드리지 않는다 — 사용자가 그 뒤에 필터를 바꾸는 것까지 막지 않기 위해서다.
let targetOrderId = idParam('orderId');
let targetOrderHandled = false;

// paymentId -> 상태. 상세 패널 렌더는 여기서만 읽는다.
const expanded = new Set();
const attemptsData = new Map(); // paymentId -> { loading, error, list }
const syncState = new Map(); // paymentId -> { syncing, result, error, polling, timedOut, pollHandle }
const refundState = new Map(); // paymentId -> { panelOpen, reasonDraft, orderId, submitting, error, resendAvailable, accepted, polling, timedOut, pollHandle, refunds, lastRequestId }

// paymentId -> { mainTr, detailTr } — 3초 목록 폴링이 재사용하는 실제 DOM 노드.
const rowRefs = new Map();

// paymentId -> 마지막으로 본 AdminPayment. 진행 중인 동기화·환불 폴링이 있는 결제가 지금
// 필터에서 빠져도(예: SUCCEEDED 필터인데 환불 접수로 REFUNDING이 됨) 그 행을 핀으로 고정해
// 계속 보여준다 — 그동안 sync/refund 폴링 콜백은 이 스냅숏으로 섹션을 계속 갱신한다.
const lastKnownPayments = new Map();

export async function initPaymentsPage(bootedUser) {
  user = bootedUser;
  wireFilterControls();
  wireRowEvents();
  await loadCampaigns();
  updateOrderingNotice();
  window.addEventListener('admin:summary', (event) => {
    latestSummary = event.detail;
    updateCapNotice();
  });
  startListPolling();
}

function getPayment(id) {
  const live = payments.find((p) => p.id === id);
  if (live) {
    lastKnownPayments.set(id, live);
    return live;
  }
  // 핀 고정된(필터 밖으로 나간) 행 — 마지막으로 본 값을 그대로 쓴다. 상태 배지 등은 새로
  // 고쳐지지 않지만, sync/refund 폴링이 그 섹션 자체는 계속 최신으로 갱신한다.
  return lastKnownPayments.get(id);
}

// --- 알림 -------------------------------------------------------------

function showNotice(message, level = 'success') {
  const el = document.getElementById('notice');
  if (!el) return;
  el.className = `notice notice--${level}`;
  el.textContent = message;
}

// --- 캠페인 드롭다운 ----------------------------------------------------

async function loadCampaigns() {
  try {
    const { data } = await api('/api/admin/campaigns'); // 파라미터 생략 = 전체 9개 상태
    campaigns = data;
  } catch (err) {
    campaigns = [];
    showNotice(`캠페인 목록을 불러오지 못했습니다: ${messageFor(err)} (요청 ID ${err.requestId ?? '-'})`, 'danger');
  }
  renderCampaignSelect();
}

function renderCampaignSelect() {
  const select = document.getElementById('campaign-filter');
  if (!select) return;
  const options = ['<option value="">전체 캠페인</option>'];
  campaigns.forEach((c) => {
    const [label] = statusOf('campaign', c.status);
    options.push(html`<option value="${c.id}">${c.name} (${label})</option>`);
  });
  if (selectedCampaignId && !campaigns.some((c) => c.id === selectedCampaignId)) {
    options.push(html`<option value="${selectedCampaignId}">캠페인 #${selectedCampaignId}</option>`);
  }
  select.innerHTML = options.join('');
  select.value = selectedCampaignId ? String(selectedCampaignId) : '';
}

// --- 필터 ---------------------------------------------------------------

function wireFilterControls() {
  const fieldset = document.getElementById('status-filter');
  fieldset.addEventListener('change', onStatusFilterChange);

  const campaignSelect = document.getElementById('campaign-filter');
  campaignSelect.addEventListener('change', () => {
    selectedCampaignId = campaignSelect.value ? Number(campaignSelect.value) : null;
    if (listPollHandle) listPollHandle.restart();
  });

  document.getElementById('refresh-button').addEventListener('click', () => {
    if (listPollHandle) listPollHandle.restart();
  });
}

function onStatusFilterChange() {
  const checked = [...document.querySelectorAll('#status-filter input[type=checkbox]:checked')].map((cb) => cb.value);
  if (checked.length === 0) {
    const unknownBox = document.querySelector('#status-filter input[value=UNKNOWN]');
    if (unknownBox) unknownBox.checked = true;
    selectedStatuses = new Set(['UNKNOWN']);
    showNotice('최소 하나의 상태를 선택해야 합니다 — UNKNOWN으로 되돌렸습니다.', 'warning');
  } else {
    selectedStatuses = new Set(checked);
  }
  updateOrderingNotice();
  if (listPollHandle) listPollHandle.restart();
}

function updateOrderingNotice() {
  const el = document.getElementById('ordering-note');
  if (!el) return;
  const oldestFirst = [...selectedStatuses].every((s) => SYNCABLE_STATUSES.has(s));
  el.textContent = oldestFirst
    ? '정렬: 선택한 상태가 모두 READY·PROCESSING·UNKNOWN이라 오래된 순으로 표시됩니다 (복구가 급한 건이 먼저 보입니다).'
    : '정렬: 최신순으로 표시됩니다.';
}

function updateCapNotice() {
  const el = document.getElementById('cap-note');
  if (!el) return;
  if (payments.length !== 100) {
    el.hidden = true;
    el.textContent = '';
    return;
  }
  el.hidden = false;
  const isUnknownOnly = selectedStatuses.size === 1 && selectedStatuses.has('UNKNOWN');
  if (isUnknownOnly) {
    const summary = latestSummary ?? currentSummary();
    const count = summary?.unknownPaymentCount;
    if (typeof count === 'number' && count > 100) {
      el.textContent = `100건 초과 — 오래된 순 100건만 표시 (전체 확정대기 ${count.toLocaleString('ko-KR')}건)`;
      return;
    }
  }
  el.textContent = '최대 100건까지 표시됩니다.';
}

// --- 목록 자동 새로고침 ---------------------------------------------------

function startListPolling() {
  if (listPollHandle) listPollHandle.stop();
  listPollHandle = poll({
    fn: fetchPayments,
    until: () => false,
    intervalMs: 3000,
    maxIntervalMs: 3000,
    timeoutMs: Infinity,
    onTick: (result, error) => {
      if (error) {
        showNotice(`결제 목록을 불러오지 못했습니다: ${messageFor(error)} (요청 ID ${error.requestId ?? '-'})`, 'danger');
        return;
      }
      payments = result.data;
      reconcileTable();
      updateCapNotice();
      if (targetOrderId && !targetOrderHandled) applyTargetOrder();
    },
  });
}

/**
 * `?orderId=`로 들어왔을 때: 지금 필터에 그 주문의 결제가 보이면 행을 펼치고 눈에 띄게
 * 한다. 안 보이면(기본 필터가 UNKNOWN만이라 그럴 때가 많다) 상태 필터를 전체로 넓혀 한 번
 * 더 찾는다. 그래도 없으면(캠페인 필터에 걸렸거나 이 주문에 결제가 없음) 포기한다 —
 * 계속 재시도하지 않는다.
 */
function applyTargetOrder() {
  const match = payments.find((p) => p.orderId === targetOrderId);
  if (match) {
    targetOrderHandled = true;
    if (!expanded.has(match.id)) {
      expanded.add(match.id);
      if (!attemptsData.has(match.id)) loadAttempts(match.id, match.orderId);
    }
    const refs = rowRefs.get(match.id);
    if (refs) {
      refs.detailTr.hidden = false;
      const btn = refs.mainTr.querySelector('button[data-action="toggle"]');
      if (btn) btn.textContent = '접기';
      refs.mainTr.classList.add('row-highlight');
      refs.mainTr.scrollIntoView({ block: 'center' });
    }
    return;
  }
  const allSelected = ALL_STATUSES.every((s) => selectedStatuses.has(s));
  if (!allSelected) {
    ALL_STATUSES.forEach((s) => {
      const box = document.querySelector(`#status-filter input[value=${s}]`);
      if (box) box.checked = true;
    });
    selectedStatuses = new Set(ALL_STATUSES);
    updateOrderingNotice();
    if (listPollHandle) listPollHandle.restart();
    return;
  }
  // 전체 상태로도 못 찾았다 — 캠페인 필터에 걸렸거나 이 주문에 결제가 없다. 더 시도하지 않는다.
  targetOrderHandled = true;
}

function fetchPayments() {
  const params = new URLSearchParams();
  params.set('status', [...selectedStatuses].join(','));
  if (selectedCampaignId) params.set('campaignId', String(selectedCampaignId));
  return api(`/api/admin/payments?${params.toString()}`);
}

// --- 목록 렌더 (행 재사용 — 상세 패널에는 손대지 않는다) --------------------

/**
 * 3초마다 불려온다. 새 행은 만들고, 사라진 행은 지우고(관련 폴링도 멈춘다), 남은 행은
 * 요약 칸만 제자리에서 고쳐 쓴다. 상세 패널(결제 시도 이력·PG 동기화·환불)의 DOM은 여기서
 * 절대 다시 만들지 않는다 — 그래서 입력 중인 텍스트나 막 누른 버튼이 끊기지 않는다.
 */
function reconcileTable() {
  const tbody = document.getElementById('payments-tbody');
  if (!tbody) return;

  const currentIds = new Set(payments.map((p) => p.id));

  // 필터에서 빠진 행: 진행 중인 동기화·환불이 있으면(핀 고정) 절대 지우지 않는다 — 지우면
  // 그 폴링이 멈추고(§ removeRow) 운영자가 환불이 COMPLETED/FAILED로 끝나는 걸 못 본다.
  // 폴링이 끝나면(다음 틱에 isRowPinned가 false가 됨) 그때 자연히 걷힌다.
  [...rowRefs.keys()].forEach((id) => {
    if (currentIds.has(id)) return;
    if (isRowPinned(id)) {
      markRowPinned(id, true);
      return;
    }
    removeRow(id);
  });

  // "결제가 없습니다"는 지금 필터에도, 핀 고정으로 남은 행에도 아무것도 없을 때만 보여준다.
  // rowRefs.size(핀 고정된 행 포함)가 아니라 payments.length로 먼저 판단해야 한다 — 그렇지
  // 않으면 행이 아직 하나도 만들어지지 않은 첫 틱(rowRefs가 비어 있는 게 당연한 순간)에
  // payments가 있어도 아래 생성 루프를 타기 전에 "없음"으로 잘못 그려버린다.
  if (payments.length === 0 && rowRefs.size === 0) {
    if (!tbody.querySelector('[data-placeholder]')) {
      tbody.innerHTML = '<tr data-placeholder="true"><td colspan="9">조건에 맞는 결제가 없습니다.</td></tr>';
    }
    return;
  }
  const placeholder = tbody.querySelector('[data-placeholder]');
  if (placeholder) placeholder.remove();

  // 순서가 실제로 바뀐 행만 옮긴다. appendChild(existingNode)는 같은 노드라도 브라우저가
  // "제거 후 삽입"으로 처리해 그 안의 포커스를 끊는다(직접 확인됨) — 그래서 이미 맞는
  // 위치에 있는 행은 절대 건드리지 않는다. 핀 고정된 행(지금 목록에 없음)은 옮기지 않고
  // 있던 자리에 그대로 둔다.
  let anchor = null; // 직전까지 올바르게 놓인 detailTr (null이면 tbody 맨 앞부터)
  payments.forEach((p) => {
    // createRowPair()가 성공하면 rowRefs에 스스로 등록한다 (상세 패널 구역을 채우려면
    // getSectionEl()이 그 시점부터 refs를 찾을 수 있어야 하기 때문).
    const refs = rowRefs.get(p.id) ?? createRowPair(p);
    updateRowSummary(refs, p);
    markRowPinned(p.id, false);
    refreshSectionsIfStatusChanged(refs, p);

    const expectedMain = anchor ? anchor.nextSibling : tbody.firstChild;
    if (expectedMain !== refs.mainTr) {
      tbody.insertBefore(refs.mainTr, expectedMain);
    }
    if (refs.mainTr.nextSibling !== refs.detailTr) {
      tbody.insertBefore(refs.detailTr, refs.mainTr.nextSibling);
    }
    anchor = refs.detailTr;
  });
}

function removeRow(id) {
  const refs = rowRefs.get(id);
  if (refs) {
    refs.mainTr.remove();
    refs.detailTr.remove();
    rowRefs.delete(id);
  }
  expanded.delete(id);
  attemptsData.delete(id);
  lastKnownPayments.delete(id);
  const sync = syncState.get(id);
  if (sync?.pollHandle) sync.pollHandle.stop();
  syncState.delete(id);
  const refund = refundState.get(id);
  if (refund?.pollHandle) refund.pollHandle.stop();
  refundState.delete(id);
}

/** 진행 중인 동기화나 환불이 있어서 지금 필터에서 빠져도 행을 지우면 안 되는지. */
function isRowPinned(id) {
  // timedOut도 핀 대상이다 — "계속 지켜보기"가 떠 있는 동안 행이 사라지면 그 버튼도 같이
  // 사라져 다시 지켜볼 방법이 없어진다.
  const sync = syncState.get(id);
  if (sync && (sync.syncing || sync.polling || sync.timedOut)) return true;
  const refund = refundState.get(id);
  if (refund && (refund.submitting || refund.polling || refund.timedOut)) return true;
  return false;
}

function markRowPinned(id, pinned) {
  const refs = rowRefs.get(id);
  if (!refs) return;
  refs.mainTr.classList.toggle('row-pinned', pinned);
  const note = refs.mainTr.querySelector('[data-field="pinned-note"]');
  if (note) note.hidden = !pinned;
}

/** 환불 사유 textarea에 포커스가 있거나 제출 중이면(§M2) 지금은 그 섹션을 다시 그리지 않는다. */
function isRefundSectionBusy(id) {
  const state = refundState.get(id);
  if (state?.submitting) return true;
  const active = document.activeElement;
  return !!(active && active.dataset?.role === 'refund-reason' && Number(active.dataset.id) === id);
}

/**
 * 목록 폴링이 가져온 p.status가 그 행의 동기화·환불 섹션을 마지막으로 그렸을 때의 상태와
 * 다르면 다시 그린다 — sync/refund 배지는 상태 배지처럼 자동으로 안 바뀌기 때문이다
 * (UNKNOWN→SUCCEEDED로 바뀌어도 그려 둔 "동기화" 버튼이 그대로 남는 문제, §M2).
 * 환불 섹션은 사용자가 타이핑·제출 중이면 미루고, focusout이나 제출 완료 시 따라잡는다.
 */
function refreshSectionsIfStatusChanged(refs, p) {
  if (refs.lastSyncStatus !== p.status) {
    updateSyncSection(p);
    refs.lastSyncStatus = p.status;
  }
  if (refs.lastRefundStatus !== p.status) {
    if (isRefundSectionBusy(p.id)) {
      refs.refundStatusPending = true;
    } else {
      updateRefundSection(p);
      refs.lastRefundStatus = p.status;
      refs.refundStatusPending = false;
    }
  }
}

/** 행이 처음 나타날 때만 부른다. 상세 패널의 세 구역을 채워 넣는다. */
function createRowPair(p) {
  const container = document.createElement('tbody');
  container.innerHTML = renderRowPairMarkup(p);
  const refs = {
    mainTr: container.querySelector('tr[data-row-id]'),
    detailTr: container.querySelector('tr.row-detail'),
  };
  // rowRefs를 먼저 채운다 — 아래 updateXSection()들이 getSectionEl()로 detailTr을 찾을 때
  // (아직 실제 문서에 붙지 않은 조각 안에서도) refs를 통해 찾도록 하기 위해서다.
  rowRefs.set(p.id, refs);
  updateAttemptsSection(p);
  updateSyncSection(p);
  updateRefundSection(p);
  refs.lastSyncStatus = p.status;
  refs.lastRefundStatus = p.status;
  return refs;
}

/**
 * 상세 패널의 한 구역(attempts/sync/refund) 컨테이너를 찾는다. 전역 document.getElementById가
 * 아니라 그 행의 detailTr 안에서 찾는다 — 행이 막 만들어져 아직 실제 문서에 붙기 전(createRowPair
 * 내부)에도 정확히 찾아야 하기 때문이다. document.getElementById는 그 순간 항상 null을 반환해
 * 동기화·환불 구역이 영영 비어 있게 되는 버그가 있었다.
 */
function getSectionEl(kind, id) {
  const refs = rowRefs.get(id);
  if (!refs) return null;
  return refs.detailTr.querySelector(`#${kind}-container-${id}`);
}

function renderRowPairMarkup(p) {
  const isOpen = expanded.has(p.id);
  const ledgerHref = `ledger.html?campaignId=${p.campaignId}&orderId=${p.orderId}`;
  return html`
    <tr data-row-id="${p.id}">
      <td>${p.id}</td>
      <td><a href="${ledgerHref}">주문 ${p.orderId}</a></td>
      <td data-field="campaign">${p.campaignName ?? `#${p.campaignId}`}</td>
      <td><status-badge domain="payment" value="${p.status}"></status-badge></td>
      <td data-field="amount">${formatMoney(p.amount)}</td>
      <td class="mono" data-field="provider">${p.providerPaymentId ?? '-'}</td>
      <td data-field="created">${formatDateTime(p.createdAt)}</td>
      <td data-field="approved">${formatDateTime(p.approvedAt)}</td>
      <td class="row-actions">
        <button type="button" class="btn btn-secondary btn-sm" data-action="toggle" data-id="${p.id}" data-order-id="${p.orderId}">${isOpen ? '접기' : '이력·조작'}</button>
        <div class="pinned-note" data-field="pinned-note" hidden>필터 밖 — 진행 중 작업 때문에 유지</div>
      </td>
    </tr>
    <tr class="row-detail" data-detail-id="${p.id}"${isOpen ? '' : ' hidden'}>
      <td colspan="9">
        <div class="detail-grid">
          <section class="detail-section" id="attempts-container-${p.id}"></section>
          <section class="detail-section" id="sync-container-${p.id}"></section>
          <section class="detail-section" id="refund-container-${p.id}"></section>
        </div>
      </td>
    </tr>
  `;
}

/** 요약 칸만 고쳐 쓴다 — 자식 노드를 새로 만들지 않으므로 포커스나 진행 중인 클릭을 건드리지 않는다. */
function updateRowSummary(refs, p) {
  const tr = refs.mainTr;
  const campaignCell = tr.querySelector('[data-field="campaign"]');
  if (campaignCell) campaignCell.textContent = p.campaignName ?? `#${p.campaignId}`;
  const badge = tr.querySelector('status-badge');
  if (badge) badge.setAttribute('value', p.status);
  const amountCell = tr.querySelector('[data-field="amount"]');
  if (amountCell) amountCell.textContent = formatMoney(p.amount);
  const providerCell = tr.querySelector('[data-field="provider"]');
  if (providerCell) providerCell.textContent = p.providerPaymentId ?? '-';
  const createdCell = tr.querySelector('[data-field="created"]');
  if (createdCell) createdCell.textContent = formatDateTime(p.createdAt);
  const approvedCell = tr.querySelector('[data-field="approved"]');
  if (approvedCell) approvedCell.textContent = formatDateTime(p.approvedAt);
}

// --- 상세 패널: 결제 시도 이력 (행 재사용, 이 구역만 다시 그린다) -------------

function updateAttemptsSection(p) {
  const el = getSectionEl('attempts', p.id);
  if (!el) return;
  el.innerHTML = renderAttemptsBody(p);
}

function renderAttemptsBody(p) {
  const state = attemptsData.get(p.id);
  let body;
  if (!state || state.loading) {
    body = '<p class="muted">불러오는 중…</p>';
  } else if (state.error) {
    body = html`<div class="notice notice--danger">이력을 불러오지 못했습니다: ${messageFor(state.error)} (요청 ID ${state.error.requestId ?? '-'})</div>`;
  } else if (!state.list.length) {
    body = '<p class="muted">결제 시도 이력이 없습니다.</p>';
  } else {
    const rows = state.list.map((attempt) => html`<tr class="${attempt.id === p.id ? 'sub-table__row--current' : ''}">
        <td>${attempt.id}</td>
        <td><status-badge domain="payment" value="${attempt.status}"></status-badge></td>
        <td>${formatMoney(attempt.amount)}</td>
        <td class="mono">${attempt.providerPaymentId ?? '-'}</td>
        <td>${formatDateTime(attempt.approvedAt)}</td>
        <td>${attempt.failureCode ?? '-'}</td>
        <td>${attempt.failureReason ?? '-'}</td>
      </tr>`).join('');
    body = html`<table class="sub-table"><thead><tr>
        <th>결제ID</th><th>상태</th><th>금액</th><th>PG 결제ID</th><th>승인시각</th><th>실패코드</th><th>실패사유</th>
      </tr></thead><tbody>${raw(rows)}</tbody></table>`;
  }
  return html`<h3>결제 시도 이력 <button type="button" class="btn btn-secondary btn-sm" data-action="reload-attempts" data-id="${p.id}" data-order-id="${p.orderId}">새로고침</button></h3>
    ${raw(body)}`;
}

// --- 상세 패널: PG 동기화 --------------------------------------------------

function updateSyncSection(p) {
  const el = getSectionEl('sync', p.id);
  if (!el) return;
  el.innerHTML = renderSyncBody(p);
  attachContinueWatcher('sync', p.id);
}

function renderSyncBody(p) {
  const state = syncState.get(p.id);
  const canSync = SYNCABLE_STATUSES.has(p.status);
  if (!canSync && !state) {
    return html`<h3>PG 동기화</h3>
      <p class="muted">READY·PROCESSING·UNKNOWN 상태의 결제만 동기화할 수 있습니다. 지금은 최종 상태입니다.</p>`;
  }
  let body = '';
  if (canSync) {
    body += html`<button type="button" class="btn btn-secondary btn-sm" data-action="sync" data-id="${p.id}" data-order-id="${p.orderId}" ${state?.syncing ? 'disabled' : ''}>${state?.syncing ? '동기화 중…' : 'PG 재조회·동기화'}</button>`;
  }
  if (state?.error) {
    body += html`<div class="notice notice--danger">동기화 실패: ${messageFor(state.error)} (요청 ID ${state.error.requestId ?? '-'})</div>`;
  }
  if (state?.result) {
    body += html`<p>동기화 응답 상태: <status-badge domain="payment" value="${state.result.status}"></status-badge></p>`;
  }
  if (state?.polling) {
    body += '<p class="muted">확정될 때까지 결제 시도 이력을 폴링 중입니다…</p>';
  }
  if (state?.timedOut) {
    body += html`<div id="sync-continue-${p.id}" class="continue-watching"></div>`;
  }
  return html`<h3>PG 동기화</h3>${raw(body)}`;
}

// --- 상세 패널: 환불 --------------------------------------------------------

function updateRefundSection(p) {
  const el = getSectionEl('refund', p.id);
  if (!el) return;
  el.innerHTML = renderRefundBody(p);
  attachContinueWatcher('refund', p.id);
}

function renderRefundBody(p) {
  const state = refundState.get(p.id);
  const canRefund = p.status === 'SUCCEEDED';
  if (!canRefund && !state) {
    return html`<h3>환불</h3>
      <p class="muted">SUCCEEDED 상태의 결제만 환불할 수 있습니다.</p>`;
  }
  let body = '';
  if (!state || !state.panelOpen) {
    body += html`<button type="button" class="btn btn-danger btn-sm" data-action="open-refund" data-id="${p.id}" data-order-id="${p.orderId}" ${canRefund ? '' : 'disabled'}>환불</button>`;
  } else {
    body += html`<div class="refund-form">
      <label for="refund-reason-${p.id}">환불 사유 (선택)</label>
      <textarea id="refund-reason-${p.id}" data-role="refund-reason" data-id="${p.id}" rows="2">${state.reasonDraft ?? ''}</textarea>
      <div class="refund-form__actions">
        <button type="button" class="btn btn-danger btn-sm" data-action="submit-refund" data-id="${p.id}" data-order-id="${p.orderId}" ${state.submitting ? 'disabled' : ''}>${state.submitting ? '요청 중…' : '전체 환불 실행'}</button>
        <button type="button" class="btn btn-secondary btn-sm" data-action="cancel-refund" data-id="${p.id}">취소</button>
      </div>
    </div>`;
  }
  if (state?.error) {
    body += html`<div class="notice notice--danger">환불 요청 실패: ${messageFor(state.error)} (요청 ID ${state.error.requestId ?? '-'})</div>`;
    if (state.resendAvailable) {
      body += html`<button type="button" class="btn btn-secondary btn-sm" data-action="resend-refund" data-id="${p.id}">같은 키로 다시 시도</button>`;
    }
  }
  if (state?.accepted) {
    body += html`<div class="notice notice--success">환불 접수됨 (요청 ID ${state.lastRequestId ?? '-'}) — 완료까지 자동으로 확인합니다.</div>`;
  }
  if (state?.polling) {
    body += '<p class="muted">환불 상태를 폴링 중입니다…</p>';
  }
  if (state?.timedOut) {
    body += html`<div id="refund-continue-${p.id}" class="continue-watching"></div>`;
  }
  if (state?.refunds?.length) {
    body += renderRefundHistoryTable(state.refunds);
  }
  return html`<h3>환불 <span class="muted">— 결제가 SUCCEEDED→REFUNDING→REFUNDED로 바뀌는 것은 위 상태 배지로 확인하세요</span></h3>${raw(body)}`;
}

function renderRefundHistoryTable(refunds) {
  const rows = refunds.map((r) => html`<tr>
      <td>${r.id}</td>
      <td><status-badge domain="refund" value="${r.status}"></status-badge></td>
      <td>${formatMoney(r.amount)}</td>
      <td>${r.compensation ? 'Y' : 'N'}</td>
      <td>${r.reason ?? '-'}</td>
      <td>${r.failureCode ?? '-'}</td>
      <td>${r.failureReason ?? '-'}</td>
      <td>${formatDateTime(r.requestedAt)}</td>
      <td>${formatDateTime(r.completedAt)}</td>
    </tr>`).join('');
  return html`<table class="sub-table"><thead><tr>
      <th>환불ID</th><th>상태</th><th>금액</th><th>보상환불</th><th>사유</th><th>실패코드</th><th>실패사유</th><th>요청시각</th><th>완료시각</th>
    </tr></thead><tbody>${raw(rows)}</tbody></table>`;
}

function attachContinueWatcher(kind, id) {
  const state = kind === 'sync' ? syncState.get(id) : refundState.get(id);
  if (!state || !state.timedOut || !state.pollHandle) return;
  const sectionEl = getSectionEl(kind, id);
  const el = sectionEl?.querySelector(`#${kind}-continue-${id}`);
  if (!el) return;
  renderContinueWatching(el, {
    stop: state.pollHandle.stop,
    restart: () => {
      state.timedOut = false;
      state.polling = true;
      state.pollHandle.restart();
      const p = getPayment(id);
      if (p) { if (kind === 'sync') updateSyncSection(p); else updateRefundSection(p); }
    },
  });
}

// --- 행 이벤트 위임 (tbody는 교체되지 않으므로 리스너를 한 번만 붙인다) --------

function wireRowEvents() {
  const tbody = document.getElementById('payments-tbody');
  if (!tbody) return;
  tbody.addEventListener('click', onRowClick);
  tbody.addEventListener('input', onRowInput);
  tbody.addEventListener('focusout', onRowFocusOut);
}

function onRowClick(event) {
  const btn = event.target.closest('button[data-action]');
  if (!btn) return;
  const action = btn.dataset.action;
  const id = Number(btn.dataset.id);
  const orderId = btn.dataset.orderId ? Number(btn.dataset.orderId) : undefined;
  if (action === 'toggle') handleToggle(id, orderId);
  else if (action === 'reload-attempts') loadAttempts(id, orderId);
  else if (action === 'sync') handleSyncClick(id, orderId);
  else if (action === 'open-refund') handleOpenRefund(id, orderId);
  else if (action === 'cancel-refund') handleCancelRefund(id);
  else if (action === 'submit-refund') handleSubmitRefund(id, orderId);
  else if (action === 'resend-refund') handleResendRefund(id);
}

function onRowInput(event) {
  const el = event.target;
  if (el?.dataset?.role === 'refund-reason') {
    const id = Number(el.dataset.id);
    const state = refundState.get(id) ?? {};
    state.reasonDraft = el.value;
    refundState.set(id, state);
    // 상세 패널을 다시 그리지 않는다 — 타이핑 중 재렌더는 입력 포커스를 끊는다.
  }
}

/**
 * 환불 사유 textarea에서 포커스가 빠져나갈 때: 타이핑 중이라 미뤄뒀던 환불 섹션 갱신이
 * 있으면(§M2 refundStatusPending) 이제 적용한다.
 */
function onRowFocusOut(event) {
  const el = event.target;
  if (el?.dataset?.role !== 'refund-reason') return;
  const id = Number(el.dataset.id);
  const refs = rowRefs.get(id);
  if (!refs || !refs.refundStatusPending) return;
  const p = getPayment(id);
  if (p) {
    updateRefundSection(p);
    refs.lastRefundStatus = p.status;
  }
  refs.refundStatusPending = false;
}

// --- 결제 시도 이력 -------------------------------------------------------

function handleToggle(id, orderId) {
  const refs = rowRefs.get(id);
  if (!refs) return;
  const nowOpen = !expanded.has(id);
  if (nowOpen) {
    expanded.add(id);
    if (!attemptsData.has(id)) loadAttempts(id, orderId);
  } else {
    expanded.delete(id);
  }
  refs.detailTr.hidden = !nowOpen;
  const btn = refs.mainTr.querySelector('button[data-action="toggle"]');
  if (btn) btn.textContent = nowOpen ? '접기' : '이력·조작';
}

async function loadAttempts(id, orderId) {
  attemptsData.set(id, { loading: true, list: [] });
  const p = getPayment(id);
  if (p) updateAttemptsSection(p);
  try {
    const { data } = await api(`/api/orders/${orderId}/payments`);
    attemptsData.set(id, { loading: false, list: data });
  } catch (err) {
    attemptsData.set(id, { loading: false, error: err, list: [] });
  }
  const p2 = getPayment(id);
  if (p2) updateAttemptsSection(p2);
}

// --- PG 동기화 -------------------------------------------------------------

async function handleSyncClick(id, orderId) {
  const state = syncState.get(id) ?? {};
  state.syncing = true;
  state.error = null;
  syncState.set(id, state);
  const p0 = getPayment(id);
  if (p0) updateSyncSection(p0);
  try {
    const { data, meta } = await api(`/api/admin/payments/${id}/sync`, { method: 'POST' });
    state.result = data;
    state.syncing = false;
    showNotice(`결제 ${id} 동기화 완료: 상태 ${data.status} (요청 ID ${meta.requestId})`, 'success');
    if (SYNCABLE_STATUSES.has(data.status)) {
      startSyncPolling(id, orderId, state);
    } else {
      state.polling = false;
    }
  } catch (err) {
    state.syncing = false;
    state.error = err;
    showNotice(`결제 ${id} 동기화 실패: ${messageFor(err)} (요청 ID ${err.requestId ?? '-'})`, 'danger');
  }
  syncState.set(id, state);
  if (listPollHandle) listPollHandle.restart();
  const p1 = getPayment(id);
  if (p1) updateSyncSection(p1);
}

function startSyncPolling(id, orderId, state) {
  if (state.pollHandle) state.pollHandle.stop(); // 중복 폴링 방지 (Minor 3)
  state.polling = true;
  state.timedOut = false;
  const handle = poll({
    fn: () => api(`/api/orders/${orderId}/payments`),
    until: (result) => {
      const match = result.data.find((x) => x.id === id);
      return !!match && PAYMENT_FINAL_STATUSES.has(match.status);
    },
    intervalMs: 2000,
    maxIntervalMs: 8000,
    timeoutMs: 60000,
    onTick: (result, err) => {
      if (!err) {
        const match = result.data.find((x) => x.id === id);
        if (match) {
          state.result = match;
          if (PAYMENT_FINAL_STATUSES.has(match.status)) state.polling = false;
        }
      }
      const p = getPayment(id);
      if (p) updateSyncSection(p);
    },
    onTimeout: () => {
      state.polling = false;
      state.timedOut = true;
      const p = getPayment(id);
      if (p) updateSyncSection(p);
    },
  });
  state.pollHandle = handle;
  syncState.set(id, state);
}

// --- 환불 -------------------------------------------------------------------

function handleOpenRefund(id, orderId) {
  const state = refundState.get(id) ?? { reasonDraft: '' };
  state.panelOpen = true;
  state.orderId = orderId;
  state.error = null;
  refundState.set(id, state);
  const p = getPayment(id);
  if (p) updateRefundSection(p);
}

function handleCancelRefund(id) {
  const state = refundState.get(id);
  if (state) {
    state.panelOpen = false;
    refundState.set(id, state);
  }
  const p = getPayment(id);
  if (p) updateRefundSection(p);
}

async function handleSubmitRefund(id, orderId) {
  const state = refundState.get(id);
  if (!state) return;
  const reason = (state.reasonDraft ?? '').trim();
  const confirmed = window.confirm(
    `결제 ${id}건을 전액 환불하시겠습니까? 이 조작은 되돌릴 수 없습니다.${reason ? `\n사유: ${reason}` : ''}`);
  if (!confirmed) return;
  state.submitting = true;
  state.error = null;
  state.resendAvailable = false;
  refundState.set(id, state);
  const p = getPayment(id);
  if (p) updateRefundSection(p);
  await submitRefundRequest(id, orderId, state, reason);
}

function handleResendRefund(id) {
  const state = refundState.get(id);
  if (!state) return;
  state.submitting = true;
  state.error = null;
  refundState.set(id, state);
  const p = getPayment(id);
  if (p) updateRefundSection(p);
  submitRefundRequest(id, state.orderId, state, (state.reasonDraft ?? '').trim());
}

/**
 * 환불 요청 실행. 멱등 키 처리는 §5.4 표 그대로:
 * 202 → rotate. 네트워크 오류·5xx → 키 유지 + 같은 키로 재전송 제공.
 * IDEMPOTENCY_REQUEST_IN_PROGRESS → 키 유지, 재전송 대신 환불 목록 폴링으로 전환.
 * IDEMPOTENCY_KEY_EXPIRED → rotate. 그 외 4xx → 키 유지해도 무방, 메시지만 표시.
 */
async function submitRefundRequest(id, orderId, state, reason) {
  const scope = refundScope(user.id, id);
  const key = idempotencyKeyFor(scope);
  try {
    const { data, meta } = await api(`/api/payments/${id}/refunds`, {
      method: 'POST',
      body: reason ? { reason } : {},
      idempotencyKey: key,
    });
    rotateIdempotencyKey(scope);
    state.submitting = false;
    state.accepted = true;
    state.panelOpen = false;
    state.lastRequestId = meta.requestId;
    showNotice(`환불 접수됨 — 결제 ${id} (요청 ID ${meta.requestId})`, 'success');
    startRefundPolling(id, state);
  } catch (err) {
    state.submitting = false;
    if (err.code === 'NETWORK_ERROR' || (typeof err.status === 'number' && err.status >= 500)) {
      state.error = err;
      state.resendAvailable = true; // 응답을 못 받았을 수 있다 — 같은 키로 재전송 가능
    } else if (err.code === 'IDEMPOTENCY_REQUEST_IN_PROGRESS') {
      state.error = err;
      state.resendAvailable = false; // 재전송 루프 금지 — 폴링으로 전환
      state.panelOpen = false;
      startRefundPolling(id, state);
    } else if (err.code === 'IDEMPOTENCY_KEY_EXPIRED') {
      rotateIdempotencyKey(scope);
      state.error = err;
      state.resendAvailable = false;
    } else {
      // PAYMENT_NOT_REFUNDABLE, REFUND_ALREADY_EXISTS, REFUND_WINDOW_CLOSED 등 — 키 유지해도 무방
      state.error = err;
      state.resendAvailable = false;
    }
    showNotice(`환불 요청 실패 — 결제 ${id}: ${messageFor(err)} (요청 ID ${err.requestId ?? '-'})`, 'danger');
  }
  refundState.set(id, state);
  if (listPollHandle) listPollHandle.restart();
  const p = getPayment(id);
  if (p) updateRefundSection(p);
}

function startRefundPolling(id, state) {
  if (state.pollHandle) state.pollHandle.stop(); // 중복 폴링 방지 (Minor 3)
  state.polling = true;
  state.timedOut = false;
  const handle = poll({
    fn: () => api(`/api/payments/${id}/refunds`),
    until: (result) => {
      const list = result.data;
      return list.length > 0 && list[list.length - 1].status !== 'REQUESTED';
    },
    intervalMs: 2000,
    maxIntervalMs: 8000,
    timeoutMs: 60000,
    onTick: (result, err) => {
      if (!err) {
        state.refunds = result.data;
        const last = state.refunds[state.refunds.length - 1];
        if (last && last.status !== 'REQUESTED') {
          state.polling = false;
          state.accepted = false; // "완료까지 자동으로 확인합니다" 안내를 그만 보여준다 (Minor 2)
          // §5.4 확정 관찰 규칙: 응답 경로와 무관하게 폴링에서 확정을 보면 키를 rotate한다 —
          // 202를 못 본 IDEMPOTENCY_REQUEST_IN_PROGRESS·네트워크 오류 경로까지 덮는다 (Minor 1).
          rotateIdempotencyKey(refundScope(user.id, id));
        }
      }
      const p = getPayment(id);
      if (p) updateRefundSection(p);
    },
    onTimeout: () => {
      state.polling = false;
      state.timedOut = true;
      const p = getPayment(id);
      if (p) updateRefundSection(p);
    },
  });
  state.pollHandle = handle;
  refundState.set(id, state);
}
