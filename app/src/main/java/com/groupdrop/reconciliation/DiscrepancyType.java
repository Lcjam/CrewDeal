package com.groupdrop.reconciliation;

/**
 * REC-01 불일치 유형 8종. 값 집합은 V5·V7의
 * {@code reconciliation_discrepancies.discrepancy_type} CHECK와 일치해야 한다.
 *
 * <p>컷 5(20장)가 발동하면 {@code MISSING_INTERNAL}·{@code AMOUNT_MISMATCH} 2종으로 축소하지만,
 * 그때도 이 열거형은 줄이지 않는다 — 분류를 줄이는 것이지 개념을 없애는 것이 아니다.
 */
public enum DiscrepancyType {

    /** PG에는 존재하지만 내부에는 매칭되는 결제가 없음. */
    MISSING_INTERNAL,
    /** 내부에는 존재하지만 PG에는 매칭되는 결제가 없음. */
    MISSING_PROVIDER,
    /** 매칭된 거래의 결제 금액 불일치. */
    AMOUNT_MISMATCH,
    /** 매칭된 거래의 결제 상태 불일치 (양쪽 모두 최종 상태인 경우만). */
    STATUS_MISMATCH,
    /** 매칭된 거래의 환불 금액 불일치. */
    REFUND_MISMATCH,
    /** 결제 승인 또는 환불 완료 시각 차이가 허용 오차 1초를 초과하거나 한쪽 시각이 없음. */
    OCCURRED_AT_MISMATCH,
    /** 같은 orderId로 PG에 SUCCEEDED가 2건 이상. */
    DUPLICATE_PAYMENT,
    /** 비최종 상태가 PG 조회로도 확정되지 않음 (해소 단계에서 등록). */
    UNRESOLVED_INTERNAL
}
