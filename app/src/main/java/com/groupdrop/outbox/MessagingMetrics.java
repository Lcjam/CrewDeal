package com.groupdrop.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/** 16.4의 메시징 지표. 이벤트 타입별로 태그를 붙여 다중 워커 실증(17.3)에서 소비량을 비교한다. */
@Component
public class MessagingMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final AtomicLong pendingCount = new AtomicLong();
    private final AtomicLong oldestEventAgeSeconds = new AtomicLong();

    public MessagingMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("outbox.pending.count", pendingCount, AtomicLong::get).register(registry);
        Gauge.builder("outbox.oldest.event.age", oldestEventAgeSeconds, AtomicLong::get).register(registry);
    }

    public void recordOutboxProcessed(String eventType) {
        counter("outbox.event.processed", eventType).increment();
    }

    public void recordOutboxFailure(String eventType) {
        counter("outbox.event.failure", eventType).increment();
    }

    public void recordInboxProcessed(String eventType) {
        counter("inbox.event.processed", eventType).increment();
    }

    public void recordInboxIgnored(String eventType) {
        counter("inbox.event.ignored", eventType).increment();
    }

    public void recordInboxFailure(String eventType) {
        counter("inbox.event.failure", eventType).increment();
    }

    /** 16.4: 폴링 시점의 DB PENDING 상태를 그대로 게이지에 반영한다. */
    public void updateOutboxPending(long count, long oldestAgeSeconds) {
        pendingCount.set(Math.max(0L, count));
        oldestEventAgeSeconds.set(Math.max(0L, oldestAgeSeconds));
    }

    private Counter counter(String name, String eventType) {
        return counters.computeIfAbsent(name + "|" + eventType,
                key -> registry.counter(name, "event", eventType));
    }
}
