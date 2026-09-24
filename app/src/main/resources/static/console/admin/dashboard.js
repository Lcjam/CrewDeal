// dashboard.js — 운영자 홈 "지금 확인할 것" (프론트엔드 계획 §6 "운영자", §8 3단계)
//
// 요약 헤더(6개 카운터)는 admin.js의 bootAdmin()이 이미 2초마다 갱신한다. 이 화면은 그 숫자들이
// "왜 0이 아닌지" 파고들 수 있는 목록형 위젯 5개를 5초마다 갱신한다: UNKNOWN 결제 상위 5건,
// REVIEWING 캠페인 수, HELD/FAILED 정산 배치, OPEN 대사 불일치 수(admin:summary 이벤트 재사용),
// 최근 대사 실행 1건. 원장 불균형은 여기 없다 — 명세가 수동 새로고침만 요구한다(§5.5).

import { api } from '/console/common/api.js';
import { bootAdmin, currentSummary } from '/console/admin/admin.js';
import { poll } from '/console/common/poll.js';
import { html } from '/console/common/html.js';
import { formatMoney, formatDateTime, STATUS_TABLES } from '/console/common/format.js';
import { messageFor } from '/console/common/codes.js';

const POLL_INTERVAL_MS = 5000;

const state = {
  unknownPayments: [],
  unknownError: null,
  reviewingCount: null,
  reviewingError: null,
  blockedSettlements: [],
  settlementsError: null,
  latestRun: null,
  runError: null,
  openDiscrepancyCount: null, // admin.js의 운영 요약 폴링(2초)에서 흘러온 값 — 별도 호출 없음
};

let unknownEl, reviewingEl, settlementsEl, discrepancyEl, reconEl;

function renderUnknownPayments() {
  if (state.unknownError) {
    unknownEl.innerHTML = html`<p class="notice notice--danger" style="margin:0">불러오지 못했습니다: ${messageFor(state.unknownError)} (요청 ID: ${state.unknownError.requestId ?? '-'})</p>`;
    return;
  }
  if (state.unknownPayments.length === 0) {
    unknownEl.innerHTML = `<p style="margin:0">확정 대기(UNKNOWN) 결제가 없습니다.</p>`;
    return;
  }
  const items = state.unknownPayments.slice(0, 5).map((p) => html`
    <li><a href="/console/admin/payments.html?orderId=${p.orderId}">
      주문 ${p.orderId} · ${p.campaignName} · ${formatMoney(p.amount)} · ${formatDateTime(p.createdAt)}
    </a></li>`).join('');
  unknownEl.innerHTML = `<ol class="watch-list">${items}</ol>`;
}

function renderReviewing() {
  if (state.reviewingError) {
    reviewingEl.textContent = `불러오지 못했습니다: ${messageFor(state.reviewingError)}`;
    return;
  }
  const n = state.reviewingCount ?? 0;
  reviewingEl.innerHTML = n === 0
    ? '승인 대기 중인 캠페인이 없습니다.'
    : html`<strong>${n}</strong>건이 승인 대기 중입니다.`;
}

function renderSettlements() {
  if (state.settlementsError) {
    settlementsEl.innerHTML = html`<p class="notice notice--danger" style="margin:0">불러오지 못했습니다: ${messageFor(state.settlementsError)} (요청 ID: ${state.settlementsError.requestId ?? '-'})</p>`;
    return;
  }
  if (state.blockedSettlements.length === 0) {
    settlementsEl.innerHTML = `<p style="margin:0">보류·실패한 정산 배치가 없습니다.</p>`;
    return;
  }
  const items = state.blockedSettlements.slice(0, 5).map((b) => {
    const [statusLabel] = STATUS_TABLES.settlementBatch[b.status] ?? [b.status];
    const reason = b.status === 'HELD' ? b.holdReason : b.failureReason;
    return html`<li><a href="/console/admin/settlements.html?campaignId=${b.campaignId}">
      ${b.campaignName} · ${statusLabel}${reason ? html` · ${reason}` : ''}
    </a></li>`;
  }).join('');
  const moreNote = state.blockedSettlements.length > 5
    ? `<p class="muted" style="margin:4px 0 0;font-size:12px">그 외 ${state.blockedSettlements.length - 5}건 더</p>` : '';
  settlementsEl.innerHTML = `<ol class="watch-list">${items}</ol>${moreNote}`;
}

function renderDiscrepancies() {
  const n = state.openDiscrepancyCount;
  if (n === null || n === undefined) {
    discrepancyEl.textContent = '불러오는 중…';
    return;
  }
  discrepancyEl.innerHTML = n === 0
    ? 'OPEN 불일치가 없습니다.'
    : html`<strong>${n}</strong>건이 미해결(OPEN) 상태입니다.`;
}

function renderReconRun() {
  if (state.runError) {
    reconEl.textContent = `불러오지 못했습니다: ${messageFor(state.runError)}`;
    return;
  }
  if (!state.latestRun) {
    reconEl.textContent = '아직 실행 이력이 없습니다.';
    return;
  }
  const run = state.latestRun;
  const [statusLabel] = STATUS_TABLES.reconciliationRun[run.status] ?? [run.status];
  reconEl.innerHTML = html`${statusLabel} · ${formatDateTime(run.startedAt)} · 불일치 <strong>${run.mismatchCount}</strong>건`;
}

function render() {
  renderUnknownPayments();
  renderReviewing();
  renderSettlements();
  renderDiscrepancies();
  renderReconRun();
}

async function fetchUnknownPayments() {
  return api('/api/admin/payments?status=UNKNOWN');
}

async function fetchReviewingCampaigns() {
  return api('/api/admin/campaigns?status=REVIEWING');
}

async function fetchBlockedSettlements() {
  return api('/api/admin/settlements?status=HELD,FAILED');
}

async function fetchLatestRun() {
  return api('/api/admin/reconciliations');
}

async function init() {
  const user = await bootAdmin();
  if (!user) return;

  unknownEl = document.getElementById('dashboard-unknown-payments');
  reviewingEl = document.getElementById('dashboard-reviewing');
  settlementsEl = document.getElementById('dashboard-settlements');
  discrepancyEl = document.getElementById('dashboard-discrepancies');
  reconEl = document.getElementById('dashboard-recon-run');

  // admin.js가 2초마다 갱신하는 운영 요약에서 openDiscrepancyCount만 얻어 쓴다 — 중복 호출 없음.
  const existing = currentSummary();
  if (existing) {
    state.openDiscrepancyCount = existing.openDiscrepancyCount;
    renderDiscrepancies();
  }
  window.addEventListener('admin:summary', (event) => {
    state.openDiscrepancyCount = event.detail?.openDiscrepancyCount ?? null;
    renderDiscrepancies();
  });

  render();

  poll({
    fn: async () => {
      const [unknownRes, reviewingRes, settlementsRes, runRes] = await Promise.allSettled([
        fetchUnknownPayments(), fetchReviewingCampaigns(), fetchBlockedSettlements(), fetchLatestRun(),
      ]);
      return { unknownRes, reviewingRes, settlementsRes, runRes };
    },
    until: () => false,
    intervalMs: POLL_INTERVAL_MS,
    maxIntervalMs: POLL_INTERVAL_MS,
    timeoutMs: Infinity, // 홈 화면은 "떠 있는 동안 계속" — §5.5 표의 "운영 요약·재고" 행과 같은 취급.
    onTick: (result) => {
      if (!result) return;
      const { unknownRes, reviewingRes, settlementsRes, runRes } = result;

      if (unknownRes.status === 'fulfilled') {
        state.unknownError = null;
        state.unknownPayments = unknownRes.value.data ?? [];
      } else {
        state.unknownError = unknownRes.reason;
      }

      if (reviewingRes.status === 'fulfilled') {
        state.reviewingError = null;
        state.reviewingCount = (reviewingRes.value.data ?? []).length;
      } else {
        state.reviewingError = reviewingRes.reason;
      }

      if (settlementsRes.status === 'fulfilled') {
        state.settlementsError = null;
        state.blockedSettlements = settlementsRes.value.data ?? [];
      } else {
        state.settlementsError = settlementsRes.reason;
      }

      if (runRes.status === 'fulfilled') {
        state.runError = null;
        state.latestRun = (runRes.value.data ?? [])[0] ?? null;
      } else {
        state.runError = runRes.reason;
      }

      render();
    },
  });
}

init();
