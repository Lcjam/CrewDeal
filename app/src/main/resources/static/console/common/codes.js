// codes.js — problem+json code → 한국어 메시지 (프론트엔드 계획 §5.3)
//
// 목록은 `grep -rn "new ApiException(" app/src/main/java`로 뽑은 전체 코드에
// GlobalExceptionHandler·SecurityConfig의 필터 단 코드(VALIDATION_FAILED, MALFORMED_JSON,
// AUTHENTICATION_REQUIRED, ACCESS_DENIED)와 프론트 전용 코드(NETWORK_ERROR)를 더한 것이다.
// 여기 없는 코드는 messageFor()가 problem detail로 폴백한다 — throw하지 않는다.

const MESSAGES = {
  // --- 필터 단 / 공통 (GlobalExceptionHandler, SecurityConfig) ---
  AUTHENTICATION_REQUIRED: '인증이 필요합니다. 다시 로그인해 주세요.',
  ACCESS_DENIED: '이 작업을 수행할 권한이 없습니다.',
  VALIDATION_FAILED: '입력값이 올바르지 않습니다.',
  MALFORMED_JSON: '요청 본문을 읽을 수 없습니다.',
  INVALID_STATUS_FILTER: '상태 필터 값이 올바르지 않습니다.',
  NETWORK_ERROR: '네트워크 오류로 응답을 받지 못했습니다. 결과를 확인한 뒤 다시 시도해 주세요.',

  // --- 인증 ---
  AUTH_INVALID_CREDENTIALS: '이메일 또는 비밀번호가 올바르지 않습니다.',
  AUTH_USER_NOT_FOUND: '사용자를 찾을 수 없습니다.',

  // --- 권한 (서비스 메서드 내부 검사) ---
  FORBIDDEN_ROLE: '이 역할은 사용할 수 없는 기능입니다.',
  FORBIDDEN_NOT_OWNER: '본인 소유가 아닙니다.',

  // --- 멱등 키 ---
  IDEMPOTENCY_KEY_REQUIRED: 'Idempotency-Key 헤더가 필요합니다.',
  IDEMPOTENCY_KEY_TOO_LONG: 'Idempotency-Key가 너무 깁니다 (최대 200자).',
  IDEMPOTENCY_KEY_REUSED: '이 키로 이미 다른 요청이 처리되었습니다.',
  IDEMPOTENCY_KEY_EXPIRED: '멱등 키가 만료되었습니다. 새 키로 다시 시도해 주세요.',
  IDEMPOTENCY_REQUEST_IN_PROGRESS: '같은 요청이 이미 처리 중입니다. 잠시 후 결과를 확인해 주세요.',

  // --- 캠페인 ---
  CAMPAIGN_NOT_FOUND: '캠페인을 찾을 수 없습니다.',
  CAMPAIGN_NAME_REQUIRED: '캠페인 이름을 입력해 주세요.',
  CAMPAIGN_SLUG_REQUIRED: '슬러그를 입력해 주세요.',
  CAMPAIGN_SLUG_DUPLICATE: '이미 사용 중인 슬러그입니다.',
  CAMPAIGN_SUPPLIER_ID_REQUIRED: '공급사를 선택해 주세요.',
  CAMPAIGN_SUPPLIER_NOT_FOUND: '공급사를 찾을 수 없습니다.',
  CAMPAIGN_PRODUCT_ID_REQUIRED: '상품을 선택해 주세요.',
  CAMPAIGN_PRODUCT_NOT_FOUND: '상품을 찾을 수 없습니다.',
  CAMPAIGN_PRODUCT_NOT_OWNED_BY_SUPPLIER: '선택한 상품이 해당 공급사 소유가 아닙니다.',
  CAMPAIGN_SKUS_REQUIRED: 'SKU를 한 개 이상 선택해 주세요.',
  CAMPAIGN_SKU_NOT_IN_PRODUCT: '선택한 SKU가 상품에 속하지 않습니다.',
  CAMPAIGN_SKU_PRODUCT_SKU_ID_REQUIRED: 'SKU를 선택해 주세요.',
  CAMPAIGN_DUPLICATE_SKU: '같은 SKU를 중복으로 선택했습니다.',
  CAMPAIGN_INVALID_ALLOCATED_QUANTITY: '배정 수량이 올바르지 않습니다.',
  CAMPAIGN_INVALID_SUPPLY_UNIT_PRICE: '공급 단가가 올바르지 않습니다.',
  CAMPAIGN_INVALID_DEAL_PRICE: '판매가가 올바르지 않습니다.',
  CAMPAIGN_INVALID_COMMISSION_RATE: '커미션율이 올바르지 않습니다.',
  CAMPAIGN_INVALID_PURCHASE_LIMIT: '인당 구매 제한이 올바르지 않습니다.',
  CAMPAIGN_INVALID_DATE_RANGE: '판매 기간이 올바르지 않습니다.',
  CAMPAIGN_AMOUNT_TOO_LARGE: '금액이 허용 범위를 초과했습니다.',
  CAMPAIGN_MARGIN_GATE_FAILED: '마진 조건을 만족하지 않습니다 (판매가가 공급가+커미션보다 커야 합니다).',
  CAMPAIGN_REJECT_REASON_REQUIRED: '반려 사유를 입력해 주세요.',
  CAMPAIGN_INVALID_TRANSITION: '캠페인 상태를 이 값으로 바꿀 수 없습니다.',
  CAMPAIGN_NOT_ORDERABLE: '지금은 주문할 수 없는 캠페인입니다.',
  CAMPAIGN_NOT_PAYABLE: '지금은 결제할 수 없는 캠페인입니다.',
  CAMPAIGN_OUTSIDE_SALES_PERIOD: '판매 기간이 아닙니다.',
  CAMPAIGN_ID_REQUIRED: '캠페인을 선택해 주세요.',
  INFLUENCER_NOT_FOUND: '인플루언서 프로필을 찾을 수 없습니다.',
  INFLUENCER_PROFILE_NOT_FOUND: '인플루언서 프로필을 찾을 수 없습니다.',
  SUPPLIER_NOT_FOUND: '공급사 프로필을 찾을 수 없습니다.',
  SUPPLIER_PROFILE_NOT_FOUND: '공급사 프로필을 찾을 수 없습니다.',

  // --- 상품 ---
  PRODUCT_NAME_REQUIRED: '상품명을 입력해 주세요.',
  PRODUCT_SKUS_REQUIRED: 'SKU를 한 개 이상 등록해 주세요.',
  PRODUCT_SKU_OPTION_NAME_REQUIRED: 'SKU 옵션명을 입력해 주세요.',
  PRODUCT_SKU_OPTION_DUPLICATE: '중복된 SKU 옵션명입니다.',

  // --- 주문 ---
  ORDER_NOT_FOUND: '주문을 찾을 수 없습니다.',
  ORDER_ITEMS_REQUIRED: '주문 항목을 한 개 이상 입력해 주세요.',
  ORDER_ITEM_INVALID: '주문 항목이 올바르지 않습니다.',
  ORDER_DUPLICATE_SKU: '같은 SKU를 중복으로 담았습니다.',
  ORDER_SKU_NOT_IN_CAMPAIGN: '선택한 SKU가 이 캠페인에 없습니다.',
  ORDER_QUANTITY_TOO_LARGE: '주문 수량이 허용 범위를 초과했습니다.',
  ORDER_AMOUNT_TOO_LARGE: '주문 금액이 허용 범위를 초과했습니다.',
  ORDER_NOT_CANCELLABLE: '지금은 취소할 수 없는 주문입니다.',
  ORDER_NOT_PAYABLE: '지금은 결제할 수 없는 주문입니다. (이미 결제가 진행되었을 수 있습니다 — 목록에서 확인해 주세요)',
  INVENTORY_SOLD_OUT: '재고가 소진되었습니다.',
  PURCHASE_LIMIT_EXCEEDED: '인당 구매 가능 수량을 초과했습니다.',

  // --- 결제 ---
  PAYMENT_NOT_FOUND: '결제 내역을 찾을 수 없습니다.',
  PAYMENT_AMOUNT_REQUIRED: '결제 금액을 입력해 주세요.',
  PAYMENT_AMOUNT_MISMATCH: '결제 금액이 주문 총액과 일치하지 않습니다.',
  PAYMENT_ALREADY_IN_PROGRESS: '이 주문에 대해 아직 끝나지 않은 결제가 있습니다.',
  PAYMENT_NOT_REFUNDABLE: '환불할 수 없는 결제 상태입니다.',
  PAYMENT_NOT_SETTLED: '아직 정산되지 않은 결제입니다.',

  // --- 환불 ---
  REFUND_ALREADY_EXISTS: '이미 진행 중이거나 완료된 환불이 있습니다.',
  REFUND_WINDOW_CLOSED: '환불 가능 기간이 지났습니다.',

  // --- 정산 ---
  SETTLEMENT_BATCH_NOT_FOUND: '정산 배치를 찾을 수 없습니다.',
  SETTLEMENT_NOT_HELD: '보류 상태가 아닌 배치입니다.',
  SETTLEMENT_NOT_HOLDABLE: '보류할 수 없는 배치 상태입니다.',
  SETTLEMENT_NOT_RETRYABLE: '재시도할 수 없는 배치 상태입니다.',

  // --- 대사 ---
  RECONCILIATION_ALREADY_RUNNING: '이미 실행 중인 대사가 있습니다.',
  RECONCILIATION_RUN_NOT_FOUND: '대사 실행 이력을 찾을 수 없습니다.',
  MIN_AGE_INVALID: 'minAgeMinutes 값이 올바르지 않습니다.',
  DISCREPANCY_NOT_FOUND: '불일치 건을 찾을 수 없습니다.',
  DISCREPANCY_NOT_OPEN: '이미 처리된 불일치 건입니다.',
  DISCREPANCY_NOT_RETRYABLE: '재처리할 수 없는 불일치 상태입니다.',
  RESOLUTION_NOTE_REQUIRED: '해결 메모를 입력해 주세요.',

  // --- Outbox / Inbox ---
  OUTBOX_EVENT_ALREADY_PENDING: '이미 대기 중인 이벤트입니다.',
  OUTBOX_EVENT_NOT_FAILED: '실패 상태가 아닌 이벤트는 재시도할 수 없습니다.',
  INBOX_EVENT_NOT_FAILED: '실패 상태가 아닌 이벤트는 재시도할 수 없습니다.',

  // --- 웹훅 (참고 — 브라우저에서 직접 호출하지 않지만 오류 로그 대조용) ---
  WEBHOOK_EVENT_ID_REQUIRED: '웹훅 이벤트 ID가 필요합니다.',
  WEBHOOK_PAYLOAD_INVALID: '웹훅 페이로드가 올바르지 않습니다.',
  WEBHOOK_SIGNATURE_INVALID: '웹훅 서명이 올바르지 않습니다.',
};

// 필드 하나짜리 사유가 아니라 "무엇이 왜 걸렸는지"가 요청마다 다른 코드들 — 매핑된 일반 문구
// 뒤에 서버 detail을 괄호로 덧붙인다. 나머지 코드는 상황이 코드 자체로 이미 특정되므로 덧붙이지 않는다.
const APPEND_DETAIL_CODES = new Set([
  'VALIDATION_FAILED',
  'MALFORMED_JSON',
  'FORBIDDEN_ROLE',
  'FORBIDDEN_NOT_OWNER',
]);

/**
 * ApiError(또는 {code, detail} 형태)를 한국어 메시지로 바꾼다.
 * 등록되지 않은 code는 서버가 준 detail로 폴백하고, detail도 없으면 code 자체를 보여준다.
 * 절대 throw하지 않는다.
 */
export function messageFor(error) {
  if (!error) return '알 수 없는 오류입니다.';
  const code = error.code;
  if (code && MESSAGES[code]) {
    const base = MESSAGES[code];
    if (APPEND_DETAIL_CODES.has(code) && error.detail) {
      return `${base} (${error.detail})`;
    }
    return base;
  }
  if (error.detail) return error.detail;
  if (code) return `오류가 발생했습니다. (${code})`;
  return '오류가 발생했습니다.';
}
