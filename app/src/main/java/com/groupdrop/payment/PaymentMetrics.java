package com.groupdrop.payment;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 16.4의 3주차 결제 지표. 확정 경로(source)를 태그로 남기는 것이 핵심이다 —
 * 응답 유실 복구가 실제로 웹훅·조회 중 어느 경로로 일어났는지 지표만 보고 말할 수 있어야 한다.
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final Timer pgConfirmDuration;
    private final Counter webhookRejected;
    private final Counter orphanSwept;
    private final Counter superseded;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.pgConfirmDuration = registry.timer("payment.pg.confirm.duration");
        this.webhookRejected = registry.counter("payment.webhook.rejected");
        this.orphanSwept = registry.counter("payment.orphan.swept");
        this.superseded = registry.counter("payment.superseded");
    }

    public void recordSucceeded(String source) {
        counter("payment.succeeded", "source", source).increment();
    }

    public void recordFailed(String source, String failureCode) {
        counter("payment.failed", "source", source).increment();
        counter("payment.failure.reason", "code", failureCode == null ? "UNKNOWN" : failureCode).increment();
    }

    public void recordUnknown() {
        counter("payment.unknown", "source", "request").increment();
    }

    public void recordWebhookRejected() {
        webhookRejected.increment();
    }

    public void recordOrphanSwept(String kind) {
        orphanSwept.increment();
        counter("payment.orphan.swept.kind", "kind", kind).increment();
    }

    public void recordSuperseded() {
        superseded.increment();
    }

    public <T> T timePgConfirm(Supplier<T> action) {
        long started = System.nanoTime();
        try {
            return action.get();
        } finally {
            pgConfirmDuration.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private Counter counter(String name, String tagKey, String tagValue) {
        return counters.computeIfAbsent(name + "|" + tagValue,
                key -> registry.counter(name, tagKey, tagValue));
    }
}
