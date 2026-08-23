package com.groupdrop.refund;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 16.4의 4주차 환불 지표. 접수 출처(source)를 태그로 남기는 이유는 결제 지표와 같다 —
 * 환불이 운영자 요청·이중 결제 보상·만료 경쟁 중 어디서 발생했는지를 지표만으로 말할 수 있어야 한다.
 */
@Component
public class RefundMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final Timer pgRefundDuration;
    private final Counter unresolved;

    public RefundMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.pgRefundDuration = registry.timer("refund.pg.duration");
        this.unresolved = registry.counter("refund.unresolved");
    }

    public void recordRequested(String source) {
        counter("refund.requested", "source", source).increment();
    }

    public void recordCompleted(String kind) {
        counter("refund.completed", "kind", kind).increment();
    }

    public void recordFailed(String failureCode) {
        counter("refund.failed", "code", failureCode == null ? "UNKNOWN" : failureCode).increment();
    }

    /** 결과 불명(REQUESTED 유지)으로 재시도 대기에 들어간 환불. 늘어나기만 하면 PG가 응답하지 않는 것이다. */
    public void recordUnresolved() {
        unresolved.increment();
    }

    public <T> T timePgRefund(Supplier<T> action) {
        long startedAt = System.nanoTime();
        try {
            return action.get();
        } finally {
            pgRefundDuration.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }

    private Counter counter(String name, String tagKey, String tagValue) {
        return counters.computeIfAbsent(name + "|" + tagKey + "|" + tagValue,
                key -> registry.counter(name, tagKey, tagValue));
    }
}
