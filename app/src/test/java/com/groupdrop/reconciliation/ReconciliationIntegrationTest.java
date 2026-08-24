package com.groupdrop.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupdrop.payment.AbstractPaymentIntegrationTest;
import com.groupdrop.payment.PaymentFinalizer;
import com.groupdrop.payment.PaymentRepository;
import com.groupdrop.payment.StubPgClient;
import com.groupdrop.refund.CreateRefundRequest;
import com.groupdrop.refund.RefundReconciliationSupport;
import com.groupdrop.refund.RefundRepository;
import com.groupdrop.refund.RefundService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * REC-01 대사 — 해소 단계(S4-b), 8개 유형 분류(S7), 원장 재검산(S5·16.4).
 *
 * <p>어서션은 전부 이 테스트가 만든 결제·주문 ID로 범위를 좁힌다. 대사는 전역 스캔이고 통합 테스트는
 * DB를 공유하므로, 전역 건수 어서션은 다른 테스트의 잔여물에 오염된다.
 */
class ReconciliationIntegrationTest extends AbstractPaymentIntegrationTest {

    @Autowired
    private ReconciliationService reconciliations;
    @Autowired
    private ReconciliationRepository repository;
    @Autowired
    private RefundService refundService;
    @Autowired
    private RefundRepository refundRepository;
    @Autowired
    private RefundReconciliationSupport refundReconciliation;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private PaymentFinalizer paymentFinalizer;
    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void S4_b_웹훅_없이_최소_경과_0의_대사로_UNKNOWN_결제를_복구한다() {
        OrderFixture order = order(10, 1);
        // PG는 승인했지만 응답이 유실됐고 웹훅도 오지 않는다 (S4의 전제).
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        Long paymentId = pay(order).body().id();
        assertThat(paymentStatus(paymentId)).isEqualTo("UNKNOWN");
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");

        ReconciliationRepository.Run run = reconciliations.run(0);

        // 대사 완료 직후 결제가 확정된다.
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.minAgeMinutes()).isZero();
        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(count("audit_logs", "action='PAYMENT_RESOLVED' AND resource_id=" + paymentId)).isEqualTo(1);
        // 주문 전이는 13.4의 워커 몫이다 — 대사가 직접 주문을 건드리지 않는다.
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");

        assertThat(outboxWorker.drain()).isPositive();
        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");
        assertThat(count("ledger_transactions",
                "transaction_type='PAYMENT' AND reference_id=" + paymentId)).isEqualTo(1);
    }

    @Test
    void 최소_경과_시간이_지나지_않은_거래는_대사_대상이_아니다() {
        OrderFixture order = order(10, 1);
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        Long paymentId = pay(order).body().id();
        assertThat(paymentStatus(paymentId)).isEqualTo("UNKNOWN");

        // 기본 30분. 방금 만든 결제는 진행 중일 수 있으므로 건드리지 않는다 (가짜 불일치 방지).
        reconciliations.run(30);

        assertThat(paymentStatus(paymentId)).isEqualTo("UNKNOWN");
        assertThat(openDiscrepancies(paymentId)).isEmpty();
    }

    @Test
    void 확정되지_않는_비최종_결제는_UNRESOLVED_INTERNAL로_등록된다() {
        OrderFixture order = order(10, 1);
        // PG에 아무것도 남지 않은 타임아웃 — 재조회해도 결과가 나오지 않는다.
        pgClient.setMode(StubPgClient.Mode.TIMEOUT_NO_CHARGE);
        Long paymentId = pay(order).body().id();
        assertThat(paymentStatus(paymentId)).isEqualTo("UNKNOWN");

        reconciliations.run(0);

        assertThat(paymentStatus(paymentId)).isEqualTo("UNKNOWN");
        assertThat(openDiscrepancies(paymentId))
                .singleElement()
                .satisfies(discrepancy -> {
                    assertThat(discrepancy.type()).isEqualTo(DiscrepancyType.UNRESOLVED_INTERNAL);
                    assertThat(discrepancy.internalStatus()).isEqualTo("UNKNOWN");
                });
    }

    @Test
    void S7_주입한_PG_불일치가_유형_코드로_분류되어_조회에_노출된다() {
        // ① 금액 불일치 — 같은 거래를 다른 금액으로 덮어쓴다.
        OrderFixture amountOrder = order(10, 1);
        Long amountPaymentId = pay(amountOrder).body().id();
        outboxWorker.drain();
        pgClient.injectTransaction(providerPaymentIdOf(amountPaymentId), amountOrder.orderId(),
                DEAL_PRICE + 1_000L, "SUCCEEDED", approvedAtOf(amountPaymentId), 0L);

        // ② 상태 불일치 — 내부는 SUCCEEDED인데 PG는 FAILED.
        OrderFixture statusOrder = order(10, 1);
        Long statusPaymentId = pay(statusOrder).body().id();
        outboxWorker.drain();
        pgClient.injectTransaction(providerPaymentIdOf(statusPaymentId), statusOrder.orderId(),
                DEAL_PRICE, "FAILED", approvedAtOf(statusPaymentId), 0L);

        // ③ 환불 금액 불일치 — PG에만 환불이 있다.
        OrderFixture refundOrder = order(10, 1);
        Long refundPaymentId = pay(refundOrder).body().id();
        outboxWorker.drain();
        pgClient.injectTransaction(providerPaymentIdOf(refundPaymentId), refundOrder.orderId(),
                DEAL_PRICE, "SUCCEEDED", approvedAtOf(refundPaymentId), DEAL_PRICE);

        // ④ PG에만 있는 거래.
        String ghostProviderId = "pg_ghost_" + UUID.randomUUID();
        pgClient.injectTransaction(ghostProviderId, 999_999_999L, 12_345L, "SUCCEEDED",
                Instant.now().minusSeconds(60), 0L);

        // ⑤ 내부에만 있는 결제 — PG에는 이 식별자가 없다.
        OrderFixture missingOrder = order(10, 1);
        String missingProviderId = "pg_missing_" + UUID.randomUUID();
        Long missingPaymentId = insertSucceededPayment(missingOrder.orderId(), missingProviderId);

        // ⑥ 같은 주문에 PG 승인 2건.
        OrderFixture duplicateOrder = order(10, 1);
        Long duplicatePaymentId = pay(duplicateOrder).body().id();
        outboxWorker.drain();
        String duplicateProviderId = "pg_dup_" + UUID.randomUUID();
        pgClient.injectTransaction(duplicateProviderId, duplicateOrder.orderId(), DEAL_PRICE, "SUCCEEDED",
                Instant.now().minusSeconds(60), 0L);

        // ⑦ 성공 결제의 내부 승인 시각과 PG 처리 시각 차이가 허용 오차를 넘는다.
        OrderFixture occurredAtOrder = order(10, 1);
        Long occurredAtPaymentId = pay(occurredAtOrder).body().id();
        outboxWorker.drain();
        pgClient.injectTransaction(providerPaymentIdOf(occurredAtPaymentId), occurredAtOrder.orderId(),
                DEAL_PRICE, "SUCCEEDED", approvedAtOf(occurredAtPaymentId).minusSeconds(2), 0L);

        reconciliations.run(0);

        assertThat(typesOf(amountPaymentId)).contains(DiscrepancyType.AMOUNT_MISMATCH);
        assertThat(typesOf(statusPaymentId)).contains(DiscrepancyType.STATUS_MISMATCH);
        assertThat(typesOf(refundPaymentId)).contains(DiscrepancyType.REFUND_MISMATCH);
        assertThat(typesOf(missingPaymentId)).containsExactly(DiscrepancyType.MISSING_PROVIDER);
        assertThat(typeOfProvider(ghostProviderId)).isEqualTo(DiscrepancyType.MISSING_INTERNAL);
        assertThat(typeOfProvider(duplicateProviderId)).isEqualTo(DiscrepancyType.DUPLICATE_PAYMENT);
        assertThat(typesOf(occurredAtPaymentId)).containsExactly(DiscrepancyType.OCCURRED_AT_MISMATCH);
        // 내부 승자는 보상 대상이 아니다 — 대사는 PAY-01의 결과를 재선정하지 않는다.
        assertThat(typeOfProvider(providerPaymentIdOf(duplicatePaymentId)))
                .isNotEqualTo(DiscrepancyType.DUPLICATE_PAYMENT);

        // 조회 API에 미해결로 노출된다 (REC-02 목록).
        List<ReconciliationRepository.Discrepancy> open = repository.findDiscrepancies("OPEN", 200);
        assertThat(open).extracting(ReconciliationRepository.Discrepancy::providerPaymentId)
                .contains(ghostProviderId, duplicateProviderId, missingProviderId);
    }

    @Test
    void 거래_발생_시각은_1초까지_허용하고_초과와_한쪽_누락은_OPEN으로_기록하며_일치하면_자동_해소한다() {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        outboxWorker.drain();
        String providerPaymentId = providerPaymentIdOf(paymentId);
        Instant approvedAt = approvedAtOf(paymentId);

        // 경계값 1,000ms는 허용한다 (D-031).
        pgClient.injectTransaction(providerPaymentId, order.orderId(), DEAL_PRICE, "SUCCEEDED",
                approvedAt.minusSeconds(1), 0L);
        reconciliations.run(0);
        assertThat(typesOf(paymentId)).doesNotContain(DiscrepancyType.OCCURRED_AT_MISMATCH);

        // 1,001ms부터 별도 유형으로 정산을 막는 OPEN 불일치가 된다.
        pgClient.injectTransaction(providerPaymentId, order.orderId(), DEAL_PRICE, "SUCCEEDED",
                approvedAt.minusMillis(1_001), 0L);
        reconciliations.run(0);
        assertThat(openDiscrepancies(paymentId))
                .filteredOn(discrepancy -> discrepancy.type() == DiscrepancyType.OCCURRED_AT_MISMATCH)
                .singleElement()
                .satisfies(discrepancy -> {
                    assertThat(discrepancy.detail()).contains("결제 발생 시각 불일치");
                    assertThat(discrepancy.detail()).contains("differenceMillis=1001");
                    assertThat(discrepancy.detail()).contains("toleranceMillis=1000");
                });

        // 다음 실행에서 다시 허용 범위로 들어오면 기존 자동 해소 규칙으로 닫힌다.
        pgClient.injectTransaction(providerPaymentId, order.orderId(), DEAL_PRICE, "SUCCEEDED",
                approvedAt.minusSeconds(1), 0L);
        reconciliations.run(0);
        assertThat(typesOf(paymentId)).doesNotContain(DiscrepancyType.OCCURRED_AT_MISMATCH);
        assertThat(count("reconciliation_discrepancies",
                "payment_id=" + paymentId + " AND discrepancy_type='OCCURRED_AT_MISMATCH'"
                        + " AND status='RESOLVED'")).isEqualTo(1);

        // 비교 대상 사건은 존재하는데 한쪽 시각이 없으면 차이를 계산할 수 없어 불일치다.
        jdbc.update("UPDATE payments SET approved_at=NULL WHERE id=?", paymentId);
        reconciliations.run(0);
        assertThat(openDiscrepancies(paymentId))
                .filteredOn(discrepancy -> discrepancy.type() == DiscrepancyType.OCCURRED_AT_MISMATCH)
                .singleElement()
                .satisfies(discrepancy -> assertThat(discrepancy.detail()).contains("differenceMillis=UNKNOWN"));
    }

    @Test
    void 완료_환불의_내부_completed_at과_PG_refundedAt도_1초_초과면_발생_시각_불일치다() {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        outboxWorker.drain();
        String providerPaymentId = providerPaymentIdOf(paymentId);
        Instant approvedAt = Instant.now().minusSeconds(60);
        Instant completedAt = Instant.now().minusSeconds(30);
        jdbc.update("UPDATE payments SET status='REFUNDED', approved_at=? WHERE id=?",
                java.sql.Timestamp.from(approvedAt), paymentId);
        Long refundId = refundRepository.insertRequested(paymentId, order.orderId(), DEAL_PRICE,
                false, "발생 시각 대사", approvedAt.plusSeconds(10));
        assertThat(refundRepository.markCompleted(refundId, "rf_time_mismatch", completedAt,
                completedAt)).isTrue();
        pgClient.injectTransaction(providerPaymentId, order.orderId(), DEAL_PRICE, "SUCCEEDED",
                approvedAt, DEAL_PRICE, completedAt.minusSeconds(2));

        reconciliations.run(0);

        assertThat(openDiscrepancies(paymentId))
                .filteredOn(discrepancy -> discrepancy.type() == DiscrepancyType.OCCURRED_AT_MISMATCH)
                .singleElement()
                .satisfies(discrepancy -> assertThat(discrepancy.detail())
                        .contains("환불 발생 시각 불일치", "differenceMillis=2000"));
    }

    @Test
    void SUPERSEDED와_PG_성공_보상환불_쌍은_승인시각까지_보존되어_정상_매칭된다() {
        OrderFixture order = order(10, 1);
        Long winnerId = pay(order).body().id();
        while (outboxWorker.drain() > 0) {
            // 승자의 주문·원장 후처리 완료
        }
        Long loserId = jdbc.queryForObject("""
                INSERT INTO payments(order_id,status,amount,created_at,updated_at)
                VALUES(?,'PROCESSING',?,now()-interval '2 minutes',now()) RETURNING id
                """, Long.class, order.orderId(), DEAL_PRICE);
        String loserProviderId = "pg_superseded_" + UUID.randomUUID();
        Instant providerApprovedAt = Instant.now().minusSeconds(60);

        assertThat(paymentFinalizer.succeed(paymentRepository.findPayment(loserId).orElseThrow(),
                loserProviderId, providerApprovedAt, "test"))
                .isEqualTo(PaymentFinalizer.Result.SUPERSEDED);
        while (outboxWorker.drain() > 0) {
            // 보상 환불 실행·완료 후처리 소진
        }
        Instant storedApprovedAt = approvedAtOf(loserId);
        Instant storedRefundedAt = jdbc.queryForObject("""
                SELECT completed_at FROM refunds
                 WHERE payment_id=? AND status='COMPLETED' AND compensation
                """, (rs, rowNum) -> rs.getTimestamp(1).toInstant(), loserId);
        pgClient.injectTransaction(loserProviderId, order.orderId(), DEAL_PRICE, "SUCCEEDED",
                storedApprovedAt, DEAL_PRICE, storedRefundedAt);

        reconciliations.run(0);

        assertThat(paymentStatus(loserId)).isEqualTo("SUPERSEDED");
        assertThat(storedApprovedAt).isNotNull();
        assertThat(openDiscrepancies(loserId)).isEmpty();
        // 승자가 아닌 PG 성공 거래는 이미 환불됐으므로 DUPLICATE_PAYMENT 보상 대상도 아니다.
        assertThat(typeOfProvider(loserProviderId)).isNull();
        assertThat(typesOf(winnerId)).isEmpty();
    }

    @Test
    void 같은_불일치가_반복_검출되어도_미해결_목록에는_한_행으로_접힌다() {
        String ghostProviderId = "pg_ghost_" + UUID.randomUUID();
        pgClient.injectTransaction(ghostProviderId, 888_888_888L, 5_000L, "SUCCEEDED",
                Instant.now().minusSeconds(60), 0L);

        reconciliations.run(0);
        Long firstRunId = runIdOfProvider(ghostProviderId);
        reconciliations.run(0);

        assertThat(count("reconciliation_discrepancies",
                "provider_payment_id='" + ghostProviderId + "'")).isEqualTo(1);
        // 재검출은 새 행이 아니라 last_seen_run_id 갱신으로 표현한다.
        assertThat(lastSeenRunIdOfProvider(ghostProviderId)).isGreaterThan(firstRunId);
    }

    @Test
    void 조건이_사라진_불일치는_다음_대사에서_자동_해소된다() {
        OrderFixture order = order(10, 1);
        String missingProviderId = "pg_missing_" + UUID.randomUUID();
        Long paymentId = insertSucceededPayment(order.orderId(), missingProviderId);

        reconciliations.run(0);
        assertThat(typesOf(paymentId)).containsExactly(DiscrepancyType.MISSING_PROVIDER);

        // PG에 거래가 나타나면(지연 반영 등) 다음 실행에서 조건이 사라진다.
        pgClient.injectTransaction(missingProviderId, order.orderId(), DEAL_PRICE, "SUCCEEDED",
                approvedAtOf(paymentId), 0L);
        reconciliations.run(0);

        assertThat(openDiscrepancies(paymentId)).isEmpty();
        assertThat(count("reconciliation_discrepancies",
                "payment_id=" + paymentId + " AND status='RESOLVED'")).isEqualTo(1);
        Long discrepancyId = jdbc.queryForObject(
                "SELECT id FROM reconciliation_discrepancies WHERE payment_id=?", Long.class, paymentId);
        assertThat(count("audit_logs",
                "action='DISCREPANCY_AUTO_RESOLVED' AND resource_id=" + discrepancyId)).isEqualTo(1);
    }

    @Test
    void minAge가_큰_다음_실행은_자기_cutoff보다_최근인_OPEN_불일치를_닫지_않는다() {
        String providerId = "pg_recent_" + UUID.randomUUID();
        pgClient.injectTransaction(providerId, 777_777_777L, 4_000L, "SUCCEEDED",
                Instant.now().minusSeconds(30), 0L);

        reconciliations.run(0);
        assertThat(typeOfProvider(providerId)).isEqualTo(DiscrepancyType.MISSING_INTERNAL);

        // 이 거래는 30분 cutoff 바깥이라 이번 실행에서 보이지 않는다. '사라짐'이 아니라 범위 밖이다.
        reconciliations.run(30);
        assertThat(typeOfProvider(providerId)).isEqualTo(DiscrepancyType.MISSING_INTERNAL);
    }

    @Test
    void RUNNING_대사가_있으면_두번째_세대를_DB가_거부한다() {
        Long first = repository.startRun(0, Instant.now());
        try {
            assertThatThrownBy(() -> repository.startRun(30, Instant.now()))
                    .isInstanceOf(ReconciliationRepository.ReconciliationAlreadyRunningException.class);
            assertThat(count("reconciliation_runs", "status='RUNNING'")).isEqualTo(1);
        } finally {
            repository.failRun(first, "test cleanup", Instant.now());
        }
    }

    @Test
    void stale_회수는_진행_중인_구_세대_쓰기와_상호_배제되고_후속_세대가_단조롭게_전진한다() throws Exception {
        Instant now = Instant.now();
        Long baseRun = jdbc.queryForObject("""
                INSERT INTO reconciliation_runs(status,min_age_minutes,started_at,finished_at)
                VALUES('COMPLETED',0,?,?) RETURNING id
                """, Long.class, java.sql.Timestamp.from(now.minusSeconds(20_000)),
                java.sql.Timestamp.from(now.minusSeconds(19_000)));
        Long discrepancyId = jdbc.queryForObject("""
                INSERT INTO reconciliation_discrepancies
                    (run_id,last_seen_run_id,discrepancy_type,provider_payment_id,detail,status,
                     subject_occurred_at,detected_at,updated_at)
                VALUES(?,?,'MISSING_INTERNAL',?,'fencing','OPEN',?,?,?) RETURNING id
                """, Long.class, baseRun, baseRun, "pg_fence_" + UUID.randomUUID(),
                java.sql.Timestamp.from(now.minusSeconds(10_000)), java.sql.Timestamp.from(now.minusSeconds(9_000)),
                java.sql.Timestamp.from(now.minusSeconds(9_000)));
        Long staleRun = repository.startRun(0, now.minusSeconds(10_000));
        CountDownLatch discrepancyLocked = new CountDownLatch(1);
        CountDownLatch releaseDiscrepancy = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> blocker = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM reconciliation_discrepancies WHERE id=? FOR UPDATE",
                        Long.class, discrepancyId);
                discrepancyLocked.countDown();
                try {
                    if (!releaseDiscrepancy.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("fencing 테스트 잠금 해제 대기 시간 초과");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(discrepancyLocked.await(20, TimeUnit.SECONDS)).isTrue();

            Future<?> oldWrite = executor.submit(() -> repository.upsertOpen(
                    staleRun, DiscrepancyType.MISSING_INTERNAL, null, null,
                    jdbc.queryForObject("SELECT provider_payment_id FROM reconciliation_discrepancies WHERE id=?",
                            String.class, discrepancyId),
                    null, "SUCCEEDED", null, 1L, "구 세대의 진행 중 쓰기",
                    now.minusSeconds(9_000), now));
            waitUntilOldWriteIsBlocked();

            Future<Integer> staleRecovery = executor.submit(
                    () -> repository.failStaleRuns(now.minusSeconds(7_200), now));
            Thread.sleep(200);
            // 구 UPSERT가 run 행 FOR SHARE를 소유하므로 stale UPDATE는 먼저 지나갈 수 없다.
            assertThat(staleRecovery.isDone()).isFalse();

            releaseDiscrepancy.countDown();
            blocker.get(20, TimeUnit.SECONDS);
            oldWrite.get(20, TimeUnit.SECONDS);
            assertThat(staleRecovery.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        }

        Long successor = repository.startRun(0, now);
        try {
            String providerId = jdbc.queryForObject(
                    "SELECT provider_payment_id FROM reconciliation_discrepancies WHERE id=?",
                    String.class, discrepancyId);
            repository.upsertOpen(successor, DiscrepancyType.MISSING_INTERNAL, null, null, providerId,
                    null, "SUCCEEDED", null, 1L, "후속 세대 쓰기", now.minusSeconds(9_000), now);
            assertThat(jdbc.queryForObject(
                    "SELECT last_seen_run_id FROM reconciliation_discrepancies WHERE id=?",
                    Long.class, discrepancyId)).isEqualTo(successor);
            assertThat(repository.completeRun(staleRun, 0, 0, 0, 0, 0, now)).isFalse();
            assertThat(repository.findRun(staleRun).orElseThrow().status()).isEqualTo("FAILED");
        } finally {
            repository.failRun(successor, "test cleanup", now);
        }
    }

    private void waitUntilOldWriteIsBlocked() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE datname=current_database() AND wait_event_type='Lock'
                       AND query LIKE '%WITH run_fence AS MATERIALIZED%'
                    """, Long.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("구 대사 UPSERT의 DB 잠금 대기를 관찰하지 못했습니다.");
    }

    @Test
    void 미완_환불_재발행이_동시에_실행되어도_PENDING_이벤트는_한_건이다() throws Exception {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        assertThat(outboxWorker.drain()).isEqualTo(1);
        Long refundId = refundService.requestRefund(ADMIN, paymentId, UUID.randomUUID().toString(),
                new CreateRefundRequest("재발행 경쟁")).body().id();
        jdbc.update("""
                UPDATE outbox_events SET status='PROCESSED', processed_at=now()
                 WHERE event_type='refund.requested' AND aggregate_id=? AND status='PENDING'
                """, refundId);
        RefundRepository.RefundSnapshot refund = refundRepository.find(refundId).orElseThrow();

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Boolean> results = new CopyOnWriteArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        results.add(refundReconciliation.republishRequestIfNoPending(refund));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        }

        assertThat(results).hasSize(threads).containsExactlyInAnyOrderElementsOf(
                java.util.stream.Stream.concat(java.util.stream.Stream.of(true),
                        java.util.stream.Stream.generate(() -> false).limit(threads - 1)).toList());
        assertThat(count("outbox_events",
                "event_type='refund.requested' AND aggregate_id=" + refundId + " AND status='PENDING'"))
                .isEqualTo(1);
        assertThat(count("audit_logs",
                "action='REFUND_REQUEST_REPUBLISHED' AND resource_id=" + refundId)).isEqualTo(1);
    }

    @Test
    void 대사는_원장을_전수_재검산하고_불균형_건수를_실행_기록에_남긴다() {
        long before = unbalancedInDatabase();
        ReconciliationRepository.Run baseline = reconciliations.run(0);
        // 재검산은 전수 검사다 — 실행 기록의 수치가 그 시점 DB의 실제 불균형 건수와 같아야 한다 (S5).
        assertThat(baseline.ledgerUnbalancedCount()).isEqualTo((int) before);

        insertUnbalancedTransaction();
        ReconciliationRepository.Run after = reconciliations.run(0);

        assertThat(after.ledgerUnbalancedCount()).isEqualTo((int) before + 1);
        assertThat(count("audit_logs", "action='LEDGER_UNBALANCED' AND resource_id=" + after.id()))
                .isEqualTo(1);
    }

    // ---- 헬퍼 ----

    private List<ReconciliationRepository.Discrepancy> openDiscrepancies(Long paymentId) {
        return repository.findDiscrepancies("OPEN", 500).stream()
                .filter(discrepancy -> paymentId.equals(discrepancy.paymentId()))
                .toList();
    }

    private List<DiscrepancyType> typesOf(Long paymentId) {
        return openDiscrepancies(paymentId).stream()
                .map(ReconciliationRepository.Discrepancy::type).toList();
    }

    private DiscrepancyType typeOfProvider(String providerPaymentId) {
        return repository.findDiscrepancies("OPEN", 500).stream()
                .filter(discrepancy -> providerPaymentId.equals(discrepancy.providerPaymentId()))
                .map(ReconciliationRepository.Discrepancy::type)
                .findFirst().orElse(null);
    }

    private Long runIdOfProvider(String providerPaymentId) {
        return jdbc.queryForObject(
                "SELECT run_id FROM reconciliation_discrepancies WHERE provider_payment_id=?",
                Long.class, providerPaymentId);
    }

    private Long lastSeenRunIdOfProvider(String providerPaymentId) {
        return jdbc.queryForObject(
                "SELECT last_seen_run_id FROM reconciliation_discrepancies WHERE provider_payment_id=?",
                Long.class, providerPaymentId);
    }

    private String providerPaymentIdOf(Long paymentId) {
        return jdbc.queryForObject("SELECT provider_payment_id FROM payments WHERE id=?", String.class,
                paymentId);
    }

    private Instant approvedAtOf(Long paymentId) {
        return jdbc.queryForObject("SELECT approved_at FROM payments WHERE id=?",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant(), paymentId);
    }

    /** PG에는 없는 성공 결제. 정상 경로로는 만들 수 없는 상태이므로 직접 심는다. */
    private Long insertSucceededPayment(Long orderId, String providerPaymentId) {
        return jdbc.queryForObject("""
                INSERT INTO payments(order_id,status,amount,provider_payment_id,approved_at,created_at,updated_at)
                VALUES(?,'SUCCEEDED',?,?, now() - interval '1 hour', now() - interval '1 hour', now())
                RETURNING id
                """, Long.class, orderId, DEAL_PRICE, providerPaymentId);
    }

    private long unbalancedInDatabase() {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT t.id FROM ledger_transactions t
                      LEFT JOIN ledger_entries e ON e.transaction_id = t.id
                     GROUP BY t.id
                    HAVING COALESCE(SUM(CASE WHEN e.side='DEBIT'  THEN e.amount ELSE 0 END),0)
                        <> COALESCE(SUM(CASE WHEN e.side='CREDIT' THEN e.amount ELSE 0 END),0)
                        OR count(e.id) = 0) x
                """, Long.class);
        return count == null ? 0L : count;
    }

    /**
     * 차변만 있는 거래를 심는다. 원장은 UPDATE·DELETE가 트리거로 막혀 있으므로(ADR-006) 되돌릴 수 없고,
     * 그래서 캠페인·주문에 귀속시키지 않는다 — 다른 테스트의 배치 범위 검사에 끼어들지 않게 하기 위해서다.
     */
    private void insertUnbalancedTransaction() {
        Long transactionId = jdbc.queryForObject("""
                INSERT INTO ledger_transactions
                    (transaction_type, reference_type, reference_id, occurred_at, created_at)
                VALUES('PAYMENT','PAYMENT', -%d, now(), now())
                RETURNING id
                """.formatted(System.nanoTime() % 1_000_000_000L), Long.class);
        jdbc.update("""
                INSERT INTO ledger_entries(transaction_id, account_id, side, amount)
                VALUES(?, (SELECT id FROM ledger_accounts WHERE code='PG_RECEIVABLE'), 'DEBIT', 1)
                """, transactionId);
    }
}
