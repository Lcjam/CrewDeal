package com.groupdrop.mockpg;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** 테스트 어서션용 수신·발송 카운터 (14.5 "결제 요청 수신 카운트 조회를 제공한다"). */
@Component
public class MockPgStats {

    private final AtomicLong confirmRequestCount = new AtomicLong();
    private final AtomicLong webhookSentCount = new AtomicLong();

    public void incrementConfirmRequests() {
        confirmRequestCount.incrementAndGet();
    }

    public void incrementWebhookSent() {
        webhookSentCount.incrementAndGet();
    }

    public long confirmRequestCount() {
        return confirmRequestCount.get();
    }

    public long webhookSentCount() {
        return webhookSentCount.get();
    }
}
