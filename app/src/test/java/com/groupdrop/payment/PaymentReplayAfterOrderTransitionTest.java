package com.groupdrop.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupdrop.common.ApiException;
import com.groupdrop.refund.CreateRefundRequest;
import com.groupdrop.refund.RefundService;
import java.sql.Timestamp;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PAY-02 결함: 주문·캠페인 상태 검사가 멱등 키 판정보다 앞서 상태 전이 이후 같은 키를 거절한다.
 * 재생 묶음은 상태 전이 뒤에도 최초 응답 또는 만료·금액 불일치·처리 중 판정을 보존해야 하므로 수정 전에는 실패한다.
 * 방어 묶음은 소유권·신규 키의 거부를 고정하며 수정 전후 모두 통과해야 한다.
 */
class PaymentReplayAfterOrderTransitionTest extends AbstractPaymentIntegrationTest {

    @Autowired
    private RefundService refundService;
    @Autowired
    private PaymentRecoveryService recoveryService;

    @Test
    void 재생_PAID_이후에도_최초_200_SUCCEEDED_응답을_그대로_반환한다() {
        OrderFixture order = order(10, 1);
        String key = key();
        PaymentService.Outcome first = request(order, key);
        assertThat(first.httpStatus()).isEqualTo(200);
        assertThat(first.body().status()).isEqualTo("SUCCEEDED");

        drainAndAssertPaid(order);

        assertReplayWithoutAdditionalPayment(order, key, first);
    }

    @Test
    void 재생_실제_환불로_REFUNDING이_돼도_최초_성공_응답을_반환한다() {
        OrderFixture order = order(10, 1);
        String key = key();
        PaymentService.Outcome first = request(order, key);
        assertThat(first.httpStatus()).isEqualTo(200);
        assertThat(first.body().status()).isEqualTo("SUCCEEDED");
        drainAndAssertPaid(order);

        RefundService.Outcome refund = refundService.requestRefund(ADMIN, first.body().id(), key(),
                new CreateRefundRequest("PAY-02 환불 이후 재생 검증"));
        assertThat(refund.httpStatus()).isEqualTo(202);
        assertThat(orderStatus(order.orderId())).isEqualTo("REFUNDING");
        assertThat(paymentStatus(first.body().id())).isEqualTo("REFUNDING");

        assertReplayWithoutAdditionalPayment(order, key, first);
    }

    @Test
    void 재생_새_키로_성공해_PAID가_돼도_최초_FAILED_키는_실패_응답을_반환한다() {
        OrderFixture order = order(10, 1);
        String failedKey = key();
        pgClient.setMode(StubPgClient.Mode.DECLINE);
        PaymentService.Outcome first = request(order, failedKey);
        assertThat(first.httpStatus()).isEqualTo(200);
        assertThat(first.body().status()).isEqualTo("FAILED");
        assertThat(first.body().failureCode()).isEqualTo("PG_DECLINED");
        outboxWorker.drain();
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");

        pgClient.setMode(StubPgClient.Mode.SUCCEED);
        PaymentService.Outcome succeeded = request(order, key());
        assertThat(succeeded.body().status()).isEqualTo("SUCCEEDED");
        assertThat(succeeded.body().id()).isNotEqualTo(first.body().id());
        drainAndAssertPaid(order);
        assertThat(count("payments", "order_id=" + order.orderId())).isEqualTo(2);

        assertReplayWithoutAdditionalPayment(order, failedKey, first);
    }

    @Test
    void 재생_UNKNOWN이_조회로_성공하고_PAID가_돼도_저장된_202_UNKNOWN을_반환한다() {
        OrderFixture order = order(10, 1);
        String key = key();
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        PaymentService.Outcome first = request(order, key);
        assertThat(first.httpStatus()).isEqualTo(202);
        assertThat(first.body().status()).isEqualTo("UNKNOWN");
        assertThat(paymentStatus(first.body().id())).isEqualTo("UNKNOWN");

        PaymentResponse resolved = recoveryService.resolveByProviderQuery(first.body().id());
        assertThat(resolved.status()).isEqualTo("SUCCEEDED");
        assertThat(paymentStatus(first.body().id())).isEqualTo("SUCCEEDED");
        drainAndAssertPaid(order);

        // 조회 복구 자체의 confirm은 허용하고, 그 뒤 같은 키 재요청의 추가 호출만 막는다.
        assertReplayWithoutAdditionalPayment(order, key, first);
    }

    @Test
    void 재생_PAID_이후_캠페인이_조기_강제_종료돼도_최초_성공_응답을_반환한다() {
        OrderFixture order = order(10, 1);
        String key = key();
        PaymentService.Outcome first = request(order, key);
        assertThat(first.httpStatus()).isEqualTo(200);
        assertThat(first.body().status()).isEqualTo("SUCCEEDED");
        drainAndAssertPaid(order);
        forceCloseCampaign(order);

        assertReplayWithoutAdditionalPayment(order, key, first);
    }

    @Test
    void 재생_결제_실패_후_실제_취소로_CANCELLED가_돼도_최초_FAILED_응답을_반환한다() {
        OrderFixture order = order(10, 1);
        String key = key();
        pgClient.setMode(StubPgClient.Mode.DECLINE);
        PaymentService.Outcome first = request(order, key);
        assertThat(first.httpStatus()).isEqualTo(200);
        assertThat(first.body().status()).isEqualTo("FAILED");
        assertThat(first.body().failureCode()).isEqualTo("PG_DECLINED");
        outboxWorker.drain();
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");

        assertThat(orderService.cancelOrder(BUYER, order.orderId()).status()).isEqualTo("CANCELLED");
        assertThat(orderStatus(order.orderId())).isEqualTo("CANCELLED");
        assertThat(paymentStatus(first.body().id())).isEqualTo("FAILED");

        assertReplayWithoutAdditionalPayment(order, key, first);
    }

    @Test
    void 재생_크래시_후_조회로_PAID가_돼도_미회수_키는_PROCESSING_사유로_거부한다() {
        OrderFixture order = order(10, 1);
        String key = key();
        // 기존 회수 테스트처럼 PG 호출 중 예외로 요청을 중단해 준비 트랜잭션의 선점만 남긴다.
        pgClient.setDuringConfirm(() -> {
            throw new IllegalStateException("테스트: PG 호출 중 프로세스 종료");
        });
        try {
            assertThatThrownBy(() -> request(order, key))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PG 호출 중 프로세스 종료");
        } finally {
            pgClient.setDuringConfirm(() -> { });
        }
        Long paymentId = jdbc.queryForObject("SELECT id FROM payments WHERE order_id=?", Long.class,
                order.orderId());
        assertThat(paymentStatus(paymentId)).isEqualTo("PROCESSING");
        var claimBefore = claimSnapshot(order, key);
        assertThat(claimBefore).containsEntry("status", "IN_PROGRESS")
                .containsEntry("resource_type", "PAYMENT").containsEntry("resource_id", paymentId)
                .containsEntry("response_status", null).containsEntry("response_body", null);

        // 스윕을 호출하지 않고 실제 조회와 Outbox만으로 결제·주문을 확정한다.
        PaymentResponse resolved = recoveryService.resolveByProviderQuery(paymentId);
        assertThat(resolved.status()).isEqualTo("SUCCEEDED");
        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        drainAndAssertPaid(order);
        assertThat(claimSnapshot(order, key)).isEqualTo(claimBefore);
        int confirmsBefore = pgClient.confirmCount();

        assertThatThrownBy(() -> request(order, key))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_REQUEST_IN_PROGRESS");
                    assertThat(exception.getStatus().value()).isEqualTo(409);
                    assertThat(exception.getDetail()).contains("PROCESSING");
                });

        assertThat(pgClient.confirmCount()).isEqualTo(confirmsBefore);
        assertThat(count("payments", "order_id=" + order.orderId())).isEqualTo(1);
        assertThat(claimSnapshot(order, key)).isEqualTo(claimBefore);
    }

    @Test
    void 방어_PAID_주문의_같은_키를_다른_구매자가_보내면_소유권_오류로_거부한다() {
        OrderFixture order = order(10, 1);
        String key = key();
        request(order, key);
        drainAndAssertPaid(order);
        int confirmsBefore = pgClient.confirmCount();

        assertThatThrownBy(() -> paymentService.requestPayment("buyer2@groupdrop.test", order.orderId(), key,
                new CreatePaymentRequest(order.totalAmount())))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("FORBIDDEN_NOT_OWNER"));

        assertThat(pgClient.confirmCount()).isEqualTo(confirmsBefore);
        assertThat(count("payments", "order_id=" + order.orderId())).isEqualTo(1);
    }

    @Test
    void 재생_PAID_주문의_같은_키에_다른_금액을_보내면_키_재사용으로_거부한다() {
        OrderFixture order = order(10, 1);
        String key = key();
        request(order, key);
        drainAndAssertPaid(order);
        int confirmsBefore = pgClient.confirmCount();
        var paymentsBefore = jdbc.queryForList("SELECT * FROM payments WHERE order_id=? ORDER BY id", order.orderId());

        // 해시는 요청 금액으로 만들므로 같은 키의 다른 금액은 요청 내용 불일치다 (PAY-02 "해시가 다르면 409").
        assertThatThrownBy(() -> paymentService.requestPayment(BUYER, order.orderId(), key,
                new CreatePaymentRequest(order.totalAmount() - 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));

        assertThat(pgClient.confirmCount()).isEqualTo(confirmsBefore);
        assertThat(count("payments", "order_id=" + order.orderId())).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM payments WHERE order_id=? ORDER BY id", order.orderId()))
                .isEqualTo(paymentsBefore);
    }

    @Test
    void 방어_PAID_주문의_새_키는_거부하고_멱등_선점을_남기지_않는다() {
        OrderFixture order = order(10, 1);
        request(order, key());
        drainAndAssertPaid(order);
        String newKey = key();
        int confirmsBefore = pgClient.confirmCount();

        assertThatThrownBy(() -> request(order, newKey))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("ORDER_NOT_PAYABLE"));

        assertThat(pgClient.confirmCount()).isEqualTo(confirmsBefore);
        assertThat(count("payments", "order_id=" + order.orderId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_requests WHERE scope=? AND idempotency_key=?",
                Long.class, scope(order), newKey)).isZero();
    }

    @Test
    void 정책_결제_실패로_PENDING_PAYMENT인_주문의_같은_키에_다른_금액을_보내면_키_재사용으로_거부한다() {
        OrderFixture order = order(10, 1);
        String key = key();
        pgClient.setMode(StubPgClient.Mode.DECLINE);
        assertThat(request(order, key).body().status()).isEqualTo("FAILED");
        outboxWorker.drain();
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
        int confirmsBefore = pgClient.confirmCount();

        // 주문이 아직 결제 가능해도 같은 키의 다른 금액은 요청 내용 불일치다. 수정 전에는 400 PAYMENT_AMOUNT_MISMATCH였다.
        assertThatThrownBy(() -> paymentService.requestPayment(BUYER, order.orderId(), key,
                new CreatePaymentRequest(order.totalAmount() - 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(pgClient.confirmCount()).isEqualTo(confirmsBefore);
        assertThat(count("payments", "order_id=" + order.orderId())).isEqualTo(1);
    }

    @Test
    void 방어_금액이_없으면_해시를_만들기_전에_400으로_거부하고_선점을_남기지_않는다() {
        OrderFixture order = order(10, 1);
        String key = key();

        // 해시가 요청 금액을 쓰므로 이 검사가 해시 계산 뒤로 밀리면 400이 아니라 NPE(500)가 된다.
        assertThatThrownBy(() -> paymentService.requestPayment(BUYER, order.orderId(), key,
                new CreatePaymentRequest(null)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PAYMENT_AMOUNT_REQUIRED"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_requests WHERE scope=? AND idempotency_key=?",
                Long.class, scope(order), key)).isZero();
        assertThat(pgClient.confirmCount()).isZero();
    }

    @Test
    void 방어_금액이_틀린_새_키는_400으로_거부하고_선점을_되돌려_같은_키로_다시_결제할_수_있다() {
        OrderFixture order = order(10, 1);
        String key = key();

        // 금액 검사는 선점 뒤에 있으므로, 거부되면 준비 트랜잭션 롤백이 방금 만든 선점까지 지워야 한다.
        assertThatThrownBy(() -> paymentService.requestPayment(BUYER, order.orderId(), key,
                new CreatePaymentRequest(order.totalAmount() - 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PAYMENT_AMOUNT_MISMATCH"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_requests WHERE scope=? AND idempotency_key=?",
                Long.class, scope(order), key)).isZero();
        assertThat(pgClient.confirmCount()).isZero();

        // 틀린 금액의 해시가 남았다면 이 요청은 IDEMPOTENCY_KEY_REUSED가 된다.
        PaymentService.Outcome retry = request(order, key);
        assertThat(retry.body().status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void 재생_PAID_주문의_만료된_키는_IDEMPOTENCY_KEY_EXPIRED로_거부한다() {
        OrderFixture order = order(10, 1);
        String key = key();
        request(order, key);
        drainAndAssertPaid(order);
        assertThat(jdbc.update("UPDATE idempotency_requests SET expires_at=? WHERE scope=? AND idempotency_key=?",
                Timestamp.from(clock.instant().minusSeconds(1)), scope(order), key)).isEqualTo(1);
        int confirmsBefore = pgClient.confirmCount();
        var paymentsBefore = jdbc.queryForList("SELECT * FROM payments WHERE order_id=? ORDER BY id", order.orderId());
        var claimBefore = claimSnapshot(order, key);
        assertThat(claimBefore).containsEntry("status", "COMPLETED").containsEntry("response_status", 200);
        assertThat(claimBefore.get("response_body")).isNotNull();

        assertThatThrownBy(() -> request(order, key))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_KEY_EXPIRED");
                    assertThat(exception.getStatus().value()).isEqualTo(409);
                });

        assertThat(pgClient.confirmCount()).isEqualTo(confirmsBefore);
        assertThat(count("payments", "order_id=" + order.orderId())).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM payments WHERE order_id=? ORDER BY id", order.orderId()))
                .isEqualTo(paymentsBefore);
        assertThat(claimSnapshot(order, key)).isEqualTo(claimBefore);
    }

    @Test
    void 방어_PAID_이후_캠페인이_조기_강제_종료되면_새_키는_CAMPAIGN_NOT_PAYABLE로_거부한다() {
        OrderFixture order = order(10, 1);
        request(order, key());
        drainAndAssertPaid(order);
        forceCloseCampaign(order);
        String newKey = key();
        int confirmsBefore = pgClient.confirmCount();

        assertThatThrownBy(() -> request(order, newKey))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("CAMPAIGN_NOT_PAYABLE"));

        assertThat(pgClient.confirmCount()).isEqualTo(confirmsBefore);
        assertThat(count("payments", "order_id=" + order.orderId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_requests WHERE scope=? AND idempotency_key=?",
                Long.class, scope(order), newKey)).isZero();
    }

    @Test
    void 재생_서로_다른_주문에_같은_키를_써도_PAID_이후_각자_응답을_반환한다() {
        OrderFixture firstOrder = order(10, 1);
        OrderFixture secondOrder = order(10, 2);
        String sharedKey = key();
        PaymentService.Outcome first = request(firstOrder, sharedKey);
        PaymentService.Outcome second = request(secondOrder, sharedKey);
        assertThat(first.httpStatus()).isEqualTo(200);
        assertThat(second.httpStatus()).isEqualTo(200);
        assertThat(first.body().status()).isEqualTo("SUCCEEDED");
        assertThat(second.body().status()).isEqualTo("SUCCEEDED");
        assertThat(first.body().orderId()).isEqualTo(firstOrder.orderId());
        assertThat(second.body().orderId()).isEqualTo(secondOrder.orderId());
        assertThat(first.body().amount()).isEqualTo(firstOrder.totalAmount());
        assertThat(second.body().amount()).isEqualTo(secondOrder.totalAmount());
        assertThat(second.body().id()).isNotEqualTo(first.body().id());
        assertThat(pgClient.confirmCount()).isEqualTo(2);

        outboxWorker.drain();
        assertThat(orderStatus(firstOrder.orderId())).isEqualTo("PAID");
        assertThat(orderStatus(secondOrder.orderId())).isEqualTo("PAID");

        assertReplayWithoutAdditionalPayment(firstOrder, sharedKey, first);
        assertReplayWithoutAdditionalPayment(secondOrder, sharedKey, second);
        assertThat(count("payments", "order_id=" + firstOrder.orderId())).isEqualTo(1);
        assertThat(count("payments", "order_id=" + secondOrder.orderId())).isEqualTo(1);
    }

    private PaymentService.Outcome request(OrderFixture order, String key) {
        return paymentService.requestPayment(BUYER, order.orderId(), key,
                new CreatePaymentRequest(order.totalAmount()));
    }

    private void drainAndAssertPaid(OrderFixture order) {
        outboxWorker.drain();
        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");
    }

    private void forceCloseCampaign(OrderFixture order) {
        assertThat(jdbc.update("""
                UPDATE campaigns SET status='CLOSED', closed_at=ends_at - interval '1 second', updated_at=?
                 WHERE id=?
                """, Timestamp.from(clock.instant()), order.campaignId())).isEqualTo(1);
    }

    private Map<String, Object> claimSnapshot(OrderFixture order, String key) {
        return jdbc.queryForMap("""
                SELECT status, response_status, response_body, expires_at, resource_type, resource_id
                  FROM idempotency_requests WHERE scope=? AND idempotency_key=?
                """, scope(order), key);
    }

    private void assertReplayWithoutAdditionalPayment(OrderFixture order, String key, PaymentService.Outcome first) {
        int confirmsBefore = pgClient.confirmCount();
        long paymentsBefore = count("payments", "order_id=" + order.orderId());

        PaymentService.Outcome replay = request(order, key);

        assertThat(replay.httpStatus()).isEqualTo(first.httpStatus());
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(pgClient.confirmCount()).isEqualTo(confirmsBefore);
        assertThat(count("payments", "order_id=" + order.orderId())).isEqualTo(paymentsBefore);
    }

    private String scope(OrderFixture order) {
        return "POST:/api/orders/" + order.orderId() + "/payments";
    }
}
