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

    /**
     * 결제 요청 응답의 HTTP 상태. 상태 코드로 결과를 위장하지 않는다 —
     * {@code UNKNOWN}은 미확정이므로 202, {@code SUPERSEDED}는 이 요청의 결제가 주문의 유효 결제가 되지 못했으므로 409다.
     * 200으로 답하면 환불 대상 결제를 "결제 성공"으로 통지하게 된다 (PAY-01, PAY-03).
     * 요청 스레드의 확정(settle)과 고착 선점 회수가 같은 규칙을 쓰도록 여기 한 곳에 둔다.
     * 회수 시점에 이미 환불이 시작된 결제({@code REFUNDING}·{@code REFUNDED})는 한때 성공한 결제이므로 200 + 현재 스냅숏이다.
     */
    public static int httpStatusFor(String paymentStatus) {
        return switch (paymentStatus) {
            case "UNKNOWN" -> 202;
            case "SUPERSEDED" -> 409;
            default -> 200;
        };
    }
}
