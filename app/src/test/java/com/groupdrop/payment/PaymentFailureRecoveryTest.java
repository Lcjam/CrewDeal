package com.groupdrop.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.groupdrop.order.ReservationExpiryService;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PAY-03 결과 불명 결제와 그 해소 경로. S4-a(성공 응답 유실 후 웹훅·조회 복구)와
 * 고아 스윕, ORD-03 만료 유예, 11.6 경쟁을 다룬다.
 */
class PaymentFailureRecoveryTest extends AbstractPaymentIntegrationTest {

    @Autowired
    private PaymentRecoveryService recoveryService;
    @Autowired
    private OrphanPaymentSweeper sweeper;
    @Autowired
    private ReservationExpiryService expiryService;

    @Test
    void 성공_응답_유실은_UNKNOWN으로_기록하고_202로_응답한다() {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);

        PaymentService.Outcome outcome = pay(order);

        assertThat(outcome.httpStatus()).isEqualTo(202);
        assertThat(outcome.body().status()).isEqualTo("UNKNOWN");
        // 타임아웃은 실패가 아니므로 아직 확정 이벤트가 없어야 한다.
        assertThat(count("outbox_events", "aggregate_id=" + outcome.body().id())).isZero();
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT outcome FROM payment_attempts WHERE payment_id=?", String.class,
                outcome.body().id())).isEqualTo("TIMEOUT");
    }

    @Test
    void S4a_웹훅_경로로_UNKNOWN_결제를_복구한다() throws Exception {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        PaymentService.Outcome outcome = pay(order);
        Long paymentId = outcome.body().id();
        String providerPaymentId = pgClient.providerPaymentIdOf("mpay_" + paymentId);

        postWebhook(webhookPayload("evt-recover-" + paymentId, providerPaymentId, order.orderId(), "SUCCEEDED",
                order.totalAmount())).andExpect(status().isOk());

        assertThat(paymentStatus(paymentId)).isEqualTo("UNKNOWN");
        assertThat(inboxWorker.drain()).isEqualTo(1);
        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT provider_payment_id FROM payments WHERE id=?", String.class,
                paymentId)).isEqualTo(providerPaymentId);

        assertThat(outboxWorker.drain()).isEqualTo(1);
        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");
        assertThat(inventoryOf(order.inventoryId())).containsExactly(10, 9, 0, 1);
    }

    @Test
    void S4a_조회_경로로_UNKNOWN_결제를_복구한다() {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        PaymentService.Outcome outcome = pay(order);
        Long paymentId = outcome.body().id();
        assertThat(paymentStatus(paymentId)).isEqualTo("UNKNOWN");

        // 같은 merchantPaymentId 재호출이 곧 조회다 (14.5). 새 결제가 만들어지면 안 된다.
        PaymentResponse resolved = recoveryService.resolveByProviderQuery(paymentId);

        assertThat(resolved.status()).isEqualTo("SUCCEEDED");
        assertThat(count("payments", "order_id=" + order.orderId())).isEqualTo(1);
        assertThat(outboxWorker.drain()).isEqualTo(1);
        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");
    }

    @Test
    void PG에_승인이_없는_타임아웃은_조회로_UNKNOWN을_유지한다() {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.TIMEOUT_NO_CHARGE);
        Long paymentId = pay(order).body().id();

        PaymentResponse resolved = recoveryService.resolveByProviderQuery(paymentId);

        // 확정할 수 없으면 UNKNOWN을 유지한다. 조회 실패를 실패로 단정하지 않는다.
        assertThat(resolved.status()).isEqualTo("UNKNOWN");
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void 고아_PROCESSING_결제는_스윕이_UNKNOWN으로_회수한다() {
        OrderFixture order = order(10, 1);
        // 요청 스레드가 결과를 기록하지 못하고 죽은 상태를 재현한다 (11.3).
        Long paymentId = jdbc.queryForObject("""
                INSERT INTO payments(order_id,status,amount,created_at,updated_at)
                VALUES(?,'PROCESSING',?,now(),now()) RETURNING id
                """, Long.class, order.orderId(), order.totalAmount());
        jdbc.update("""
                INSERT INTO payment_attempts(payment_id,merchant_payment_id,amount,outcome,requested_at)
                VALUES(?,?,?,'REQUESTED',now())
                """, paymentId, "mpay_" + paymentId, order.totalAmount());
        backdatePayment(paymentId, 3600);

        assertThat(sweeper.sweep()).isPositive();

        assertThat(paymentStatus(paymentId)).isEqualTo("UNKNOWN");
        // 결과를 모르는 단계이므로 아직 확정 이벤트는 없다.
        assertThat(count("outbox_events", "aggregate_id=" + paymentId)).isZero();
    }

    @Test
    void PG_호출_기록이_없는_READY_고아는_스윕이_FAILED로_확정한다() {
        OrderFixture order = order(10, 1);
        Long paymentId = jdbc.queryForObject("""
                INSERT INTO payments(order_id,status,amount,created_at,updated_at)
                VALUES(?,'READY',?,now(),now()) RETURNING id
                """, Long.class, order.orderId(), order.totalAmount());
        backdatePayment(paymentId, 3600);

        assertThat(sweeper.sweep()).isPositive();

        assertThat(paymentStatus(paymentId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM payments WHERE id=?", String.class, paymentId))
                .isEqualTo("ORPHAN_READY");
        // 13.4: 고아 스윕도 확정 트랜잭션이므로 payment.finalized를 발행한다.
        assertThat(count("outbox_events", "aggregate_id=" + paymentId + " AND event_type='payment.finalized'"))
                .isEqualTo(1);
    }

    @Test
    void 임계_시간_전의_PROCESSING_결제는_스윕이_건드리지_않는다() {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        Long paymentId = pay(order).body().id();
        jdbc.update("UPDATE payments SET status='PROCESSING' WHERE id=?", paymentId);

        sweeper.sweep();
        assertThat(paymentStatus(paymentId)).isEqualTo("PROCESSING");
    }

    @Test
    void ORD03_만료_유예는_UNKNOWN_결제가_붙은_주문을_만료시키지_않는다() {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        Long paymentId = pay(order).body().id();
        assertThat(paymentStatus(paymentId)).isEqualTo("UNKNOWN");
        jdbc.update("UPDATE orders SET expires_at=? WHERE id=?",
                Timestamp.from(Instant.now().minusSeconds(60)), order.orderId());

        expiryService.expireDueReservations();

        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(inventoryOf(order.inventoryId())).containsExactly(10, 9, 1, 0);

        // 결제가 실패로 확정되면 유예가 풀리고 정상적으로 만료된다.
        jdbc.update("UPDATE payments SET status='FAILED' WHERE id=?", paymentId);
        assertThat(expiryService.expireDueReservations()).isPositive();
        assertThat(orderStatus(order.orderId())).isEqualTo("EXPIRED");
        assertThat(inventoryOf(order.inventoryId())).containsExactly(10, 10, 0, 0);
    }

    @Test
    void 만료가_이긴_뒤_결제_성공이_확인되면_REFUNDING으로_전환하고_운영자에게_넘긴다() {
        OrderFixture order = order(10, 1);
        // 11.6: 만료 판정과 결제 확정이 겹치는 좁은 창. 만료가 먼저 커밋된 상태를 재현한다.
        pgClient.setDuringConfirm(() -> forceExpire(order));

        PaymentService.Outcome outcome = pay(order);
        assertThat(outcome.body().status()).isEqualTo("SUCCEEDED");

        assertThat(outboxWorker.drain()).isEqualTo(1);

        assertThat(orderStatus(order.orderId())).isEqualTo("REFUNDING");
        assertThat(jdbc.queryForObject("SELECT ops_hold FROM orders WHERE id=?", Boolean.class, order.orderId()))
                .isTrue();
        assertThat(count("audit_logs", "action='ORDER_EXPIRED_BUT_PAID' AND resource_id=" + order.orderId()))
                .isEqualTo(1);
        // 이미 풀린 재고를 도로 뺏지 않는다.
        assertThat(inventoryOf(order.inventoryId())).containsExactly(10, 10, 0, 0);
    }

    /** 예약 만료 워커가 이겼을 때 남는 상태를 그대로 만든다 (ReservationExpiryService와 같은 효과). */
    private void forceExpire(OrderFixture order) {
        jdbc.update("UPDATE orders SET status='EXPIRED', updated_at=now() WHERE id=?", order.orderId());
        jdbc.update("""
                UPDATE stock_reservations sr SET status='EXPIRED', updated_at=now()
                  FROM order_items oi
                 WHERE sr.order_item_id=oi.id AND oi.order_id=? AND sr.status='ACTIVE'
                """, order.orderId());
        jdbc.update("""
                UPDATE campaign_inventories
                   SET available_quantity=available_quantity+reserved_quantity, reserved_quantity=0
                 WHERE id=?
                """, order.inventoryId());
    }

}
