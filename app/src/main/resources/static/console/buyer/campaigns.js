// campaigns.js — 구매자 공구 목록 (프론트엔드 계획 §6 "구매자", S1-a)
//
// GET /api/campaigns?status=SCHEDULED,OPEN,SOLD_OUT 를 1초 폴링한다(§5.5 "운영 요약·재고" 행 —
// 타임아웃 없음). k6 부하 중에도 재고 100 → 0 → SOLD_OUT 배지가 화면에 그대로 반영되어야 한다.
// 갱신 실패 시에는 마지막으로 받은 목록을 그대로 유지하고 "갱신 실패" 안내만 덧붙인다
// (admin.js의 renderSummary와 같은 패턴 — 화면을 비우지 않는다).

import { api } from '../common/api.js';
import { html, raw } from '../common/html.js';
import { formatMoney, formatDateTime } from '../common/format.js';
import { messageFor } from '../common/codes.js';
import { poll } from '../common/poll.js';

const DEFAULT_STATUSES = 'SCHEDULED,OPEN,SOLD_OUT';

// 폴링이 마지막으로 성공했을 때 받은 목록. 실패 tick에서도 이 값을 계속 그린다.
let lastGoodCampaigns = null;

export function initCampaignsPage() {
  wireRowNavigation();
  poll({
    fn: () => api(`/api/campaigns?status=${DEFAULT_STATUSES}`),
    until: () => false,
    intervalMs: 1000,
    maxIntervalMs: 2000,
    timeoutMs: Infinity,
    onTick: (result, error) => {
      if (!error) lastGoodCampaigns = result.data;
      render(lastGoodCampaigns, error);
    },
  });
}

function wireRowNavigation() {
  const tbody = document.getElementById('campaigns-tbody');
  if (!tbody) return;
  tbody.addEventListener('click', (event) => {
    if (event.target.closest('a')) return; // 이름 링크는 자체 동작에 맡긴다
    const row = event.target.closest('tr[data-href]');
    if (row) location.href = row.dataset.href;
  });
}

function render(campaigns, error) {
  const tbody = document.getElementById('campaigns-tbody');
  if (!tbody) return;

  if (!campaigns) {
    // 첫 로드가 아직 성공한 적이 없다 — 오류면 오류를, 아니면 로딩 중 문구를 보여준다.
    tbody.innerHTML = error
      ? html`<tr><td colspan="7">불러오지 못했습니다: ${messageFor(error)} (요청 ID ${error.requestId ?? '-'})</td></tr>`
      : '<tr><td colspan="7">불러오는 중…</td></tr>';
  } else if (campaigns.length === 0) {
    tbody.innerHTML = '<tr><td colspan="7">지금 볼 수 있는 공구가 없습니다.</td></tr>';
  } else {
    tbody.innerHTML = campaigns.map(renderRow).join('');
  }

  updateNotice(error, campaigns !== null);
}

function updateNotice(error, hasGoodData) {
  const el = document.getElementById('notice');
  if (!el) return;
  if (error && hasGoodData) {
    el.innerHTML = html`<div class="notice notice--danger">갱신 실패 — 마지막 값을 표시 중입니다: ${messageFor(error)} (요청 ID ${error.requestId ?? '-'})</div>`;
  } else {
    el.innerHTML = '';
  }
}

function renderRow(c) {
  const skus = c.skus ?? [];
  const total = skus.reduce((sum, s) => sum + (s.availableQuantity ?? 0), 0);
  const skuRows = skus
    .map((s) => html`<div class="camp-sku-list__row"><span>${s.optionName}</span><span>${s.availableQuantity}개</span></div>`)
    .join('');
  const href = `campaign.html?id=${c.id}`;
  return html`
    <tr class="camp-row" data-href="${href}">
      <td><a href="${href}">${c.name}</a></td>
      <td>${c.productName}</td>
      <td>${c.supplierName}</td>
      <td>${formatMoney(c.dealPrice)}</td>
      <td><status-badge domain="campaign" value="${c.status}"></status-badge></td>
      <td class="camp-period">${formatDateTime(c.startsAt)}<br>~ ${formatDateTime(c.endsAt)}</td>
      <td>
        <div class="camp-sku-list">
          ${raw(skuRows)}
          <div class="camp-sku-list__total">합계 ${total}개</div>
        </div>
      </td>
    </tr>
  `;
}
