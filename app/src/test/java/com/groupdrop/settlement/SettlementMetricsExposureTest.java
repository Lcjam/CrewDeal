package com.groupdrop.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.groupdrop.payment.AbstractPaymentIntegrationTest;
import com.groupdrop.reconciliation.ReconciliationService;
import com.groupdrop.refund.CreateRefundRequest;
import com.groupdrop.refund.RefundService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 5주차 완료 기준: 정산·대사 지표가 {@code /actuator/prometheus}에 노출된다 (16.4).
 *
 * <p>기획서가 이름까지 못박은 것은 {@code settlement_failed_total}과 {@code ledger_unbalanced_total} 둘이다.
 */
class SettlementMetricsExposureTest extends AbstractPaymentIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private SettlementService settlementService;
    @Autowired
    private SettlementFailureInjector failureInjector;
    @Autowired
    private ReconciliationService reconciliations;
    @Autowired
    private RefundService refundService;

    @AfterEach
    void disarmInjector() {
        failureInjector.disarm();
    }

    @Test
    void 정산과_대사_지표가_prometheus에_노출된다() throws Exception {
        double failedBefore = meterRegistry.counter("settlement.failed").count();

        // 지급 실패 → 재시도 → 완료, 그리고 정산 후 환불의 회수까지 한 번씩 통과시킨다.
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        assertThat(outboxWorker.drain()).isEqualTo(1);
        closeCampaign(order.campaignId(), 8);

        failureInjector.armOnce();
        settlementService.run(ADMIN, order.campaignId());
        Long failedBatchId = jdbc.queryForObject(
                "SELECT id FROM settlement_batches WHERE campaign_id=? AND status='FAILED'",
                Long.class, order.campaignId());
        settlementService.retry(ADMIN, failedBatchId);

        refundService.requestRefund(ADMIN, paymentId, UUID.randomUUID().toString(),
                new CreateRefundRequest("정산 후 환불"));
        while (outboxWorker.drain() > 0) {
            // 환불 실행 → 역분개 → 회수까지 소진
        }
        long unbalancedBefore = unbalancedTransactions();
        insertUnbalancedTransaction();
        double ledgerMetricBefore = meterRegistry.counter("ledger.unbalanced").count();
        reconciliations.run(0);

        assertThat(meterRegistry.counter("settlement.failed").count()).isEqualTo(failedBefore + 1.0);
        assertThat(meterRegistry.counter("settlement.completed").count()).isPositive();
        assertThat(meterRegistry.counter("settlement.recovered").count()).isPositive();
        assertThat(meterRegistry.counter("reconciliation.run").count()).isPositive();
        double expectedLedgerMetric = ledgerMetricBefore + unbalancedBefore + 1.0;
        assertThat(meterRegistry.counter("ledger.unbalanced").count()).isEqualTo(expectedLedgerMetric);
        assertThat(meterRegistry.get("settlement.unrecovered.adjustments").gauge().value()).isNotNegative();

        MvcResult prometheus = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("settlement_failed_total")))
                .andExpect(content().string(containsString("settlement_completed_total")))
                .andExpect(content().string(containsString("settlement_determined_total")))
                .andExpect(content().string(containsString("settlement_recovered_total")))
                .andExpect(content().string(containsString("settlement_unrecovered_adjustments")))
                .andExpect(content().string(containsString("settlement_batches_blocked")))
                // HELP 설명이 아니라 실제 Prometheus 샘플의 정확한 지표명을 검사한다.
                .andExpect(content().string(containsString("\nledger_unbalanced_total ")))
                .andExpect(content().string(containsString("reconciliation_run_total")))
                .andExpect(content().string(containsString("reconciliation_open")))
                .andReturn();
        String sample = prometheus.getResponse().getContentAsString().lines()
                .filter(line -> line.startsWith("ledger_unbalanced_total "))
                .findFirst().orElseThrow();
        assertThat(Double.parseDouble(sample.substring(sample.indexOf(' ') + 1)))
                .isEqualTo(expectedLedgerMetric);
    }

    private long unbalancedTransactions() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT t.id FROM ledger_transactions t
                      LEFT JOIN ledger_entries e ON e.transaction_id=t.id
                     GROUP BY t.id
                    HAVING COALESCE(SUM(CASE WHEN e.side='DEBIT' THEN e.amount ELSE 0 END),0)
                        <> COALESCE(SUM(CASE WHEN e.side='CREDIT' THEN e.amount ELSE 0 END),0)
                        OR count(e.id)=0
                ) x
                """, Long.class);
    }

    private void insertUnbalancedTransaction() {
        long referenceId = -Math.max(1L, Math.abs(UUID.randomUUID().getMostSignificantBits()));
        Long transactionId = jdbc.queryForObject("""
                INSERT INTO ledger_transactions
                    (transaction_type,reference_type,reference_id,occurred_at,created_at)
                VALUES('PAYMENT','PAYMENT',?,now(),now()) RETURNING id
                """, Long.class, referenceId);
        Long accountId = jdbc.queryForObject("SELECT id FROM ledger_accounts ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO ledger_entries(transaction_id,account_id,side,amount)
                VALUES(?,?,'DEBIT',1)
                """, transactionId, accountId);
    }
}
