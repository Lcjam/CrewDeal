// campaigns.js — 인플루언서 내 캠페인 목록 + 개설 (프론트엔드 계획 §6 "인플루언서", CAM-01·CAM-03)
//
// 목록은 GET /api/influencers/me/campaigns를 2초 폴링한다(타임아웃 없음) — 1초 캠페인 생명주기
// 스케줄러가 SCHEDULED→OPEN 등을 조작 없이 진행시키므로 가만히 둬도 배지가 바뀐다. 반려는
// REJECTED 상태가 따로 있는 게 아니라 DRAFT + rejectionReason이다(CampaignService:170-171).
//
// 개설 폼은 목록 폴링과 완전히 분리된 DOM 영역이다 — 폴링 tick은 tbody만 다시 그리고 폼에는
// 손대지 않는다. 그래야 입력 중인 폼이 2초마다 지워지지 않는다.
//
// 마진 게이트(CAM-01)·정산 미리보기는 서버 계산식(CampaignService#validateMarginGate,
// LedgerService#breakdownOf)을 그대로 옮긴 것이다. 입력은 문자열 단계에서 정수 형식을 검증한 뒤
// BigInt로만 계산한다 — Number()를 거치면 큰 입력에서 정밀도를 잃고, 빈 문자열이 0으로 조용히
// 바뀌는 사고(리뷰 MAJOR#1)도 막을 수 없다. 클라이언트 판정은 참고용 미리보기일 뿐이고, 실제
// 게이트는 서버가 통과·차단한다 — 게이트 실패를 이 화면에서 제출 자체를 막는 데 쓰지 않는다
// (정책 결정, 아래 onCreateSubmit 참고).
//
// Create는 멱등하지 않다(POST /api/campaigns에 Idempotency-Key가 없다 — §5.4는 주문·결제·환불
// 셋만 다룬다). 응답을 못 받은 제출(NETWORK_ERROR·5xx)은 "커밋됐는지 알 수 없는" 상태로 남는데,
// 이걸 세션스토리지에 pendingSlug로 기록해 새로고침 후에도 같은 슬러그로 확인을 이어가고, 확인이
// 끝나기 전까지는 슬러그 입력을 잠가 사용자가 다른 슬러그로 새 캠페인을 만들어 상황을 더 꼬는 걸
// 막는다 (리뷰 MAJOR#3·#4).

import { api } from '../common/api.js';
import { html, raw } from '../common/html.js';
import { formatMoney, formatDateTime } from '../common/format.js';
import { messageFor } from '../common/codes.js';
import { poll } from '../common/poll.js';

const POLL_INTERVAL_MS = 2000;
const HIGHLIGHT_DURATION_MS = 8000;

// CAM-01 마진 게이트 상수 — CampaignService.java와 반드시 같은 값이어야 한다.
const BP_SCALE = 10000n;
const PG_FEE_BP = 300n;
// CampaignService#validateMarginGate는 long(Math.multiplyExact)으로 계산해 이 범위를 넘으면
// CAMPAIGN_AMOUNT_TOO_LARGE로 거부한다. 미리보기도 같은 한계를 보여준다(리뷰 MINOR#6).
const LONG_MAX = 2n ** 63n - 1n;
const LONG_MIN = -(2n ** 63n);

const INT32_MAX = 2147483647; // perUserPurchaseLimit·allocatedQuantity는 DB int 컬럼이다.
const SLUG_PATTERN = /^[a-z0-9-]+$/;

const state = {
  campaigns: null,
  loadError: null,
  submitting: new Set(), // 심사 제출 중인 campaignId
  highlightId: null,     // 방금 개설/복구된 campaignId — 목록에서 강조
  products: [],
  creating: false,
  userId: null,
};

let els = {};
let pollHandle;
let unhighlightTimer = null;

// --- 유틸 ------------------------------------------------------------------------------------

function commissionText(bp) {
  if (bp === null || bp === undefined) return '-';
  return `${(bp / 100).toFixed(2)}%`;
}

function generateSlug() {
  return `camp-${Date.now().toString(36)}`;
}

/** Date → <input type="datetime-local"> 값 (브라우저 로컬 시간대, "YYYY-MM-DDTHH:mm"). */
function toDatetimeLocalValue(date) {
  const pad = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function inLongRange(x) {
  return x >= LONG_MIN && x <= LONG_MAX;
}

/**
 * "빈 문자열/소수/음수는 절대 0이나 절사값으로 조용히 바뀌지 않는다"는 규칙을 지키는 정수 파서
 * (리뷰 MAJOR#1). 형식이 어긋나면 ok:false와 사람이 읽을 메시지를 돌려줄 뿐, 어떤 값으로도
 * 보정하지 않는다. 반환하는 value는 일반 number(요청 바디용)다 — 미리보기 계산은 BigInt로 별도 처리.
 */
function readRequiredInt(input, label, { min = null, max = null } = {}) {
  const raw = input.value.trim();
  if (raw === '') return { ok: false, message: `${label}을(를) 입력해 주세요.`, input };
  if (!/^\d+$/.test(raw)) return { ok: false, message: `${label}은(는) 0 이상의 정수여야 합니다.`, input };
  const value = Number(raw);
  if (!Number.isSafeInteger(value)) return { ok: false, message: `${label} 값이 너무 큽니다.`, input };
  if (min !== null && value < min) return { ok: false, message: `${label}은(는) ${min} 이상이어야 합니다.`, input };
  if (max !== null && value > max) return { ok: false, message: `${label}은(는) ${max} 이하여야 합니다.`, input };
  return { ok: true, value, input };
}

/** 0 이상의 순수 정수 문자열만 BigInt로 파싱한다. 소수·음수·빈 문자열·공백 포함은 전부 null. */
function parseNonNegativeBigIntStrict(raw) {
  if (typeof raw !== 'string') return null;
  const trimmed = raw.trim();
  if (!/^\d+$/.test(trimmed)) return null;
  return BigInt(trimmed);
}

// --- pendingSlug (세션스토리지) — 응답을 못 받은 제출의 슬러그를 기억한다 (리뷰 MAJOR#3) ----------

function pendingSlugKey(userId) {
  return `campaign-create-pending:${userId}`;
}

function savePendingSlug(userId, slug, body) {
  try {
    sessionStorage.setItem(pendingSlugKey(userId), JSON.stringify({ slug, body }));
  } catch {
    // 저장 실패(사생활 보호 모드 등)해도 흐름은 계속된다 — 같은 세션 안의 409 복구 경로는
    // sessionStorage 없이도 동작하고, 새로고침 후 프리필만 못 받을 뿐이다.
  }
}

function loadPendingSlug(userId) {
  try {
    const raw = sessionStorage.getItem(pendingSlugKey(userId));
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function clearPendingSlug(userId) {
  try {
    sessionStorage.removeItem(pendingSlugKey(userId));
  } catch {
    // 위와 같은 이유로 무시한다.
  }
}

function lockSlugField() {
  els.slug.readOnly = true;
}

function unlockSlugField() {
  els.slug.readOnly = false;
}

// --- 마진 게이트·정산 미리보기 (BigInt, CampaignService·LedgerService와 동일한 정수 연산) --------

/** dealPrice × (10000 − commissionRateBp − 300) > supplyUnitPrice × 10000 (연속, 절사 전). 모두 BigInt. */
function computeMarginProducts(dealPrice, commissionRateBp, supplyUnitPrice) {
  const marginFactor = BP_SCALE - commissionRateBp - PG_FEE_BP;
  const lhs = dealPrice * marginFactor;
  const rhs = supplyUnitPrice * BP_SCALE;
  return { lhs, rhs, overflow: !inLongRange(lhs) || !inLongRange(rhs) };
}

/** 수량 1개 주문 기준 정산 분해 (ADR-008 절사 규칙 — floor, 피연산자가 모두 0 이상이라 BigInt 나눗셈과 같다). */
function settlementBreakdown(dealPrice, commissionRateBp, supplyUnitPrice) {
  const commission = (dealPrice * commissionRateBp) / BP_SCALE;
  const pgFee = (dealPrice * PG_FEE_BP) / BP_SCALE;
  const platform = dealPrice - supplyUnitPrice - commission - pgFee;
  return { paid: dealPrice, commission, pgFee, supplier: supplyUnitPrice, platform };
}

function renderPreviewFor(dealPrice, commissionRateBp, supplyUnitPrice, pass) {
  const b = settlementBreakdown(dealPrice, commissionRateBp, supplyUnitPrice);
  const gateBadge = pass
    ? '<span class="status-badge status-badge--green">마진 게이트 통과</span>'
    : '<span class="status-badge status-badge--red">마진 게이트 실패</span>';
  return raw(`
    <div>${gateBadge}</div>
    <div class="sku-preview__breakdown">
      수량 1개 주문 기준 — 공급사 ${formatMoney(Number(b.supplier))} · 커미션 ${formatMoney(Number(b.commission))} ·
      PG수수료 ${formatMoney(Number(b.pgFee))} · 플랫폼 ${formatMoney(Number(b.platform))}
    </div>
  `);
}

// --- 목록 (2초 폴링, 타임아웃 없음) ------------------------------------------------------------

function rowHtml(c) {
  const highlight = state.highlightId === c.id ? ' row--highlight' : '';
  const skuText = (c.skus ?? []).map((s) => `${s.optionName} ${s.availableQuantity}개`).join(' · ') || '-';
  const nameBlock = c.rejectionReason
    ? raw(html`<div><a href="campaign.html?id=${c.id}">${c.name}</a></div><div class="rejection-reason">반려 사유: ${c.rejectionReason}</div>`)
    : raw(html`<a href="campaign.html?id=${c.id}">${c.name}</a>`);
  const actions = [];
  if (c.status === 'DRAFT') {
    const submitting = state.submitting.has(c.id);
    actions.push(html`<button type="button" class="btn btn-primary btn-sm" data-action="submit" data-id="${c.id}"${submitting ? ' disabled' : ''}>${submitting ? '제출 중…' : '심사 제출'}</button>`);
  }
  actions.push(html`<a href="campaign.html?id=${c.id}">상세</a>`);
  return html`
    <tr class="${raw(highlight)}">
      <td>${nameBlock}</td>
      <td>${c.productName}</td>
      <td>${c.supplierName}</td>
      <td>${formatMoney(c.dealPrice)}</td>
      <td>${commissionText(c.commissionRateBp)}</td>
      <td class="camp-period">${formatDateTime(c.startsAt)}<br>~ ${formatDateTime(c.endsAt)}</td>
      <td>${skuText}</td>
      <td><status-badge domain="campaign" value="${c.status}"></status-badge></td>
      <td>${raw(actions.join(' '))}</td>
    </tr>
  `;
}

function renderList() {
  if (state.loadError && !state.campaigns) {
    els.tbody.innerHTML = html`<tr><td colspan="9">불러오지 못했습니다: ${messageFor(state.loadError)} (요청 ID ${state.loadError.requestId ?? '-'})</td></tr>`;
    return;
  }
  if (!state.campaigns) {
    els.tbody.innerHTML = '<tr><td colspan="9">불러오는 중…</td></tr>';
    return;
  }
  if (state.campaigns.length === 0) {
    els.tbody.innerHTML = '<tr><td colspan="9">아직 개설한 캠페인이 없습니다. 아래에서 첫 캠페인을 만들어 보세요.</td></tr>';
  } else {
    els.tbody.innerHTML = state.campaigns.map(rowHtml).join('');
  }
  els.listStaleHint.hidden = !state.loadError;
}

function scheduleUnhighlight() {
  if (unhighlightTimer) clearTimeout(unhighlightTimer);
  unhighlightTimer = setTimeout(() => {
    state.highlightId = null;
    renderList();
  }, HIGHLIGHT_DURATION_MS);
}

async function handleSubmitReview(id) {
  if (state.submitting.has(id)) return;
  state.submitting.add(id);
  renderList();
  try {
    const { data, meta } = await api(`/api/campaigns/${id}/submit`, { method: 'POST' });
    showNotice('success', `캠페인을 심사에 제출했습니다. (상태: ${data.status})`, meta.requestId);
  } catch (err) {
    if (err.code === 'CAMPAIGN_INVALID_TRANSITION') {
      // 다른 탭에서 이미 제출·처리된 경우 — 오류로 그리지 않고 목록만 새로고침한다.
      showNotice('info', '이미 다른 곳에서 처리되어 상태가 바뀌었습니다. 목록을 새로고침했습니다.');
    } else {
      showNotice('danger', messageFor(err), err.requestId);
    }
  } finally {
    state.submitting.delete(id);
    pollHandle.restart();
  }
}

function onTableClick(event) {
  const btn = event.target.closest('[data-action="submit"]');
  if (!btn) return;
  handleSubmitReview(Number(btn.dataset.id));
}

function showNotice(level, message, requestId) {
  els.notice.innerHTML = html`<div class="notice notice--${level}">${message}${requestId ? ` (요청 ID: ${requestId})` : ''}</div>`;
}

// --- 상품·SKU 선택 ------------------------------------------------------------------------------

function currentProduct() {
  const id = Number(els.productSelect.value);
  return state.products.find((p) => p.id === id) ?? null;
}

function renderSkuRows(product) {
  if (!product) {
    els.skus.innerHTML = '<p class="hint">먼저 상품을 선택하세요.</p>';
    return;
  }
  els.skus.innerHTML = (product.skus ?? []).map((s) => html`
    <div class="sku-row" data-sku-id="${s.id}" data-sku-name="${s.optionName}">
      <label class="sku-row__check"><input type="checkbox" class="js-sku-check" data-sku-id="${s.id}" checked> ${s.optionName}</label>
      <input type="number" class="js-sku-qty" data-sku-id="${s.id}" min="1" max="${INT32_MAX}" step="1" value="100" inputmode="numeric" placeholder="배정 수량" required>
      <input type="number" class="js-sku-price" data-sku-id="${s.id}" min="0" step="1" inputmode="numeric" placeholder="공급 단가(원)" required>
      <div class="sku-preview" data-sku-id="${s.id}"><span class="muted">공급 단가를 입력하면 마진·정산 미리보기가 표시됩니다.</span></div>
    </div>
  `).join('');
  recomputeAllPreviews();
}

/**
 * SKU별 미리보기를 다시 그린다. 형식이 어긋난 입력(소수·범위 밖 bp 등)은 절사해서 계산하지 않고
 * "입력 오류" 문구로 대체한다(리뷰 MINOR#7) — 잘못된 값으로 통과/실패를 잘못 알려주는 것보다 낫다.
 * 오버플로(리뷰 MINOR#6)도 같은 방식으로 표시한다.
 */
function recomputeAllPreviews() {
  const dealPriceRaw = els.dealPrice.value;
  const commissionRaw = els.commission.value;
  const dealPriceBig = parseNonNegativeBigIntStrict(dealPriceRaw);
  const commissionBig = parseNonNegativeBigIntStrict(commissionRaw);
  const dealPriceValid = dealPriceBig !== null && dealPriceBig > 0n;
  const commissionValid = commissionBig !== null && commissionBig <= BP_SCALE;

  els.commissionPct.textContent = commissionBig !== null
    ? `(${(Number(commissionBig) / 100).toFixed(2)}%)`
    : '';

  let anyFail = false;
  els.skus.querySelectorAll('.sku-row').forEach((row) => {
    const checkbox = row.querySelector('.js-sku-check');
    const priceInput = row.querySelector('.js-sku-price');
    const previewEl = row.querySelector('.sku-preview');
    if (!checkbox.checked) {
      previewEl.innerHTML = '<span class="muted">선택 안 함</span>';
      return;
    }
    if (dealPriceRaw.trim() === '' || commissionRaw.trim() === '' || priceInput.value.trim() === '') {
      previewEl.innerHTML = '<span class="muted">공급 단가·딜가·커미션율을 입력하면 마진·정산 미리보기가 표시됩니다.</span>';
      return;
    }
    if (!dealPriceValid) {
      previewEl.innerHTML = '<span class="input-error">딜가는 1 이상의 정수여야 합니다.</span>';
      anyFail = true;
      return;
    }
    if (!commissionValid) {
      previewEl.innerHTML = '<span class="input-error">커미션율은 0~10000bp 사이의 정수여야 합니다.</span>';
      anyFail = true;
      return;
    }
    const supplyBig = parseNonNegativeBigIntStrict(priceInput.value);
    if (supplyBig === null) {
      previewEl.innerHTML = '<span class="input-error">공급 단가는 0 이상의 정수여야 합니다.</span>';
      anyFail = true;
      return;
    }
    const { lhs, rhs, overflow } = computeMarginProducts(dealPriceBig, commissionBig, supplyBig);
    if (overflow) {
      previewEl.innerHTML = '<span class="input-error">금액 범위 초과 (서버 CAMPAIGN_AMOUNT_TOO_LARGE)</span>';
      anyFail = true;
      return;
    }
    const pass = lhs > rhs;
    if (!pass) anyFail = true;
    previewEl.innerHTML = renderPreviewFor(dealPriceBig, commissionBig, supplyBig, pass);
  });

  els.gateWarning.hidden = !anyFail;
}

async function loadProducts() {
  try {
    const { data } = await api('/api/products');
    state.products = data;
  } catch (err) {
    state.products = [];
    els.productSelect.innerHTML = html`<option value="">불러오지 못했습니다: ${messageFor(err)}</option>`;
    els.productSelect.disabled = true;
    renderSkuRows(null);
    return;
  }
  if (state.products.length === 0) {
    els.productSelect.innerHTML = '<option value="">등록된 상품이 없습니다</option>';
    els.productSelect.disabled = true;
    renderSkuRows(null);
    return;
  }
  els.productSelect.disabled = false;
  els.productSelect.innerHTML = state.products.map((p) => html`<option value="${p.id}">${p.name} (${p.supplierName})</option>`).join('');
  renderSkuRows(currentProduct());
}

// --- 개설 폼 ------------------------------------------------------------------------------------

function setDefaults() {
  els.slug.value = generateSlug();
  const now = Date.now();
  els.starts.value = toDatetimeLocalValue(new Date(now + 60_000));
  els.ends.value = toDatetimeLocalValue(new Date(now + 2 * 3600_000));
}

/** 진짜 성공(신규 201, 또는 확인된 복구)했을 때만 부른다 — 이름·딜가 등 나머지 입력도 전부 지운다. */
function resetFormAfterSuccess() {
  els.name.value = '';
  els.dealPrice.value = '';
  els.commission.value = '';
  els.commissionPct.textContent = '';
  els.limit.value = '';
  unlockSlugField();
  setDefaults();
  renderSkuRows(currentProduct());
}

function onFormInputOrChange(event) {
  const t = event.target;
  if (t === els.dealPrice || t === els.commission
      || t.classList.contains('js-sku-price') || t.classList.contains('js-sku-qty') || t.classList.contains('js-sku-check')) {
    recomputeAllPreviews();
  }
}

function hideCreateNotice() {
  els.createNotice.innerHTML = '';
}

/**
 * @param {object} [opts]
 * @param {boolean} [opts.showRegenerateSlug]
 * @param {boolean} [opts.showRetry]
 * @param {string} [opts.retryLabel]
 * @param {() => void} [opts.retryAction] - 지정하지 않으면 기본값은 폼 재제출이다.
 */
function showCreateNotice(level, message, requestId, opts = {}) {
  const body = html`<div class="notice notice--${level}">${message}${requestId ? ` (요청 ID: ${requestId})` : ''}</div>`;
  if (opts.showRegenerateSlug) {
    els.createNotice.innerHTML = html`${body}<div><button type="button" class="btn btn-secondary btn-sm" id="regen-slug-btn">새 슬러그로 다시 시도</button></div>`;
    document.getElementById('regen-slug-btn').addEventListener('click', () => {
      clearPendingSlug(state.userId);
      unlockSlugField();
      els.slug.value = generateSlug();
      hideCreateNotice();
    });
  } else if (opts.showRetry) {
    els.createNotice.innerHTML = html`${body}<div><button type="button" class="btn btn-secondary btn-sm" id="create-retry-btn">${opts.retryLabel ?? '같은 정보로 다시 시도'}</button></div>`;
    document.getElementById('create-retry-btn').addEventListener('click', () => {
      hideCreateNotice();
      if (opts.retryAction) opts.retryAction();
      else els.form.requestSubmit();
    });
  } else {
    els.createNotice.innerHTML = body;
  }
}

/**
 * 서버에 저장된 캠페인(full, GET /api/campaigns/{id} 응답)이 우리가 보낸 body와 같은 내용인지
 * 비교한다. CampaignResponse에는 supplyUnitPrice가 없으므로(공급사 정보라 응답에 없음) 배정 수량
 * (allocatedQuantity)까지만 비교한다 — 리뷰 MAJOR#3.
 */
function matchesAttempted(full, attempted) {
  if (full.productId !== attempted.productId) return false;
  if (Number(full.dealPrice) !== Number(attempted.dealPrice)) return false;
  if (Number(full.commissionRateBp) !== Number(attempted.commissionRateBp)) return false;
  if (Number(full.perUserPurchaseLimit) !== Number(attempted.perUserPurchaseLimit)) return false;
  const fullAlloc = new Map((full.skus ?? []).map((s) => [s.productSkuId, s.allocatedQuantity]));
  const attemptedSkus = attempted.skus ?? [];
  if (fullAlloc.size !== attemptedSkus.length) return false;
  return attemptedSkus.every((s) => fullAlloc.get(s.productSkuId) === s.allocatedQuantity);
}

/**
 * 이 슬러그가 "내 것"인지 확인한다. 항상 서버에서 새로 조회한다 — 폴링 캐시(state.campaigns)는
 * 네트워크 장애 직후엔 오래됐거나 아직 null일 수 있어 소유권 판단의 근거로 쓰지 않는다
 * (리뷰 MAJOR#2). 조회 자체가 실패하면(네트워크·5xx·401) 'unknown'을 돌려준다 — 이땐 진짜 충돌인지
 * 내 것인지 판단할 근거가 없으므로 절대 "다른 사람 것"으로 단정하지 않는다 (리뷰 MAJOR#4).
 */
async function checkSlugOwnership(slug, attemptedBody) {
  let myCampaigns;
  try {
    const { data } = await api('/api/influencers/me/campaigns');
    myCampaigns = data;
  } catch (err) {
    return { outcome: 'unknown', error: err };
  }
  const match = myCampaigns.find((c) => c.slug === slug);
  if (!match) {
    return { outcome: 'not-found' };
  }
  let full;
  try {
    const { data } = await api(`/api/campaigns/${match.id}`);
    full = data;
  } catch (err) {
    return { outcome: 'unknown', error: err };
  }
  return matchesAttempted(full, attemptedBody)
    ? { outcome: 'confirmed', campaign: full }
    : { outcome: 'mismatch', campaign: full };
}

/** checkSlugOwnership()의 결과를 화면에 반영한다. 같은 시도(slug, body)의 재확인에도 재사용한다. */
function applyOwnershipOutcome(outcome, slug, attemptedBody) {
  if (outcome.outcome === 'confirmed') {
    showCreateNotice('success', `이미 개설되었습니다. (ID ${outcome.campaign.id}, 상태 ${outcome.campaign.status})`);
    state.highlightId = outcome.campaign.id;
    scheduleUnhighlight();
    clearPendingSlug(state.userId);
    resetFormAfterSuccess();
    pollHandle.restart();
    return;
  }
  if (outcome.outcome === 'unknown') {
    // 확인 자체가 실패했다 — pendingSlug를 그대로 두고 슬러그도 잠근 채, 다시 확인할 방법만 준다
    // (리뷰 MAJOR#4: "다른 사람 것"으로 단정하지 않고, 새 슬러그 버튼도 보여주지 않는다).
    showCreateNotice('danger', `이 슬러그의 이전 제출 결과를 확인하지 못했습니다: ${messageFor(outcome.error)}`, outcome.error.requestId, {
      showRetry: true,
      retryLabel: '다시 확인',
      retryAction: () => recheckOwnership(slug, attemptedBody),
    });
    return;
  }
  // not-found: 이 슬러그로 커밋된 캠페인이 아예 없다 — 우리 제출은 확실히 반영되지 않았다.
  // mismatch: 이 슬러그의 캠페인은 있지만 내용이 다르다 — 그것도 우리 제출의 결과가 아니다.
  // 둘 다 "이 시도는 끝났다(성공하지 않았다)"는 확정적 결론이므로 잠금을 풀고 새 슬러그를 권한다.
  clearPendingSlug(state.userId);
  unlockSlugField();
  const detail = outcome.outcome === 'mismatch'
    ? '같은 슬러그의 캠페인이 이미 있지만 상품·딜가·커미션율·구매한도·SKU 배정이 이번 시도와 달라 다른 캠페인입니다.'
    : '내 캠페인 중에는 이 슬러그가 없습니다 — 이번 제출은 반영되지 않았고, 다른 인플루언서가 이 슬러그를 쓰고 있을 수 있습니다.';
  showCreateNotice('danger', `이 슬러그는 그대로 쓸 수 없습니다. ${detail} 아래에서 새 슬러그를 받아 다시 시도하세요.`, null, { showRegenerateSlug: true });
}

async function recheckOwnership(slug, attemptedBody) {
  showCreateNotice('info', '확인 중…');
  const outcome = await checkSlugOwnership(slug, attemptedBody);
  applyOwnershipOutcome(outcome, slug, attemptedBody);
}

async function handleCreateError(err, attemptedSlug, attemptedBody) {
  const isNetworkOrServerError = err.code === 'NETWORK_ERROR' || (typeof err.status === 'number' && err.status >= 500);
  if (isNetworkOrServerError) {
    // 응답을 못 받았다 — 서버가 실제로 커밋했는지 알 수 없다. 슬러그를 잠그고 세션에 남겨 새로고침
    // 후에도 같은 슬러그로 확인을 이어갈 수 있게 한다 (리뷰 MAJOR#3).
    savePendingSlug(state.userId, attemptedSlug, attemptedBody);
    lockSlugField();
    showCreateNotice('danger', '결과를 확인하지 못했습니다. 같은 정보로 다시 시도하거나, 아래에서 이 슬러그가 이미 커밋됐는지 바로 확인할 수 있습니다.', err.requestId, {
      showRetry: true,
      retryLabel: '이 슬러그가 커밋됐는지 확인',
      retryAction: () => recheckOwnership(attemptedSlug, attemptedBody),
    });
    return;
  }
  if (err.code === 'CAMPAIGN_SLUG_DUPLICATE') {
    await recheckOwnership(attemptedSlug, attemptedBody);
    return;
  }
  // 그 외 확정적인 4xx(CAMPAIGN_MARGIN_GATE_FAILED 포함) — 서버가 최종 판정자다. 이 요청이 바로
  // 반려됐으므로 이 슬러그로는 아무것도 커밋되지 않았다는 뜻이다 — pendingSlug가 있었다면 해소된
  // 것이므로 잠금을 푼다 (리뷰 MAJOR#3: "그 외 확정적인 4xx에서 pendingSlug를 지운다").
  clearPendingSlug(state.userId);
  unlockSlugField();
  showCreateNotice('danger', messageFor(err), err.requestId);
}

function validateCreateForm(product) {
  if (!product) return { ok: false, message: '상품을 선택해 주세요.', input: els.productSelect };

  const name = els.name.value.trim();
  if (!name) return { ok: false, message: '캠페인 이름을 입력해 주세요.', input: els.name };

  const slug = els.slug.value.trim();
  if (!slug) return { ok: false, message: '슬러그를 입력해 주세요.', input: els.slug };
  if (!SLUG_PATTERN.test(slug)) {
    return { ok: false, message: '슬러그는 소문자·숫자·하이픈(-)만 사용할 수 있습니다.', input: els.slug };
  }

  const dealPriceField = readRequiredInt(els.dealPrice, '딜가', { min: 1 });
  if (!dealPriceField.ok) return dealPriceField;
  const commissionField = readRequiredInt(els.commission, '커미션율', { min: 0, max: 10000 });
  if (!commissionField.ok) return commissionField;
  const limitField = readRequiredInt(els.limit, '인당 구매 한도', { min: 1, max: INT32_MAX });
  if (!limitField.ok) return limitField;

  if (!els.starts.value) return { ok: false, message: '판매 시작 시각을 입력해 주세요.', input: els.starts };
  if (!els.ends.value) return { ok: false, message: '판매 종료 시각을 입력해 주세요.', input: els.ends };

  const skuRows = [...els.skus.querySelectorAll('.sku-row')].filter((row) => row.querySelector('.js-sku-check').checked);
  if (skuRows.length === 0) return { ok: false, message: 'SKU를 한 개 이상 선택해 주세요.', input: null };

  const skus = [];
  for (const row of skuRows) {
    const skuLabel = row.dataset.skuName ?? `SKU ${row.dataset.skuId}`;
    const qtyField = readRequiredInt(row.querySelector('.js-sku-qty'), `${skuLabel}의 배정 수량`, { min: 1, max: INT32_MAX });
    if (!qtyField.ok) return qtyField;
    const priceField = readRequiredInt(row.querySelector('.js-sku-price'), `${skuLabel}의 공급 단가`, { min: 0 });
    if (!priceField.ok) return priceField;
    skus.push({ productSkuId: Number(row.dataset.skuId), allocatedQuantity: qtyField.value, supplyUnitPrice: priceField.value });
  }

  return {
    ok: true,
    slug,
    body: {
      name,
      slug,
      supplierId: product.supplierId,
      productId: product.id,
      dealPrice: dealPriceField.value,
      startsAt: new Date(els.starts.value).toISOString(),
      endsAt: new Date(els.ends.value).toISOString(),
      perUserPurchaseLimit: limitField.value,
      commissionRateBp: commissionField.value,
      skus,
    },
  };
}

async function onCreateSubmit(event) {
  event.preventDefault();
  if (state.creating) return;
  hideCreateNotice();

  const validated = validateCreateForm(currentProduct());
  if (!validated.ok) {
    showCreateNotice('danger', validated.message);
    validated.input?.focus();
    return;
  }
  const { slug, body } = validated;

  // 시작 시각이 이미 지났다면 승인 즉시 OPEN으로 넘어간다 — 막지 않고 성공 알림에만 덧붙인다
  // (리뷰 MINOR#8).
  const startsInPast = new Date(body.startsAt).getTime() <= Date.now();

  state.creating = true;
  els.submitBtn.disabled = true;
  try {
    // POST /api/campaigns에는 Idempotency-Key가 없다(§5.4 — 주문·결제·환불 세 경로만 멱등키를 쓴다).
    const { data, meta } = await api('/api/campaigns', { method: 'POST', body });
    let message = `캠페인 "${data.name}"을(를) 개설했습니다. (ID ${data.id}, 상태 ${data.status}) 아래 목록에서 "심사 제출"을 눌러 승인 절차를 시작하세요.`;
    if (startsInPast) message += ' 시작 시각이 이미 지났습니다 — 승인 즉시 OPEN 상태가 됩니다.';
    showCreateNotice('success', message, meta.requestId);
    state.highlightId = data.id;
    scheduleUnhighlight();
    clearPendingSlug(state.userId);
    resetFormAfterSuccess();
    pollHandle.restart();
  } catch (err) {
    await handleCreateError(err, slug, body);
  } finally {
    state.creating = false;
    els.submitBtn.disabled = false;
  }
}

// --- 초기화 --------------------------------------------------------------------------------

/** @param {{id:number,email:string,displayName:string,role:string}} user */
export async function initCampaignsPage(user) {
  state.userId = user.id;

  els = {
    tbody: document.getElementById('campaigns-tbody'),
    listStaleHint: document.getElementById('list-stale-hint'),
    notice: document.getElementById('notice'),
    form: document.getElementById('create-form'),
    productSelect: document.getElementById('f-product'),
    name: document.getElementById('f-name'),
    slug: document.getElementById('f-slug'),
    dealPrice: document.getElementById('f-deal-price'),
    commission: document.getElementById('f-commission'),
    commissionPct: document.getElementById('f-commission-pct'),
    limit: document.getElementById('f-limit'),
    starts: document.getElementById('f-starts'),
    ends: document.getElementById('f-ends'),
    skus: document.getElementById('f-skus'),
    gateWarning: document.getElementById('create-gate-warning'),
    createNotice: document.getElementById('create-notice'),
    submitBtn: document.getElementById('create-submit'),
  };

  els.tbody.addEventListener('click', onTableClick);
  els.form.addEventListener('submit', onCreateSubmit);
  els.form.addEventListener('input', onFormInputOrChange);
  els.form.addEventListener('change', onFormInputOrChange);
  els.productSelect.addEventListener('change', () => renderSkuRows(currentProduct()));

  setDefaults();
  await loadProducts();

  // 새로고침 전에 응답을 못 받은 제출이 있으면(§ pendingSlug) 그 슬러그를 프리필하고 잠근 뒤
  // 바로 확인을 시작한다 — 사용자가 무엇이 "미확인" 상태인지 몰라 다른 슬러그로 또 시도하는
  // 사고를 막는다 (리뷰 MAJOR#3).
  const pending = loadPendingSlug(state.userId);
  if (pending) {
    els.slug.value = pending.slug;
    lockSlugField();
    showCreateNotice('warning', `이전 제출(슬러그 "${pending.slug}")의 응답을 받지 못해 결과가 아직 확인되지 않았습니다. 확인 중…`);
    recheckOwnership(pending.slug, pending.body);
  }

  pollHandle = poll({
    fn: () => api('/api/influencers/me/campaigns'),
    until: () => false,
    intervalMs: POLL_INTERVAL_MS,
    maxIntervalMs: POLL_INTERVAL_MS,
    timeoutMs: Infinity,
    onTick: (result, error) => {
      if (!error) {
        state.loadError = null;
        state.campaigns = result.data ?? [];
      } else {
        state.loadError = error;
      }
      renderList();
    },
  });
}
