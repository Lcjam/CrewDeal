// demo.js — 장애 주입 패널 부트스트랩·렌더링·이벤트 바인딩 (프론트엔드 계획 §7 전체, §8 6단계)
//
// 이 화면은 BUYER·ADMIN 세션 모두에서 열린다(§7.1 — 구매자 탭 localhost, 운영자 탭 127.0.0.1).
// nav.js 메뉴에도 이 두 역할에만 노출되므로 requireRole('BUYER','ADMIN')로 좁힌다. 실행 가능한
// 시나리오는 그중에서도 현재 role로 다시 걸러 비활성화한다. 증거 카운터·mock-pg 연결은 두 역할
// 모두에서 항상 보인다(§7.3).
//
// 판정 로직은 scenarios.js가 갖고 있다 — 이 파일은 그 결과를 타임라인·판정 배지로 그리고,
// 시나리오가 필요로 하는 DOM 종속 입력(대상 캠페인, 대사 시나리오의 confirm 등은 scenarios.js 쪽에서
// window.confirm 직접 호출)만 ctx로 넘긴다.

import { api } from '../common/api.js';
import { requireRole } from '../common/session.js';
import { mountNav } from '../common/nav.js';
import { html, raw } from '../common/html.js';
import { messageFor } from '../common/codes.js';
import { formatMoney, formatDateTime } from '../common/format.js';
import { poll } from '../common/poll.js';
import * as pg from './pg.js';
import * as metrics from './metrics.js';
import { runScenario, SCENARIO_DEFS, ScenarioAbort } from './scenarios.js';

const state = {
  user: null,
  pgStatus: 'idle', // 'idle' | 'checking' | 'ok' | 'error'
  pgStatusMessage: null,
  buyer: { campaigns: [], loading: false, error: null, selectedId: null },
  admin: { campaigns: [], loading: false, error: null, selectedId: null, targetOrder: null, targetLoading: false, targetError: null },
  counters: { snapshot: null, error: null },
  evidenceBaseline: null, // 실행 중인 시나리오가 scenarios.js에서 알려준 시작 스냅숏
  running: null, // 실행 중인 시나리오 key, 아니면 null
  abortRequested: false,
  timeline: [],
  verdict: null,
};

// --- 부트스트랩 ------------------------------------------------------------------------------

export async function initDemoPage() {
  const user = await requireRole('BUYER', 'ADMIN');
  if (!user) return;
  state.user = user;
  mountNav(user);

  renderIntro();
  wirePgControls();
  wireManualControls();

  window.addEventListener('beforeunload', handleBeforeUnload);
  window.addEventListener('pagehide', handlePageHide);
  pg.onFailureModeChange(() => renderPgStatus());

  renderPgStatus();
  checkPgConnection();

  renderTimeline();
  renderVerdict();
  renderScenarioSection();

  // 현재 역할에 필요한 목록만 불러온다(둘 다 부르면 반대 역할 쪽 데이터는 화면에 쓰이지도 않으면서
  // 요청 로그만 채운다 — item d와 같은 이유).
  if (state.user.role === 'BUYER') await loadBuyerCampaigns();
  else await loadAdminCampaigns();

  startCountersPolling();
}

function handleBeforeUnload(event) {
  if (!state.running) return;
  event.preventDefault();
  event.returnValue = '';
}

function handlePageHide() {
  if (state.running) pg.resetNormalBestEffort();
}

// --- 안내 (섹션 1) ---------------------------------------------------------------------------

function renderIntro() {
  const el = document.getElementById('demo-intro');
  const port = location.port ? `:${location.port}` : '';
  const buyerOrigin = `${location.protocol}//localhost${port}`;
  const adminOrigin = `${location.protocol}//127.0.0.1${port}`;
  const onLocalhost = location.hostname === 'localhost';
  const otherOrigin = onLocalhost ? adminOrigin : buyerOrigin;
  const otherLabel = onLocalhost ? '운영자 탭 (127.0.0.1) 열기' : '구매자 탭 (localhost) 열기';

  let roleNote;
  if (state.user.role === 'BUYER') {
    roleNote = '이 탭(구매자)에서 실행 가능: UNKNOWN 복구(S4-a) · 웹훅 중복·역순(S3) · 중복 결제 차단(S2)';
  } else if (state.user.role === 'ADMIN') {
    roleNote = '이 탭(운영자)에서 실행 가능: 대사 불일치 → 정산 보류(S7)';
  } else {
    roleNote = `이 탭의 역할(${state.user.role})로는 실행 가능한 시나리오가 없습니다 — 증거 카운터·mock-pg 연결 상태만 볼 수 있습니다.`;
  }

  el.innerHTML = html`
    <h1>장애 주입 데모</h1>
    <p>mock-pg 테스트 제어 API로 결제·웹훅 장애를 주입하고, 시스템이 스스로 복구하는 과정을 화면에서 확인합니다.
      세션 쿠키는 호스트 단위입니다(§7.1) — <strong>구매자 탭은 <code>${buyerOrigin}</code></strong>,
      <strong>운영자 탭은 <code>${adminOrigin}</code></strong>로 각각 로그인해 두 탭을 나란히 띄우세요.</p>
    <p>${roleNote}</p>
    <p><a href="${otherOrigin}/console/demo/index.html">${otherLabel} (호스트만 바뀐 같은 경로)</a></p>
  `;
}

// --- mock-pg 연결 (섹션 2) --------------------------------------------------------------------

function wirePgControls() {
  const input = document.getElementById('pg-url');
  input.value = pg.getMockPgUrl();
  document.getElementById('pg-url-save').addEventListener('click', () => {
    pg.setMockPgUrl(input.value);
    input.value = pg.getMockPgUrl();
    checkPgConnection();
  });
  document.getElementById('pg-url-default').addEventListener('click', () => {
    input.value = pg.defaultMockPgUrl();
    pg.setMockPgUrl(input.value);
    checkPgConnection();
  });
}

async function checkPgConnection() {
  state.pgStatus = 'checking';
  renderPgStatus();
  try {
    await pg.stats();
    state.pgStatus = 'ok';
    state.pgStatusMessage = null;
  } catch (err) {
    state.pgStatus = 'error';
    state.pgStatusMessage = err.message;
  }
  renderPgStatus();
}

function renderPgStatus() {
  const el = document.getElementById('pg-status');
  const badge = {
    idle: ['gray', '미확인'],
    checking: ['blue', '확인 중…'],
    ok: ['green', '연결됨'],
    error: ['red', '연결 실패'],
  }[state.pgStatus];

  const lastMode = pg.lastKnownFailureMode();
  const modeText = lastMode
    ? `mode=${lastMode.mode}, delayMs=${lastMode.delayMs}, blockWebhook=${lastMode.blockWebhook}, `
      + `webhookDuplicateCount=${lastMode.webhookDuplicateCount}, webhookReverseOrder=${lastMode.webhookReverseOrder}, `
      + `refundMode=${lastMode.refundMode}`
    : '없음';

  el.innerHTML = html`
    <p>
      <span class="status-badge status-badge--${badge[0]}">${badge[1]}</span>
      ${state.pgStatus === 'error' && state.pgStatusMessage ? html` ${state.pgStatusMessage}` : ''}
    </p>
    <p class="hint" title="mock-pg에는 현재 모드 조회 API가 없습니다 — 이 탭이 마지막으로 적용에 성공한 값만 기억합니다. 다른 탭이 그 뒤 모드를 바꿨을 수 있습니다.">
      이 탭이 마지막으로 적용한 모드: <span class="mono">${modeText}</span>
    </p>
  `;
}

// --- 증거 카운터 (섹션 3) ---------------------------------------------------------------------

// 시나리오 실행 중에는 이 자동 갱신을 건너뛴다 — 5~10초마다 앱 카운터 5개+mock-pg stats를 부르면
// 하단 "최근 요청" 표시줄(reqlog)이 이 요청들로 채워져 시나리오 자체의 요청이 묻힌다. 실행이
// 끝나면 startScenario()가 refreshCountersNow()로 한 번 더 갱신한다.
function startCountersPolling() {
  poll({
    fn: async () => {
      if (state.running) return null; // 건너뜀 표시 — onTick에서 무시한다.
      return metrics.snapshotCounters();
    },
    until: () => false,
    intervalMs: 10000,
    maxIntervalMs: 10000,
    timeoutMs: Infinity,
    onTick: (result) => {
      if (!result) return;
      state.counters.snapshot = result;
      renderCounters();
    },
  });
}

async function refreshCountersNow() {
  state.counters.snapshot = await metrics.snapshotCounters();
  renderCounters();
}

function counterCellHtml(label, value, baselineValue) {
  const displayValue = value === null || value === undefined ? '-' : value.toLocaleString('ko-KR');
  const hasBaseline = baselineValue !== null && baselineValue !== undefined && value !== null && value !== undefined;
  const delta = hasBaseline ? value - baselineValue : null;
  const deltaHtml = delta === null ? '' : html` <span class="counter-delta">Δ${delta >= 0 ? '+' : ''}${delta}</span>`;
  return html`
    <div class="counter-cell">
      <div class="counter-cell__label">${label}</div>
      <div class="counter-cell__value">${displayValue}${raw(deltaHtml)}</div>
    </div>
  `;
}

function renderCounters() {
  const el = document.getElementById('counters-panel');
  const snap = state.counters.snapshot;
  if (!snap) {
    el.innerHTML = html`<p class="hint">불러오는 중…</p>`;
    return;
  }
  const baseline = state.running ? state.evidenceBaseline : null;
  const appCells = metrics.APP_COUNTER_NAMES
    .map((name) => counterCellHtml(metrics.APP_COUNTER_LABELS[name], snap.app?.[name] ?? null, baseline?.app?.[name] ?? null))
    .join('');
  const pgCells = Object.keys(metrics.PG_STAT_LABELS)
    .map((key) => counterCellHtml(metrics.PG_STAT_LABELS[key], snap.pg?.[key] ?? null, baseline?.pg?.[key] ?? null))
    .join('');
  const runningHint = state.running ? html`<p class="hint">실행 중 — 시작 스냅숏 대비 Δ를 함께 표시합니다. 실행 중에는 자동 갱신을 멈춥니다(요청 로그가 시나리오 요청에 묻히지 않도록).</p>` : '';
  const pgErrorHint = !snap.pg ? html`<p class="hint">mock-pg stats는 연결 실패로 표시하지 못했습니다.</p>` : '';
  el.innerHTML = html`
    ${runningHint}
    ${pgErrorHint}
    <div class="counters-grid">${raw(appCells)}${raw(pgCells)}</div>
  `;
}

// --- 대상 캠페인 선택 (섹션 4 일부) ------------------------------------------------------------

async function loadBuyerCampaigns() {
  state.buyer.loading = true;
  state.buyer.error = null;
  renderScenarioSection();
  try {
    const { data } = await api('/api/campaigns?status=OPEN');
    state.buyer.campaigns = data;
    if (state.buyer.selectedId == null || !data.some((c) => c.id === state.buyer.selectedId)) {
      state.buyer.selectedId = data[0]?.id ?? null;
    }
  } catch (err) {
    state.buyer.error = err;
    state.buyer.campaigns = [];
  }
  state.buyer.loading = false;
  renderScenarioSection();
}

async function loadAdminCampaigns() {
  if (state.user.role !== 'ADMIN') {
    renderScenarioSection();
    return;
  }
  state.admin.loading = true;
  state.admin.error = null;
  renderScenarioSection();
  try {
    const { data } = await api('/api/admin/campaigns?status=OPEN');
    state.admin.campaigns = data;
    if (state.admin.selectedId == null || !data.some((c) => c.id === state.admin.selectedId)) {
      state.admin.selectedId = data[0]?.id ?? null;
    }
  } catch (err) {
    state.admin.error = err;
    state.admin.campaigns = [];
  }
  state.admin.loading = false;
  renderScenarioSection();
  if (state.admin.selectedId != null) await loadAdminTargetOrder(state.admin.selectedId);
}

async function loadAdminTargetOrder(campaignId) {
  state.admin.targetOrder = null;
  state.admin.targetError = null;
  if (campaignId == null) {
    renderScenarioSection();
    return;
  }
  state.admin.targetLoading = true;
  renderScenarioSection();
  try {
    const { data } = await api(`/api/admin/payments?campaignId=${campaignId}&status=SUCCEEDED`);
    state.admin.targetOrder = data[0] ? { orderId: data[0].orderId, amount: data[0].amount } : null;
  } catch (err) {
    state.admin.targetError = err;
  }
  state.admin.targetLoading = false;
  renderScenarioSection();
}

// 역할에 맞는 선택기만 그린다 — BUYER 탭에는 구매자 선택기만, ADMIN 탭에는 S7 선택기만 보인다
// (requireRole이 BUYER·ADMIN만 통과시키므로 그 외 값은 없다).
function renderCampaignSelectors() {
  const el = document.getElementById('scenario-targets');

  if (state.user.role === 'BUYER') {
    let buyerBodyHtml;
    if (state.buyer.campaigns.length === 0) {
      buyerBodyHtml = html`<p class="hint">${state.buyer.loading ? '불러오는 중…'
        : state.buyer.error ? `불러오지 못했습니다: ${messageFor(state.buyer.error)}`
        : '지금 OPEN 상태인 캠페인이 없습니다.'}</p>`;
    } else {
      const options = state.buyer.campaigns.map((c) => html`<option value="${c.id}">${c.name} (#${c.id})</option>`).join('');
      buyerBodyHtml = html`<select id="buyer-campaign-select">${raw(options)}</select>`;
    }

    el.innerHTML = html`
      <div class="scenario-target">
        <h3>대상 캠페인 (OPEN)</h3>
        ${raw(buyerBodyHtml)}
        <button type="button" class="btn btn-secondary btn-sm" id="buyer-campaign-refresh">새로고침</button>
      </div>
    `;

    const buyerSelect = document.getElementById('buyer-campaign-select');
    if (buyerSelect) {
      buyerSelect.value = state.buyer.selectedId != null ? String(state.buyer.selectedId) : '';
      buyerSelect.disabled = !!state.running;
      buyerSelect.addEventListener('change', () => {
        state.buyer.selectedId = Number(buyerSelect.value) || null;
        renderScenarioButtons();
      });
    }
    const buyerRefresh = document.getElementById('buyer-campaign-refresh');
    buyerRefresh.disabled = !!state.running;
    buyerRefresh.addEventListener('click', loadBuyerCampaigns);
    return;
  }

  // ADMIN
  let adminBodyHtml;
  if (state.admin.campaigns.length === 0) {
    adminBodyHtml = html`<p class="hint">${state.admin.loading ? '불러오는 중…'
      : state.admin.error ? `불러오지 못했습니다: ${messageFor(state.admin.error)}`
      : 'PAID 주문이 있는 OPEN 캠페인이 필요합니다(seed-demo.sh 캠페인 B).'}</p>`;
  } else {
    const options = state.admin.campaigns.map((c) => html`<option value="${c.id}">${c.name} (#${c.id})</option>`).join('');
    let targetLine;
    if (state.admin.targetLoading) targetLine = html`<p class="hint">대상 주문 조회 중…</p>`;
    else if (state.admin.targetError) targetLine = html`<p class="notice notice--danger">대상 주문 조회 실패: ${messageFor(state.admin.targetError)}</p>`;
    else if (state.admin.targetOrder) {
      targetLine = html`<p class="hint">대상 주문: 주문 #${state.admin.targetOrder.orderId} (금액 ${formatMoney(state.admin.targetOrder.amount)}) — 자동 선택됨</p>`;
    } else {
      targetLine = html`<p class="notice notice--warning">이 캠페인에는 SUCCEEDED 결제가 있는 주문이 없습니다 — PAID 주문이 있는 OPEN 캠페인이 필요합니다(seed-demo.sh 캠페인 B).</p>`;
    }
    adminBodyHtml = html`<select id="admin-campaign-select">${raw(options)}</select>${targetLine}`;
  }

  el.innerHTML = html`
    <div class="scenario-target">
      <h3>대사 시나리오(S7) 대상 캠페인 (OPEN)</h3>
      ${raw(adminBodyHtml)}
      <button type="button" class="btn btn-secondary btn-sm" id="admin-campaign-refresh">새로고침</button>
    </div>
  `;

  const adminSelect = document.getElementById('admin-campaign-select');
  if (adminSelect) {
    adminSelect.value = state.admin.selectedId != null ? String(state.admin.selectedId) : '';
    adminSelect.disabled = !!state.running;
    adminSelect.addEventListener('change', () => {
      state.admin.selectedId = Number(adminSelect.value) || null;
      loadAdminTargetOrder(state.admin.selectedId);
    });
  }
  const adminRefresh = document.getElementById('admin-campaign-refresh');
  adminRefresh.disabled = !!state.running;
  adminRefresh.addEventListener('click', loadAdminCampaigns);
}

// --- 시나리오 버튼 (섹션 4) -------------------------------------------------------------------

function scenarioReadiness(def) {
  if (state.running) {
    return { ready: state.running === def.key, reason: state.running === def.key ? null : '다른 시나리오가 실행 중입니다.' };
  }
  if (state.user.role !== def.role) {
    const tabLabel = def.role === 'BUYER' ? '구매자(localhost)' : '운영자(127.0.0.1)';
    return { ready: false, reason: `${tabLabel} 탭에서 로그인해야 실행할 수 있습니다(현재 역할: ${state.user.role}).` };
  }
  if (def.role === 'BUYER') {
    if (state.buyer.selectedId == null) return { ready: false, reason: '대상 캠페인을 먼저 선택하세요.' };
  } else if (state.admin.selectedId == null || !state.admin.targetOrder) {
    return { ready: false, reason: 'PAID 주문이 있는 OPEN 캠페인을 대상으로 선택해야 합니다.' };
  }
  return { ready: true, reason: null };
}

function scenarioCardHtml(def) {
  const readiness = scenarioReadiness(def);
  const isRunningThis = state.running === def.key;
  const disabled = !!state.running || !readiness.ready;
  const timeoutOptionHtml = def.hasTimeoutOption
    ? html`<label class="checkbox-row"><input type="checkbox" id="s4a-timeout-checkbox" ${state.running ? 'disabled' : ''}>
        3초 타임아웃 경로로 보기 (delayMs 3500 — 기본은 해제, delayMs 0으로 즉시 504)</label>`
    : '';
  return html`
    <div class="scenario-card">
      <h3>${def.title}</h3>
      <p class="hint">${def.summary}</p>
      ${def.warningNote ? html`<div class="notice notice--warning">${def.warningNote}</div>` : ''}
      ${raw(timeoutOptionHtml)}
      <button type="button" class="btn btn-primary" id="run-${def.key}" ${disabled ? 'disabled' : ''}>
        ${isRunningThis ? '실행 중…' : '실행'}
      </button>
      ${!readiness.ready ? html`<p class="hint">${readiness.reason}</p>` : ''}
    </div>
  `;
}

function renderScenarioButtons() {
  const el = document.getElementById('scenario-buttons');
  const wasTimeoutChecked = document.getElementById('s4a-timeout-checkbox')?.checked ?? false;

  el.innerHTML = SCENARIO_DEFS.map(scenarioCardHtml).join('') + html`
    <div class="scenario-abort-row">
      <button type="button" class="btn btn-danger" id="abort-button" ${state.running ? '' : 'disabled'}>중단</button>
      <span class="hint">${state.running ? '실행 중 — 단계 사이에서 중단을 확인합니다(진행 중인 요청은 끝까지 기다립니다).' : '시나리오 실행 중에만 사용할 수 있습니다.'}</span>
    </div>
  `;

  const checkbox = document.getElementById('s4a-timeout-checkbox');
  if (checkbox) checkbox.checked = wasTimeoutChecked;

  SCENARIO_DEFS.forEach((def) => {
    const btn = document.getElementById(`run-${def.key}`);
    if (btn) btn.addEventListener('click', () => startScenario(def.key));
  });
  document.getElementById('abort-button').addEventListener('click', handleAbortClick);
}

function renderScenarioSection() {
  renderCampaignSelectors();
  renderScenarioButtons();
}

function handleAbortClick() {
  if (!state.running) return;
  state.abortRequested = true;
  const btn = document.getElementById('abort-button');
  if (btn) {
    btn.disabled = true;
    btn.textContent = '중단 요청됨…';
  }
}

// --- 시나리오 실행 ---------------------------------------------------------------------------

function buildScenarioOptions(key) {
  if (key === 's4a') {
    return { campaignId: state.buyer.selectedId, useTimeoutDelay: !!document.getElementById('s4a-timeout-checkbox')?.checked };
  }
  if (key === 's3' || key === 's2') {
    return { campaignId: state.buyer.selectedId };
  }
  if (key === 's7') {
    if (state.admin.selectedId == null || !state.admin.targetOrder) return null;
    const campaign = state.admin.campaigns.find((c) => c.id === state.admin.selectedId);
    return {
      campaignId: state.admin.selectedId,
      campaignName: campaign ? campaign.name : String(state.admin.selectedId),
      orderId: state.admin.targetOrder.orderId,
      orderAmount: state.admin.targetOrder.amount,
    };
  }
  return null;
}

/**
 * 같은 단계의 '진행' 행을 제자리에서 갱신한다(진행→성공/실패/경고, 소요 시간 표시) — 타임라인이
 * 단계마다 두 줄(진행+완료)로 두 배가 되지 않게 한다. "직전 행"이 아니라 "같은 step 이름의 가장
 * 최근 행"을 뒤에서부터 찾는다 — 예: S2 "동시 결제 2회"(진행) → "NORMAL 복구 (조기)"(진행+성공,
 * 다른 이름) → "동시 결제 2회"(성공)처럼 사이에 다른 단계가 끼어도 올바른 진행 행을 찾아 닫는다.
 * 찾은 행이 이미 'progress'가 아니면(이미 닫혔으면) 새 행을 쌓는다 — 예: 사전 준비 진행 → (보류
 * 웹훅 경고, 별도 행) → 사전 준비 성공(새 진행 행이 없으므로 이것도 별도 행)처럼 자연히 분리된다.
 */
function addTimelineEntry(step, status, detail) {
  const now = Date.now();
  if (status !== 'progress') {
    for (let i = state.timeline.length - 1; i >= 0; i--) {
      if (state.timeline[i].step === step) {
        if (state.timeline[i].status === 'progress') {
          const entry = state.timeline[i];
          entry.status = status;
          entry.detail = detail ?? '';
          entry.time = new Date();
          entry.elapsedMs = now - entry.startedAtMs;
          renderTimeline();
          return;
        }
        break; // 같은 이름의 가장 최근 행은 찾았지만 이미 닫혀 있다 — 새 행을 쌓는다(더 과거 것은 보지 않는다).
      }
    }
  }
  state.timeline.push({ time: new Date(), step, status, detail: detail ?? '', startedAtMs: now, elapsedMs: null });
  renderTimeline();
}

/**
 * 시나리오가 끝났는데도 'progress' 상태로 남은 타임라인 행이 있으면(이름 불일치·조기 반환 등으로
 * 놓친 경우에 대한 안전망) 최종 판정에 맞춰 강제로 닫는다. 정상 경로라면 이 함수는 아무 것도 하지
 * 않아야 한다 — 뭔가 닫혔다면 그 자체가 로직 결함의 신호다.
 */
function closeStrayProgressRows(outcome) {
  const closeStatus = outcome.result === 'fail' ? 'failure'
    : outcome.result === 'aborted' || outcome.result === 'warn' ? 'warning'
    : 'success';
  const now = Date.now();
  let changed = false;
  for (const entry of state.timeline) {
    if (entry.status === 'progress') {
      entry.status = closeStatus;
      entry.detail = entry.detail || '(실행 종료 — 최종 판정에 따라 닫힘)';
      entry.time = new Date();
      entry.elapsedMs = now - entry.startedAtMs;
      changed = true;
    }
  }
  if (changed) renderTimeline();
}

async function startScenario(key) {
  if (state.running) return;
  const options = buildScenarioOptions(key);
  if (!options) return; // 버튼이 이미 막아두지만 방어적으로 한 번 더 확인한다.

  state.running = key;
  state.abortRequested = false;
  state.timeline = [];
  state.verdict = null;
  state.evidenceBaseline = null;
  renderTimeline();
  renderVerdict();
  renderScenarioSection();
  setManualControlsDisabled(true);

  const ctx = {
    api,
    pg,
    log: addTimelineEntry,
    checkAbort: () => {
      if (state.abortRequested) throw new ScenarioAbort('사용자가 중단했습니다.');
    },
    snapshotCounters: metrics.snapshotCounters,
    diffCounters: metrics.diffCounters,
    onStartSnapshot: (snapshot) => {
      state.evidenceBaseline = snapshot;
      // 증거 패널의 "현재값"도 같은 스냅숏으로 맞춘다 — 그렇지 않으면 시작 직후 Δ가 마지막 10초
      // 폴링 시점과의 차이로 음수·엉뚱한 값이 잠깐 찍힐 수 있다.
      state.counters.snapshot = snapshot;
      renderCounters();
    },
    options,
  };

  let outcome;
  try {
    outcome = await runScenario(key, ctx);
  } catch (err) {
    // runScenario는 내부에서 모든 경로를 처리해 항상 결과 객체를 돌려준다 — 이 catch는 방어적이다.
    outcome = { result: 'fail', reasons: [`알 수 없는 오류: ${err.message ?? err}`] };
  }

  closeStrayProgressRows(outcome);
  state.verdict = outcome;
  state.running = null;
  renderVerdict();
  renderScenarioSection();
  setManualControlsDisabled(false);
  refreshCountersNow();
}

// --- 타임라인·판정 (섹션 5) -------------------------------------------------------------------

const TIMELINE_BADGE = {
  progress: ['blue', '진행'],
  success: ['green', '성공'],
  failure: ['red', '실패'],
  warning: ['yellow', '경고'],
};

function renderTimeline() {
  const el = document.getElementById('timeline-list');
  if (state.timeline.length === 0) {
    el.innerHTML = html`<li class="hint">아직 실행한 시나리오가 없습니다.</li>`;
    return;
  }
  el.innerHTML = state.timeline.map((entry) => {
    const [color, label] = TIMELINE_BADGE[entry.status] ?? ['gray', entry.status];
    const elapsedText = entry.elapsedMs != null ? ` (${(entry.elapsedMs / 1000).toFixed(1)}초)` : '';
    return html`
      <li>
        <span class="mono">${formatDateTime(entry.time)}</span>
        <span class="status-badge status-badge--${color}">${label}</span>
        <strong>${entry.step}</strong>${elapsedText}${entry.detail ? html` — ${entry.detail}` : ''}
      </li>
    `;
  }).join('');
  el.scrollTop = el.scrollHeight;
}

const VERDICT_BADGE = {
  pass: ['green', '통과'],
  warn: ['yellow', '경고'],
  fail: ['red', '실패'],
  aborted: ['gray', '중단'],
};

function renderVerdict() {
  const el = document.getElementById('verdict-panel');
  if (state.running) {
    const title = SCENARIO_DEFS.find((d) => d.key === state.running)?.title ?? state.running;
    el.innerHTML = html`<div class="notice notice--info">실행 중 — ${title}</div>`;
    return;
  }
  if (!state.verdict) {
    el.innerHTML = html`<p class="hint">아직 실행한 시나리오가 없습니다. 위에서 시나리오를 실행하면 여기에 최종 판정과 근거가 쌓입니다.</p>`;
    return;
  }
  const { result, reasons, links } = state.verdict;
  const [color, label] = VERDICT_BADGE[result] ?? ['gray', result];
  const reasonsHtml = (reasons ?? []).map((r) => html`<li>${r}</li>`).join('');
  const linksHtml = (links ?? []).map((l) => html`<li><a href="${l.href}" target="_blank" rel="noopener noreferrer">${l.label}</a></li>`).join('');
  el.innerHTML = html`
    <p><span class="status-badge status-badge--${color}">${label}</span></p>
    <h3>판정 근거</h3>
    <ul class="verdict-reasons">${raw(reasonsHtml)}</ul>
    ${links && links.length > 0 ? html`<h3>운영자 탭 증거</h3><ul>${raw(linksHtml)}</ul>` : ''}
  `;
}

// --- 수동 조작 (섹션 6) -----------------------------------------------------------------------

function setManualResult(status, text) {
  const el = document.getElementById('manual-result');
  const cls = status === 'success' ? 'notice--success' : status === 'failure' ? 'notice--danger' : 'notice--info';
  el.className = `notice ${cls}`;
  el.hidden = false;
  el.textContent = text;
}

function updateReplayButtonDisabled() {
  const submit = document.getElementById('manual-replay-submit');
  const provider = document.getElementById('manual-replay-provider').value.trim();
  const merchant = document.getElementById('manual-replay-merchant').value.trim();
  submit.disabled = !!state.running || (!provider && !merchant);
}

/** 시나리오 실행 중에는 수동 조작 전체를 비활성화한다 (§7 "수동 조작은 시나리오 실행 중에는 비활성"). */
function setManualControlsDisabled(disabled) {
  document.querySelectorAll('#manual-controls input, #manual-controls select, #manual-controls button')
    .forEach((el) => { el.disabled = disabled; });
  if (!disabled) updateReplayButtonDisabled();
}

function wireManualControls() {
  document.getElementById('manual-apply-mode').addEventListener('click', async () => {
    const settings = {
      mode: document.getElementById('manual-mode').value,
      delayMs: Number(document.getElementById('manual-delay-ms').value) || 0,
      blockWebhook: document.getElementById('manual-block-webhook').checked,
      webhookDuplicateCount: Number(document.getElementById('manual-webhook-dup').value) || 0,
      webhookReverseOrder: document.getElementById('manual-webhook-reverse').checked,
      refundMode: document.getElementById('manual-refund-mode').value,
    };
    setManualResult('info', '장애 모드 적용 중…');
    try {
      const data = await pg.setFailureMode(settings);
      setManualResult('success', `적용됨 — mode=${data.mode}, delayMs=${data.delayMs}, blockWebhook=${data.blockWebhook}, `
        + `webhookDuplicateCount=${data.webhookDuplicateCount}, webhookReverseOrder=${data.webhookReverseOrder}, refundMode=${data.refundMode}`);
    } catch (err) {
      setManualResult('failure', err.message);
    }
  });

  const replayProvider = document.getElementById('manual-replay-provider');
  const replayMerchant = document.getElementById('manual-replay-merchant');
  replayProvider.addEventListener('input', updateReplayButtonDisabled);
  replayMerchant.addEventListener('input', updateReplayButtonDisabled);
  updateReplayButtonDisabled();

  document.getElementById('manual-replay-submit').addEventListener('click', async () => {
    setManualResult('info', '웹훅 재발사 중…');
    try {
      const data = await pg.replayWebhooks({
        providerPaymentId: replayProvider.value.trim() || undefined,
        merchantPaymentId: replayMerchant.value.trim() || undefined,
      });
      setManualResult('success', `replayedCount=${data.replayedCount}`);
    } catch (err) {
      setManualResult('failure', err.message);
    }
  });

  document.getElementById('manual-inject-submit').addEventListener('click', async () => {
    const orderId = Number(document.getElementById('manual-inject-order-id').value);
    const amount = Number(document.getElementById('manual-inject-amount').value);
    if (!orderId || !amount) {
      setManualResult('failure', 'orderId와 amount는 필수입니다.');
      return;
    }
    if (!confirm('주입 거래는 mock-pg 메모리에 남고 삭제 API가 없습니다. 계속할까요?')) return;
    const providerPaymentId = document.getElementById('manual-inject-provider-id').value.trim();
    const merchantPaymentId = document.getElementById('manual-inject-merchant-id').value.trim();
    const processedAt = document.getElementById('manual-inject-processed-at').value.trim();
    const refundedAmount = Number(document.getElementById('manual-inject-refunded-amount').value);
    const request = {
      orderId,
      amount,
      status: document.getElementById('manual-inject-status').value,
      providerPaymentId: providerPaymentId || undefined,
      merchantPaymentId: merchantPaymentId || undefined,
      processedAt: processedAt || undefined,
      refundedAmount: refundedAmount > 0 ? refundedAmount : undefined,
    };
    setManualResult('info', '거래 주입 중…');
    try {
      const data = await pg.injectTransaction(request);
      setManualResult('success', `주입됨 — providerPaymentId=${data.providerPaymentId}, merchantPaymentId=${data.merchantPaymentId}`);
    } catch (err) {
      setManualResult('failure', err.message);
    }
  });

  document.getElementById('manual-reset-normal').addEventListener('click', async () => {
    setManualResult('info', 'NORMAL 복구 중…');
    try {
      await pg.resetNormal();
      setManualResult('success', 'NORMAL로 복구했습니다.');
    } catch (err) {
      setManualResult('failure', err.message);
    }
  });
}
