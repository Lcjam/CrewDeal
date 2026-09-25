// settlements.js — 공급사 정산 배치 목록 (프론트엔드 계획 §6 "공급사", SET-01~03)
//
// GET /api/suppliers/me/settlements는 campaignName을 주지 않으므로(SettlementService.
// SettlementBatchResponse) refcache.campaignName()으로 채운다. 정산 스케줄러가 10초마다 도므로
// 5초 폴링(타임아웃 없음)이면 조작 없이도 상태 변화가 보인다.
//
// RECOVERY 배치(SET-03, 정산 완료 후 환불이 생기면 자동으로 만들어지는 회수 배치)는 금액이
// 음수다. 실수로 손실처럼 보이지 않도록(사실은 정상 동작이다) 행 배경·라벨·빨간 음수 금액으로
// 눈에 띄게 구분한다.

import { api } from '../common/api.js';
import { html, raw } from '../common/html.js';
import { formatMoney, formatDateTime } from '../common/format.js';
import { messageFor } from '../common/codes.js';
import { campaignName } from '../common/refcache.js';
import { poll } from '../common/poll.js';
import '../common/widgets.js';

let lastGoodBatches = null;

export function initSettlementsPage() {
  poll({
    fn: fetchWithCampaignNames,
    until: () => false,
    intervalMs: 5000,
    maxIntervalMs: 5000,
    timeoutMs: Infinity,
    onTick: (result, error) => {
      if (!error) lastGoodBatches = result;
      render(lastGoodBatches, error);
    },
  });
}

async function fetchWithCampaignNames() {
  const { data } = await api('/api/suppliers/me/settlements');
  const names = await Promise.all(
    data.map((b) => campaignName(b.campaignId).catch(() => `캠페인 #${b.campaignId}`)),
  );
  return data.map((b, i) => ({ ...b, campaignName: names[i] }));
}

function render(batches, error) {
  const tbody = document.getElementById('settlements-tbody');
  const netTotals = document.getElementById('net-totals');

  if (!batches) {
    tbody.innerHTML = error
      ? html`<tr><td colspan="10">불러오지 못했습니다: ${messageFor(error)} (요청 ID ${error.requestId ?? '-'})</td></tr>`
      : '<tr><td colspan="10">불러오는 중…</td></tr>';
    netTotals.innerHTML = '';
  } else if (batches.length === 0) {
    tbody.innerHTML = html`<tr><td colspan="10">아직 정산 배치가 없습니다.</td></tr>`;
    netTotals.innerHTML = '';
  } else {
    tbody.innerHTML = batches.map(renderRow).join('');
    renderNetTotals(batches);
  }

  updateNotice(error, batches !== null);
}

function updateNotice(error, hasGoodData) {
  const el = document.getElementById('notice');
  if (error && hasGoodData) {
    el.innerHTML = html`<div class="notice notice--danger">갱신 실패 — 마지막 값을 표시 중입니다: ${messageFor(error)} (요청 ID ${error.requestId ?? '-'})</div>`;
  } else {
    el.innerHTML = '';
  }
}

function amountClass(totalAmount) {
  return Number(totalAmount) < 0 ? ' amount--negative' : '';
}

function rowClass(batchType) {
  return batchType === 'RECOVERY' ? 'row--recovery' : '';
}

function renderRow(b) {
  const holdReasonCell = b.status === 'HELD' ? (b.holdReason ?? '-') : '-';
  const failureCell = b.status === 'FAILED' && b.failureCode
    ? html`${b.failureCode}: ${b.failureReason ?? ''}`
    : '-';
  return html`
    <tr class="${raw(rowClass(b.batchType))}">
      <td>${b.id}</td>
      <td>${b.campaignName}</td>
      <td>
        <status-badge domain="settlementBatchType" value="${b.batchType}"></status-badge>
        ${b.batchType === 'RECOVERY' ? html`<div class="recovery-label">환불 회수</div>` : ''}
      </td>
      <td><status-badge domain="settlementBatch" value="${b.status}"></status-badge></td>
      <td class="num${raw(amountClass(b.totalAmount))}">${formatMoney(b.totalAmount)}</td>
      <td class="num">${b.attempts}</td>
      <td>${formatDateTime(b.determinedAt)}</td>
      <td>${formatDateTime(b.completedAt)}</td>
      <td>${holdReasonCell}</td>
      <td>${failureCell}</td>
    </tr>
  `;
}

/** 참고용 — 캠페인별 정산·회수 합계(순 지급액). "nice to have"라 배치 개수가 적은 데모 규모 전제. */
function renderNetTotals(batches) {
  const container = document.getElementById('net-totals');
  const byCampaign = new Map();
  for (const b of batches) {
    const entry = byCampaign.get(b.campaignId) ?? { campaignName: b.campaignName, net: 0 };
    entry.net += Number(b.totalAmount);
    byCampaign.set(b.campaignId, entry);
  }
  const rows = [...byCampaign.values()]
    .map((entry) => html`
      <tr>
        <td>${entry.campaignName}</td>
        <td class="num${raw(amountClass(entry.net))}">${formatMoney(entry.net)}</td>
      </tr>
    `)
    .join('');
  container.innerHTML = html`
    <table>
      <thead><tr><th>캠페인</th><th>순 정산액 (정산 + 회수)</th></tr></thead>
      <tbody>${raw(rows)}</tbody>
    </table>
  `;
}
