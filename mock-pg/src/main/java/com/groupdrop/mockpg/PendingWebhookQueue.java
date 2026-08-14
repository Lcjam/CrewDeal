package com.groupdrop.mockpg;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code blockWebhook} 상태에서 발송이 보류된 웹훅 이벤트를 담아둔다.
 * {@code /mock-pg/test/webhooks/replay}가 이 큐에서 대상을 꺼내 재발사한다 (S4-a 전제).
 */
@Component
public class PendingWebhookQueue {

    private record Entry(String merchantPaymentId, String providerPaymentId, WebhookEvent event) {
    }

    private final List<Entry> pending = new ArrayList<>();

    public synchronized void enqueue(String merchantPaymentId, String providerPaymentId, WebhookEvent event) {
        pending.add(new Entry(merchantPaymentId, providerPaymentId, event));
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    /**
     * providerPaymentId·merchantPaymentId가 모두 null이면 보류분 전체를, 아니면 둘 중 하나라도 일치하는
     * 항목만 꺼내 큐에서 제거하고 반환한다.
     */
    public synchronized List<WebhookEvent> drainMatching(String providerPaymentId, String merchantPaymentId) {
        boolean replayAll = providerPaymentId == null && merchantPaymentId == null;
        List<WebhookEvent> drained = new ArrayList<>();
        pending.removeIf(entry -> {
            boolean matches = replayAll
                    || Objects.equals(providerPaymentId, entry.providerPaymentId())
                    || Objects.equals(merchantPaymentId, entry.merchantPaymentId());
            if (matches) {
                drained.add(entry.event());
            }
            return matches;
        });
        return drained;
    }
}
