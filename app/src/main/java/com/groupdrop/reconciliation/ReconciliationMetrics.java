package com.groupdrop.reconciliation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 16.4의 대사 지표. {@code ledger_unbalanced_total}과 {@code reconciliation_mismatch_total}이
 * 기획서에 명시된 두 항목이다.
 *
 * <p>원장 불균형은 카운터가 아니라 <b>게이지</b>다. 대사 실행마다 전수 재검산한 결과이므로,
 * 카운터로 두면 같은 불균형 거래가 실행 횟수만큼 누적되어 "지금 몇 건이 깨져 있는가"를 말할 수 없다.
 */
@Component
public class ReconciliationMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final Counter runs;
    private final Counter failures;
    private final Counter resolutions;
    private final AtomicLong ledgerUnbalanced = new AtomicLong();
    private final AtomicLong openDiscrepancies = new AtomicLong();

    public ReconciliationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.runs = registry.counter("reconciliation.run");
        this.failures = registry.counter("reconciliation.run.failed");
        this.resolutions = registry.counter("reconciliation.resolved");
        registry.gauge("ledger.unbalanced", ledgerUnbalanced, AtomicLong::get);
        registry.gauge("reconciliation.open", openDiscrepancies, AtomicLong::get);
    }

    public void recordRun() {
        runs.increment();
    }

    public void recordRunFailed() {
        failures.increment();
    }

    public void recordResolved() {
        resolutions.increment();
    }

    public void recordMismatch(DiscrepancyType type) {
        counters.computeIfAbsent(type.name(), key -> registry.counter("reconciliation.mismatch", "type", key))
                .increment();
    }

    public void updateGauges(long unbalanced, long open) {
        ledgerUnbalanced.set(unbalanced);
        openDiscrepancies.set(open);
    }
}
