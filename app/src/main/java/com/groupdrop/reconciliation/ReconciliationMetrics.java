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
 * <p>원장 불균형은 대사 실행마다 발견한 건수를 누적하는 카운터다. 현재 실행의 재검산 결과는
 * {@code reconciliation_runs.ledger_unbalanced_count}에 보존하고, Prometheus에는 16.4가 고정한
 * {@code ledger_unbalanced_total} 이름으로 발행한다.
 */
@Component
public class ReconciliationMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final Counter runs;
    private final Counter failures;
    private final Counter resolutions;
    private final Counter ledgerUnbalanced;
    private final AtomicLong openDiscrepancies = new AtomicLong();

    public ReconciliationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.runs = registry.counter("reconciliation.run");
        this.failures = registry.counter("reconciliation.run.failed");
        this.resolutions = registry.counter("reconciliation.resolved");
        // Micrometer Counter의 논리 이름 ledger.unbalanced는 Prometheus에서 정확히
        // ledger_unbalanced_total로 노출된다 (16.4).
        this.ledgerUnbalanced = registry.counter("ledger.unbalanced");
        registry.gauge("reconciliation.open", openDiscrepancies, AtomicLong::get);
        // 16.4가 고정한 지표는 불일치가 한 번도 없어도 노출돼야 한다. 지연 등록으로 두면
        // 정상 운영 중인 앱에서 reconciliation_mismatch_total 자체가 사라져, 대시보드·경보가
        // "지표 없음"과 "불일치 0"을 구분하지 못한다.
        for (DiscrepancyType type : DiscrepancyType.values()) {
            counters.put(type.name(), registry.counter("reconciliation.mismatch", "type", type.name()));
        }
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
        if (unbalanced > 0) {
            ledgerUnbalanced.increment(unbalanced);
        }
        openDiscrepancies.set(open);
    }
}
