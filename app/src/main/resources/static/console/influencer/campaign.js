// campaign.js — 인플루언서 캠페인 상세·정산 대시보드 (프론트엔드 계획 §6 "인플루언서")
//
// GET /api/campaigns/{id}는 이름·딜가·기간·SKU 배정 수량처럼 판매 중 바뀌지 않는 값이라 한 번만
// 불러온다(이 엔드포인트는 상태·소유권을 검사하지 않는다 — refcache.js 주석 참고). 정산 대시보드
// (GET /api/influencers/me/campaigns/{id}/dashboard, LED-01)는 5초마다 폴링한다 — 환불은 이미
// LED-03 역분개로 반영되어 있으므로 이 화면이 따로 빼지 않는다.

import { api } from '../common/api.js';
import { html, idParam } from '../common/html.js';
import { formatMoney, formatDateTime } from '../common/format.js';
import { messageFor } from '../common/codes.js';
import { poll } from '../common/poll.js';
import '../common/widgets.js';

const DASHBOARD_POLL_INTERVAL_MS = 5000;

export async function initCampaignPage() {
  const id = idParam('id');
  if (id === null) {
    showFatal('잘못된 캠페인 ID입니다.');
    return; // §html.js 규칙: id가 없으면 API를 호출하지 않는다
  }

  let campaign;
  try {
    const { data } = await api(`/api/campaigns/${id}`);
    campaign = data;
  } catch (err) {
    showFatal(html`캠페인을 불러오지 못했습니다: ${messageFor(err)} (요청 ID ${err.requestId ?? '-'})`);
    return;
  }

  document.getElementById('camp-body').hidden = false;
  document.getElementById('camp-settlements-link').href = '/console/influencer/settlements.html';
  renderCampaignInfo(campaign);
  startDashboardPolling(id);
}

function showFatal(message) {
  document.getElementById('camp-error').innerHTML = html`<div class="notice notice--danger">${message}</div>`;
}

function renderCampaignInfo(c) {
  document.getElementById('camp-name').textContent = c.name;
  document.getElementById('camp-slug').textContent = c.slug;
  document.getElementById('camp-status-badge').setAttribute('value', c.status);
  document.getElementById('camp-price').textContent = formatMoney(c.dealPrice);
  document.getElementById('camp-commission').textContent = (c.commissionRateBp === null || c.commissionRateBp === undefined)
    ? '-'
    : `${(c.commissionRateBp / 100).toFixed(2)}%`;
  document.getElementById('camp-limit').textContent = `${c.perUserPurchaseLimit}개`;
  document.getElementById('camp-period').textContent = `${formatDateTime(c.startsAt)} ~ ${formatDateTime(c.endsAt)}`;

  const skus = c.skus ?? [];
  document.getElementById('camp-sku-list').innerHTML = skus.length
    ? skus.map((s) => html`<div class="camp-sku-list__row"><span>${s.optionName}</span><span>배정 ${s.allocatedQuantity ?? '-'}개</span></div>`).join('')
    : '<p class="hint">SKU 정보가 없습니다.</p>';
}

function renderDashboard(d) {
  document.getElementById('dash-settled').textContent = `${d.settledOrderCount}건`;
  document.getElementById('dash-refunded').textContent = `${d.refundedOrderCount}건`;
  document.getElementById('dash-net-sales').textContent = formatMoney(d.netSalesAmount);
  document.getElementById('dash-expected-commission').textContent = formatMoney(d.expectedCommission);
}

function startDashboardPolling(campaignId) {
  let handle;
  handle = poll({
    fn: () => api(`/api/influencers/me/campaigns/${campaignId}/dashboard`),
    until: () => false,
    intervalMs: DASHBOARD_POLL_INTERVAL_MS,
    maxIntervalMs: DASHBOARD_POLL_INTERVAL_MS,
    timeoutMs: Infinity,
    onTick: (result, error) => {
      if (error) {
        // 403 FORBIDDEN_NOT_OWNER·404 CAMPAIGN_NOT_FOUND는 다시 시도해도 결과가 바뀌지 않는 영구
        // 오류다 — 계속 두드리지 않고 여기서 멈춘다. 그 외(네트워크 오류 등)는 폴링을 유지하고
        // 마지막 값을 보여준 채 갱신 실패만 알린다.
        if (error.status === 403 || error.status === 404) {
          document.getElementById('dash-error').innerHTML =
            html`<div class="notice notice--danger">대시보드를 불러올 수 없습니다: ${messageFor(error)} (요청 ID ${error.requestId ?? '-'})</div>`;
          document.getElementById('dash-body').hidden = true;
          handle.stop();
          return;
        }
        document.getElementById('dash-error').innerHTML =
          html`<div class="notice notice--danger">갱신 실패 — 마지막 값을 표시 중입니다: ${messageFor(error)} (요청 ID ${error.requestId ?? '-'})</div>`;
        return;
      }
      document.getElementById('dash-error').innerHTML = '';
      document.getElementById('dash-body').hidden = false;
      renderDashboard(result.data);
    },
  });
}
