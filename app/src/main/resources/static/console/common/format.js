// format.js — 로직 없는 선언 표 (프론트엔드 계획 §5.7)
//
// 도메인별 [라벨, 색]을 DB CHECK 제약 값 그대로 옮긴다. 새 상태가 추가되면 여기와 CHECK 제약을
// 함께 고친다 (주문 상태는 문자열이고 DB CHECK가 진실의 원천 — CLAUDE.md).
// 표에 없는 값은 색 gray에 원문을 그대로 보여준다 — 절대 throw하지 않는다.

// 색 토큰: green(정상/완료) yellow(대기/주의) red(실패/취소) blue(진행중) gray(중립/종결)

export const STATUS_TABLES = {
  // 캠페인 상태 (V1__base_domain.sql)
  campaign: {
    DRAFT: ['임시저장', 'gray'],
    REVIEWING: ['심사중', 'blue'],
    SCHEDULED: ['개설예정', 'blue'],
    OPEN: ['진행중', 'green'],
    SOLD_OUT: ['품절', 'yellow'],
    CLOSED: ['종료', 'gray'],
    CANCELLED: ['취소', 'red'],
    SETTLING: ['정산중', 'blue'],
    SETTLED: ['정산완료', 'green'],
  },
  // 주문 상태 (V2__orders_and_stock_reservations.sql)
  order: {
    PENDING_PAYMENT: ['결제대기', 'yellow'],
    PAID: ['결제완료', 'green'],
    EXPIRED: ['만료', 'gray'],
    CANCELLED: ['취소', 'red'],
    REFUNDING: ['환불중', 'blue'],
    REFUNDED: ['환불완료', 'gray'],
  },
  // 재고 예약 상태 (OrderResponse.items[].reservationStatus, V2)
  reservation: {
    ACTIVE: ['예약중', 'blue'],
    CONFIRMED: ['확정', 'green'],
    RELEASED: ['해제됨', 'gray'],
    EXPIRED: ['만료', 'gray'],
  },
  // 결제 상태 (V3)
  payment: {
    READY: ['준비', 'gray'],
    PROCESSING: ['처리중', 'blue'],
    SUCCEEDED: ['성공', 'green'],
    FAILED: ['실패', 'red'],
    UNKNOWN: ['확정대기', 'yellow'],
    SUPERSEDED: ['대체됨', 'blue'],
    REFUNDING: ['환불중', 'blue'],
    REFUNDED: ['환불완료', 'gray'],
  },
  // 결제 시도 상태 (V3)
  paymentAttempt: {
    REQUESTED: ['요청됨', 'blue'],
    SUCCEEDED: ['성공', 'green'],
    FAILED: ['실패', 'red'],
    TIMEOUT: ['시간초과', 'yellow'],
  },
  // Outbox 상태 (V3)
  outbox: {
    PENDING: ['대기', 'yellow'],
    PROCESSED: ['처리완료', 'green'],
    FAILED: ['실패', 'red'],
  },
  // Inbox 상태 (V3)
  inbox: {
    PENDING: ['대기', 'yellow'],
    PROCESSED: ['처리완료', 'green'],
    IGNORED: ['무시됨', 'gray'],
    FAILED: ['실패', 'red'],
  },
  // 환불 상태 (V4)
  refund: {
    REQUESTED: ['요청됨', 'blue'],
    COMPLETED: ['완료', 'green'],
    FAILED: ['실패', 'red'],
  },
  // 원장 거래 유형 (V4)
  ledgerTransaction: {
    PAYMENT: ['결제', 'blue'],
    REFUND: ['환불', 'yellow'],
    PAYOUT: ['지급', 'green'],
    RECOVERY: ['회수', 'gray'],
  },
  // 원장 분개 방향 (V4)
  ledgerEntry: {
    DEBIT: ['차변', 'blue'],
    CREDIT: ['대변', 'gray'],
  },
  // 정산 배치 상태 (V5)
  settlementBatch: {
    PENDING: ['대기', 'gray'],
    READY: ['준비완료', 'blue'],
    PROCESSING: ['처리중', 'blue'],
    COMPLETED: ['완료', 'green'],
    FAILED: ['실패', 'red'],
    HELD: ['보류', 'yellow'],
  },
  // 정산 배치 유형 (V5)
  settlementBatchType: {
    SETTLEMENT: ['정산', 'blue'],
    RECOVERY: ['회수', 'gray'],
  },
  // 정산 수령 주체 (V5)
  settlementPayeeType: {
    SUPPLIER: ['공급사', 'gray'],
    INFLUENCER: ['인플루언서', 'gray'],
  },
  // 대사 실행 상태 (V5)
  reconciliationRun: {
    RUNNING: ['실행중', 'blue'],
    COMPLETED: ['완료', 'green'],
    FAILED: ['실패', 'red'],
  },
  // 불일치 상태 (V5)
  discrepancyStatus: {
    OPEN: ['미해결', 'yellow'],
    RESOLVED: ['해결됨', 'green'],
    IGNORED: ['무시됨', 'gray'],
  },
  // 불일치 유형 (V7, V5 대체)
  discrepancyType: {
    MISSING_INTERNAL: ['내부 누락', 'gray'],
    MISSING_PROVIDER: ['PG 누락', 'gray'],
    AMOUNT_MISMATCH: ['금액 불일치', 'gray'],
    STATUS_MISMATCH: ['상태 불일치', 'gray'],
    REFUND_MISMATCH: ['환불 불일치', 'gray'],
    OCCURRED_AT_MISMATCH: ['발생시각 불일치', 'gray'],
    DUPLICATE_PAYMENT: ['중복 결제', 'gray'],
    UNRESOLVED_INTERNAL: ['내부 미해결', 'gray'],
  },
};

/**
 * domain·value에 대응하는 [라벨, 색]. 표에 없으면 원문을 회색으로.
 * `Object.hasOwn`으로 조회한다 — `value`가 `constructor`·`toString`·`__proto__` 같은
 * Object.prototype 상속 프로퍼티 이름과 우연히 같아도 그 함수/프로토타입을 돌려주며 죽지 않는다.
 */
export function statusOf(domain, value) {
  const table = STATUS_TABLES[domain];
  if (table && typeof value === 'string' && Object.hasOwn(table, value)) {
    return table[value];
  }
  return [value === null || value === undefined ? '' : String(value), 'gray'];
}

/** KRW, "19,900원" */
export function formatMoney(amount) {
  if (amount === null || amount === undefined || Number.isNaN(Number(amount))) return '-';
  return `${Number(amount).toLocaleString('ko-KR')}원`;
}

// sv-SE 로캘은 "YYYY-MM-DD HH:mm:ss" 형태(24시간, 구분자 하이픈·콜론)를 그대로 낸다.
const KST_DATE_TIME_FORMATTER = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Seoul',
  year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit',
  hour12: false,
});

/** Asia/Seoul, "YYYY-MM-DD HH:mm:ss" */
export function formatDateTime(value) {
  if (!value) return '-';
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return KST_DATE_TIME_FORMATTER.format(date).replace(',', '');
}

/** "3분 전" / "2시간 전" 같은 상대 시각. 미래는 "곧"으로 뭉갠다 — 시연에서 정밀도가 필요 없다. */
export function formatRelative(value) {
  if (!value) return '-';
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  const diffSeconds = Math.floor((Date.now() - date.getTime()) / 1000);
  if (diffSeconds < 0) return '곧';
  if (diffSeconds < 60) return '방금 전';
  if (diffSeconds < 3600) return `${Math.floor(diffSeconds / 60)}분 전`;
  if (diffSeconds < 86400) return `${Math.floor(diffSeconds / 3600)}시간 전`;
  return `${Math.floor(diffSeconds / 86400)}일 전`;
}
