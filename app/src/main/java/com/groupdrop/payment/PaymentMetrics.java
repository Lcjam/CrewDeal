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

    /** 16.4: 실제 PG confirm 호출 직전에만 센다. 재생 응답은 외부 호출이 아니므로 제외한다. */
    public void recordAttempt() {
        registry.counter("payment.attempt").increment();
    }

    /** 16.4: 기존 멱등 키를 다시 받은 모든 경로(재생·충돌)를 관측한다. */
    public void recordDuplicatePrevented() {
        registry.counter("payment.duplicate.prevented").increment();
    }

    /** 16.4: Inbox UNIQUE가 이미 수신한 웹훅을 막은 경우다. */
    public void recordWebhookDuplicate() {
        registry.counter("webhook.duplicate").increment();
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
        // confirm을 호출하는 모든 경로(request·provider-query recovery)를 이 경계로 모은다 (16.4).
        recordAttempt();
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
