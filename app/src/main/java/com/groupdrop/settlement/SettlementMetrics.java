package com.groupdrop.settlement;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 16.4의 5주차 정산 지표. {@code settlement_failed_total}이 기획서에 명시된 항목이고, 나머지는
 * "왜 실패했는지"가 아니라 "무엇이 멈춰 있는지"를 말하기 위한 게이지다 — 보류(HELD)와 미회수 잔액은
 * 카운터로는 회복이 표현되지 않는다.
 */
@Component
public class SettlementMetrics {

    private final Counter determined;
    private final Counter deferred;
    private final Counter completed;
    private final Counter failed;
    private final Counter held;
    private final Counter recovered;
    private final AtomicLong unrecoveredAdjustments = new AtomicLong();
    private final AtomicLong failedOrHeldBatches = new AtomicLong();

    public SettlementMetrics(MeterRegistry registry) {
        this.determined = registry.counter("settlement.determined");
        this.deferred = registry.counter("settlement.deferred");
        this.completed = registry.counter("settlement.completed");
        this.failed = registry.counter("settlement.failed");
        this.held = registry.counter("settlement.held");
        this.recovered = registry.counter("settlement.recovered");
        registry.gauge("settlement.unrecovered.adjustments", unrecoveredAdjustments, AtomicLong::get);
        registry.gauge("settlement.batches.blocked", failedOrHeldBatches, AtomicLong::get);
    }

    public void recordDetermined(int batchCount) {
        determined.increment(batchCount);
    }

    /** 진입 전제 미충족으로 CLOSED에 머문 캠페인. 계속 증가하면 결제 확정이 막혀 있는 것이다. */
    public void recordDeferred() {
        deferred.increment();
    }

    public void recordCompleted() {
        completed.increment();
    }

    public void recordFailed() {
        failed.increment();
    }

    public void recordHeld() {
        held.increment();
    }

    public void recordRecovered() {
        recovered.increment();
    }

    public void updateBlocked(long unrecovered, long failedOrHeld) {
        unrecoveredAdjustments.set(unrecovered);
        failedOrHeldBatches.set(failedOrHeld);
    }
}
