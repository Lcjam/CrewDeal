// campaigns.js — 운영자 캠페인 승인함 (프론트엔드 계획 §6 "운영자", CAM-02, CAM-05)
//
// 기본 필터는 REVIEWING(승인 대기)이다. 5초마다 목록을 다시 읽되(1초 스케줄러가
// SCHEDULED→OPEN 등을 조작 없이 진행시킨다), 열려 있는 반려 사유 입력이나 정산 미리보기
// 패널을 갱신이 지우지 않도록 상태를 모듈 스코프 state에 두고 각 렌더에서 그대로 되살린다.
// 반려 사유 textarea에 포커스가 있는 동안은 이번 tick의 재렌더를 건너뛴다(입력 중 깜빡임 방지).
// 데이터 자체는 계속 최신으로 갱신되고, 포커스가 빠지면 다음 tick에서 반영된다.

import { api } from '/console/common/api.js';
import { bootAdmin } from '/console/admin/admin.js';
import { poll, renderContinueWatching } from '/console/common/poll.js';
import { html, raw } from '/console/common/html.js';
import { formatMoney, formatDateTime, STATUS_TABLES } from '/console/common/format.js';
import { messageFor } from '/console/common/codes.js';
import '/console/common/widgets.js';

const CAMPAIGN_STATUSES = [
  'DRAFT', 'REVIEWING', 'SCHEDULED', 'OPEN', 'SOLD_OUT', 'CLOSED', 'CANCELLED', 'SETTLING', 'SETTLED',
];

const POLL_INTERVAL_MS = 5000;
const POLL_TIMEOUT_MS = 30 * 60 * 1000; // "긴 타임아웃" — 30분 동안은 사람이 지켜본다고 가정.

const state = {
  statuses: new Set(['REVIEWING']),
  campaigns: [],
  loadError: null,
  openReject: new Map(),   // campaignId -> 입력 중인 반려 사유
  rejectError: new Map(),  // campaignId -> 반려 검증/서버 오류 메시지
  expandedPreview: new Set(), // 정산 미리보기 패널이 열려 있는 campaignId
  preview: new Map(),      // campaignId -> { loading, data, error }
};

let filterEl, tbodyEl, tableWrapEl, noticeEl, limitNoticeEl, pollStatusEl;
let pollHandle;

function showNotice(kind, message, requestId) {
  noticeEl.hidden = false;
  noticeEl.className = `notice notice--${kind}`;
  noticeEl.textContent = requestId ? `${message} (요청 ID: ${requestId})` : message;
}

function isEditingReject() {
  const active = document.activeElement;
  return !!(active && active.classList && active.classList.contains('js-reject-reason'));
}

async function fetchCampaigns() {
  if (state.statuses.size === 0) return { data: [] };
  const query = [...state.statuses].join(',');
  return api(`/api/admin/campaigns?status=${encodeURIComponent(query)}`);
}

function skuSummaryText(skus) {
  if (!skus || skus.length === 0) return '-';
  return skus.map((s) => `${s.optionName} ${s.availableQuantity}개`).join(' · ');
}

function commissionText(bp) {
  if (bp === null || bp === undefined) return '-';
  return `${(bp / 100).toFixed(2)}%`;
}

function actionsHtml(c) {
  const buttons = [];
  if (c.status === 'REVIEWING') {
    buttons.push(html`<button type="button" class="btn btn-primary" data-action="approve" data-id="${c.id}" data-name="${c.name}">승인</button>`);
    if (state.openReject.has(c.id)) {
      buttons.push(html`<button type="button" class="btn btn-secondary" data-action="reject-cancel" data-id="${c.id}">반려 취소</button>`);
    } else {
      buttons.push(html`<button type="button" class="btn btn-secondary" data-action="reject-open" data-id="${c.id}">반려</button>`);
    }
  } else if (c.status === 'SCHEDULED' || c.status === 'OPEN' || c.status === 'SOLD_OUT') {
    buttons.push(html`<button type="button" class="btn btn-danger" data-action="force-close" data-id="${c.id}" data-status="${c.status}" data-name="${c.name}">강제 종료</button>`);
  }
  const previewLabel = state.expandedPreview.has(c.id) ? '정산 미리보기 닫기' : '정산 미리보기';
  buttons.push(html`<button type="button" class="btn btn-secondary" data-action="preview-toggle" data-id="${c.id}">${previewLabel}</button>`);
  return buttons.join('');
}

function campaignRowHtml(c) {
  const nameBlock = c.rejectionReason
    ? raw(html`<div>${c.name}</div><div class="muted">반려 사유: ${c.rejectionReason}</div>`)
    : raw(html`<div>${c.name}</div>`);
  return html`<tr>
    <td class="row-name">${nameBlock}</td>
    <td>${c.supplierName}</td>
    <td>${c.productName}</td>
    <td>${formatMoney(c.dealPrice)}</td>
    <td>${formatDateTime(c.startsAt)} ~ ${formatDateTime(c.endsAt)}</td>
    <td>${c.perUserPurchaseLimit}개</td>
    <td>${commissionText(c.commissionRateBp)}</td>
    <td>${skuSummaryText(c.skus)}</td>
    <td><status-badge domain="campaign" value="${c.status}"></status-badge></td>
    <td class="actions-cell">
      ${raw(actionsHtml(c))}
      <div class="quick-links">
        <a href="/console/admin/payments.html?campaignId=${c.id}">결제</a>
        <a href="/console/admin/ledger.html?campaignId=${c.id}">원장</a>
        <a href="/console/admin/settlements.html?campaignId=${c.id}">정산</a>
      </div>
    </td>
  </tr>`;
}

function rejectPanelHtml(c) {
  const value = state.openReject.get(c.id) ?? '';
  const err = state.rejectError.get(c.id);
  return html`<div class="detail-panel">
    <label for="reject-reason-${c.id}">반려 사유 (필수)</label>
    <textarea id="reject-reason-${c.id}" class="js-reject-reason" data-id="${c.id}" rows="2">${value}</textarea>
    ${err ? raw(html`<div class="notice notice--danger" style="margin:8px 0">${err}</div>`) : ''}
    <button type="button" class="btn btn-danger" data-action="reject-submit" data-id="${c.id}">반려 확정</button>
    <button type="button" class="btn btn-secondary" data-action="reject-cancel" data-id="${c.id}">취소</button>
  </div>`;
}

function previewPanelHtml(c) {
  const entry = state.preview.get(c.id);
  if (!entry || entry.loading) {
    return html`<div class="detail-panel"><p class="muted">정산 미리보기를 불러오는 중입니다…</p></div>`;
  }
  if (entry.error) {
    return html`<div class="detail-panel">
      <div class="notice notice--danger" style="margin:0 0 8px">정산 미리보기를 불러오지 못했습니다: ${messageFor(entry.error)} (요청 ID: ${entry.error.requestId ?? '-'})</div>
      <button type="button" class="btn btn-secondary" data-action="preview-refresh" data-id="${c.id}">다시 시도</button>
    </div>`;
  }
  const b = entry.data;
  return html`<div class="detail-panel">
    <h3>정산 미리보기 (원장 계정 잔액에서 파생 — 재계산 아님)</h3>
    <dl class="preview-dl">
      <dt>정산 대상 주문 수</dt><dd>${b.settledOrderCount}건</dd>
      <dt>환불된 주문 수</dt><dd>${b.refundedOrderCount}건</dd>
      <dt>순매출액</dt><dd>${formatMoney(b.netSalesAmount)}</dd>
      <dt>공급사 지급액</dt><dd>${formatMoney(b.supplierPayable)}</dd>
      <dt>인플루언서 커미션</dt><dd>${formatMoney(b.influencerCommission)}</dd>
      <dt>PG 수수료</dt><dd>${formatMoney(b.pgFee)}</dd>
      <dt>플랫폼 수익</dt><dd>${formatMoney(b.platformRevenue)}</dd>
    </dl>
    <button type="button" class="btn btn-secondary" data-action="preview-refresh" data-id="${c.id}">새로고침</button>
  </div>`;
}

function detailRowHtml(c) {
  const parts = [];
  if (state.openReject.has(c.id)) parts.push(rejectPanelHtml(c));
  if (state.expandedPreview.has(c.id)) parts.push(previewPanelHtml(c));
  if (parts.length === 0) return '';
  return html`<tr class="detail-row"><td colspan="10">${raw(parts.join(''))}</td></tr>`;
}

function renderFilter() {
  filterEl.innerHTML = CAMPAIGN_STATUSES.map((s) => {
    const [label] = STATUS_TABLES.campaign[s] ?? [s];
    const checkedAttr = state.statuses.has(s) ? ' checked' : '';
    return `<label class="chip"><input type="checkbox" data-status="${s}"${checkedAttr}> ${label}</label>`;
  }).join('');
}

function renderTable() {
  if (state.loadError) {
    tbodyEl.innerHTML = html`<tr><td colspan="10">목록을 불러오지 못했습니다: ${messageFor(state.loadError)} (요청 ID: ${state.loadError.requestId ?? '-'})</td></tr>`;
    return;
  }
  if (state.statuses.size === 0) {
    tbodyEl.innerHTML = `<tr><td colspan="10">표시할 상태를 하나 이상 선택하세요.</td></tr>`;
    limitNoticeEl.hidden = true;
    return;
  }
  if (state.campaigns.length === 0) {
    tbodyEl.innerHTML = `<tr><td colspan="10">선택한 상태의 캠페인이 없습니다.</td></tr>`;
    limitNoticeEl.hidden = true;
    return;
  }
  tbodyEl.innerHTML = state.campaigns.map((c) => campaignRowHtml(c) + detailRowHtml(c)).join('');
  limitNoticeEl.hidden = state.campaigns.length < 100;
}

function render() {
  renderTable();
}

async function handleApprove(id, name) {
  if (!confirm(`캠페인 "${name}"을(를) 승인합니다 (REVIEWING → SCHEDULED). 계속할까요?`)) return;
  try {
    const { data, meta } = await api(`/api/admin/campaigns/${id}/approve`, { method: 'POST' });
    showNotice('success', `캠페인 "${name}"을(를) 승인했습니다. (상태: ${data.status})`, meta.requestId);
    pollHandle.restart();
  } catch (err) {
    showNotice('danger', messageFor(err), err.requestId);
  }
}

function handleRejectOpen(id) {
  if (!state.openReject.has(id)) state.openReject.set(id, '');
  render();
  document.getElementById(`reject-reason-${id}`)?.focus();
}

function handleRejectCancel(id) {
  state.openReject.delete(id);
  state.rejectError.delete(id);
  render();
}

async function handleRejectSubmit(id) {
  const reason = (state.openReject.get(id) ?? '').trim();
  if (!reason) {
    state.rejectError.set(id, '반려 사유를 입력해 주세요.');
    render();
    document.getElementById(`reject-reason-${id}`)?.focus();
    return;
  }
  try {
    const { data, meta } = await api(`/api/admin/campaigns/${id}/reject`, {
      method: 'POST',
      body: { reason },
    });
    state.openReject.delete(id);
    state.rejectError.delete(id);
    showNotice('success', `캠페인을 반려했습니다. (상태: ${data.status})`, meta.requestId);
    pollHandle.restart();
  } catch (err) {
    state.rejectError.set(id, `${messageFor(err)} (요청 ID: ${err.requestId ?? '-'})`);
    render();
  }
}

async function handleForceClose(id, lastKnownStatus, name) {
  // 목록은 최대 5초 전 스냅숏이고 1초 스케줄러가 그 사이 SCHEDULED→OPEN을 진행시킬 수 있다.
  // confirm() 문구가 실제로 일어날 전이와 어긋나지 않도록 다이얼로그 직전에 최신 상태를 다시 읽는다.
  let status = lastKnownStatus;
  try {
    const { data } = await api(`/api/campaigns/${id}`);
    status = data.status;
  } catch {
    // 조회 실패 시 마지막으로 알려진 상태로 계속 진행한다 — 강제 종료 자체를 막지 않는다.
  }
  if (status !== 'SCHEDULED' && status !== 'OPEN' && status !== 'SOLD_OUT') {
    showNotice('danger', `캠페인 상태가 바뀌어 지금은 강제 종료할 수 없습니다 (현재 상태: ${status}).`);
    pollHandle.restart();
    return;
  }
  const target = status === 'SCHEDULED' ? 'CANCELLED' : 'CLOSED';
  let message = `캠페인 "${name}"을(를) 강제 종료합니다 (${status} → ${target}).`;
  if (target === 'CLOSED') message += ' 이 시연 스택은 정산 유예가 0초라 종료 즉시 정산이 시작됩니다.';
  message += ' 계속할까요?';
  if (!confirm(message)) return;
  try {
    const { data, meta } = await api(`/api/admin/campaigns/${id}/cancel`, { method: 'POST' });
    showNotice('success', `캠페인을 종료했습니다. (상태: ${data.status})`, meta.requestId);
    pollHandle.restart();
  } catch (err) {
    showNotice('danger', messageFor(err), err.requestId);
  }
}

async function loadPreview(id) {
  state.preview.set(id, { loading: true, data: null, error: null });
  render();
  try {
    const { data } = await api(`/api/admin/campaigns/${id}/settlement-preview`);
    state.preview.set(id, { loading: false, data, error: null });
  } catch (err) {
    state.preview.set(id, { loading: false, data: null, error: err });
  }
  render();
}

function handlePreviewToggle(id) {
  if (state.expandedPreview.has(id)) {
    state.expandedPreview.delete(id);
    render();
    return;
  }
  state.expandedPreview.add(id);
  render();
  loadPreview(id);
}

function handlePreviewRefresh(id) {
  loadPreview(id);
}

function onFilterChange(event) {
  const cb = event.target.closest('input[type=checkbox][data-status]');
  if (!cb) return;
  if (cb.checked) state.statuses.add(cb.dataset.status);
  else state.statuses.delete(cb.dataset.status);
  render();
  pollHandle.restart();
}

function onTableClick(event) {
  const btn = event.target.closest('[data-action]');
  if (!btn) return;
  const id = Number(btn.dataset.id);
  switch (btn.dataset.action) {
    case 'approve': handleApprove(id, btn.dataset.name); break;
    case 'reject-open': handleRejectOpen(id); break;
    case 'reject-cancel': handleRejectCancel(id); break;
    case 'reject-submit': handleRejectSubmit(id); break;
    case 'force-close': handleForceClose(id, btn.dataset.status, btn.dataset.name); break;
    case 'preview-toggle': handlePreviewToggle(id); break;
    case 'preview-refresh': handlePreviewRefresh(id); break;
    default: break;
  }
}

function onTableInput(event) {
  const el = event.target;
  if (el.classList && el.classList.contains('js-reject-reason')) {
    state.openReject.set(Number(el.dataset.id), el.value);
  }
}

async function init() {
  const user = await bootAdmin();
  if (!user) return;

  filterEl = document.getElementById('campaigns-filter');
  tbodyEl = document.getElementById('campaigns-tbody');
  tableWrapEl = document.getElementById('campaigns-table');
  noticeEl = document.getElementById('campaigns-notice');
  limitNoticeEl = document.getElementById('campaigns-limit-notice');
  pollStatusEl = document.getElementById('campaigns-poll-status');

  filterEl.addEventListener('change', onFilterChange);
  tableWrapEl.addEventListener('click', onTableClick);
  tableWrapEl.addEventListener('input', onTableInput);

  // 필터 체크박스는 한 번만 그린다 — 매 tick 다시 그리면 타이핑/포커스와 무관하게라도
  // 체크박스 자체의 키보드 포커스가 5초마다 날아간다. 이후 상태는 사용자 클릭이 DOM을
  // 그대로 바꾸고(네이티브 checked), render()는 표만 다시 그린다.
  renderFilter();
  render();

  pollHandle = poll({
    fn: fetchCampaigns,
    until: () => false,
    intervalMs: POLL_INTERVAL_MS,
    maxIntervalMs: POLL_INTERVAL_MS,
    timeoutMs: POLL_TIMEOUT_MS,
    onTick: (result, error) => {
      if (error) {
        state.loadError = error;
      } else {
        state.loadError = null;
        state.campaigns = result.data ?? [];
      }
      if (!isEditingReject()) render();
    },
    onTimeout: () => renderContinueWatching(pollStatusEl, pollHandle, '계속 지켜보기'),
  });
}

init();
