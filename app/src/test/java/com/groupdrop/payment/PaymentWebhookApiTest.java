package com.groupdrop.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** PAY-04 웹훅. S3(중복 웹훅), 11.5(역순 웹훅), 16.3(서명·타임스탬프)을 다룬다. */
class PaymentWebhookApiTest extends AbstractPaymentIntegrationTest {

    @Test
    void S3_동일_이벤트_ID의_웹훅_10회는_Inbox_1건과_전이_1회만_만든다() throws Exception {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        Long paymentId = pay(order).body().id();
        String providerPaymentId = pgClient.providerPaymentIdOf("mpay_" + paymentId);
        String eventId = "evt-dup-" + paymentId;
        String body = webhookPayload(eventId, providerPaymentId, order.orderId(), "SUCCEEDED", order.totalAmount());

        for (int i = 0; i < 10; i++) {
            postWebhook(body).andExpect(status().isOk());
        }

        assertThat(count("inbox_events", "provider_event_id='" + eventId + "'")).isEqualTo(1);

        while (inboxWorker.drain() > 0) {
            // 처리 완료까지
        }
        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        // 결제 확정 이벤트도 한 번만 발행돼야 한다 (11.4).
        assertThat(count("outbox_events",
                "aggregate_id=" + paymentId + " AND event_type='payment.finalized'")).isEqualTo(1);

        assertThat(outboxWorker.drain()).isEqualTo(1);
        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");
        assertThat(inventoryOf(order.inventoryId())).containsExactly(10, 9, 0, 1);
        // S3의 세 번째 어서션 (4주차 편입): 원장 거래도 정확히 1건이다 (LED-02).
        assertThat(count("ledger_transactions",
                "transaction_type='PAYMENT' AND reference_id=" + paymentId)).isEqualTo(1);
        assertThat(count("ledger_entries", "transaction_id IN (SELECT id FROM ledger_transactions"
                + " WHERE transaction_type='PAYMENT' AND reference_id=" + paymentId + ")")).isEqualTo(5);

        // 재전송이 더 와도 상태는 그대로다.
        postWebhook(body).andExpect(status().isOk());
        while (inboxWorker.drain() > 0) {
            // 처리 완료까지
        }
        while (outboxWorker.drain() > 0) {
            // 재전달된 확정 이벤트가 있어도 효과는 한 번이어야 한다
        }
        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");
        assertThat(inventoryOf(order.inventoryId())).containsExactly(10, 9, 0, 1);
        assertThat(count("ledger_transactions",
                "transaction_type='PAYMENT' AND reference_id=" + paymentId)).isEqualTo(1);
    }

    @Test
    void 중복_수신은_거부가_아니라_200_DUPLICATE로_응답한다() throws Exception {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        Long paymentId = pay(order).body().id();
        String body = webhookPayload("evt-ack-" + paymentId, pgClient.providerPaymentIdOf("mpay_" + paymentId),
                order.orderId(), "SUCCEEDED", order.totalAmount());

        postWebhook(body).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.result").value("ACCEPTED"));
        postWebhook(body).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.result").value("DUPLICATE"));
    }

    @Test
    void 역순_웹훅은_허용_전이표_밖이므로_무시하고_감사로그만_남긴다() throws Exception {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        Long paymentId = pay(order).body().id();
        String providerPaymentId = pgClient.providerPaymentIdOf("mpay_" + paymentId);

        // 11.5: SUCCEEDED를 먼저 받고, 더 오래된 PROCESSING을 나중에 받는다.
        postWebhook(webhookPayload("evt-rev-ok-" + paymentId, providerPaymentId, order.orderId(), "SUCCEEDED",
                order.totalAmount(), Instant.now().toString()));
        while (inboxWorker.drain() > 0) {
            // 처리 완료까지
        }
        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");

        postWebhook(webhookPayload("evt-rev-old-" + paymentId, providerPaymentId, order.orderId(), "PROCESSING",
                order.totalAmount(), Instant.now().minusSeconds(600).toString()));
        while (inboxWorker.drain() > 0) {
            // 처리 완료까지
        }

        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM inbox_events WHERE provider_event_id=?", String.class,
                "evt-rev-old-" + paymentId)).isEqualTo("IGNORED");
        assertThat(count("audit_logs",
                "action='WEBHOOK_IGNORED' AND resource_id=" + paymentId)).isPositive();
    }

    @Test
    void 서명이_틀린_웹훅은_401이고_Inbox에_적재되지_않는다() throws Exception {
        OrderFixture order = order(10, 1);
        String eventId = "evt-badsig-" + order.orderId();
        String body = webhookPayload(eventId, "pg_x", order.orderId(), "SUCCEEDED", order.totalAmount());

        postWebhook(body, "deadbeef", String.valueOf(Instant.now().getEpochSecond()))
                .andExpect(status().isUnauthorized());

        assertThat(count("inbox_events", "provider_event_id='" + eventId + "'")).isZero();
    }

    @Test
    void 타임스탬프가_5분_이상_어긋나면_거부한다() throws Exception {
        OrderFixture order = order(10, 1);
        String eventId = "evt-stale-" + order.orderId();
        String body = webhookPayload(eventId, "pg_y", order.orderId(), "SUCCEEDED", order.totalAmount());
        String staleTimestamp = String.valueOf(Instant.now().minusSeconds(600).getEpochSecond());

        // 서명 자체는 유효하다 — 거부 사유는 재생 방지를 위한 타임스탬프 창이다 (16.3).
        postWebhook(body, verifier.sign(staleTimestamp, body), staleTimestamp)
                .andExpect(status().isUnauthorized());

        assertThat(count("inbox_events", "provider_event_id='" + eventId + "'")).isZero();
    }

    @Test
    void 정확히_5분_어긋난_타임스탬프도_거부한다() {
        Instant now = Instant.parse("2026-08-19T00:00:00Z");
        com.groupdrop.common.GroupdropProperties properties =
                org.mockito.Mockito.mock(com.groupdrop.common.GroupdropProperties.class);
        org.mockito.Mockito.when(properties.webhook()).thenReturn(
                new com.groupdrop.common.GroupdropProperties.Webhook("boundary-secret", Duration.ofMinutes(5)));
        WebhookSignatureVerifier boundaryVerifier = new WebhookSignatureVerifier(
                properties, Clock.fixed(now, ZoneOffset.UTC));
        String timestamp = String.valueOf(now.minus(Duration.ofMinutes(5)).getEpochSecond());
        String body = "{\"eventId\":\"exact-boundary\"}";

        assertThat(boundaryVerifier.verify(boundaryVerifier.sign(timestamp, body), timestamp, body))
                .isEqualTo(WebhookSignatureVerifier.Verdict.STALE_TIMESTAMP);
    }

    @Test
    void 확정된_결제에_다른_종국상태_웹훅이_오면_Inbox를_IGNORED로_종결한다() throws Exception {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        Long paymentId = pay(order).body().id();
        String providerPaymentId = pgClient.providerPaymentIdOf("mpay_" + paymentId);

        postWebhook(webhookPayload("evt-final-success-" + paymentId, providerPaymentId, order.orderId(),
                "SUCCEEDED", order.totalAmount())).andExpect(status().isOk());
        while (inboxWorker.drain() > 0) {
            // 성공 확정
        }

        String forbiddenEventId = "evt-final-failed-" + paymentId;
        postWebhook(webhookPayload(forbiddenEventId, providerPaymentId, order.orderId(),
                "FAILED", order.totalAmount())).andExpect(status().isOk());
        while (inboxWorker.drain() > 0) {
            // 금지 전이 무시
        }

        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM inbox_events WHERE provider_event_id=?", String.class,
                forbiddenEventId)).isEqualTo("IGNORED");
        assertThat(count("audit_logs", "action='WEBHOOK_IGNORED' AND resource_id=" + paymentId)).isPositive();
    }

    @Test
    void 이미_SUPERSEDED인_패자에_별도_성공_웹훅이_오면_Inbox를_IGNORED로_종결한다() throws Exception {
        OrderFixture order = order(10, 1);
        Long winnerId = pay(order).body().id();
        assertThat(paymentStatus(winnerId)).isEqualTo("SUCCEEDED");

        String loserProviderId = "pg_already_superseded_" + winnerId;
        Long loserId = jdbc.queryForObject("""
                INSERT INTO payments(order_id,status,amount,provider_payment_id,failure_code,created_at,updated_at)
                VALUES(?,'SUPERSEDED',?,?,'DUPLICATE_PAYMENT',now(),now()) RETURNING id
                """, Long.class, order.orderId(), order.totalAmount(), loserProviderId);
        String eventId = "evt-already-superseded-" + loserId;

        postWebhook(webhookPayload(eventId, loserProviderId, order.orderId(),
                "SUCCEEDED", order.totalAmount())).andExpect(status().isOk());
        while (inboxWorker.drain() > 0) {
            // 이미 종결된 패자의 금지 전이 무시
        }

        assertThat(paymentStatus(loserId)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM inbox_events WHERE provider_event_id=?", String.class,
                eventId)).isEqualTo("IGNORED");
        assertThat(count("audit_logs", "action='WEBHOOK_IGNORED' AND resource_id=" + loserId)).isPositive();
    }

    @Test
    void 금액이_다른_웹훅은_적용하지_않고_무시한다() throws Exception {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        Long paymentId = pay(order).body().id();
        String eventId = "evt-amount-" + paymentId;

        postWebhook(webhookPayload(eventId, pgClient.providerPaymentIdOf("mpay_" + paymentId), order.orderId(),
                "SUCCEEDED", order.totalAmount() + 1_000)).andExpect(status().isOk());
        while (inboxWorker.drain() > 0) {
            // 처리 완료까지
        }

        assertThat(paymentStatus(paymentId)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT status FROM inbox_events WHERE provider_event_id=?", String.class,
                eventId)).isEqualTo("IGNORED");
    }

    @Test
    void providerPaymentId를_모르는_결제도_주문으로_이어_복구한다() throws Exception {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        Long paymentId = pay(order).body().id();
        assertThat(jdbc.queryForObject("SELECT provider_payment_id FROM payments WHERE id=?", String.class,
                paymentId)).isNull();

        postWebhook(webhookPayload("evt-byorder-" + paymentId, "pg_unknown_" + paymentId, order.orderId(),
                "SUCCEEDED", order.totalAmount())).andExpect(status().isOk());
        while (inboxWorker.drain() > 0) {
            // 처리 완료까지
        }

        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT provider_payment_id FROM payments WHERE id=?", String.class,
                paymentId)).isEqualTo("pg_unknown_" + paymentId);
    }
}
