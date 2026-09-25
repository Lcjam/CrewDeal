// nav.js — 공용 헤더·역할별 메뉴 (프론트엔드 계획 §5.2, §6, §7.1)
//
// 요청 ID 표시줄은 reqlog.js가 맡는다(session.js가 import해 항상 먼저 마운트된다) — 여기서는
// 다루지 않는다. 이중 리스너 등록 문제 자체가 없다 (Minor 9).

import { html, raw } from './html.js';
import { logout, homeFor } from './session.js';

// 역할별 메뉴. §5.2 디렉토리 구조를 그대로 따른다. ready:false인 항목은 아직 페이지가 없어
// 렌더링하지 않는다 — 이후 단계에서 페이지가 생기면 이 플래그만 뒤집는다.
const MENUS = {
  BUYER: [
    // 캠페인 상세(campaign.html?id=)·결제(payment.html?orderId=)는 ID가 필요해 메뉴에 두지 않는다 —
    // 목록 화면의 링크로만 들어간다.
    { label: '공구 목록', href: '/console/buyer/campaigns.html', ready: true },
    { label: '내 주문', href: '/console/buyer/orders.html', ready: true },
    { label: '장애 주입 데모', href: '/console/demo/index.html', ready: false },
  ],
  INFLUENCER: [
    { label: '내 캠페인', href: '/console/influencer/campaigns.html', ready: true },
    { label: '캠페인 상세', href: '/console/influencer/campaign.html', ready: false },
    { label: '정산', href: '/console/influencer/settlements.html', ready: false },
  ],
  SUPPLIER: [
    { label: '상품', href: '/console/supplier/products.html', ready: true },
    { label: '참여 캠페인', href: '/console/supplier/campaigns.html', ready: false },
    { label: '정산', href: '/console/supplier/settlements.html', ready: false },
  ],
  ADMIN: [
    { label: '운영 요약', href: '/console/admin/index.html', ready: true },
    { label: '캠페인 승인', href: '/console/admin/campaigns.html', ready: true },
    { label: '결제', href: '/console/admin/payments.html', ready: true },
    { label: '원장', href: '/console/admin/ledger.html', ready: true },
    { label: '정산', href: '/console/admin/settlements.html', ready: true },
    { label: '대사', href: '/console/admin/reconciliation.html', ready: true },
    { label: '이벤트', href: '/console/admin/events.html', ready: true },
    { label: '장애 주입 데모', href: '/console/demo/index.html', ready: false },
  ],
};

const ROLE_LABELS = {
  BUYER: '구매자',
  INFLUENCER: '인플루언서',
  SUPPLIER: '공급사',
  ADMIN: '운영자',
};

function menuHtml(role, currentPath) {
  const entries = (MENUS[role] ?? []).filter((entry) => entry.ready);
  return entries
    .map((entry) => {
      const active = currentPath.endsWith(entry.href) ? ' nav-link--active' : '';
      return html`<a class="nav-link${active ? raw(active) : ''}" href="${entry.href}">${entry.label}</a>`;
    })
    .join('');
}

/**
 * 공용 헤더(앱 이름·현재 역할·사용자·호스트명·역할별 메뉴·로그아웃)를 렌더링한다.
 * @param {{id:number,email:string,displayName:string,role:string}} user
 * @param {{ mountPoint?: HTMLElement }} [opts]
 */
export function mountNav(user, opts = {}) {
  const mountPoint = opts.mountPoint ?? document.getElementById('console-nav');
  if (!mountPoint) return;

  const roleLabel = ROLE_LABELS[user.role] ?? user.role;
  const currentPath = location.pathname;

  mountPoint.innerHTML = html`
    <header class="app-header">
      <div class="app-header__top">
        <div class="app-header__identity">
          <span class="app-header__app-name">GroupDrop 콘솔</span>
          <span class="app-header__role">${roleLabel}</span>
        </div>
        <div class="app-header__meta">
          <span class="app-header__host" title="세션 쿠키는 호스트 단위입니다 (§7.1)">${location.hostname}</span>
          <span class="app-header__user">${user.displayName} · ${user.email}</span>
          <button type="button" class="btn btn-secondary" id="nav-logout-btn">로그아웃</button>
        </div>
      </div>
      <nav class="app-header__menu">${raw(menuHtml(user.role, currentPath))}</nav>
    </header>
    <div class="notice notice--local-only">로컬 시연 전용 콘솔입니다. 공개 배포 대상이 아닙니다.</div>
  `;

  mountPoint.querySelector('#nav-logout-btn').addEventListener('click', async () => {
    try {
      await logout();
    } finally {
      location.replace('/console/index.html');
    }
  });

  return { home: homeFor(user.role) };
}
