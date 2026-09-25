// campaigns.js — 공급사 참여 캠페인 목록 (프론트엔드 계획 §6 "공급사")
//
// GET /api/suppliers/me/campaigns를 5초 폴링(타임아웃 없음, buyer/campaigns.js와 같은 패턴)하고,
// 캠페인마다 GET /api/suppliers/me/campaigns/{id}/expected-settlement를 함께 불러 예상 공급대금을
// 보여준다. 데모 규모(캠페인 수십 건 이하)라 캠페인당 별도 요청을 그대로 병렬로 보낸다 —
// Promise.allSettled로 묶어 한 캠페인의 조회가 실패해도 나머지 행은 그대로 그려지게 한다.

import { api } from '../common/api.js';
import { html, raw } from '../common/html.js';
import { formatMoney, formatDateTime } from '../common/format.js';
import { messageFor } from '../common/codes.js';
import { poll } from '../common/poll.js';
import '../common/widgets.js';

// 폴링이 마지막으로 성공했을 때 받은 목록(예상 공급대금 병합 완료). 실패 tick에서도 이 값을 그린다.
let lastGoodCampaigns = null;
let pollHandle = null;

export function initCampaignsPage() {
  document.getElementById('refresh-btn').addEventListener('click', () => {
    if (pollHandle) pollHandle.restart();
  });

  pollHandle = poll({
    fn: fetchCampaignsWithExpected,
    until: () => false,
    intervalMs: 5000,
    maxIntervalMs: 5000,
    timeoutMs: Infinity,
    onTick: (result, error) => {
      if (!error) lastGoodCampaigns = result;
      render(lastGoodCampaigns, error);
    },
  });
}

async function fetchCampaignsWithExpected() {
  const { data: campaigns } = await api('/api/suppliers/me/campaigns');
  const settled = await Promise.allSettled(
    campaigns.map((c) => api(`/api/suppliers/me/campaigns/${c.id}/expected-settlement`)),
  );
  return campaigns.map((c, i) => {
    const outcome = settled[i];
    return outcome.status === 'fulfilled'
      ? { ...c, expected: outcome.value.data, expectedError: null }
      : { ...c, expected: null, expectedError: outcome.reason };
  });
}

function render(campaigns, error) {
  const tbody = document.getElementById('campaigns-tbody');

  if (!campaigns) {
    // 첫 로드가 아직 성공한 적이 없다 — 오류면 오류를, 아니면 로딩 중 문구를 보여준다.
    tbody.innerHTML = error
      ? html`<tr><td colspan="7">불러오지 못했습니다: ${messageFor(error)} (요청 ID ${error.requestId ?? '-'})</td></tr>`
      : '<tr><td colspan="7">불러오는 중…</td></tr>';
  } else if (campaigns.length === 0) {
    tbody.innerHTML = html`<tr><td colspan="7">참여 중인 캠페인이 없습니다.</td></tr>`;
  } else {
    tbody.innerHTML = campaigns.map(renderRow).join('');
  }

  updateNotice(error, campaigns !== null);
}

function updateNotice(error, hasGoodData) {
  const el = document.getElementById('notice');
  if (error && hasGoodData) {
    el.innerHTML = html`<div class="notice notice--danger">갱신 실패 — 마지막 값을 표시 중입니다: ${messageFor(error)} (요청 ID ${error.requestId ?? '-'})</div>`;
  } else {
    el.innerHTML = '';
  }
}

function expectedCellHtml(c) {
  if (c.expected) return html`${formatMoney(c.expected.expectedSupplyAmount)}`;
  return html`— ${c.expectedError ? messageFor(c.expectedError) : '조회 실패'}`;
}

function renderRow(c) {
  const skus = c.skus ?? [];
  const skuRows = skus
    .map((s) => html`<div class="camp-sku-list__row"><span>${s.optionName}</span><span>${s.availableQuantity}개</span></div>`)
    .join('');
  return html`
    <tr>
      <td>
        ${c.name}
        ${c.rejectionReason ? html`<div class="hint">반려 사유: ${c.rejectionReason}</div>` : ''}
      </td>
      <td>${c.productName}</td>
      <td><status-badge domain="campaign" value="${c.status}"></status-badge></td>
      <td class="camp-period">${formatDateTime(c.startsAt)}<br>~ ${formatDateTime(c.endsAt)}</td>
      <td class="num">${formatMoney(c.dealPrice)}</td>
      <td><div class="camp-sku-list">${raw(skuRows)}</div></td>
      <td class="num">${expectedCellHtml(c)}</td>
    </tr>
  `;
}
