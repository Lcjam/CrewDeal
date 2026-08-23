package com.groupdrop.payment;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.groupdrop.outbox.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 3주차 완료 기준: 결제 지표가 /actuator/prometheus에 노출된다 (16.4). */
class PaymentMetricsExposureTest extends AbstractPaymentIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private PaymentRecoveryService recoveryService;

    @Test
    void 결제_확정과_메시징_지표가_prometheus에_노출된다() throws Exception {
        double attemptsBefore = meterRegistry.counter("payment.attempt").count();
        OrderFixture succeeded = order(10, 1);
        pay(succeeded);
        outboxWorker.drain();

        OrderFixture declined = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.DECLINE);
        pay(declined);
        outboxWorker.drain();

        OrderFixture unknown = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        PaymentService.Outcome timedOut = pay(unknown);
        recoveryService.resolveByProviderQuery(timedOut.body().id());

        pgClient.setMode(StubPgClient.Mode.SUCCEED);
        OrderFixture idempotent = order(10, 1);
        String idempotencyKey = key();
        PaymentService.Outcome first = paymentService.requestPayment(BUYER, idempotent.orderId(), idempotencyKey,
                new CreatePaymentRequest(idempotent.totalAmount()));
        paymentService.requestPayment(BUYER, idempotent.orderId(), idempotencyKey,
                new CreatePaymentRequest(idempotent.totalAmount()));

        String eventId = "evt-metrics-" + first.body().id();
        String webhook = webhookPayload(eventId, pgClient.providerPaymentIdOf("mpay_" + first.body().id()),
                idempotent.orderId(), "SUCCEEDED", idempotent.totalAmount());
        postWebhook(webhook).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("ACCEPTED"));
        postWebhook(webhook).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("DUPLICATE"));

        // 아직 처리 대상이 아닌 PENDING 행도 poll/drain 시 DB 기준으로 관측한다.
        Long pendingId = outboxRepository.append("payment.finalized", "PAYMENT", -1L, "{}",
                Instant.now().minusSeconds(2));
        jdbc.update("UPDATE outbox_events SET available_at = created_at + interval '1 hour' WHERE id=?", pendingId);
        outboxWorker.drain();

        // 요청 4회 + UNKNOWN provider-query 복구 1회. 후자는 request 경로 밖의 PG 호출이다.
        assertThat(meterRegistry.counter("payment.attempt").count()).isGreaterThanOrEqualTo(attemptsBefore + 5.0);
        assertThat(meterRegistry.counter("payment.duplicate.prevented").count()).isGreaterThanOrEqualTo(1.0);
        assertThat(meterRegistry.counter("webhook.duplicate").count()).isGreaterThanOrEqualTo(1.0);
        assertThat(meterRegistry.get("outbox.pending.count").gauge().value()).isGreaterThanOrEqualTo(1.0);
        assertThat(meterRegistry.get("outbox.oldest.event.age").gauge().value()).isGreaterThan(0.0);

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                // 확정 경로(source)를 태그로 구분할 수 있어야 복구가 어느 경로로 일어났는지 말할 수 있다.
                .andExpect(content().string(containsString("payment_succeeded_total")))
                .andExpect(content().string(containsString("source=\"request\"")))
                .andExpect(content().string(containsString("payment_failed_total")))
                .andExpect(content().string(containsString("payment_unknown_total")))
                .andExpect(content().string(containsString("payment_attempt_total")))
                .andExpect(content().string(containsString("payment_duplicate_prevented_total")))
                .andExpect(content().string(containsString("webhook_duplicate_total")))
                .andExpect(content().string(containsString("payment_pg_confirm_duration_seconds")))
                .andExpect(content().string(containsString("outbox_event_processed_total")))
                .andExpect(content().string(containsString("outbox_pending_count")))
                .andExpect(content().string(containsString("outbox_oldest_event_age")))
                .andExpect(content().string(containsString("event=\"payment.finalized\"")));
    }
}
