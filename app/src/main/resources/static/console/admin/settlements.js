// settlements.js — 정산 배치 화면 (SET-01~03, 계약 §6 "운영자" settlements row)
//
// 목록은 3초 폴링(서버 정산 스케줄러 10초 주기 근거), 상세 패널은 재시도/보류/해제 조작 후에만
// 배치를 폴링한다(60초, 종료 상태 COMPLETED/FAILED/HELD). 3초 폴링은 목록 표(tbody)만 다시
// 그리므로 열려 있는 상세 패널·입력값을 건드리지 않는다.

import { api, ApiError } from '../common/api.js';
import { messageFor } from '../common/codes.js';
import { html, raw, escapeHtml, idParam } from '../common/html.js';
import { formatMoney, formatDateTime, statusOf } from '../common/format.js';
import { poll, renderContinueWatching } from '../common/poll.js';
import '../common/widgets.js';

const BATCH_STATUSES = ['PENDING', 'READY', 'PROCESSING', 'COMPLETED', 'FAILED', 'HELD'];
const TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED', 'HELD']);
const RETRYABLE = new Set(['FAILED', 'READY']);
const HOLDABLE = new Set(['PENDING', 'READY', 'FAILED']);

const state = {
  campaigns: [],
  detailBatchId: null,
  detailPollHandle: null,
  listPollHandle: null,
  listTickCount: 0,
  listRequestSeq: 0,
  timeline: [],
  lastTimelineStatus: null,
};

// 목록 3초 폴링 몇 회마다 run-campaign 드롭다운·미회수 조정을 함께 새로고침할지 (약 15초마다).
const PERIODIC_REFRESH_EVERY_N_TICKS = 5;

let els = {};
let notice;

function showNotice(kind, message, requestId) {
  const cls = kind === 'success' ? 'notice--success' : 'notice--danger';
  notice.className = `notice ${cls}`;
  notice.hidden = false;
  notice.innerHTML = html`${message}${requestId ? raw(` <span class="mono">(요청 ID: ${escapeHtml(requestId)})</span>`) : ''}`;
}

function clearNotice() {
  notice.hidden = true;
  notice.innerHTML = '';
}

// --- 캠페인 목록 (필터 드롭다운·정산 실행 드롭다운 공용) -----------------------------------

async function loadCampaigns() {
  const { data } = await api('/api/admin/campaigns');
  state.campaigns = data;
  renderCampaignFilterOptions();
  renderRunCampaignOptions();
}

function campaignLabel(c) {
  const [label] = statusOf('campaign', c.status);
  return `${c.name} (${label})`;
}

function renderCampaignFilterOptions() {
  // 최초 로드는 URL의 campaignId를, 이후 주기적 새로고침은 사용자가 이미 고른 값을 우선한다 —
  // 그래야 목록을 보던 중 드롭다운이 조용히 "전체 캠페인"으로 되돌아가지 않는다.
  const currentValue = els.filterCampaign.value;
  const urlPreselect = idParam('campaignId');
  const preselect = currentValue || (urlPreselect ? String(urlPreselect) : '');
  els.filterCampaign.innerHTML = html`<option value="">전체 캠페인</option>` +
    state.campaigns.map((c) => html`<option value="${c.id}">${campaignLabel(c)}</option>`).join('');
  if (preselect && state.campaigns.some((c) => String(c.id) === preselect)) {
    els.filterCampaign.value = preselect;
  }
}

function renderRunCampaignOptions() {
  const currentValue = els.runCampaign.value;
  const eligible = state.campaigns.filter((c) => c.status === 'CLOSED' || c.status === 'SETTLING');
  if (eligible.length === 0) {
    els.runCampaign.innerHTML = '';
    els.runCampaign.disabled = true;
    els.runButton.disabled = true;
    els.runEmpty.hidden = false;
    return;
  }
  els.runEmpty.hidden = true;
  els.runCampaign.disabled = false;
  els.runButton.disabled = false;
  els.runCampaign.innerHTML = eligible.map((c) => html`<option value="${c.id}">${campaignLabel(c)}</option>`).join('');
  if (currentValue && eligible.some((c) => String(c.id) === currentValue)) {
    els.runCampaign.value = currentValue;
  }
}

// --- 목록 --------------------------------------------------------------------------------

function currentStatusFilter() {
  const checked = [...els.statusChecks].filter((cb) => cb.checked).map((cb) => cb.value);
  return checked.length === BATCH_STATUSES.length ? '' : checked.join(',');
}

function currentCampaignFilter() {
  const value = els.filterCampaign.value;
  return value ? value : '';
}

/**
 * 필터 변경이 poll 핸들의 restart()로 들어오면, 아직 끝나지 않은 이전 필터의 요청과 경쟁할 수
 * 있다(응답 순서는 요청 순서와 다를 수 있다). 요청 시작마다 시퀀스를 올리고, 응답이 돌아왔을 때
 * 그 사이 더 최신 요청이 시작됐으면(시퀀스가 더 커졌으면) 렌더링하지 않는다 — 오래된 응답이
 * 최신 필터의 결과를 덮어쓰지 못하게 한다.
 */
async function fetchAndRenderList() {
  const seq = ++state.listRequestSeq;
  const params = new URLSearchParams();
  const campaignId = currentCampaignFilter();
  const status = currentStatusFilter();
  if (campaignId) params.set('campaignId', campaignId);
  if (status) params.set('status', status);
  const qs = params.toString();
  const { data } = await api(`/api/admin/settlements${qs ? `?${qs}` : ''}`);
  if (seq !== state.listRequestSeq) return; // 그 사이 더 최신 요청이 시작됐다 — 이 응답은 버린다
  renderList(data);
}

function batchTypeClass(batchType) {
  return batchType === 'RECOVERY' ? ' row--recovery' : '';
}

function amountClass(totalAmount) {
  return Number(totalAmount) < 0 ? ' amount--negative' : '';
}

function actionButtonsForRow(batch) {
  const buttons = [];
  buttons.push(html`<button type="button" class="btn btn-secondary btn-sm" data-open-detail="${batch.id}">상세</button>`);
  if (RETRYABLE.has(batch.status)) {
    buttons.push(html`<button type="button" class="btn btn-secondary btn-sm" data-quick-retry="${batch.id}">재시도</button>`);
  }
  return buttons.join(' ');
}

function renderList(batches) {
  if (batches.length === 0) {
    els.listBody.innerHTML = html`<tr><td colspan="11">조건에 맞는 정산 배치가 없습니다.</td></tr>`;
    els.listLimitNote.hidden = true;
    return;
  }
  els.listLimitNote.hidden = batches.length < 100;
  els.listBody.innerHTML = batches.map((b) => html`
    <tr class="${raw(batchTypeClass(b.batchType))}">
      <td>${b.id}</td>
      <td>${b.campaignName}</td>
      <td><status-badge domain="settlementPayeeType" value="${b.payeeType}"></status-badge></td>
      <td><status-badge domain="settlementBatchType" value="${b.batchType}"></status-badge></td>
      <td><status-badge domain="settlementBatch" value="${b.status}"></status-badge></td>
      <td class="num${raw(amountClass(b.totalAmount))}">${formatMoney(b.totalAmount)}</td>
      <td class="num">${b.attempts}</td>
      <td>${formatDateTime(b.determinedAt)}</td>
      <td>${formatDateTime(b.completedAt)}</td>
      <td>${b.holdReason ?? '-'}</td>
      <td>${b.failureCode ? html`${b.failureCode}: ${b.failureReason ?? ''}` : '-'}</td>
      <td>${raw(actionButtonsForRow(b))}</td>
    </tr>
  `).join('');

  els.listBody.querySelectorAll('[data-open-detail]').forEach((btn) => {
    btn.addEventListener('click', () => openDetail(Number(btn.dataset.openDetail)));
  });
  els.listBody.querySelectorAll('[data-quick-retry]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const batchId = Number(btn.dataset.quickRetry);
      openDetail(batchId).then(() => retryBatch(batchId));
    });
  });
}

function startAutoRefresh() {
  state.listPollHandle = poll({
    fn: () => fetchAndRenderList(),
    until: () => false,
    intervalMs: 3000,
    maxIntervalMs: 3000,
    timeoutMs: Infinity,
    onTick: (_result, error) => {
      // 목록 자체(마지막으로 불러온 값)는 건드리지 않는다 — 실패했다고 표를 비우지 않는다.
      els.listStaleHint.hidden = !error;

      state.listTickCount += 1;
      if (!error && state.listTickCount % PERIODIC_REFRESH_EVERY_N_TICKS === 0) {
        // run-campaign 드롭다운(CLOSED/SETTLING 진입·이탈)과 미회수 조정은 목록보다 느리게 바뀌므로
        // 3초마다는 과하다 — 약 15초마다 같이 새로고침한다.
        loadCampaigns().catch(() => {});
        loadAdjustments().catch(() => {});
      }
    },
  });
}

// --- 정산 실행 -----------------------------------------------------------------------------

function renderRunResult(result) {
  const batchRows = result.batches.map((b) => html`
    <tr>
      <td>${b.id}</td>
      <td><status-badge domain="settlementPayeeType" value="${b.payeeType}"></status-badge></td>
      <td><status-badge domain="settlementBatchType" value="${b.batchType}"></status-badge></td>
      <td><status-badge domain="settlementBatch" value="${b.status}"></status-badge></td>
      <td class="num${raw(amountClass(b.totalAmount))}">${formatMoney(b.totalAmount)}</td>
      <td><button type="button" class="btn btn-secondary btn-sm" data-open-detail="${b.id}">상세</button></td>
    </tr>
  `).join('');
  els.runResult.hidden = false;
  els.runResult.innerHTML = html`
    <p><strong>결과:</strong> ${result.outcome}${result.reason ? html` — ${result.reason}` : ''}</p>
    ${result.batches.length === 0 ? html`<p>생성·갱신된 배치가 없습니다.</p>` : raw(`
      <table><thead><tr><th>ID</th><th>수령주체</th><th>유형</th><th>상태</th><th>금액</th><th></th></tr></thead>
      <tbody>${batchRows}</tbody></table>
    `)}
  `;
  els.runResult.querySelectorAll('[data-open-detail]').forEach((btn) => {
    btn.addEventListener('click', () => openDetail(Number(btn.dataset.openDetail)));
  });
}

async function runSettlement() {
  const campaignId = Number(els.runCampaign.value);
  if (!campaignId) return;
  const campaign = state.campaigns.find((c) => c.id === campaignId);
  if (!confirm(`캠페인 "${campaign ? campaign.name : campaignId}" 정산을 실행할까요?\n(스케줄러가 10초마다 자동 실행하며, 중복 실행은 안전합니다 — S6)`)) return;
  els.runButton.disabled = true;
  try {
    const { data, meta } = await api('/api/admin/settlements', { method: 'POST', body: { campaignId } });
    showNotice('success', `정산 실행 요청을 처리했습니다 (${data.outcome}).`, meta.requestId);
    renderRunResult(data);
    await fetchAndRenderList();
    // 실행한 캠페인이 CLOSED/SETTLING을 벗어났을 수 있다 — run-campaign 드롭다운을 맞춘다.
    loadCampaigns().catch(() => {});
    loadAdjustments().catch(() => {});
  } catch (err) {
    if (err instanceof ApiError) {
      showNotice('danger', messageFor(err), err.requestId);
    } else {
      throw err;
    }
  } finally {
    els.runButton.disabled = false;
  }
}

// --- 상세 패널 -----------------------------------------------------------------------------

function stopDetailPoll() {
  if (state.detailPollHandle) {
    state.detailPollHandle.stop();
    state.detailPollHandle = null;
  }
}

function resetTimeline() {
  state.timeline = [];
  state.lastTimelineStatus = null;
}

function recordTimeline(status, requestId) {
  if (status === state.lastTimelineStatus) return;
  state.lastTimelineStatus = status;
  state.timeline.push({ time: new Date(), status, requestId: requestId ?? '-' });
  renderTimeline();
}

function renderTimeline() {
  if (state.timeline.length === 0) {
    els.timelineBody.innerHTML = html`<tr><td colspan="3">아직 관찰된 전이가 없습니다.</td></tr>`;
    return;
  }
  els.timelineBody.innerHTML = state.timeline.map((t) => html`
    <tr>
      <td>${formatDateTime(t.time)}</td>
      <td><status-badge domain="settlementBatch" value="${t.status}"></status-badge></td>
      <td class="mono">${t.requestId}</td>
    </tr>
  `).join('');
}

function detailActionsHtml(batch) {
  const buttons = [];
  if (RETRYABLE.has(batch.status)) {
    buttons.push(html`<button type="button" class="btn btn-primary" id="detail-retry-btn">재시도</button>`);
  }
  if (HOLDABLE.has(batch.status)) {
    buttons.push(html`<button type="button" class="btn btn-secondary" id="detail-hold-toggle-btn">보류</button>`);
  }
  if (batch.status === 'HELD') {
    buttons.push(html`<button type="button" class="btn btn-secondary" id="detail-release-btn">해제</button>`);
  }
  if (buttons.length === 0) {
    return html`<p class="hint">현재 상태(${statusOf('settlementBatch', batch.status)[0]})에서는 운영자 조작이 없습니다.</p>`;
  }
  return raw(buttons.join(' '));
}

/**
 * resetHoldForm이 true일 때만 보류 사유 입력 폼을 닫는다 — 기본은 false. 2초 상세 폴링(startDetailPoll)
 * 은 이 함수를 매 tick 호출하는데, 기본값이 true였다면 조작자가 보류 사유를 입력하는 도중에도
 * 폼이 닫히고 포커스가 빠졌다(M3). 폼은 openDetail(새로 연 경우)과 조작 완료 직후에만 닫는다.
 */
function renderDetail(detail, { resetHoldForm = false } = {}) {
  const { batch, items } = detail;
  els.detailPanel.hidden = false;
  const sum = items.reduce((acc, it) => acc + Number(it.amount), 0);
  els.detailFields.innerHTML = html`
    <div><dt>배치 ID</dt><dd>${batch.id}</dd></div>
    <div><dt>캠페인 ID</dt><dd>${batch.campaignId}</dd></div>
    <div><dt>수령 주체</dt><dd><status-badge domain="settlementPayeeType" value="${batch.payeeType}"></status-badge> #${batch.payeeId}</dd></div>
    <div><dt>유형</dt><dd><status-badge domain="settlementBatchType" value="${batch.batchType}"></status-badge></dd></div>
    <div><dt>상태</dt><dd><status-badge domain="settlementBatch" value="${batch.status}"></status-badge></dd></div>
    <div><dt>금액</dt><dd class="${raw(amountClass(batch.totalAmount))}">${formatMoney(batch.totalAmount)}</dd></div>
    <div><dt>시도 횟수</dt><dd>${batch.attempts}</dd></div>
    <div><dt>결정 시각</dt><dd>${formatDateTime(batch.determinedAt)}</dd></div>
    <div><dt>완료 시각</dt><dd>${formatDateTime(batch.completedAt)}</dd></div>
    <div><dt>보류 사유</dt><dd>${batch.holdReason ?? '-'}</dd></div>
    <div><dt>실패</dt><dd>${batch.failureCode ? html`${batch.failureCode}: ${batch.failureReason ?? ''}` : '-'}</dd></div>
  `;
  els.detailActions.innerHTML = '';
  const actionsMarkup = detailActionsHtml(batch);
  els.detailActions.innerHTML = typeof actionsMarkup === 'string' ? actionsMarkup : actionsMarkup.value;
  if (resetHoldForm) els.detailHoldForm.hidden = true;

  const retryBtn = els.detailActions.querySelector('#detail-retry-btn');
  if (retryBtn) retryBtn.addEventListener('click', () => retryBatch(batch.id));
  const holdToggleBtn = els.detailActions.querySelector('#detail-hold-toggle-btn');
  if (holdToggleBtn) holdToggleBtn.addEventListener('click', () => {
    els.detailHoldForm.hidden = false;
    els.detailHoldReason.value = '운영자 보류';
    els.detailHoldReason.focus();
  });
  const releaseBtn = els.detailActions.querySelector('#detail-release-btn');
  if (releaseBtn) releaseBtn.addEventListener('click', () => releaseBatch(batch.id));

  if (items.length === 0) {
    els.detailItemsBody.innerHTML = html`<tr><td colspan="3">항목이 없습니다.</td></tr>`;
    els.detailItemsFoot.innerHTML = '';
  } else {
    els.detailItemsBody.innerHTML = items.map((it) => html`
      <tr>
        <td><a href="/console/admin/ledger.html?campaignId=${batch.campaignId}&orderId=${it.orderId}">${it.orderId}</a></td>
        <td>${it.orderItemId}</td>
        <td class="num">${formatMoney(it.amount)}</td>
      </tr>
    `).join('');
    els.detailItemsFoot.innerHTML = html`<tr><td colspan="2">합계</td><td class="num">${formatMoney(sum)}</td></tr>`;
  }
}

/** 배치 상세를 다시 읽어 렌더링하고 타임라인에 관찰된 상태를 기록한다(타임라인은 초기화하지 않는다). */
async function syncDetail(batchId, { resetHoldForm = false } = {}) {
  const { data, meta } = await api(`/api/admin/settlements/${batchId}`);
  recordTimeline(data.batch.status, meta.requestId);
  renderDetail(data, { resetHoldForm });
  return data.batch.status;
}

async function openDetail(batchId) {
  stopDetailPoll();
  state.detailBatchId = batchId;
  resetTimeline();
  els.detailTimeoutArea.innerHTML = '';
  try {
    await syncDetail(batchId, { resetHoldForm: true });
    els.detailPanel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    const url = new URL(location.href);
    url.searchParams.set('batchId', String(batchId));
    history.replaceState(null, '', url);
  } catch (err) {
    if (err instanceof ApiError) {
      showNotice('danger', messageFor(err), err.requestId);
    } else {
      throw err;
    }
  }
}

function closeDetail() {
  stopDetailPoll();
  state.detailBatchId = null;
  els.detailPanel.hidden = true;
  const url = new URL(location.href);
  url.searchParams.delete('batchId');
  history.replaceState(null, '', url);
}

function startDetailPoll(batchId) {
  let handle;
  handle = poll({
    fn: () => api(`/api/admin/settlements/${batchId}`),
    until: (result) => TERMINAL_STATUSES.has(result.data.batch.status),
    intervalMs: 2000,
    maxIntervalMs: 8000,
    timeoutMs: 60000,
    onTick: (result, error) => {
      if (error) return;
      if (state.detailBatchId !== batchId) return; // 다른 배치로 이동했으면 무시
      recordTimeline(result.data.batch.status, result.meta.requestId);
      renderDetail(result.data);
      if (TERMINAL_STATUSES.has(result.data.batch.status)) fetchAndRenderList();
    },
    onTimeout: () => {
      if (state.detailBatchId !== batchId) return;
      renderContinueWatching(els.detailTimeoutArea, handle);
    },
  });
  state.detailPollHandle = handle;
}

/**
 * 정산 조작(재시도/보류/해제) 공통 처리: 응답을 즉시 타임라인에 기록하고, 목록·캠페인·미회수
 * 조정을 새로고침하고, 상세를 다시 읽어(요청 사이 상태가 더 진행됐을 수 있다) 종결 상태가
 * 아니면 폴링을 시작한다. 조작이 성공적으로 끝났으므로 보류 폼은 여기서 닫는다(M3).
 */
async function afterMutatingAction(batchId, actionResult, successMessage) {
  // MINOR3: 이전에 걸어둔 상세 폴링이 남아 있으면 새로 시작하기 전에 반드시 멈춘다 — 아니면
  // 두 폴링이 동시에 돌며 타임라인에 중복 기록을 남기거나 서로 다른 타이밍에 화면을 덮어쓴다.
  stopDetailPoll();
  const { data, meta } = actionResult;
  showNotice('success', successMessage, meta.requestId);
  recordTimeline(data.status, meta.requestId);
  await fetchAndRenderList();
  // run-campaign 드롭다운(CLOSED/SETTLING 여부)과 미회수 조정은 이 조작으로 바뀌었을 수 있다.
  loadCampaigns().catch(() => {});
  loadAdjustments().catch(() => {});
  const status = await syncDetail(batchId, { resetHoldForm: true });
  if (!TERMINAL_STATUSES.has(status)) startDetailPoll(batchId);
}

async function handleActionError(batchId, err) {
  if (err instanceof ApiError) {
    // 409 SETTLEMENT_NOT_RETRYABLE/NOT_HOLDABLE/NOT_HELD 등 — 그 사이 상태가 바뀌었을 수 있다.
    // 오류로만 그리지 않고 실제 서버 상태를 다시 읽어 화면을 맞춘다.
    showNotice('danger', messageFor(err), err.requestId);
    await syncDetail(batchId).catch(() => {});
  } else {
    throw err;
  }
}

async function retryBatch(batchId) {
  if (!confirm(`배치 #${batchId}을(를) 재시도할까요?`)) return;
  try {
    const result = await api(`/api/admin/settlements/${batchId}/retry`, { method: 'POST' });
    await afterMutatingAction(batchId, result, `배치 #${batchId} 재시도를 요청했습니다.`);
  } catch (err) {
    await handleActionError(batchId, err);
  }
}

async function releaseBatch(batchId) {
  if (!confirm(`배치 #${batchId} 보류를 해제할까요? (PENDING으로 돌아가 재검증됩니다)`)) return;
  try {
    const result = await api(`/api/admin/settlements/${batchId}/release`, { method: 'POST' });
    await afterMutatingAction(batchId, result, `배치 #${batchId} 보류를 해제했습니다.`);
  } catch (err) {
    await handleActionError(batchId, err);
  }
}

async function holdBatch(batchId, reason) {
  try {
    const result = await api(`/api/admin/settlements/${batchId}/hold`, { method: 'POST', body: { reason } });
    els.detailHoldForm.hidden = true;
    await afterMutatingAction(batchId, result, `배치 #${batchId}을(를) 보류했습니다.`);
  } catch (err) {
    await handleActionError(batchId, err);
  }
}

// --- 미회수 조정 ----------------------------------------------------------------------------

async function loadAdjustments() {
  const { data } = await api('/api/admin/settlement-adjustments');
  if (data.length === 0) {
    els.adjustmentsBody.innerHTML = html`<tr><td colspan="7">미회수 조정 내역이 없습니다.</td></tr>`;
    return;
  }
  els.adjustmentsBody.innerHTML = data.map((a) => html`
    <tr>
      <td>${a.id}</td>
      <td>${a.campaignId}</td>
      <td><status-badge domain="settlementPayeeType" value="${a.payeeType}"></status-badge></td>
      <td>${a.payeeId}</td>
      <td><button type="button" class="btn btn-secondary btn-sm" data-open-detail="${a.batchId}">#${a.batchId}</button></td>
      <td>${a.refundId}</td>
      <td class="num${raw(amountClass(a.amount))}">${formatMoney(a.amount)}</td>
      <td>${formatDateTime(a.createdAt)}</td>
    </tr>
  `).join('');
  els.adjustmentsBody.querySelectorAll('[data-open-detail]').forEach((btn) => {
    btn.addEventListener('click', () => openDetail(Number(btn.dataset.openDetail)));
  });
}

// --- 초기화 --------------------------------------------------------------------------------

export async function initSettlementsPage() {
  els = {
    filterCampaign: document.getElementById('filter-campaign'),
    statusChecks: document.querySelectorAll('[data-status-check]'),
    listBody: document.getElementById('settlements-body'),
    listLimitNote: document.getElementById('list-limit-note'),
    listStaleHint: document.getElementById('list-stale-hint'),
    runCampaign: document.getElementById('run-campaign'),
    runButton: document.getElementById('run-button'),
    runEmpty: document.getElementById('run-empty'),
    runResult: document.getElementById('run-result'),
    detailPanel: document.getElementById('detail-panel'),
    detailFields: document.getElementById('detail-fields'),
    detailActions: document.getElementById('detail-actions'),
    detailHoldForm: document.getElementById('detail-hold-form'),
    detailHoldReason: document.getElementById('detail-hold-reason'),
    detailItemsBody: document.getElementById('detail-items-body'),
    detailItemsFoot: document.getElementById('detail-items-foot'),
    timelineBody: document.getElementById('timeline-body'),
    detailTimeoutArea: document.getElementById('detail-timeout-area'),
    adjustmentsBody: document.getElementById('adjustments-body'),
  };
  notice = document.getElementById('notice');

  document.getElementById('filter-form').addEventListener('change', () => {
    // fetchAndRenderList()를 따로 호출하지 않는다 — 진행 중인 3초 폴링의 요청과 경쟁하면
    // (poll.js는 setTimeout 체인이라 새 tick이 이전 tick의 in-flight 요청을 기다리지 않는다)
    // 오래된 필터의 응답이 나중에 도착해 새 필터의 결과를 덮어쓸 수 있다. restart()로 폴링
    // 자체를 즉시 재시작해 같은 fn()과 스테일 가드(listRequestSeq)를 그대로 쓴다.
    if (state.listPollHandle) {
      state.listPollHandle.restart();
    } else {
      // 아직 폴링이 시작되지 않은 초기화 구간의 극히 드문 경합 대비 폴백.
      fetchAndRenderList().catch((err) => {
        if (err instanceof ApiError) showNotice('danger', messageFor(err), err.requestId);
        else throw err;
      });
    }
  });
  els.runButton.addEventListener('click', runSettlement);
  document.getElementById('detail-close').addEventListener('click', closeDetail);
  document.getElementById('detail-hold-cancel').addEventListener('click', () => { els.detailHoldForm.hidden = true; });
  document.getElementById('detail-hold-submit').addEventListener('click', () => {
    const reason = els.detailHoldReason.value.trim() || '운영자 보류';
    if (!confirm(`배치 #${state.detailBatchId}을(를) 보류할까요? (사유: ${reason})`)) return;
    holdBatch(state.detailBatchId, reason);
  });

  clearNotice();
  await loadCampaigns();
  await fetchAndRenderList();
  await loadAdjustments();
  startAutoRefresh();

  const batchId = idParam('batchId');
  if (batchId) await openDetail(batchId);
}
