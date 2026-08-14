package com.groupdrop.payment;

/** 시각은 ISO-8601 문자열로 고정한다 — 멱등 응답 본문으로 저장했다가 그대로 재생해야 하기 때문이다 (PAY-02). */
public record PaymentResponse(Long id, Long orderId, String status, long amount, String providerPaymentId,
                              String approvedAt, String failureCode, String failureReason) {

    public static PaymentResponse from(PaymentRepository.PaymentSnapshot snapshot) {
        return new PaymentResponse(snapshot.id(), snapshot.orderId(), snapshot.status(), snapshot.amount(),
                snapshot.providerPaymentId(),
                snapshot.approvedAt() == null ? null : snapshot.approvedAt().toString(),
                snapshot.failureCode(), snapshot.failureReason());
    }
}
