package com.groupdrop.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupdrop.common.ApiException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 17.3 1계층(단일 JVM 스레드 동시성). S2와 PAY-01 이중 결제 예방·보상을 다룬다.
 *
 * <p>이 계층은 다중 인스턴스 안전성을 증명하지 못한다 — 그것은 2계층(Compose 2인스턴스)의 몫이다.
 */
class PaymentConcurrencyTest extends AbstractPaymentIntegrationTest {

    /** S2 원문이 요구하는 동일 멱등 키 동시 요청 수. */
    private static final int THREADS = 10;

    @org.springframework.beans.factory.annotation.Autowired
    private PaymentFinalizer finalizer;
    @org.springframework.beans.factory.annotation.Autowired
    private PaymentRepository paymentRepository;

    @Test
    void S2_같은_멱등키의_동시_결제_요청은_결제를_하나만_만든다() throws Exception {
        OrderFixture order = order(10, 1);
        String key = key();
        // PG 호출 구간을 넓혀 두 번째 요청이 "처리 중"과 정면으로 만나게 한다.
        pgClient.setDuringConfirm(() -> sleep(200));

        List<String> results = runConcurrently(THREADS, () -> {
            try {
                PaymentService.Outcome outcome = paymentService.requestPayment(BUYER, order.orderId(), key,
                        new CreatePaymentRequest(order.totalAmount()));
                return "OK:" + outcome.body().status();
            } catch (ApiException exception) {
                return exception.getCode();
            }
        });

        assertThat(count("payments", "order_id=" + order.orderId())).isEqualTo(1);
        assertThat(pgClient.confirmCount()).isEqualTo(1);
        assertThat(results).allSatisfy(result -> assertThat(result)
                .isIn("OK:SUCCEEDED", "IDEMPOTENCY_REQUEST_IN_PROGRESS"));
        assertThat(results).contains("OK:SUCCEEDED");

        assertThat(outboxWorker.drain()).isEqualTo(1);
        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");
        assertThat(inventoryOf(order.inventoryId())).containsExactly(10, 9, 0, 1);
    }

    @Test
    void 서로_다른_멱등키의_동시_결제는_유효한_성공_결제를_하나만_남긴다() throws Exception {
        OrderFixture order = order(10, 1);
        pgClient.setDuringConfirm(() -> sleep(200));

        List<String> results = runConcurrently(THREADS, () -> {
            try {
                PaymentService.Outcome outcome = paymentService.requestPayment(BUYER, order.orderId(), key(),
                        new CreatePaymentRequest(order.totalAmount()));
                return "OK:" + outcome.body().status();
            } catch (ApiException exception) {
                return exception.getCode();
            } catch (RuntimeException exception) {
                // 부분 유니크에 부딪힌 패자의 트랜잭션 롤백. 예방 규칙의 경쟁 창이 뚫린 경우다.
                return "CONSTRAINT_LOSS";
            }
        });

        // 12.2: 한 주문에 유효한 성공 결제는 최대 1건. 경쟁 창이 열렸는지 여부와 무관하게 이것만은 불변이다.
        assertThat(count("payments", "order_id=" + order.orderId()
                + " AND status IN ('SUCCEEDED','REFUNDING','REFUNDED')")).isEqualTo(1);
        assertThat(results).contains("OK:SUCCEEDED");
        // 결과는 실행마다 다르다 — 예방(409)에서 끝날 수도, 창이 뚫려 보상(SUPERSEDED)까지 갈 수도 있다.
        assertThat(results).allSatisfy(result -> assertThat(result)
                .isIn("OK:SUCCEEDED", "OK:SUPERSEDED", "PAYMENT_ALREADY_IN_PROGRESS", "CONSTRAINT_LOSS"));
        // 패자의 종착지는 둘 중 하나다. 확정 트랜잭션까지 간 패자는 SUPERSEDED로 종결되고,
        // 부분 유니크에 부딪혀 롤백된 패자는 PROCESSING으로 남아 고아 스윕 → UNKNOWN → 조회 해소를 거쳐
        // 같은 보상 경로로 합류한다. 어느 쪽도 유효 성공 결제가 되지는 못한다.
        assertThat(count("payments", "order_id=" + order.orderId()
                + " AND status NOT IN ('SUCCEEDED','SUPERSEDED','PROCESSING','UNKNOWN')")).isZero();

        while (outboxWorker.drain() > 0) {
            // 후처리 완료까지
        }
        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");
        // 성공 결제 1건에 대해서만 재고가 확정된다.
        assertThat(inventoryOf(order.inventoryId())).containsExactly(10, 9, 0, 1);
    }

    /**
     * 위 테스트의 경쟁 창은 실행마다 열릴 수도, 안 열릴 수도 있다. 보상 경로 자체는 결정적으로 고정해 둔다 —
     * 4주차의 자동 환불이 이 상태(SUPERSEDED)를 입력으로 받기 때문이다 (PAY-01).
     */
    @Test
    void 이미_성공_결제가_있는_주문의_두번째_승인은_SUPERSEDED로_종결한다() {
        OrderFixture order = order(10, 1);
        Long winner = pay(order).body().id();
        assertThat(paymentStatus(winner)).isEqualTo("SUCCEEDED");
        while (outboxWorker.drain() > 0) {
            // 승자 후처리 완료까지
        }

        // 예방 규칙을 뚫고 PG에서 2건이 승인된 상태를 만든다.
        Long loser = jdbc.queryForObject("""
                INSERT INTO payments(order_id,status,amount,created_at,updated_at)
                VALUES(?,'PROCESSING',?,now(),now()) RETURNING id
                """, Long.class, order.orderId(), order.totalAmount());

        PaymentFinalizer.Result result = finalizer.succeed(
                paymentRepository.findPayment(loser).orElseThrow(), "pg_duplicate_" + loser,
                java.time.Instant.now(), "test");

        assertThat(result).isEqualTo(PaymentFinalizer.Result.SUPERSEDED);
        assertThat(paymentStatus(loser)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM payments WHERE id=?", String.class, loser))
                .isEqualTo("DUPLICATE_PAYMENT");
        // 패자는 수익 분해에 진입한 적이 없으므로 확정 이벤트를 발행하지 않는다.
        assertThat(count("outbox_events",
                "aggregate_type='PAYMENT' AND aggregate_id=" + loser)).isZero();
        // 대신 4주차의 보상 환불이 같은 트랜잭션에서 접수된다 (PAY-01).
        assertThat(count("refunds", "payment_id=" + loser + " AND status='REQUESTED' AND compensation")).isEqualTo(1);
        assertThat(count("audit_logs", "action='PAYMENT_SUPERSEDED' AND resource_id=" + loser)).isEqualTo(1);
        assertThat(count("payments", "order_id=" + order.orderId()
                + " AND status IN ('SUCCEEDED','REFUNDING','REFUNDED')")).isEqualTo(1);
    }

    private List<String> runConcurrently(int threads, java.util.concurrent.Callable<String> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<String>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> results = new java.util.ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
