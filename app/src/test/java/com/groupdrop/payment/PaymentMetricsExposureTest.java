package com.groupdrop.payment;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/** 3주차 완료 기준: 결제 지표가 /actuator/prometheus에 노출된다 (16.4). */
class PaymentMetricsExposureTest extends AbstractPaymentIntegrationTest {

    @Test
    void 결제_확정과_메시징_지표가_prometheus에_노출된다() throws Exception {
        OrderFixture succeeded = order(10, 1);
        pay(succeeded);
        outboxWorker.drain();

        OrderFixture declined = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.DECLINE);
        pay(declined);
        outboxWorker.drain();

        OrderFixture unknown = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        pay(unknown);

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                // 확정 경로(source)를 태그로 구분할 수 있어야 복구가 어느 경로로 일어났는지 말할 수 있다.
                .andExpect(content().string(containsString("payment_succeeded_total")))
                .andExpect(content().string(containsString("source=\"request\"")))
                .andExpect(content().string(containsString("payment_failed_total")))
                .andExpect(content().string(containsString("payment_unknown_total")))
                .andExpect(content().string(containsString("payment_pg_confirm_duration_seconds")))
                .andExpect(content().string(containsString("outbox_event_processed_total")))
                .andExpect(content().string(containsString("event=\"payment.finalized\"")));
    }
}
