package com.groupdrop.refund;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupdrop.payment.AbstractPaymentIntegrationTest;
import com.groupdrop.payment.PaymentFinalizer;
import com.groupdrop.payment.PaymentRepository;
import com.groupdrop.payment.StubPgClient;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 환불 실행 워커 (REF-02 실행 경로, 13.4의 {@code refund.requested}).
 *
 * <p>17.4의 "환불 성공 응답 유실"과 PG 명시 실패 시의 복귀 경로(10.2·10.3), 그리고 PAY-01 이중 결제
 * 보상 환불을 다룬다.
 */
class RefundExecutionTest extends AbstractPaymentIntegrationTest {

    private static final String ADMIN = "admin@groupdrop.test";

    @Autowired
    private RefundService refundService;
    @Autowired
    private PaymentFinalizer finalizer;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private TransactionTemplate transactions;

    @Test
    void 환불이_성공하면_결제와_주문이_REFUNDED가_되고_역분개가_남는다() {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        assertThat(outboxWorker.drain()).isEqualTo(1);

        Long refundId = requestRefund(paymentId);
        // 접수 시점: 아직 PG에 나가지 않았다.
        assertThat(refundStatus(refundId)).isEqualTo("REQUESTED");
        assertThat(paymentStatus(paymentId)).isEqualTo("REFUNDING");
        assertThat(orderStatus(order.orderId())).isEqualTo("REFUNDING");
        assertThat(pgClient.refundCount()).isZero();

        drainAll();

        assertThat(refundStatus(refundId)).isEqualTo("COMPLETED");
        assertThat(paymentStatus(paymentId)).isEqualTo("REFUNDED");
        assertThat(orderStatus(order.orderId())).isEqualTo("REFUNDED");
        assertThat(count("ledger_transactions",
                "transaction_type='REFUND' AND reference_id=" + refundId)).isEqualTo(1);
        // 환불은 재고를 복구하지 않는다 (5장, 12.1).
        assertThat(inventoryOf(order.inventoryId())).containsExactly(10, 9, 0, 1);
    }

    @Test
    void 환불_성공_응답이_유실되면_REQUESTED를_유지하고_재시도로_해소한다() {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        assertThat(outboxWorker.drain()).isEqualTo(1);

        pgClient.setRefundMode(StubPgClient.RefundMode.SUCCEED_BUT_TIMEOUT);
        Long refundId = requestRefund(paymentId);

        // 1차 실행: PG에는 환불이 남지만 응답이 유실된다. 실패로 단정하지 않는다 (PAY-03).
        outboxWorker.drain();
        assertThat(refundStatus(refundId)).isEqualTo("REQUESTED");
        assertThat(paymentStatus(paymentId)).isEqualTo("REFUNDING");
        assertThat(pgClient.providerRefundIdOf(providerPaymentIdOf(paymentId))).isNotNull();
        assertThat(count("outbox_events",
                "event_type='refund.requested' AND aggregate_id=" + refundId + " AND status='PENDING'"))
                .isEqualTo(1);

        // 재시도: 가상 PG가 providerPaymentId 기준 멱등이므로 저장된 결과가 재생되고 환불이 확정된다.
        backdateOutbox(refundId);
        drainAll();

        assertThat(refundStatus(refundId)).isEqualTo("COMPLETED");
        assertThat(paymentStatus(paymentId)).isEqualTo("REFUNDED");
        assertThat(orderStatus(order.orderId())).isEqualTo("REFUNDED");
        // 두 번 호출했지만 PG에서 실제 환불은 1건이다.
        assertThat(pgClient.refundCount()).isEqualTo(2);
        assertThat(count("refunds", "payment_id=" + paymentId)).isEqualTo(1);
        assertThat(count("ledger_transactions",
                "transaction_type='REFUND' AND reference_id=" + refundId)).isEqualTo(1);
    }

    @Test
    void PG가_환불_실패를_명시하면_결제는_SUCCEEDED_주문은_PAID로_복귀한다() {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        assertThat(outboxWorker.drain()).isEqualTo(1);

        pgClient.setRefundMode(StubPgClient.RefundMode.DECLINE);
        Long refundId = requestRefund(paymentId);
        drainAll();

        assertThat(refundStatus(refundId)).isEqualTo("FAILED");
        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT ops_hold FROM orders WHERE id=?", Boolean.class, order.orderId()))
                .isFalse();
        // 역분개는 없다 — 환불되지 않았으므로 원장도 그대로여야 한다.
        assertThat(count("ledger_transactions",
                "transaction_type='REFUND' AND reference_id=" + refundId)).isZero();
        // 실패한 환불 행은 부분 유니크 집합 밖이므로 재요청이 가능하다 (13.2).
        pgClient.setRefundMode(StubPgClient.RefundMode.SUCCEED);
        Long retryId = requestRefund(paymentId);
        drainAll();
        assertThat(refundStatus(retryId)).isEqualTo("COMPLETED");
        assertThat(paymentStatus(paymentId)).isEqualTo("REFUNDED");
    }

    @Test
    void 만료_출신_주문의_환불이_실패하면_PAID로_복귀시키지_않고_운영자에게_넘긴다() {
        OrderFixture order = order(10, 1);
        // 11.6: 만료가 이긴 뒤 결제 성공이 확인된 주문 (예약은 EXPIRED, 재고는 이미 풀렸다).
        pgClient.setDuringConfirm(() -> forceExpire(order));
        pgClient.setRefundMode(StubPgClient.RefundMode.DECLINE);
        Long paymentId = pay(order).body().id();

        drainAll();

        Long refundId = jdbc.queryForObject("SELECT id FROM refunds WHERE payment_id=?", Long.class, paymentId);
        assertThat(refundStatus(refundId)).isEqualTo("FAILED");
        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        // 10.2: 재고가 이미 방출됐으므로 PAID 복귀 금지. REFUNDING 유지 + 운영자 이관.
        assertThat(orderStatus(order.orderId())).isEqualTo("REFUNDING");
        assertThat(jdbc.queryForObject("SELECT ops_hold FROM orders WHERE id=?", Boolean.class, order.orderId()))
                .isTrue();
        assertThat(count("audit_logs", "action='REFUND_FAILED_OPS_HOLD' AND resource_id=" + order.orderId()))
                .isEqualTo(1);
    }

    @Test
    void 만료_경쟁_주문은_자동_환불로_REFUNDED까지_스스로_끝난다() {
        OrderFixture order = order(10, 1);
        pgClient.setDuringConfirm(() -> forceExpire(order));
        Long paymentId = pay(order).body().id();

        drainAll();

        assertThat(paymentStatus(paymentId)).isEqualTo("REFUNDED");
        assertThat(orderStatus(order.orderId())).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT ops_hold FROM orders WHERE id=?", Boolean.class, order.orderId()))
                .isFalse();
        // 결제 원장과 역분개가 쌍으로 남아 서로 상쇄한다 (LED-02 + LED-03).
        assertThat(count("ledger_transactions", "order_id=" + order.orderId())).isEqualTo(2);
        // 이미 풀린 재고를 도로 뺏지 않는다 (11.6).
        assertThat(inventoryOf(order.inventoryId())).containsExactly(10, 10, 0, 0);
    }

    @Test
    void PAY_01_이중_결제_패자는_자동_보상_환불로_종결하고_원장에는_남지_않는다() {
        OrderFixture order = order(10, 1);
        Long winnerId = pay(order).body().id();
        assertThat(outboxWorker.drain()).isEqualTo(1);

        // 예방 규칙의 경쟁 창을 뚫고 PG에서 2건이 승인된 상태를 결정적으로 재현한다.
        Long loserId = forceSecondApprovedPayment(order);

        assertThat(paymentStatus(loserId)).isEqualTo("SUPERSEDED");
        Long refundId = jdbc.queryForObject("SELECT id FROM refunds WHERE payment_id=?", Long.class, loserId);
        assertThat(jdbc.queryForObject("SELECT compensation FROM refunds WHERE id=?", Boolean.class, refundId))
                .isTrue();

        drainAll();

        assertThat(refundStatus(refundId)).isEqualTo("COMPLETED");
        // 패자는 수익 분해에 진입한 적이 없으므로 원장에 결제·역분개 어느 쪽도 없다 (PAY-01).
        assertThat(count("ledger_transactions", "reference_id=" + loserId)).isZero();
        assertThat(count("ledger_transactions", "transaction_type='REFUND' AND reference_id=" + refundId)).isZero();
        // 승자 결제와 주문은 멀쩡하다.
        assertThat(paymentStatus(winnerId)).isEqualTo("SUCCEEDED");
        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");
        assertThat(count("ledger_transactions",
                "transaction_type='PAYMENT' AND reference_id=" + winnerId)).isEqualTo(1);
    }

    // ---- 헬퍼 ----

    /**
     * 승자 결제가 이미 있는 주문에 PG가 두 번째 승인을 돌려준 상태를 결정적으로 재현한다
     * (PaymentConcurrencyTest의 보상 경로 픽스처와 같은 방식).
     */
    private Long forceSecondApprovedPayment(OrderFixture order) {
        Long loser = jdbc.queryForObject("""
                INSERT INTO payments(order_id,status,amount,created_at,updated_at)
                VALUES(?,'PROCESSING',?,now(),now()) RETURNING id
                """, Long.class, order.orderId(), order.totalAmount());
        transactions.executeWithoutResult(status -> finalizer.succeed(
                paymentRepository.findPayment(loser).orElseThrow(), "pg_duplicate_" + loser,
                Instant.now(), "test"));
        return loser;
    }

    private Long requestRefund(Long paymentId) {
        return refundService.requestRefund(ADMIN, paymentId, UUID.randomUUID().toString(),
                new CreateRefundRequest("테스트 환불")).body().id();
    }

    private String refundStatus(Long refundId) {
        return jdbc.queryForObject("SELECT status FROM refunds WHERE id=?", String.class, refundId);
    }

    private String providerPaymentIdOf(Long paymentId) {
        return jdbc.queryForObject("SELECT provider_payment_id FROM payments WHERE id=?", String.class, paymentId);
    }

    /** 재시도 백오프를 기다리지 않고 다음 폴링에서 바로 집히게 한다. */
    private void backdateOutbox(Long refundId) {
        jdbc.update("""
                UPDATE outbox_events SET available_at = now() - interval '1 minute'
                 WHERE event_type='refund.requested' AND aggregate_id=? AND status='PENDING'
                """, refundId);
    }

    private void drainAll() {
        while (outboxWorker.drain() > 0) {
            // refund.requested → refund.completed 연쇄
        }
    }

    /** 예약 만료 워커가 이겼을 때 남는 상태를 그대로 만든다 (11.6). */
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
