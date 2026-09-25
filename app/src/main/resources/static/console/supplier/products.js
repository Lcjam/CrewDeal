// products.js — 공급사 상품·SKU 등록·조회 (프론트엔드 계획 §6 "공급사")
//
// GET /api/suppliers/me/products로 목록을, POST /api/suppliers/me/products로 등록한다.
//
// 상품 생성은 멱등하지 않고 자연 유니크 키도 없다(같은 이름으로 여러 상품을 몇 번이든 만들 수
// 있다 — ProductService#createProduct는 이름 중복을 검사하지 않는다). 그래서 결제·주문과 달리
// Idempotency-Key를 붙이지 않고(계획 §5.4 대상은 주문·결제·환불 셋뿐), 네트워크 오류·5xx가 나도
// 같은 요청을 자동 재전송하지 않는다 — 요청이 서버에 도달해 상품이 이미 생성됐을 수도 있는데
// 재전송하면 중복 상품을 만든다. 대신 목록을 다시 불러와 사용자가 직접 "방금 그 이름의 상품이
// 이미 있는지" 확인한 뒤에만 다시 제출하도록 안내한다.

import { api, ApiError } from '../common/api.js';
import { html, raw, escapeHtml } from '../common/html.js';
import { messageFor } from '../common/codes.js';

let skuRowSeq = 0;
let submitting = false;
let highlightProductId = null;

// --- SKU 옵션 행 (추가/삭제, 최소 1행) --------------------------------------------------

function addSkuRow(container, value = '') {
  skuRowSeq += 1;
  const row = document.createElement('div');
  row.className = 'sku-row';
  row.dataset.rowId = String(skuRowSeq);
  row.innerHTML = html`
    <input type="text" data-sku-option placeholder="예: 블랙 / L" value="${value}">
    <button type="button" class="btn btn-secondary btn-sm" data-remove-row>삭제</button>
  `;
  container.appendChild(row);
  updateRemoveButtons(container);
  return row;
}

function updateRemoveButtons(container) {
  const rows = container.querySelectorAll('.sku-row');
  rows.forEach((row) => {
    const btn = row.querySelector('[data-remove-row]');
    if (btn) btn.disabled = rows.length <= 1; // 최소 1행은 남긴다
  });
}

function collectSkuOptions(container) {
  return [...container.querySelectorAll('input[data-sku-option]')].map((input) => input.value.trim());
}

function resetForm() {
  document.getElementById('product-name').value = '';
  const container = document.getElementById('sku-rows');
  container.innerHTML = '';
  addSkuRow(container);
  showFormError(null);
}

// --- 알림 --------------------------------------------------------------------------------

function showFormError(message) {
  const el = document.getElementById('form-error');
  el.innerHTML = message ? html`<div class="notice notice--danger">${message}</div>` : '';
}

function showRegisterNotice(kind, message, requestId) {
  const notice = document.getElementById('register-notice');
  if (!kind) {
    notice.hidden = true;
    notice.innerHTML = '';
    return;
  }
  const cls = kind === 'success' ? 'notice--success' : 'notice--danger';
  notice.className = `notice ${cls}`;
  notice.hidden = false;
  notice.innerHTML = html`${message}${requestId ? raw(` <span class="mono">(요청 ID: ${escapeHtml(requestId)})</span>`) : ''}`;
}

function setSubmitDisabled(disabled) {
  document.getElementById('submit-btn').disabled = disabled;
}

// --- 클라이언트 검증 ------------------------------------------------------------------------
// 서버 규칙(ProductService#createProduct)과 같은 조건을 미리 걸러 왕복 없이 안내한다.
// 그래도 서버가 거절하면(PRODUCT_NAME_REQUIRED 등) messageFor()로 서버 메시지를 그대로 보여준다 —
// 클라이언트 검증은 왕복을 줄이는 용도일 뿐 서버 검증을 대체하지 않는다.

function validate(name, options) {
  if (!name) return '상품명을 입력해 주세요.';
  if (options.length === 0) return 'SKU 옵션을 한 개 이상 등록해 주세요.';
  if (options.some((o) => o === '')) return 'SKU 옵션명을 모두 입력해 주세요 (빈 값은 안 됩니다).';
  const seen = new Set();
  for (const option of options) {
    if (seen.has(option)) return `중복된 SKU 옵션명입니다: ${option}`;
    seen.add(option);
  }
  return null;
}

// --- 등록 --------------------------------------------------------------------------------

async function onSubmit(event) {
  event.preventDefault();
  if (submitting) return;

  showFormError(null);
  showRegisterNotice(null);

  const name = document.getElementById('product-name').value.trim();
  const skuContainer = document.getElementById('sku-rows');
  const options = collectSkuOptions(skuContainer);

  const validationError = validate(name, options);
  if (validationError) {
    showFormError(validationError);
    return;
  }

  submitting = true;
  setSubmitDisabled(true);
  try {
    const { data, meta } = await api('/api/suppliers/me/products', {
      method: 'POST',
      body: { name, skus: options.map((optionName) => ({ optionName })) },
    });
    highlightProductId = data.id;
    showRegisterNotice('success', `상품 #${data.id} "${data.name}"을(를) 등록했습니다.`, meta.requestId);
    resetForm();
    await loadProducts();
  } catch (err) {
    if (!(err instanceof ApiError)) throw err;
    const isNetworkOrServerError = err.code === 'NETWORK_ERROR' || (typeof err.status === 'number' && err.status >= 500);
    if (isNetworkOrServerError) {
      // 응답을 못 받았을 뿐 서버에는 도달했을 수 있다 — 자동 재시도 대신 목록을 새로고침해
      // 사용자가 직접 확인하게 한다 (파일 상단 주석 참고).
      showRegisterNotice(
        'danger',
        '응답을 받지 못했습니다. 아래 목록에 방금 등록하려던 상품이 이미 있는지 확인한 뒤, 없을 때만 다시 등록해 주세요.',
        err.requestId,
      );
      await loadProducts().catch(() => {});
    } else {
      showRegisterNotice('danger', messageFor(err), err.requestId);
    }
  } finally {
    submitting = false;
    setSubmitDisabled(false);
  }
}

// --- 목록 --------------------------------------------------------------------------------

async function loadProducts() {
  const tbody = document.getElementById('products-tbody');
  const staleHint = document.getElementById('list-stale-hint');
  try {
    const { data } = await api('/api/suppliers/me/products');
    staleHint.hidden = true;
    renderProducts(data);
  } catch (err) {
    if (!(err instanceof ApiError)) throw err;
    staleHint.hidden = false;
    tbody.innerHTML = html`<tr><td colspan="3">불러오지 못했습니다: ${messageFor(err)} (요청 ID ${err.requestId ?? '-'})</td></tr>`;
  }
}

function rowClass(product) {
  return product.id === highlightProductId ? 'product-row--new' : '';
}

function renderProducts(products) {
  const tbody = document.getElementById('products-tbody');
  if (products.length === 0) {
    tbody.innerHTML = html`<tr><td colspan="3">등록된 상품이 없습니다.</td></tr>`;
    return;
  }
  tbody.innerHTML = products.map((p) => {
    const skuChips = (p.skus ?? [])
      .map((s) => html`<span class="sku-chip">${s.optionName}</span>`)
      .join('');
    return html`
      <tr class="${raw(rowClass(p))}">
        <td>${p.id}</td>
        <td>${p.name}</td>
        <td><div class="sku-chip-list">${raw(skuChips)}</div></td>
      </tr>
    `;
  }).join('');
}

// --- 초기화 --------------------------------------------------------------------------------

export async function initProductsPage() {
  const skuContainer = document.getElementById('sku-rows');
  addSkuRow(skuContainer);

  document.getElementById('add-sku-btn').addEventListener('click', () => addSkuRow(skuContainer));
  skuContainer.addEventListener('click', (event) => {
    const btn = event.target.closest('[data-remove-row]');
    if (!btn) return;
    if (skuContainer.querySelectorAll('.sku-row').length <= 1) return; // 최소 1행 유지
    btn.closest('.sku-row').remove();
    updateRemoveButtons(skuContainer);
  });
  document.getElementById('product-form').addEventListener('submit', onSubmit);

  await loadProducts();
}
