// reconciliation.js — 대사 실행·불일치 화면 (REC-01~02, S7, 계약 §7 "운영자" reconciliation row)
//
// 이 화면은 기존 /admin/index.html의 불일치 목록·재처리·해결 기능을 완전히 대체한다 (계획 §6).

import { api, ApiError } from '../common/api.js';
import { messageFor } from '../common/codes.js';
import { html, raw, escapeHtml } from '../common/html.js';
import { formatMoney, formatDateTime, statusOf } from '../common/format.js';
import { poll, renderContinueWatching } from '../common/poll.js';
import '../common/widgets.js';

// 8개 불일치 유형 — 표시 순서와 한 줄 설명 (V7 CHECK 제약, 계약서 §7).
const DISCREPANCY_TYPES = [
  ['MISSING_INTERNAL', 'PG에는 거래가 있는데 내부 DB에는 그 결제가 없다 — 웹훅·조회 경로가 모두 놓친 경우.'],
  ['MISSING_PROVIDER', '내부에는 결제 기록이 있는데 PG에는 해당 거래가 없다 — PG 쪽에서 사라진 경우.'],
  ['AMOUNT_MISMATCH', '내부 금액과 PG가 보고하는 금액이 서로 다르다.'],
  ['STATUS_MISMATCH', '내부 결제 상태와 PG가 보고하는 상태가 서로 다르다.'],
  ['REFUND_MISMATCH', '환불 처리 여부·금액이 내부와 PG 간에 다르다.'],
  ['OCCURRED_AT_MISMATCH', '거래가 실제로 일어난 시각이 내부 기록과 PG 기록 간에 다르다.'],
  ['DUPLICATE_PAYMENT', '같은 주문에 대해 PG에 중복된 거래가 존재한다.'],
  ['UNRESOLVED_INTERNAL', 'UNKNOWN 등으로 내부에서 미확정 상태로 남아 있던 결제가 대사 시점까지 해소되지 않았다.'],
];

const RUN_TERMINAL = new Set(['COMPLETED', 'FAILED']);

const state = {
  runPollHandle: null,
};

let els = {};
let notice;

function showNotice(kind, message, requestId) {
  const cls = kind === 'success' ? 'notice--success' : 'notice--danger';
  notice.className = `notice ${cls}`;
  notice.hidden = false;
  notice.innerHTML = html`${message}${requestId ? raw(` <span class="mono">(요청 ID: ${escapeHtml(requestId)})</span>`) : ''}`;
}

// --- 대사 실행 -----------------------------------------------------------------------------

function renderRunStatus(run) {
  els.runStatus.hidden = false;
  els.runStatus.innerHTML = html`
    <dl class="detail-fields-grid">
      <div><dt>실행 ID</dt><dd>${run.id}</dd></div>
      <div><dt>상태</dt><dd><status-badge domain="reconciliationRun" value="${run.status}"></status-badge></dd></div>
      <div><dt>최소 경과(분)</dt><dd>${run.minAgeMinutes}</dd></div>
      <div><dt>PG 거래 수</dt><dd>${run.providerTransactionCount}</dd></div>
      <div><dt>내부 결제 수</dt><dd>${run.internalPaymentCount}</dd></div>
      <div><dt>불일치 발견</dt><dd>${run.mismatchCount}</dd></div>
      <div><dt>해결됨</dt><dd>${run.resolvedCount}</dd></div>
      <div><dt>원장 불균형</dt><dd>${run.ledgerUnbalancedCount}</dd></div>
      <div><dt>시작</dt><dd>${formatDateTime(run.startedAt)}</dd></div>
      <div><dt>종료</dt><dd>${formatDateTime(run.finishedAt)}</dd></div>
      <div><dt>오류</dt><dd>${run.error ?? '-'}</dd></div>
    </dl>
  `;
}

function stopRunPoll() {
  if (state.runPollHandle) {
    state.runPollHandle.stop();
    state.runPollHandle = null;
  }
}

function setRunButtonDisabled(disabled) {
  els.runButton.disabled = disabled;
  els.runButton.textContent = disabled ? '실행 중…' : '대사 실행';
}

function startRunPoll(runId) {
  let handle;
  handle = poll({
    fn: () => api(`/api/admin/reconciliations/${runId}`),
    until: (result) => RUN_TERMINAL.has(result.data.status),
    intervalMs: 2000,
    maxIntervalMs: 8000,
    timeoutMs: 30000,
    onTick: (result, error) => {
      if (error) return;
      renderRunStatus(result.data);
      if (RUN_TERMINAL.has(result.data.status)) {
        setRunButtonDisabled(false);
        els.runTimeoutArea.innerHTML = '';
        loadRuns();
        loadDiscrepancies();
      }
    },
    onTimeout: () => {
      renderContinueWatching(els.runTimeoutArea, handle);
    },
  });
  state.runPollHandle = handle;
}

async function triggerRun() {
  const rawMinAge = els.minAge.value.trim();
  // Number('')는 0이라 빈 입력이 조용히 0으로 통과한다 — 정수 문자열만 명시적으로 받는다.
  if (!/^\d+$/.test(rawMinAge)) {
    showNotice('danger', 'minAgeMinutes는 0 이상의 정수로 입력해 주세요.');
    els.minAge.focus();
    return;
  }
  const minAgeMinutes = Number(rawMinAge);
  if (!confirm(`대사를 실행할까요? (minAgeMinutes=${minAgeMinutes})`)) return;
  stopRunPoll();
  setRunButtonDisabled(true);
  try {
    const { data, meta } = await api('/api/admin/reconciliations', { method: 'POST', body: { minAgeMinutes } });
    showNotice('success', `대사 실행 #${data.id}을(를) 시작했습니다.`, meta.requestId);
    renderRunStatus(data);
    if (RUN_TERMINAL.has(data.status)) {
      setRunButtonDisabled(false);
      loadRuns();
      loadDiscrepancies();
    } else {
      startRunPoll(data.id);
    }
  } catch (err) {
    setRunButtonDisabled(false);
    if (err instanceof ApiError) {
      showNotice('danger', messageFor(err), err.requestId);
      if (err.code === 'RECONCILIATION_ALREADY_RUNNING') {
        // 이미 실행 중인 대사가 있다 — 이력에서 RUNNING 건을 찾아 이어서 지켜본다.
        await resumeRunningIfAny();
      }
    } else {
      throw err;
    }
  }
}

async function resumeRunningIfAny() {
  const { data } = await api('/api/admin/reconciliations');
  const running = data.find((r) => r.status === 'RUNNING');
  if (running) {
    setRunButtonDisabled(true);
    renderRunStatus(running);
    startRunPoll(running.id);
  }
  renderRuns(data);
}

// --- 실행 이력 -----------------------------------------------------------------------------

async function loadRuns() {
  const { data } = await api('/api/admin/reconciliations');
  renderRuns(data);
}

function renderRuns(runs) {
  if (runs.length === 0) {
    els.runsBody.innerHTML = html`<tr><td colspan="10">실행 이력이 없습니다.</td></tr>`;
    return;
  }
  els.runsBody.innerHTML = runs.map((r) => html`
    <tr>
      <td>${r.id}</td>
      <td><status-badge domain="reconciliationRun" value="${r.status}"></status-badge></td>
      <td class="num">${r.minAgeMinutes}</td>
      <td class="num">${r.providerTransactionCount}</td>
      <td class="num">${r.internalPaymentCount}</td>
      <td class="num">${r.mismatchCount}</td>
      <td class="num">${r.resolvedCount}</td>
      <td class="num">${r.ledgerUnbalancedCount}</td>
      <td>${formatDateTime(r.startedAt)}</td>
      <td>${formatDateTime(r.finishedAt)}</td>
      <td>${r.error ?? '-'}</td>
    </tr>
  `).join('');
}

// --- 불일치 -------------------------------------------------------------------------------

function orderLink(orderId) {
  // 계약상 Discrepancy 레코드에는 campaignId가 없다 — orderId만으로 ledger.html을 연다.
  return html`<a href="/console/admin/ledger.html?orderId=${orderId}">${orderId}</a>`;
}

function retryDisabledTitle(d) {
  if (d.status !== 'OPEN') return '이미 처리된(OPEN이 아닌) 불일치는 재처리할 수 없습니다.';
  if (d.paymentId == null) return 'paymentId가 없어 재처리할 수 없습니다 — 수동으로 해결하세요 (DISCREPANCY_NOT_RETRYABLE)';
  return null;
}

function discrepancyRowHtml(d) {
  const retryDisabledReason = retryDisabledTitle(d);
  const retryable = retryDisabledReason === null;
  const resolvable = d.status === 'OPEN';
  return html`
    <tr>
      <td>${d.id}</td>
      <td><status-badge domain="discrepancyStatus" value="${d.status}"></status-badge></td>
      <td class="pg-compare">
        <div class="pg-compare__col"><span class="pg-compare__label">내부</span>
          <div>상태: ${d.internalStatus ?? '-'}</div>
          <div>금액: ${formatMoney(d.internalAmount)}</div>
        </div>
        <div class="pg-compare__col"><span class="pg-compare__label">PG</span>
          <div>상태: ${d.providerStatus ?? '-'}</div>
          <div>금액: ${formatMoney(d.providerAmount)}</div>
          <div>PG결제ID: ${d.providerPaymentId ?? '-'}</div>
        </div>
      </td>
      <td>${d.paymentId ?? '-'}</td>
      <td>${d.orderId != null ? raw(orderLink(d.orderId)) : '-'}</td>
      <td>${d.detail ?? '-'}</td>
      <td>${formatDateTime(d.subjectOccurredAt)}</td>
      <td>${formatDateTime(d.detectedAt)}</td>
      <td>${d.runId}${d.lastSeenRunId !== d.runId ? html` (최근 ${d.lastSeenRunId})` : ''}</td>
      <td>${d.resolutionNote ?? '-'}<br>${formatDateTime(d.resolvedAt)}</td>
      <td class="discrepancy-actions">
        <button type="button" class="btn btn-secondary btn-sm" data-retry="${d.id}" ${raw(retryable ? '' : `disabled title="${escapeHtml(retryDisabledReason)}"`)}>재처리</button>
        <button type="button" class="btn btn-secondary btn-sm" data-resolve-toggle="${d.id}" ${resolvable ? '' : 'disabled'}>해결</button>
        <div class="resolve-form" id="resolve-form-${d.id}" hidden>
          <input type="text" placeholder="해결 메모 (필수)" id="resolve-note-${d.id}">
          <button type="button" class="btn btn-primary btn-sm" data-resolve-submit="${d.id}">확정</button>
          <button type="button" class="btn btn-secondary btn-sm" data-resolve-cancel="${d.id}">취소</button>
        </div>
      </td>
    </tr>
  `;
}

/** 값이 있는 유형만 전체 블록으로 그리고, 0건 유형은 한 줄 요약(설명은 title 툴팁)으로 묶는다. */
function renderDiscrepancies(list) {
  const withRows = [];
  const zeroTypes = [];
  DISCREPANCY_TYPES.forEach(([type, explanation]) => {
    const rows = list.filter((d) => d.type === type);
    if (rows.length > 0) withRows.push({ type, explanation, rows });
    else zeroTypes.push({ type, explanation });
  });

  const fullBlocksHtml = withRows.map(({ type, explanation, rows }) => {
    const [label] = statusOf('discrepancyType', type);
    return html`
      <div class="discrepancy-group">
        <h3>${label} <span class="hint">(${type})</span> — ${rows.length}건</h3>
        <p class="hint">${explanation}</p>
        ${raw(`
          <table>
            <thead><tr>
              <th>ID</th><th>상태</th><th>내부 값 / PG 값</th><th>결제 ID</th><th>주문</th>
              <th>상세</th><th>발생시각</th><th>감지시각</th><th>실행</th><th>해결 메모</th><th></th>
            </tr></thead>
            <tbody>${rows.map(discrepancyRowHtml).join('')}</tbody>
          </table>
        `)}
      </div>
    `;
  }).join('');

  const zeroLineHtml = zeroTypes.length === 0 ? '' : html`
    <p class="hint discrepancy-zero-line">0건: ${raw(zeroTypes.map(({ type, explanation }) => {
      const [label] = statusOf('discrepancyType', type);
      return html`<span title="${explanation}">${label}</span>`;
    }).join(' · '))}</p>
  `;

  const emptyLineHtml = list.length === 0 ? html`<p class="hint">해당 상태의 불일치가 없습니다.</p>` : '';

  els.discrepancyGroups.innerHTML = emptyLineHtml + fullBlocksHtml + zeroLineHtml;

  if (list.length >= 100) {
    els.discrepancyLimitNote.hidden = false;
  } else {
    els.discrepancyLimitNote.hidden = true;
  }

  els.discrepancyGroups.querySelectorAll('[data-retry]').forEach((btn) => {
    btn.addEventListener('click', () => retryDiscrepancy(Number(btn.dataset.retry)));
  });
  els.discrepancyGroups.querySelectorAll('[data-resolve-toggle]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const id = btn.dataset.resolveToggle;
      const form = document.getElementById(`resolve-form-${id}`);
      form.hidden = !form.hidden;
      if (!form.hidden) document.getElementById(`resolve-note-${id}`).focus();
    });
  });
  els.discrepancyGroups.querySelectorAll('[data-resolve-cancel]').forEach((btn) => {
    btn.addEventListener('click', () => {
      document.getElementById(`resolve-form-${btn.dataset.resolveCancel}`).hidden = true;
    });
  });
  els.discrepancyGroups.querySelectorAll('[data-resolve-submit]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const id = Number(btn.dataset.resolveSubmit);
      const noteInput = document.getElementById(`resolve-note-${id}`);
      const note = noteInput.value.trim();
      if (!note) {
        noteInput.focus();
        showNotice('danger', '해결 메모를 입력해 주세요.');
        return;
      }
      resolveDiscrepancy(id, note);
    });
  });
}

async function loadDiscrepancies() {
  const status = els.discrepancyStatus.value;
  const qs = status ? `?status=${encodeURIComponent(status)}` : '';
  const { data } = await api(`/api/admin/reconciliation-discrepancies${qs}`);
  renderDiscrepancies(data);
}

async function retryDiscrepancy(id) {
  if (!confirm(`불일치 #${id}을(를) 재처리할까요? (PG를 다시 조회합니다)`)) return;
  try {
    const { meta } = await api(`/api/admin/reconciliation-discrepancies/${id}/retry`, { method: 'POST' });
    showNotice('success',
      `불일치 #${id}을(를) 재처리했습니다. 재처리했다고 자동으로 해결되지 않습니다 — UNRESOLVED_INTERNAL 유형이 최종 상태로 확정된 경우만 자동으로 닫힙니다.`,
      meta.requestId);
    await loadDiscrepancies();
  } catch (err) {
    if (err instanceof ApiError) {
      showNotice('danger', messageFor(err), err.requestId);
      await loadDiscrepancies();
    } else {
      throw err;
    }
  }
}

async function resolveDiscrepancy(id, note) {
  if (!confirm(`불일치 #${id}을(를) 해결 처리할까요?\n메모: ${note}`)) return;
  try {
    const { meta } = await api(`/api/admin/reconciliation-discrepancies/${id}/resolve`, { method: 'POST', body: { note } });
    showNotice('success', `불일치 #${id}을(를) 해결 처리했습니다.`, meta.requestId);
    await loadDiscrepancies();
  } catch (err) {
    if (err instanceof ApiError) {
      showNotice('danger', messageFor(err), err.requestId);
      await loadDiscrepancies();
    } else {
      throw err;
    }
  }
}

// --- 초기화 --------------------------------------------------------------------------------

export async function initReconciliationPage() {
  els = {
    minAge: document.getElementById('min-age'),
    runButton: document.getElementById('run-button'),
    runStatus: document.getElementById('run-status'),
    runTimeoutArea: document.getElementById('run-timeout-area'),
    runsBody: document.getElementById('runs-body'),
    discrepancyStatus: document.getElementById('discrepancy-status'),
    discrepancyGroups: document.getElementById('discrepancy-groups'),
    discrepancyLimitNote: document.getElementById('discrepancy-limit-note'),
  };
  notice = document.getElementById('notice');
  notice.hidden = true;

  els.runButton.addEventListener('click', triggerRun);
  document.getElementById('min-age-demo-button').addEventListener('click', () => {
    els.minAge.value = '0';
    els.minAge.focus();
  });
  els.discrepancyStatus.addEventListener('change', () => {
    loadDiscrepancies().catch((err) => {
      if (err instanceof ApiError) showNotice('danger', messageFor(err), err.requestId);
      else throw err;
    });
  });
  document.getElementById('discrepancy-refresh').addEventListener('click', () => {
    loadDiscrepancies().catch((err) => {
      if (err instanceof ApiError) showNotice('danger', messageFor(err), err.requestId);
      else throw err;
    });
  });

  await Promise.all([resumeRunningIfAny(), loadDiscrepancies()]);
}
