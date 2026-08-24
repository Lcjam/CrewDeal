package com.groupdrop.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupdrop.common.ApiException;
import org.junit.jupiter.api.Test;

/** PAY-01 결제 승인과 PAY-02 멱등성 정책. */
class PaymentApiTest extends AbstractPaymentIntegrationTest {

    @Test
    void 결제_성공은_주문을_PAID로_바꾸고_예약을_확정한다() {
        OrderFixture order = order(10, 2);

        PaymentService.Outcome outcome = pay(order);

        assertThat(outcome.httpStatus()).isEqualTo(200);
        assertThat(outcome.body().status()).isEqualTo("SUCCEEDED");
        assertThat(outcome.body().providerPaymentId()).isNotBlank();
        assertThat(outcome.body().amount()).isEqualTo(39_800L);

        // 주문 후처리는 동기 응답 경로에서도 Outbox를 지난다 (13.4의 단일 코드 경로).
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(count("outbox_events", "event_type='payment.finalized' AND status='PENDING'")).isEqualTo(1);

        assertThat(outboxWorker.drain()).isEqualTo(1);
        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");
        assertThat(count("stock_reservations sr JOIN order_items oi ON oi.id=sr.order_item_id",
                "oi.order_id=" + order.orderId() + " AND sr.status='CONFIRMED'")).isEqualTo(1);
        assertThat(inventoryOf(order.inventoryId())).containsExactly(10, 8, 0, 2);
    }

    @Test
    void 결제_거절은_주문을_유지해_재시도를_허용한다() {
        OrderFixture order = order(10, 2);
        pgClient.setMode(StubPgClient.Mode.DECLINE);

        PaymentService.Outcome outcome = pay(order);

        assertThat(outcome.body().status()).isEqualTo("FAILED");
        assertThat(outcome.body().failureCode()).isEqualTo("PG_DECLINED");

        outboxWorker.drain();
        // 만료 시각 전이므로 예약을 풀지 않는다 (ORD-03, 8.2-7).
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(inventoryOf(order.inventoryId())).containsExactly(10, 8, 2, 0);

        pgClient.setMode(StubPgClient.Mode.SUCCEED);
        PaymentService.Outcome retry = pay(order);

        assertThat(retry.body().status()).isEqualTo("SUCCEEDED");
        assertThat(count("payments", "order_id=" + order.orderId())).isEqualTo(2);
        outboxWorker.drain();
        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");
    }

    @Test
    void 결제_금액이_주문_금액과_다르면_거부한다() {
        OrderFixture order = order(10, 2);

        assertThatThrownBy(() -> paymentService.requestPayment(BUYER, order.orderId(), key(),
                new CreatePaymentRequest(order.totalAmount() - 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PAYMENT_AMOUNT_MISMATCH"));
        assertThat(count("payments", "order_id=" + order.orderId())).isZero();
        assertThat(pgClient.confirmCount()).isZero();
    }

    @Test
    void 진행_중인_결제가_있으면_다른_멱등키의_신규_결제를_거부한다() {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        PaymentService.Outcome first = pay(order);
        assertThat(first.body().status()).isEqualTo("UNKNOWN");

        // PAY-01: 브라우저 탭 2개처럼 서로 다른 키로 들어오는 동시 결제는 멱등 키가 아니라 이 규칙이 막는다.
        assertThatThrownBy(() -> paymentService.requestPayment(BUYER, order.orderId(), key(),
                new CreatePaymentRequest(order.totalAmount())))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PAYMENT_ALREADY_IN_PROGRESS"));
        assertThat(count("payments", "order_id=" + order.orderId())).isEqualTo(1);
    }

    @Test
    void 이미_결제된_주문은_추가_결제를_거부한다() {
        OrderFixture order = order(10, 1);
        pay(order);
        outboxWorker.drain();

        assertThatThrownBy(() -> paymentService.requestPayment(BUYER, order.orderId(), key(),
                new CreatePaymentRequest(order.totalAmount())))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("ORDER_NOT_PAYABLE"));
    }

    @Test
    void 자연_종료_CLOSED_캠페인의_종료_직전_주문은_결제를_허용한다() {
        OrderFixture order = order(10, 1);
        jdbc.update("""
                UPDATE campaigns SET status='CLOSED', closed_at=ends_at, updated_at=now() WHERE id=?
                """, order.campaignId());

        PaymentService.Outcome outcome = pay(order);

        assertThat(outcome.body().status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void 조기_강제_종료_CLOSED_캠페인의_기존_주문은_신규_결제를_거부한다() {
        OrderFixture order = order(10, 1);
        jdbc.update("""
                UPDATE campaigns SET status='CLOSED', closed_at=ends_at - interval '1 second', updated_at=now()
                 WHERE id=?
                """, order.campaignId());

        assertThatThrownBy(() -> pay(order))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("CAMPAIGN_NOT_PAYABLE"));
        assertThat(count("payments", "order_id=" + order.orderId())).isZero();
    }

    @Test
    void 같은_멱등키_재요청은_최초_응답을_그대로_재생한다() {
        OrderFixture order = order(10, 1);
        String key = key();

        PaymentService.Outcome first = paymentService.requestPayment(BUYER, order.orderId(), key,
                new CreatePaymentRequest(order.totalAmount()));
        PaymentService.Outcome replay = paymentService.requestPayment(BUYER, order.orderId(), key,
                new CreatePaymentRequest(order.totalAmount()));

        assertThat(replay.httpStatus()).isEqualTo(first.httpStatus());
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(pgClient.confirmCount()).isEqualTo(1);
        assertThat(count("payments", "order_id=" + order.orderId())).isEqualTo(1);
    }

    @Test
    void 같은_멱등키에_다른_요청_내용이_오면_409다() {
        OrderFixture first = order(10, 1);
        OrderFixture second = order(10, 1);
        String key = key();
        paymentService.requestPayment(BUYER, first.orderId(), key, new CreatePaymentRequest(first.totalAmount()));

        // scope가 주문 ID를 포함하므로 다른 주문에는 같은 키를 쓸 수 있다 (PAY-02의 요청 범위 정의).
        PaymentService.Outcome other = paymentService.requestPayment(BUYER, second.orderId(), key,
                new CreatePaymentRequest(second.totalAmount()));
        assertThat(other.body().orderId()).isEqualTo(second.orderId());

        jdbc.update("UPDATE idempotency_requests SET request_hash='다른요청' WHERE scope=? AND idempotency_key=?",
                "POST:/api/orders/" + first.orderId() + "/payments", key);
        assertThatThrownBy(() -> paymentService.requestPayment(BUYER, first.orderId(), key,
                new CreatePaymentRequest(first.totalAmount())))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 만료된_멱등키는_409로_거부한다() {
        OrderFixture order = order(10, 1);
        String key = key();
        paymentService.requestPayment(BUYER, order.orderId(), key, new CreatePaymentRequest(order.totalAmount()));

        jdbc.update("UPDATE idempotency_requests SET expires_at = now() - interval '1 second' "
                + "WHERE scope=? AND idempotency_key=?", "POST:/api/orders/" + order.orderId() + "/payments", key);

        assertThatThrownBy(() -> paymentService.requestPayment(BUYER, order.orderId(), key,
                new CreatePaymentRequest(order.totalAmount())))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_KEY_EXPIRED"));
    }

    @Test
    void 처리_중인_멱등키_재요청은_PROCESSING_사유와_함께_409다() {
        OrderFixture order = order(10, 1);
        String key = key();
        // 최초 요청이 아직 응답을 기록하지 못한 상태를 재현한다 (요청 내용은 동일).
        jdbc.update("""
                INSERT INTO idempotency_requests(scope,idempotency_key,request_hash,status,expires_at,
                    created_at,updated_at)
                VALUES(?,?,?,'IN_PROGRESS', now() + interval '1 hour', now(), now())
                """, "POST:/api/orders/" + order.orderId() + "/payments", key,
                sha256(order.orderId() + "|" + order.totalAmount()));

        assertThatThrownBy(() -> paymentService.requestPayment(BUYER, order.orderId(), key,
                new CreatePaymentRequest(order.totalAmount())))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_REQUEST_IN_PROGRESS");
                    assertThat(exception.getMessage()).contains("PROCESSING");
                });
        assertThat(count("payments", "order_id=" + order.orderId())).isZero();
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void 본인_주문이_아니면_결제할_수_없다() {
        OrderFixture order = order(10, 1);

        assertThatThrownBy(() -> paymentService.requestPayment("buyer2@groupdrop.test", order.orderId(), key(),
                new CreatePaymentRequest(order.totalAmount())))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("FORBIDDEN_NOT_OWNER"));
    }

    @Test
    void 결제_시도_기록과_PROCESSING_전이는_같은_트랜잭션에_남는다() {
        OrderFixture order = order(10, 1);

        PaymentService.Outcome outcome = pay(order);

        // PAY-01: attempt 없는 PROCESSING(또는 그 반대)이 남으면 스윕도 대사도 잡지 못한다.
        assertThat(count("payment_attempts", "payment_id=" + outcome.body().id())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT merchant_payment_id FROM payment_attempts WHERE payment_id=?", String.class,
                outcome.body().id())).isEqualTo("mpay_" + outcome.body().id());
        assertThat(jdbc.queryForObject("SELECT outcome FROM payment_attempts WHERE payment_id=?", String.class,
                outcome.body().id())).isEqualTo("SUCCEEDED");
    }
}
