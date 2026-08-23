package com.groupdrop.mockpg;

import java.time.Instant;

/** 가상 PG 내부에 보관하는 환불 확정 결과. 부분 환불은 지원하지 않으므로(REF-02) status는 항상 "REFUNDED". */
public record RefundRecord(
        String providerRefundId,
        String providerPaymentId,
        String merchantRefundId,
        long amount,
        String status,
        Instant refundedAt) {
}
