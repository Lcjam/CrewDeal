// settlements.js — 인플루언서 정산 배치 목록 (SET-01~03, 프론트엔드 계획 §6 "인플루언서")
//
// GET /api/influencers/me/settlements는 campaignName을 주지 않으므로(SettlementBatchResponse에
// campaignId만 있다) refcache.js로 캠페인 이름을 채운다. supplier/settlements.js와 같은 패턴 —
// 이름을 전부 resolve한 뒤 표를 한 번에 그린다("…" 자리표시자를 넣었다가 5초마다 다시 채우면
// 폴링마다 셀이 깜빡인다). 5초 폴링(서버 정산 스케줄러 10초 주기 근거, §5.5), 초기 fetch는
// poll()의 첫 tick이 즉시 실행되므로 별도로 한 번 더 부르지 않는다.
// RECOVERY 배치는 금액이 음수다 — 행 배경과 금액 색으로 눈에 띄게 구분한다(SET-03).

import { api } from '../common/api.js';
import { html, raw } from '../common/html.js';
import { formatMoney, formatDateTime } from '../common/format.js';
import { messageFor } from '../common/codes.js';
import { campaignName } from '../common/refcache.js';
import { poll } from '../common/poll.js';
import '../common/widgets.js';

const POLL_INTERVAL_MS = 5000;

// 마지막으로 성공한 목록(이름까지 채워진 상태). 실패 tick에서도 이 값을 계속 그린다.
let lastGoodBatches = null;

function amountClass(totalAmount) {
  return Number(totalAmount) < 0 ? ' amount--negative' : '';
}

function rowTypeClass(batchType) {
  return batchType === 'RECOVERY' ? ' row--recovery' : '';
}

function rowHtml(b) {
  return html`
    <tr class="${raw(rowTypeClass(b.batchType))}">
      <td>${b.id}</td>
      <td>${b.campaignName}</td>
      <td><status-badge domain="settlementBatchType" value="${b.batchType}"></status-badge></td>
      <td><status-badge domain="settlementBatch" value="${b.status}"></status-badge></td>
      <td class="num${raw(amountClass(b.totalAmount))}">${formatMoney(b.totalAmount)}</td>
      <td class="num">${b.attempts}</td>
      <td>${formatDateTime(b.determinedAt)}</td>
      <td>${formatDateTime(b.completedAt)}</td>
      <td>${b.holdReason ?? '-'}</td>
      <td>${b.failureCode ? html`${b.failureCode}: ${b.failureReason ?? ''}` : '-'}</td>
    </tr>
  `;
}

/** 목록을 불러오고 캠페인 이름을 전부 resolve한 뒤에야 반환한다 — DOM에는 완성된 행만 그린다. */
async function fetchWithCampaignNames() {
  const { data } = await api('/api/influencers/me/settlements');
  // refcache가 캠페인당 한 번만 조회해 캐시하므로 여러 배치가 같은 캠페인이어도 중복 호출되지 않는다.
  const names = await Promise.all(
    data.map((b) => campaignName(b.campaignId).catch(() => `캠페인 #${b.campaignId}`)),
  );
  return data.map((b, i) => ({ ...b, campaignName: names[i] }));
}

function render(batches, error) {
  const tbody = document.getElementById('settlements-tbody');
  const staleHintEl = document.getElementById('list-stale-hint');

  if (!batches) {
    tbody.innerHTML = error
      ? html`<tr><td colspan="10">불러오지 못했습니다: ${messageFor(error)} (요청 ID ${error.requestId ?? '-'})</td></tr>`
      : '<tr><td colspan="10">불러오는 중…</td></tr>';
  } else if (batches.length === 0) {
    tbody.innerHTML = html`<tr><td colspan="10">아직 정산 배치가 없습니다. 캠페인이 <code>CLOSED</code>되고 정산 유예 기간이 지나면 배치가 생성됩니다.</td></tr>`;
  } else {
    tbody.innerHTML = batches.map(rowHtml).join('');
  }

  // 목록 자체(마지막으로 불러온 값)는 건드리지 않는다 — 실패했다고 표를 비우지 않는다.
  staleHintEl.hidden = !(error && batches);
}

export function initSettlementsPage() {
  poll({
    fn: fetchWithCampaignNames,
    until: () => false,
    intervalMs: POLL_INTERVAL_MS,
    maxIntervalMs: POLL_INTERVAL_MS,
    timeoutMs: Infinity,
    onTick: (result, error) => {
      if (!error) lastGoodBatches = result;
      render(lastGoodBatches, error);
    },
  });
}
