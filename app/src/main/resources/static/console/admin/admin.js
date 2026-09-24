// admin.js — 운영자 화면 공통 부트스트랩 (프론트엔드 계획 §6 "운영자")
//
// 모든 운영자 화면은 bootAdmin()으로 시작한다: ADMIN 역할 가드 → 공용 헤더 → 요약 헤더.
// 요약 헤더는 GET /api/admin/ops/summary의 6개 카운터를 2초마다 갱신한다.
// 이 숫자들이 0으로 수렴하는 것이 복구 시연의 결론이다.
//
// 최신 요약은 window 'admin:summary' CustomEvent로도 흘려보낸다 — 결제 화면이 UNKNOWN 목록
// 길이(LIMIT 100)와 unknownPaymentCount를 비교해 "100건 초과"를 표시하는 데 쓴다 (§4).

import { api } from '../common/api.js';
import { requireRole } from '../common/session.js';
import { mountNav } from '../common/nav.js';
import { poll } from '../common/poll.js';
import { html } from '../common/html.js';

const SUMMARY_FIELDS = [
  ['unknownPaymentCount', '확정 대기 결제 (UNKNOWN)', '/console/admin/payments.html'],
  ['pendingOutboxCount', '처리 대기 Outbox', '/console/admin/events.html'],
  ['oldestOutboxAgeSeconds', '가장 오래된 Outbox (초)', '/console/admin/events.html'],
  ['openDiscrepancyCount', '미해결 대사 불일치', '/console/admin/reconciliation.html'],
  ['blockedSettlementCount', '막힌 정산 배치', '/console/admin/settlements.html'],
  ['unrecoveredAdjustmentCount', '미회수 조정', '/console/admin/settlements.html'],
];

let latestSummary = null;

/** 가장 최근에 받은 운영 요약 (아직 없으면 null). */
export function currentSummary() {
  return latestSummary;
}

function renderSummary(el, summary, error) {
  if (error && !summary) {
    el.innerHTML = html`<div class="notice notice--danger">운영 요약을 불러오지 못했습니다: ${error.message}</div>`;
    return;
  }
  el.innerHTML = SUMMARY_FIELDS.map(([key, label, href]) => {
    const value = Number(summary[key] ?? 0);
    const state = value === 0 ? 'summary-card--zero' : 'summary-card--nonzero';
    return html`<a class="summary-card ${state}" href="${href}">
      <div class="summary-card__label">${label}</div>
      <div class="summary-card__number">${value.toLocaleString('ko-KR')}</div>
    </a>`;
  }).join('');
  if (error) {
    el.insertAdjacentHTML('beforeend',
      html`<div class="summary-grid__stale">갱신 실패 — 마지막 값 표시 중 (${error.code ?? error.message})</div>`);
  }
}

/**
 * 운영자 화면 공통 시작. 역할이 맞지 않으면 리다이렉트되고 null을 돌려준다.
 * 페이지는 <div id="console-nav"></div>와 <section id="admin-summary"></section>을 가진다.
 * @returns {Promise<{id:number,email:string,displayName:string,role:string}|null>}
 */
export async function bootAdmin() {
  const user = await requireRole('ADMIN');
  if (!user) return null;
  mountNav(user);

  const summaryEl = document.getElementById('admin-summary');
  if (summaryEl) {
    summaryEl.classList.add('summary-grid');
    poll({
      fn: () => api('/api/admin/ops/summary'),
      until: () => false,
      intervalMs: 2000,
      maxIntervalMs: 2000,
      timeoutMs: Infinity,
      onTick: (result, error) => {
        if (!error) {
          latestSummary = result.data;
          window.dispatchEvent(new CustomEvent('admin:summary', { detail: latestSummary }));
        }
        renderSummary(summaryEl, latestSummary, error);
      },
    });
  }
  return user;
}
