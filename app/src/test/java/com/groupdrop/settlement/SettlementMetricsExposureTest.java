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
        reconciliations.run(0);

        assertThat(meterRegistry.counter("settlement.failed").count()).isEqualTo(failedBefore + 1.0);
        assertThat(meterRegistry.counter("settlement.completed").count()).isPositive();
        assertThat(meterRegistry.counter("settlement.recovered").count()).isPositive();
        assertThat(meterRegistry.counter("reconciliation.run").count()).isPositive();
        assertThat(meterRegistry.get("ledger.unbalanced").gauge().value()).isNotNegative();
        assertThat(meterRegistry.get("settlement.unrecovered.adjustments").gauge().value()).isNotNegative();

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("settlement_failed_total")))
                .andExpect(content().string(containsString("settlement_completed_total")))
                .andExpect(content().string(containsString("settlement_determined_total")))
                .andExpect(content().string(containsString("settlement_recovered_total")))
                .andExpect(content().string(containsString("settlement_unrecovered_adjustments")))
                .andExpect(content().string(containsString("settlement_batches_blocked")))
                .andExpect(content().string(containsString("ledger_unbalanced")))
                .andExpect(content().string(containsString("reconciliation_run_total")))
                .andExpect(content().string(containsString("reconciliation_open")));
    }
}
