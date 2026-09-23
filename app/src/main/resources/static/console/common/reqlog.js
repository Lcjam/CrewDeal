// reqlog.js — 하단 요청 ID 표시줄 (프론트엔드 계획 §5.3)
//
// api.js는 DOM에 손대지 않는 순수 HTTP 클라이언트로 남겨둔다. 이 모듈이 대신 window의
// 'console:api' CustomEvent(api.js가 쏜다)를 구독해 표시줄을 그린다.
// import되는 즉시(모듈 최상위 코드) 리스너를 붙이고 표시줄을 마운트한다 — 그래서 이 모듈을
// session.js가 import해 두면(모든 보호 페이지·로그인 화면이 session.js를 쓴다) 그 페이지의
// 첫 /api/auth/me·로그인 요청부터 놓치지 않고 잡힌다. api.js에서 직접 import하지 않는다
// (api.js를 DOM과 결합시키지 않기 위해 — nav.js를 역참조하던 예전 구조와 같은 이유).

import { html } from './html.js';

const MAX_REQUEST_LOG = 8;

const log = [];
let mounted = false;

function statusClass(status) {
  if (status === 0 || status >= 500) return 'request-log__status--error';
  if (status >= 400) return 'request-log__status--warn';
  return 'request-log__status--ok';
}

function render(listEl) {
  listEl.innerHTML = log
    .map((entry) => html`<li class="request-log__item">
      <span class="request-log__method">${entry.method}</span>
      <span class="request-log__path" title="${entry.path}">${entry.path}</span>
      <span class="request-log__status ${statusClass(entry.status)}">${entry.status}</span>
      <span class="request-log__id">${entry.requestId}</span>
    </li>`)
    .join('');
}

function mount() {
  if (mounted) return;
  mounted = true;

  let bar = document.getElementById('console-request-log');
  if (!bar) {
    bar = document.createElement('div');
    bar.id = 'console-request-log';
    bar.className = 'request-log';
    bar.innerHTML = '<span class="request-log__label">최근 요청</span><ul class="request-log__list"></ul>';
    document.body.appendChild(bar);
  }
  const listEl = bar.querySelector('.request-log__list');

  window.addEventListener('console:api', (event) => {
    const detail = event.detail ?? {};
    log.unshift({
      method: detail.method ?? '',
      path: detail.path ?? '',
      status: detail.status,
      requestId: detail.requestId ?? '',
    });
    if (log.length > MAX_REQUEST_LOG) log.length = MAX_REQUEST_LOG;
    render(listEl);
  });
}

mount();
