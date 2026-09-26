package com.groupdrop.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupdrop.common.ApiException;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * PAY-02 고착 선점 회수. PG 호출 중 요청 스레드가 죽으면 결제 멱등 레코드가 IN_PROGRESS로 남는다.
 * 고아 스윕 뒤 회수 단계가 결제의 현재 상태로 그 레코드를 완료하는지, 그리고 그 과정에서 같은 키로
 * PG 승인이 다시 나가거나 결제 행이 늘지 않는지(이중 승인 방지)를 고정한다.
 */
class PaymentIdempotencyReclaimTest extends AbstractPaymentIntegrationTest {

    /** 임계(PG 타임아웃 × 2)를 확실히 넘기는 역산 폭. */
    private static final long WELL_PAST_THRESHOLD_SECONDS = 3600;

    @Autowired
    private OrphanPaymentSweeper sweeper;
    @Autowired
    private OrphanPaymentSweepService sweepService;

    @Test
    void 크래시로_남은_선점은_스윕_뒤_UNKNOWN_202로_완료되고_같은_키는_PG_호출_없이_재생된다() {
        OrderFixture order = order(10, 1);
        String key = key();
        Long paymentId = crashDuringPgCall(order, key);
        backdate(paymentId);
        int confirmsBefore = pgClient.confirmCount();

        sweeper.scheduledSweep();

        assertThat(paymentStatus(paymentId)).isEqualTo("UNKNOWN");
        assertThat(claimStatus(order, key)).isEqualTo("COMPLETED");
        assertThat(claimResponseStatus(order, key)).isEqualTo(202);

        PaymentService.Outcome replay = retry(order, key);

        assertThat(replay.httpStatus()).isEqualTo(202);
        assertThat(replay.body().id()).isEqualTo(paymentId);
        assertThat(replay.body().status()).isEqualTo("UNKNOWN");
        assertThat(pgClient.confirmCount()).isEqualTo(confirmsBefore);
        assertThat(count("payments", "order_id=" + order.orderId())).isEqualTo(1);
    }

    @Test
    void 다른_경로로_확정된_결제의_선점도_그_상태로_회수된다() {
        OrderFixture order = order(10, 1);
        String key = key();
        Long paymentId = crashDuringPgCall(order, key);
        // 스윕이 아니라 웹훅·조회·대사가 먼저 확정한 경우 — 스윕은 이 결제를 건드리지 않는다.
        jdbc.update("UPDATE payments SET status='FAILED', failure_code='PG_DECLINED' WHERE id=?", paymentId);
        backdate(paymentId);
        int confirmsBefore = pgClient.confirmCount();

        sweeper.scheduledSweep();
        PaymentService.Outcome replay = retry(order, key);

        assertThat(replay.httpStatus()).isEqualTo(200);
        assertThat(replay.body().status()).isEqualTo("FAILED");
        assertThat(pgClient.confirmCount()).isEqualTo(confirmsBefore);
        assertThat(count("payments", "order_id=" + order.orderId())).isEqualTo(1);
    }

    @Test
    void 임계_전에는_결제가_확정돼도_회수하지_않는다() {
        OrderFixture order = order(10, 1);
        String key = key();
        Long paymentId = crashDuringPgCall(order, key);
        jdbc.update("UPDATE payments SET status='FAILED' WHERE id=?", paymentId);

        sweeper.scheduledSweep();

        // 원 요청이 아직 확정 트랜잭션에 있을 수 있는 구간이다. "처리 중" 응답이 맞다.
        assertThat(claimStatus(order, key)).isEqualTo("IN_PROGRESS");
        assertThatThrownBy(() -> retry(order, key))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_REQUEST_IN_PROGRESS"));
    }

    @Test
    void 결제가_아직_PROCESSING이면_회수하지_않는다() {
        OrderFixture order = order(10, 1);
        String key = key();
        Long paymentId = crashDuringPgCall(order, key);
        backdateClaimOnly(order, key);

        sweepService.reclaimStaleIdempotency();

        assertThat(paymentStatus(paymentId)).isEqualTo("PROCESSING");
        assertThat(claimStatus(order, key)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void 결제가_연결되지_않은_선점과_만료된_선점은_회수하지_않는다() {
        OrderFixture unlinked = order(10, 1);
        String unlinkedKey = key();
        jdbc.update("""
                INSERT INTO idempotency_requests(scope,idempotency_key,request_hash,status,expires_at,
                    created_at,updated_at)
                VALUES(?,?,'hash','IN_PROGRESS', now() + interval '1 hour', now() - interval '1 hour', now())
                """, scope(unlinked), unlinkedKey);

        // 연결 필드 중 resource_id만 비어 있는 결제 선점도 회수하지 않는다 (JOIN에서 빠진다).
        OrderFixture typedOnly = order(10, 1);
        String typedOnlyKey = key();
        jdbc.update("""
                INSERT INTO idempotency_requests(scope,idempotency_key,request_hash,resource_type,status,expires_at,
                    created_at,updated_at)
                VALUES(?,?,'hash','PAYMENT','IN_PROGRESS', now() + interval '1 hour', now() - interval '1 hour', now())
                """, scope(typedOnly), typedOnlyKey);

        OrderFixture expired = order(10, 1);
        String expiredKey = key();
        Long paymentId = crashDuringPgCall(expired, expiredKey);
        jdbc.update("UPDATE payments SET status='FAILED' WHERE id=?", paymentId);
        backdate(paymentId);
        jdbc.update("UPDATE idempotency_requests SET expires_at = now() - interval '1 second' "
                + "WHERE scope=? AND idempotency_key=?", scope(expired), expiredKey);

        sweeper.scheduledSweep();

        assertThat(claimStatus(unlinked, unlinkedKey)).isEqualTo("IN_PROGRESS");
        assertThat(claimStatus(typedOnly, typedOnlyKey)).isEqualTo("IN_PROGRESS");
        assertThat(claimStatus(expired, expiredKey)).isEqualTo("IN_PROGRESS");
        // 만료 판정이 회수보다 먼저다 (PAY-02 키 수명 정책).
        assertThatThrownBy(() -> retry(expired, expiredKey))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_KEY_EXPIRED"));
    }

    @Test
    void 요청_확정이_회수보다_늦으면_저장된_응답을_재생하고_결제_확정은_커밋된다() {
        OrderFixture order = order(10, 1);
        String key = key();
        // PG 호출이 임계보다 오래 걸린 사이 스윕이 UNKNOWN 전이·선점 회수를 먼저 끝낸 경합을 재현한다.
        pgClient.setDuringConfirm(() -> {
            Long paymentId = jdbc.queryForObject("SELECT id FROM payments WHERE order_id=?", Long.class,
                    order.orderId());
            backdate(paymentId);
            sweeper.scheduledSweep();
        });

        PaymentService.Outcome outcome = paymentService.requestPayment(BUYER, order.orderId(), key,
                new CreatePaymentRequest(order.totalAmount()));

        Long paymentId = outcome.body().id();
        // 같은 키에는 한 가지 응답만 — 회수가 저장한 202를 원 요청도 받는다. 덮어쓰지 않는다.
        assertThat(outcome.httpStatus()).isEqualTo(202);
        assertThat(outcome.body().status()).isEqualTo("UNKNOWN");
        assertThat(claimResponseStatus(order, key)).isEqualTo(202);
        // 결제 확정 자체는 롤백되지 않는다 (예전에는 완료 기록 0행 예외로 확정 트랜잭션이 통째로 롤백됐다).
        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(count("outbox_events", "aggregate_id=" + paymentId + " AND event_type='payment.finalized'"))
                .isEqualTo(1);
        assertThat(pgClient.confirmCount()).isEqualTo(1);
    }

    @Test
    void 두_인스턴스의_회수가_동시에_돌아도_선점마다_한_번만_완료한다() throws Exception {
        // 같은 DB를 쓰는 앞선 테스트의 잔여 선점을 먼저 비운다.
        while (sweepService.reclaimStaleIdempotency() > 0) {
            // 소진
        }
        Timestamp startedAt = jdbc.queryForObject("SELECT now()", Timestamp.class);
        List<String> keys = List.of(key(), key(), key());
        List<OrderFixture> orders = new java.util.ArrayList<>();
        for (String key : keys) {
            OrderFixture order = order(10, 1);
            Long paymentId = crashDuringPgCall(order, key);
            jdbc.update("UPDATE payments SET status='FAILED' WHERE id=?", paymentId);
            backdate(paymentId);
            orders.add(order);
        }

        CountDownLatch start = new CountDownLatch(1);
        int total = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results = List.of(
                    executor.submit(() -> { start.await(); return sweepService.reclaimStaleIdempotency(); }),
                    executor.submit(() -> { start.await(); return sweepService.reclaimStaleIdempotency(); }));
            start.countDown();
            for (Future<Integer> result : results) {
                total += result.get(30, TimeUnit.SECONDS);
            }
        }

        for (int i = 0; i < keys.size(); i++) {
            assertThat(claimStatus(orders.get(i), keys.get(i))).isEqualTo("COMPLETED");
        }
        // 두 회수가 보고한 완료 건수가 실제로 완료된 행 수와 같아야 한다 — 같은 행을 두 번 완료했다면 합이 더 크다.
        // (이 테스트 도중 임계를 넘긴 다른 잔여 선점도 함께 완료될 수 있으므로 행 수로 비교한다.)
        long completedDuringTest = jdbc.queryForObject("""
                SELECT count(*) FROM idempotency_requests
                 WHERE resource_type='PAYMENT' AND status='COMPLETED' AND updated_at >= ?
                """, Long.class, startedAt);
        assertThat(total).isGreaterThanOrEqualTo(keys.size());
        assertThat((long) total).isEqualTo(completedDuringTest);
    }

    /** 준비 트랜잭션은 커밋되고 PG 호출 도중 요청 스레드가 죽은 상태를 만든다 (11.3). */
    private Long crashDuringPgCall(OrderFixture order, String key) {
        pgClient.setDuringConfirm(() -> {
            throw new IllegalStateException("테스트: PG 호출 중 프로세스 종료");
        });
        try {
            assertThatThrownBy(() -> retry(order, key)).hasMessageContaining("PG 호출 중 프로세스 종료");
        } finally {
            pgClient.setDuringConfirm(() -> { });
        }
        Long paymentId = jdbc.queryForObject("SELECT id FROM payments WHERE order_id=?", Long.class,
                order.orderId());
        assertThat(paymentStatus(paymentId)).isEqualTo("PROCESSING");
        assertThat(claimStatus(order, key)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT resource_id FROM idempotency_requests WHERE scope=? AND idempotency_key=?",
                Long.class, scope(order), key)).isEqualTo(paymentId);
        return paymentId;
    }

    private PaymentService.Outcome retry(OrderFixture order, String key) {
        return paymentService.requestPayment(BUYER, order.orderId(), key,
                new CreatePaymentRequest(order.totalAmount()));
    }

    private void backdate(Long paymentId) {
        backdatePayment(paymentId, WELL_PAST_THRESHOLD_SECONDS);
        jdbc.update("UPDATE idempotency_requests SET created_at = created_at - make_interval(secs => ?) "
                + "WHERE resource_type='PAYMENT' AND resource_id=?", (double) WELL_PAST_THRESHOLD_SECONDS, paymentId);
    }

    private void backdateClaimOnly(OrderFixture order, String key) {
        jdbc.update("UPDATE idempotency_requests SET created_at = created_at - make_interval(secs => ?) "
                + "WHERE scope=? AND idempotency_key=?", (double) WELL_PAST_THRESHOLD_SECONDS, scope(order), key);
    }

    private String claimStatus(OrderFixture order, String key) {
        return jdbc.queryForObject("SELECT status FROM idempotency_requests WHERE scope=? AND idempotency_key=?",
                String.class, scope(order), key);
    }

    private Integer claimResponseStatus(OrderFixture order, String key) {
        return jdbc.queryForObject(
                "SELECT response_status FROM idempotency_requests WHERE scope=? AND idempotency_key=?",
                Integer.class, scope(order), key);
    }

    private String scope(OrderFixture order) {
        return "POST:/api/orders/" + order.orderId() + "/payments";
    }
}
