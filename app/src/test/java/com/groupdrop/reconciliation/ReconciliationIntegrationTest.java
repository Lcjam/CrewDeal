package com.groupdrop.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupdrop.payment.AbstractPaymentIntegrationTest;
import com.groupdrop.payment.StubPgClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * REC-01 대사 — 해소 단계(S4-b), 7개 유형 분류(S7), 원장 재검산(S5·16.4).
 *
 * <p>어서션은 전부 이 테스트가 만든 결제·주문 ID로 범위를 좁힌다. 대사는 전역 스캔이고 통합 테스트는
 * DB를 공유하므로, 전역 건수 어서션은 다른 테스트의 잔여물에 오염된다.
 */
class ReconciliationIntegrationTest extends AbstractPaymentIntegrationTest {

    @Autowired
    private ReconciliationService reconciliations;
    @Autowired
    private ReconciliationRepository repository;

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
                DEAL_PRICE + 1_000L, "SUCCEEDED", Instant.now().minusSeconds(60), 0L);

        // ② 상태 불일치 — 내부는 SUCCEEDED인데 PG는 FAILED.
        OrderFixture statusOrder = order(10, 1);
        Long statusPaymentId = pay(statusOrder).body().id();
        outboxWorker.drain();
        pgClient.injectTransaction(providerPaymentIdOf(statusPaymentId), statusOrder.orderId(),
                DEAL_PRICE, "FAILED", Instant.now().minusSeconds(60), 0L);

        // ③ 환불 금액 불일치 — PG에만 환불이 있다.
        OrderFixture refundOrder = order(10, 1);
        Long refundPaymentId = pay(refundOrder).body().id();
        outboxWorker.drain();
        pgClient.injectTransaction(providerPaymentIdOf(refundPaymentId), refundOrder.orderId(),
                DEAL_PRICE, "SUCCEEDED", Instant.now().minusSeconds(60), DEAL_PRICE);

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

        reconciliations.run(0);

        assertThat(typesOf(amountPaymentId)).contains(DiscrepancyType.AMOUNT_MISMATCH);
        assertThat(typesOf(statusPaymentId)).contains(DiscrepancyType.STATUS_MISMATCH);
        assertThat(typesOf(refundPaymentId)).contains(DiscrepancyType.REFUND_MISMATCH);
        assertThat(typesOf(missingPaymentId)).containsExactly(DiscrepancyType.MISSING_PROVIDER);
        assertThat(typeOfProvider(ghostProviderId)).isEqualTo(DiscrepancyType.MISSING_INTERNAL);
        assertThat(typeOfProvider(duplicateProviderId)).isEqualTo(DiscrepancyType.DUPLICATE_PAYMENT);
        // 내부 승자는 보상 대상이 아니다 — 대사는 PAY-01의 결과를 재선정하지 않는다.
        assertThat(typeOfProvider(providerPaymentIdOf(duplicatePaymentId)))
                .isNotEqualTo(DiscrepancyType.DUPLICATE_PAYMENT);

        // 조회 API에 미해결로 노출된다 (REC-02 목록).
        List<ReconciliationRepository.Discrepancy> open = repository.findDiscrepancies("OPEN", 200);
        assertThat(open).extracting(ReconciliationRepository.Discrepancy::providerPaymentId)
                .contains(ghostProviderId, duplicateProviderId, missingProviderId);
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
                Instant.now().minusSeconds(60), 0L);
        reconciliations.run(0);

        assertThat(openDiscrepancies(paymentId)).isEmpty();
        assertThat(count("reconciliation_discrepancies",
                "payment_id=" + paymentId + " AND status='RESOLVED'")).isEqualTo(1);
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
