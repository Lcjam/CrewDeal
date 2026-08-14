package com.groupdrop.mockpg;

import java.time.Instant;

/** PAY-04 결제 웹훅 페이로드에 대응하는 내부 표현. */
public record WebhookEvent(
        String eventId,
        String providerPaymentId,
        String orderId,
        String status,
        long amount,
        Instant occurredAt) {
}
