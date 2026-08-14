package com.groupdrop.mockpg;

import java.time.Instant;

/** 가상 PG 내부에 보관하는 결제 확정 결과. status는 "SUCCEEDED" 또는 "FAILED"만 저장한다(둘 다 종국 상태). */
public record PaymentRecord(
        String providerPaymentId,
        String merchantPaymentId,
        String orderId,
        long amount,
        String status,
        Instant processedAt) {
}
