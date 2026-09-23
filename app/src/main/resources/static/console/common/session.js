// session.js — 인증 상태, 역할 가드, 로그인/로그아웃 (프론트엔드 계획 §5.2)

import { api, ApiError, clearIdempotencyKeys } from './api.js';
import { html } from './html.js';
import { messageFor } from './codes.js';
// 부수효과 import — 모듈 로드 시점에 요청 ID 표시줄을 마운트하고 'console:api' 리스너를 붙인다.
// session.js는 모든 보호 페이지와 로그인 화면이 공통으로 불러오므로, 여기서 한 번만 import해 두면
// 첫 /api/auth/me·로그인 요청부터 표시줄에 잡힌다 (Minor 8).
import './reqlog.js';

const HOME_BY_ROLE = {
  ADMIN: '/console/admin/index.html',
  BUYER: '/console/buyer/campaigns.html',
  INFLUENCER: '/console/influencer/campaigns.html',
  SUPPLIER: '/console/supplier/products.html',
};

export function homeFor(role) {
  return HOME_BY_ROLE[role] ?? '/console/index.html';
}

/** 로그인. 성공 시 이전 세션이 남긴 멱등 키를 지운다 (§5.2). */
export async function login(email, password) {
  const { data: user } = await api('/api/auth/login', {
    method: 'POST',
    body: { email, password },
  });
  clearIdempotencyKeys();
  return user;
}

/** 로그아웃. 실패해도 멱등 키는 지운다 — 다음 로그인이 이전 세션의 키를 물려받지 않게 한다. */
export async function logout() {
  try {
    await api('/api/auth/logout', { method: 'POST' });
  } finally {
    clearIdempotencyKeys();
  }
}

/** 로그인 화면에서 씀 — 401이어도 던지지 않고 null을 돌려준다. */
export async function currentUserOrNull() {
  try {
    const { data } = await api('/api/auth/me');
    return data;
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) return null;
    throw err;
  }
}

/**
 * /api/auth/me가 401이 아닌 이유로 실패했을 때(5xx, 네트워크 오류 등) 화면에 직접 오류를 보여준다.
 * 이 경우는 "미인증"이 아니라 "서버·네트워크 장애"이므로 로그인 화면으로 리다이렉트하지 않는다 —
 * 리다이렉트하면 사용자가 장애를 인지하지 못한 채 로그인 화면만 반복해서 보게 된다 (M3).
 */
function renderAuthErrorPanel(err) {
  const existing = document.getElementById('console-auth-error');
  if (existing) existing.remove();

  const requestId = err && err.requestId ? err.requestId : '-';
  const panel = document.createElement('div');
  panel.id = 'console-auth-error';
  panel.innerHTML = html`
    <div class="notice notice--danger">
      <p>${messageFor(err)}</p>
      <p class="mono">요청 ID: ${requestId}</p>
      <button type="button" class="btn btn-secondary" id="console-auth-error-retry">다시 시도</button>
    </div>
  `;
  document.body.prepend(panel);
  panel.querySelector('#console-auth-error-retry').addEventListener('click', () => {
    location.reload();
  });
}

/**
 * 보호 페이지 진입점. GET /api/auth/me로 확인하고,
 * - 401이면 /console/index.html로 직접 리다이렉트한다 (location.replace, 뒤로가기로 못 돌아옴).
 * - 401이 아닌 오류(5xx·네트워크 오류)면 리다이렉트하지 않고 화면에 오류·요청 ID·"다시 시도"
 *   버튼을 보여준다 (M3).
 * - roles가 주어졌고 현재 역할이 포함되지 않으면 그 사용자의 홈으로 리다이렉트한다.
 * 세 경우 모두 null을 반환한다 — 호출부는 반환값이 null이면 렌더링을 멈춰야 한다
 * (`if (!user) return;` 패턴 — 리다이렉트 완료 전이나 오류 패널을 띄운 뒤 페이지 스크립트가
 * 계속 실행되는 것을 막기 위해).
 */
export async function requireRole(...roles) {
  let user;
  try {
    const { data } = await api('/api/auth/me');
    user = data;
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      location.replace('/console/index.html');
      return null;
    }
    renderAuthErrorPanel(err);
    return null;
  }
  if (roles.length > 0 && !roles.includes(user.role)) {
    location.replace(homeFor(user.role));
    return null;
  }
  return user;
}
