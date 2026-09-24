// events.js — Outbox·Inbox 적체·실패 화면 (프론트엔드 계획 §6 "운영자", 13.4, API 계약 §7)
//
// 서버 워커 주기가 outbox·inbox 모두 5초이므로(§3 폴링 표) 화면도 5초마다 두 목록을 함께
// 다시 읽는다. 자유 입력 필드가 없는 화면이라(체크박스·버튼뿐) 캠페인 화면과 달리 "입력 중
// 포커스" 보호는 필요 없다 — 매 tick 그냥 다시 그린다.

import { api } from '/console/common/api.js';
import { bootAdmin } from '/console/admin/admin.js';
import { poll, renderContinueWatching } from '/console/common/poll.js';
import { html, raw } from '/console/common/html.js';
import { formatDateTime, STATUS_TABLES } from '/console/common/format.js';
import { messageFor } from '/console/common/codes.js';
import '/console/common/widgets.js';

const OUTBOX_STATUSES = ['PENDING', 'PROCESSED', 'FAILED'];
const INBOX_STATUSES = ['PENDING', 'PROCESSED', 'IGNORED', 'FAILED'];
const OUTBOX_DEFAULT = ['PENDING', 'FAILED'];
const INBOX_DEFAULT = ['PENDING', 'FAILED', 'IGNORED'];

const POLL_INTERVAL_MS = 5000;
const POLL_TIMEOUT_MS = 30 * 60 * 1000;

const state = {
  outboxStatuses: new Set(OUTBOX_DEFAULT),
  inboxStatuses: new Set(INBOX_DEFAULT),
  outbox: [],
  inbox: [],
  outboxError: null,
  inboxError: null,
};

let outboxFilterEl, outboxTbodyEl, outboxTableEl, outboxCountsEl, outboxLimitEl;
let inboxFilterEl, inboxTbodyEl, inboxTableEl, inboxCountsEl, inboxLimitEl;
let noticeEl, pollStatusEl;
let pollHandle;

function showNotice(kind, message, requestId) {
  noticeEl.hidden = false;
  noticeEl.className = `notice notice--${kind}`;
  noticeEl.textContent = requestId ? `${message} (요청 ID: ${requestId})` : message;
}

async function fetchOutbox() {
  if (state.outboxStatuses.size === 0) return { data: [] };
  return api(`/api/admin/outbox-events?status=${encodeURIComponent([...state.outboxStatuses].join(','))}`);
}

async function fetchInbox() {
  if (state.inboxStatuses.size === 0) return { data: [] };
  return api(`/api/admin/inbox-events?status=${encodeURIComponent([...state.inboxStatuses].join(','))}`);
}

function lastErrorHtml(text) {
  if (!text) return '-';
  const truncated = text.length > 80 ? `${text.slice(0, 80)}…` : text;
  return html`<span class="last-error" title="${text}">${truncated}</span>`;
}

function countsLine(rows, allowedStatuses, domain) {
  const counts = new Map();
  rows.forEach((r) => counts.set(r.status, (counts.get(r.status) ?? 0) + 1));
  return allowedStatuses
    .filter((s) => counts.has(s))
    .map((s) => {
      const [label] = STATUS_TABLES[domain][s] ?? [s];
      return `${label} ${counts.get(s)}건`;
    })
    .join(' · ') || '표시된 항목 없음';
}

function renderFilterChips(container, allowedStatuses, selectedSet, domain) {
  container.innerHTML = allowedStatuses.map((s) => {
    const [label] = STATUS_TABLES[domain][s] ?? [s];
    const checkedAttr = selectedSet.has(s) ? ' checked' : '';
    return `<label class="chip"><input type="checkbox" data-status="${s}"${checkedAttr}> ${label}</label>`;
  }).join('');
}

function outboxRowHtml(e) {
  const canRetry = e.status === 'FAILED';
  return html`<tr>
    <td class="mono">${e.id}</td>
    <td>${e.eventType}</td>
    <td>${e.aggregateType}</td>
    <td class="mono">${e.aggregateId}</td>
    <td><status-badge domain="outbox" value="${e.status}"></status-badge></td>
    <td>${e.attempts}</td>
    <td>${raw(lastErrorHtml(e.lastError))}</td>
    <td>${formatDateTime(e.createdAt)}</td>
    <td>${formatDateTime(e.processedAt)}</td>
    <td>${canRetry ? raw(html`<button type="button" class="btn btn-secondary" data-channel="outbox" data-id="${e.id}">재시도</button>`) : ''}</td>
  </tr>`;
}

function inboxRowHtml(e) {
  const canRetry = e.status === 'FAILED';
  return html`<tr>
    <td class="mono">${e.id}</td>
    <td class="mono">${e.providerEventId}</td>
    <td>${e.eventType}</td>
    <td><status-badge domain="inbox" value="${e.status}"></status-badge></td>
    <td>${e.attempts}</td>
    <td>${raw(lastErrorHtml(e.lastError))}</td>
    <td>${formatDateTime(e.receivedAt)}</td>
    <td>${formatDateTime(e.processedAt)}</td>
    <td>${canRetry ? raw(html`<button type="button" class="btn btn-secondary" data-channel="inbox" data-id="${e.id}">재시도</button>`) : ''}</td>
  </tr>`;
}

function renderOutbox() {
  if (state.outboxError) {
    outboxTbodyEl.innerHTML = html`<tr><td colspan="10">목록을 불러오지 못했습니다: ${messageFor(state.outboxError)} (요청 ID: ${state.outboxError.requestId ?? '-'})</td></tr>`;
    outboxCountsEl.textContent = '';
    outboxLimitEl.hidden = true;
    return;
  }
  if (state.outboxStatuses.size === 0) {
    outboxTbodyEl.innerHTML = `<tr><td colspan="10">표시할 상태를 하나 이상 선택하세요.</td></tr>`;
    outboxCountsEl.textContent = '';
    outboxLimitEl.hidden = true;
    return;
  }
  if (state.outbox.length === 0) {
    outboxTbodyEl.innerHTML = `<tr><td colspan="10">선택한 상태의 Outbox 이벤트가 없습니다.</td></tr>`;
    outboxCountsEl.textContent = '';
    outboxLimitEl.hidden = true;
    return;
  }
  outboxTbodyEl.innerHTML = state.outbox.map(outboxRowHtml).join('');
  outboxCountsEl.textContent = `현재 필터 기준: ${countsLine(state.outbox, OUTBOX_STATUSES, 'outbox')}`;
  outboxLimitEl.hidden = state.outbox.length < 100;
}

function renderInbox() {
  if (state.inboxError) {
    inboxTbodyEl.innerHTML = html`<tr><td colspan="9">목록을 불러오지 못했습니다: ${messageFor(state.inboxError)} (요청 ID: ${state.inboxError.requestId ?? '-'})</td></tr>`;
    inboxCountsEl.textContent = '';
    inboxLimitEl.hidden = true;
    return;
  }
  if (state.inboxStatuses.size === 0) {
    inboxTbodyEl.innerHTML = `<tr><td colspan="9">표시할 상태를 하나 이상 선택하세요.</td></tr>`;
    inboxCountsEl.textContent = '';
    inboxLimitEl.hidden = true;
    return;
  }
  if (state.inbox.length === 0) {
    inboxTbodyEl.innerHTML = `<tr><td colspan="9">선택한 상태의 Inbox 이벤트가 없습니다.</td></tr>`;
    inboxCountsEl.textContent = '';
    inboxLimitEl.hidden = true;
    return;
  }
  inboxTbodyEl.innerHTML = state.inbox.map(inboxRowHtml).join('');
  inboxCountsEl.textContent = `현재 필터 기준: ${countsLine(state.inbox, INBOX_STATUSES, 'inbox')}`;
  inboxLimitEl.hidden = state.inbox.length < 100;
}

function render() {
  renderOutbox();
  renderInbox();
}

async function handleRetry(channel, id) {
  const path = channel === 'outbox' ? `/api/admin/outbox-events/${id}/retry` : `/api/admin/inbox-events/${id}/retry`;
  const label = channel === 'outbox' ? 'Outbox' : 'Inbox';
  if (!confirm(`${label} 이벤트 ${id}를 재시도합니다 (FAILED → PENDING). 계속할까요?`)) return;
  try {
    const { data, meta } = await api(path, { method: 'POST' });
    showNotice('success', `${label} 이벤트 ${id}을(를) 재시도했습니다. (상태: ${data.status})`, meta.requestId);
    pollHandle.restart();
  } catch (err) {
    showNotice('danger', messageFor(err), err.requestId);
  }
}

function onOutboxFilterChange(event) {
  const cb = event.target.closest('input[type=checkbox][data-status]');
  if (!cb) return;
  if (cb.checked) state.outboxStatuses.add(cb.dataset.status);
  else state.outboxStatuses.delete(cb.dataset.status);
  render();
  pollHandle.restart();
}

function onInboxFilterChange(event) {
  const cb = event.target.closest('input[type=checkbox][data-status]');
  if (!cb) return;
  if (cb.checked) state.inboxStatuses.add(cb.dataset.status);
  else state.inboxStatuses.delete(cb.dataset.status);
  render();
  pollHandle.restart();
}

function onTableClick(event) {
  const btn = event.target.closest('button[data-channel]');
  if (!btn) return;
  handleRetry(btn.dataset.channel, Number(btn.dataset.id));
}

async function init() {
  const user = await bootAdmin();
  if (!user) return;

  outboxFilterEl = document.getElementById('outbox-filter');
  outboxTbodyEl = document.getElementById('outbox-tbody');
  outboxTableEl = document.getElementById('outbox-table');
  outboxCountsEl = document.getElementById('outbox-counts');
  outboxLimitEl = document.getElementById('outbox-limit-notice');

  inboxFilterEl = document.getElementById('inbox-filter');
  inboxTbodyEl = document.getElementById('inbox-tbody');
  inboxTableEl = document.getElementById('inbox-table');
  inboxCountsEl = document.getElementById('inbox-counts');
  inboxLimitEl = document.getElementById('inbox-limit-notice');

  noticeEl = document.getElementById('events-notice');
  pollStatusEl = document.getElementById('events-poll-status');

  outboxFilterEl.addEventListener('change', onOutboxFilterChange);
  inboxFilterEl.addEventListener('change', onInboxFilterChange);
  outboxTableEl.addEventListener('click', onTableClick);
  inboxTableEl.addEventListener('click', onTableClick);

  // 필터 체크박스는 한 번만 그린다 — 매 tick(5초) 다시 그리면 키보드 포커스가 날아간다.
  renderFilterChips(outboxFilterEl, OUTBOX_STATUSES, state.outboxStatuses, 'outbox');
  renderFilterChips(inboxFilterEl, INBOX_STATUSES, state.inboxStatuses, 'inbox');
  render();

  pollHandle = poll({
    fn: async () => {
      const [outboxResult, inboxResult] = await Promise.allSettled([fetchOutbox(), fetchInbox()]);
      return { outboxResult, inboxResult };
    },
    until: () => false,
    intervalMs: POLL_INTERVAL_MS,
    maxIntervalMs: POLL_INTERVAL_MS,
    timeoutMs: POLL_TIMEOUT_MS,
    onTick: (result, error) => {
      if (error) {
        // fn 자체가 던진 경우(이론상 Promise.allSettled 사용으로 거의 없음) — 두 목록 모두 오류로 표시.
        state.outboxError = error;
        state.inboxError = error;
      } else {
        const { outboxResult, inboxResult } = result;
        if (outboxResult.status === 'fulfilled') {
          state.outboxError = null;
          state.outbox = outboxResult.value.data ?? [];
        } else {
          state.outboxError = outboxResult.reason;
        }
        if (inboxResult.status === 'fulfilled') {
          state.inboxError = null;
          state.inbox = inboxResult.value.data ?? [];
        } else {
          state.inboxError = inboxResult.reason;
        }
      }
      render();
    },
    onTimeout: () => renderContinueWatching(pollStatusEl, pollHandle, '계속 지켜보기'),
  });
}

init();
