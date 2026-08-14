package com.groupdrop.payment;

/** PAY-04 결제 웹훅 페이로드. 기획서 명세 그대로이며 필드를 임의로 늘리지 않는다. */
public record PaymentWebhookPayload(String eventId, String providerPaymentId, String orderId, String status,
                                    Long amount, String occurredAt) {

    /** 예시의 {@code ord_1024}처럼 접두사가 붙어 올 수 있으므로 숫자 부분만 취한다. */
    public Long numericOrderId() {
        if (orderId == null || orderId.isBlank()) {
            return null;
        }
        String digits = orderId.substring(orderId.lastIndexOf('_') + 1);
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
