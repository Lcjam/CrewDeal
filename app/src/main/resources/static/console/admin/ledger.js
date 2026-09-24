// ledger.js — 운영자 원장 화면 (LED-01~03, S5, ADR-006, 프론트엔드 계획 §6 "운영자")
//
// 캠페인 선택 → 계정 잔액(차대 합계 일치 여부) / 주문 선택(결제 목록에서 고른다, ID 직접 입력
// 없음) → 거래 단위로 묶은 분개 / 원장 무결성 수동 확인. 세 구역 모두 읽기 전용이다
// (LedgerAdminService 자체가 조회만 한다).

import { api } from '../common/api.js';
import { messageFor } from '../common/codes.js';
import { html, raw, idParam } from '../common/html.js';
import { formatMoney, formatDateTime, statusOf } from '../common/format.js';

// V4__refunds_and_ledger.sql의 ledger_accounts 시드 행 그대로 (LedgerAccount enum과 값이 일치해야 한다).
// 표에 없는 코드는 원문을 그대로 보여준다 — throw하지 않는다.
const LEDGER_ACCOUNT_LABELS = {
  PG_RECEIVABLE: 'PG 미수금',
  SUPPLIER_PAYABLE: '공급사 지급 예정금',
  INFLUENCER_PAYABLE: '인플루언서 지급 예정금',
  PG_FEE_PAYABLE: 'PG 수수료 예정금',
  PLATFORM_REVENUE: '플랫폼 수익',
  PAYOUT_CASH: '지급 완료 (가상 현금)',
};

const ALL_PAYMENT_STATUSES = 'READY,PROCESSING,SUCCEEDED,FAILED,UNKNOWN,SUPERSEDED,REFUNDING,REFUNDED';

function accountLabel(code) {
  return LEDGER_ACCOUNT_LABELS[code] ?? code;
}

let campaigns = [];
let selectedCampaignId = idParam('campaignId');
let selectedOrderId = idParam('orderId');
let orderOptions = []; // [{orderId, status, amount}], 캠페인당 최신 결제 상태 1건

export async function initLedgerPage() {
  wireControls();
  await loadCampaigns();
  if (selectedCampaignId) {
    document.getElementById('campaign-select').value = String(selectedCampaignId);
    await onCampaignSelected(selectedCampaignId, { keepOrderId: true });
  } else if (selectedOrderId) {
    // 대사 불일치(reconciliation-discrepancies) 등 일부 발견 데이터는 orderId/paymentId만 갖고
    // campaignId가 없다 — 캠페인을 모르더라도 주문 원장만은 바로 보여준다. 캠페인 선택기는
    // 그대로 쓸 수 있다 (선택하면 onCampaignSelected가 이 상태를 정상적으로 이어받는다).
    await loadOrderLedger(selectedOrderId, { standalone: true });
  }
  document.getElementById('unbalanced-check-button').addEventListener('click', runUnbalancedCheck);
}

function showNotice(message, level = 'success') {
  const el = document.getElementById('notice');
  if (!el) return;
  el.className = `notice notice--${level}`;
  el.textContent = message;
}

function wireControls() {
  document.getElementById('campaign-select').addEventListener('change', (event) => {
    const value = event.target.value;
    selectedCampaignId = value ? Number(value) : null;
    selectedOrderId = null;
    onCampaignSelected(selectedCampaignId, { keepOrderId: false });
  });
  document.getElementById('order-select').addEventListener('change', (event) => {
    const value = event.target.value;
    selectedOrderId = value ? Number(value) : null;
    updateUrl();
    if (selectedOrderId) loadOrderLedger(selectedOrderId);
    else renderOrderLedgerEmpty();
  });
}

function updateUrl() {
  const params = new URLSearchParams();
  if (selectedCampaignId) params.set('campaignId', String(selectedCampaignId));
  if (selectedOrderId) params.set('orderId', String(selectedOrderId));
  const query = params.toString();
  history.replaceState(null, '', query ? `ledger.html?${query}` : 'ledger.html');
}

// --- 캠페인 선택 ------------------------------------------------------------

async function loadCampaigns() {
  try {
    const { data } = await api('/api/admin/campaigns'); // 전체 9개 상태
    campaigns = data;
  } catch (err) {
    campaigns = [];
    showNotice(`캠페인 목록을 불러오지 못했습니다: ${messageFor(err)} (요청 ID ${err.requestId ?? '-'})`, 'danger');
  }
  const select = document.getElementById('campaign-select');
  const options = ['<option value="">캠페인을 선택하세요</option>'];
  campaigns.forEach((c) => {
    const [label] = statusOf('campaign', c.status);
    options.push(html`<option value="${c.id}">${c.name} (${label})</option>`);
  });
  if (selectedCampaignId && !campaigns.some((c) => c.id === selectedCampaignId)) {
    options.push(html`<option value="${selectedCampaignId}">캠페인 #${selectedCampaignId}</option>`);
  }
  select.innerHTML = options.join('');
}

async function onCampaignSelected(campaignId, { keepOrderId }) {
  if (!keepOrderId) selectedOrderId = null;
  updateUrl();
  if (!campaignId) {
    renderCampaignLedgerEmpty();
    resetOrderSelect();
    renderOrderLedgerEmpty();
    return;
  }
  await Promise.all([loadCampaignLedger(campaignId), loadOrderOptions(campaignId)]);
  if (selectedOrderId && orderOptions.some((o) => o.orderId === selectedOrderId)) {
    document.getElementById('order-select').value = String(selectedOrderId);
    await loadOrderLedger(selectedOrderId);
  } else if (selectedOrderId) {
    // 링크로 넘어온 orderId가 이 캠페인의 결제 목록에 없다 (예: 결제 기록이 아직 없는 주문).
    // 그래도 원장 조회는 조건부이므로 시도는 해본다.
    await loadOrderLedger(selectedOrderId);
  } else {
    renderOrderLedgerEmpty();
  }
}

// --- 캠페인 원장 (계정 잔액) -------------------------------------------------

function renderCampaignLedgerEmpty() {
  document.getElementById('campaign-ledger-body').innerHTML = '<p class="muted">캠페인을 선택하면 계정 잔액을 표시합니다.</p>';
}

async function loadCampaignLedger(campaignId) {
  const body = document.getElementById('campaign-ledger-body');
  body.innerHTML = '<p class="muted">불러오는 중…</p>';
  try {
    const { data } = await api(`/api/admin/campaigns/${campaignId}/ledger`);
    renderCampaignLedger(data);
  } catch (err) {
    body.innerHTML = html`<div class="notice notice--danger">캠페인 원장을 불러오지 못했습니다: ${messageFor(err)} (요청 ID ${err.requestId ?? '-'})</div>`;
  }
}

function renderCampaignLedger(data) {
  const body = document.getElementById('campaign-ledger-body');
  const balances = data.balances ?? {};
  const codes = Object.keys(balances).sort();
  const rows = codes.length
    ? codes.map((code) => html`<tr><td>${accountLabel(code)} <span class="mono muted">(${code})</span></td><td>${formatMoney(balances[code])}</td></tr>`).join('')
    : '<tr><td colspan="2">계정 잔액이 없습니다 (이 캠페인에 아직 원장 거래가 없습니다).</td></tr>';

  const balanced = data.debitTotal === data.creditTotal;
  const diff = data.debitTotal - data.creditTotal;

  body.innerHTML = html`
    <table class="balances-table">
      <thead><tr><th>계정</th><th>잔액</th></tr></thead>
      <tbody>${raw(rows)}</tbody>
    </table>
    <p style="margin-top: var(--spacing-3);">
      차변 합계 ${formatMoney(data.debitTotal)} · 대변 합계 ${formatMoney(data.creditTotal)}
      &nbsp;
      <span class="balance-check ${balanced ? 'balance-check--ok' : 'balance-check--bad'}">
        ${balanced ? '대차 일치' : `불일치 (차이 ${formatMoney(Math.abs(diff))})`}
      </span>
    </p>
  `;
}

// --- 주문 선택 (결제 목록에서 고른다) -----------------------------------------

function resetOrderSelect() {
  const select = document.getElementById('order-select');
  select.innerHTML = '<option value="">캠페인을 먼저 선택하세요</option>';
  select.disabled = true;
}

async function loadOrderOptions(campaignId) {
  const select = document.getElementById('order-select');
  select.innerHTML = '<option value="">불러오는 중…</option>';
  select.disabled = true;
  let paymentRowCount = 0;
  try {
    const { data } = await api(`/api/admin/payments?campaignId=${campaignId}&status=${ALL_PAYMENT_STATUSES}`);
    paymentRowCount = data.length;
    // 응답은 결제 id 내림차순이므로 orderId를 처음 만난 것이 그 주문의 가장 최근 결제 시도다.
    const seen = new Map();
    data.forEach((p) => {
      if (!seen.has(p.orderId)) seen.set(p.orderId, { orderId: p.orderId, status: p.status, amount: p.amount });
    });
    orderOptions = [...seen.values()].sort((a, b) => b.orderId - a.orderId);
  } catch (err) {
    orderOptions = [];
    showNotice(`주문 목록을 불러오지 못했습니다: ${messageFor(err)} (요청 ID ${err.requestId ?? '-'})`, 'danger');
  }
  renderOrderSelect();
  // GET /api/admin/payments는 LIMIT 100 고정이다 — 결제가 100건 걸리면 그 뒤(더 오래된) 주문은
  // 이 드롭다운에 아예 나타나지 않을 수 있다.
  const capNote = document.getElementById('order-select-cap-note');
  if (capNote) capNote.hidden = paymentRowCount !== 100;
}

function renderOrderSelect() {
  const select = document.getElementById('order-select');
  if (!orderOptions.length) {
    select.innerHTML = '<option value="">이 캠페인에는 결제 기록이 있는 주문이 없습니다</option>';
    select.disabled = true;
    return;
  }
  select.disabled = false;
  const options = ['<option value="">주문을 선택하세요</option>'];
  orderOptions.forEach((o) => {
    const [label] = statusOf('payment', o.status);
    options.push(html`<option value="${o.orderId}">주문 ${o.orderId} — ${label} · ${formatMoney(o.amount)}</option>`);
  });
  if (selectedOrderId && !orderOptions.some((o) => o.orderId === selectedOrderId)) {
    options.push(html`<option value="${selectedOrderId}">주문 #${selectedOrderId} (이 캠페인 결제 목록 밖)</option>`);
  }
  select.innerHTML = options.join('');
}

// --- 주문별 분개 (거래 단위로 묶기) ------------------------------------------

function renderOrderLedgerEmpty() {
  document.getElementById('order-ledger-body').innerHTML = '<p class="muted">주문을 선택하면 거래별 분개를 표시합니다.</p>';
}

/**
 * @param {number} orderId
 * @param {{standalone?: boolean}} [opts] standalone: true면 campaignId 없이 orderId만으로
 *   들어온 경우다 (예: 대사 불일치 화면의 링크). 주문 원장 위에 안내를 얹는다.
 */
async function loadOrderLedger(orderId, opts = {}) {
  const body = document.getElementById('order-ledger-body');
  body.innerHTML = '<p class="muted">불러오는 중…</p>';
  try {
    const { data } = await api(`/api/admin/orders/${orderId}/ledger`);
    renderOrderLedger(data, { standalone: !!opts.standalone, orderId });
  } catch (err) {
    body.innerHTML = html`<div class="notice notice--danger">주문 원장을 불러오지 못했습니다: ${messageFor(err)} (요청 ID ${err.requestId ?? '-'})</div>`;
  }
}

function renderOrderLedger(postings, { standalone = false, orderId } = {}) {
  const body = document.getElementById('order-ledger-body');
  const standaloneNote = standalone
    ? html`<div class="notice notice--info">캠페인 미지정 — 주문 #${orderId} 분개만 표시 (위 캠페인 선택기에서 캠페인을 고르면 계정 잔액도 볼 수 있습니다)</div>`
    : '';
  if (!postings.length) {
    body.innerHTML = `${standaloneNote}<p class="muted">이 주문에는 아직 원장 거래가 없습니다.</p>`;
    return;
  }
  // 서버가 t.id DESC, e.id 순으로 정렬해 준다(최신 거래 먼저, LIMIT 100) — 그래서 같은
  // transactionId는 항상 연속으로 묶이고, 이 페이지는 그 순서를 그대로 따른다 (최신순).
  // 계획 문서 초안은 오름차순으로 적었지만 실제 코드(LedgerRepository.java:149)가 맞다.
  const groups = [];
  let current = null;
  postings.forEach((posting) => {
    if (!current || current.transactionId !== posting.transactionId) {
      current = { transactionId: posting.transactionId, transactionType: posting.transactionType,
        referenceType: posting.referenceType, referenceId: posting.referenceId,
        occurredAt: posting.occurredAt, entries: [] };
      groups.push(current);
    }
    current.entries.push(posting);
  });

  body.innerHTML = standaloneNote + groups.map(renderTransactionCard).join('');
}

function renderTransactionCard(group) {
  const debits = group.entries.filter((e) => e.side === 'DEBIT');
  const credits = group.entries.filter((e) => e.side === 'CREDIT');
  const debitSum = debits.reduce((sum, e) => sum + e.amount, 0);
  const creditSum = credits.reduce((sum, e) => sum + e.amount, 0);
  const balanced = debitSum === creditSum;

  return html`<div class="txn-card txn-card--${group.transactionType}">
    <div class="txn-card__head">
      <span><status-badge domain="ledgerTransaction" value="${group.transactionType}"></status-badge></span>
      <span>거래 #${group.transactionId}</span>
      <span>근거: ${group.referenceType} #${group.referenceId}</span>
      <span>발생 <strong>${formatDateTime(group.occurredAt)}</strong></span>
    </div>
    <div class="txn-columns">
      <div>
        <h4>차변 (DEBIT)</h4>
        ${raw(renderEntryTable(debits))}
        <p class="txn-sum">합계 ${formatMoney(debitSum)}</p>
      </div>
      <div>
        <h4>대변 (CREDIT)</h4>
        ${raw(renderEntryTable(credits))}
        <p class="txn-sum">합계 ${formatMoney(creditSum)}</p>
      </div>
    </div>
    <p class="txn-sum">
      <span class="balance-check ${balanced ? 'balance-check--ok' : 'balance-check--bad'}">
        ${balanced ? '이 거래는 차대 일치' : '이 거래는 차대 불일치 — 원장 무결성 확인 필요'}
      </span>
    </p>
  </div>`;
}

function renderEntryTable(entries) {
  if (!entries.length) return '<p class="muted">없음</p>';
  const rows = entries.map((e) => html`<tr><td>${accountLabel(e.accountCode)}</td><td>${formatMoney(e.amount)}</td></tr>`).join('');
  return html`<table><tbody>${raw(rows)}</tbody></table>`;
}

// --- 원장 무결성 (수동) -------------------------------------------------------

async function runUnbalancedCheck() {
  const button = document.getElementById('unbalanced-check-button');
  const body = document.getElementById('unbalanced-body');
  button.disabled = true;
  body.innerHTML = '<p class="muted">확인 중…</p>';
  try {
    const { data, meta } = await api('/api/admin/ledger/unbalanced');
    if (!data.length) {
      body.innerHTML = '<div class="notice notice--success">대차 일치 — 불균형 거래 0건</div>';
    } else {
      const rows = data.map((item) => html`<tr>
          <td>${item.transactionId}</td>
          <td>${formatMoney(item.debitTotal)}</td>
          <td>${formatMoney(item.creditTotal)}</td>
        </tr>`).join('');
      // LedgerAdminService가 애플리케이션에서 100건으로 자른다 — 정확히 100건이면 실제로는
      // 더 있을 수 있다는 뜻이므로 숨기지 않는다.
      const capNote = data.length === 100
        ? html`<div class="notice notice--warning">최대 100건까지만 표시됩니다 — 실제 불균형 거래가 더 있을 수 있습니다.</div>`
        : '';
      body.innerHTML = html`
        <div class="notice notice--danger">불균형 거래 ${data.length}건 발견 (S5 불변식 위반 — 즉시 조사 필요)</div>
        ${raw(capNote)}
        <table class="unbalanced-table">
          <thead><tr><th>거래 ID</th><th>차변 합계</th><th>대변 합계</th></tr></thead>
          <tbody>${raw(rows)}</tbody>
        </table>`;
    }
    showNotice(`원장 무결성 확인 완료 (요청 ID ${meta.requestId})`, data.length ? 'danger' : 'success');
  } catch (err) {
    body.innerHTML = html`<div class="notice notice--danger">불균형 거래 조회 실패: ${messageFor(err)} (요청 ID ${err.requestId ?? '-'})</div>`;
  }
  button.disabled = false;
}
