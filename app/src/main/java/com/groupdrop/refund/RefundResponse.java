package com.groupdrop.refund;

import java.time.Instant;

public record RefundResponse(Long id, Long paymentId, Long orderId, String status, long amount,
                             boolean compensation, String reason, String providerRefundId,
                             String failureCode, String failureReason, Instant requestedAt,
                             Instant completedAt) {

    public static RefundResponse from(RefundRepository.RefundSnapshot refund) {
        return new RefundResponse(refund.id(), refund.paymentId(), refund.orderId(), refund.status(),
                refund.amount(), refund.compensation(), refund.reason(), refund.providerRefundId(),
                refund.failureCode(), refund.failureReason(), refund.requestedAt(), refund.completedAt());
    }
}
