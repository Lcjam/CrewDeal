package com.groupdrop.mockpg;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** 테스트 어서션용 수신·발송 카운터 (14.5 "결제 요청 수신 카운트 조회를 제공한다"). */
@Component
public class MockPgStats {

    private final AtomicLong confirmRequestCount = new AtomicLong();
    private final AtomicLong webhookSentCount = new AtomicLong();
    private final AtomicLong refundRequestCount = new AtomicLong();
    private final AtomicLong refundExecutedCount = new AtomicLong();

    public void incrementConfirmRequests() {
        confirmRequestCount.incrementAndGet();
    }

    public void incrementWebhookSent() {
        webhookSentCount.incrementAndGet();
    }

    /** 환불 엔드포인트에 수신된 요청 수 — 멱등 재호출도 포함해 센다. */
    public void incrementRefundRequests() {
        refundRequestCount.incrementAndGet();
    }

    /** 실제로 환불이 실행된 횟수 — 멱등 재호출(이미 REFUNDED)은 증가시키지 않는다. */
    public void incrementRefundExecuted() {
        refundExecutedCount.incrementAndGet();
    }

    public long confirmRequestCount() {
        return confirmRequestCount.get();
    }

    public long webhookSentCount() {
        return webhookSentCount.get();
    }

    public long refundRequestCount() {
        return refundRequestCount.get();
    }

    public long refundExecutedCount() {
        return refundExecutedCount.get();
    }
}
