package com.groupdrop.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupdrop.payment.AbstractPaymentIntegrationTest;
import com.groupdrop.payment.PaymentFinalizer;
import com.groupdrop.payment.PaymentRepository;
import com.groupdrop.payment.StubPgClient;
import com.groupdrop.refund.CreateRefundRequest;
import com.groupdrop.refund.RefundService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SET-01·SET-02 정산 대상 확정과 지급, 그리고 S6.
 *
 * <p>금액 픽스처는 3.3이 강제한 대로 나누어떨어지지 않는다 — 공구가 19,900원 × 수수료율 7.5%는
 * 1,492.5원이라 절사 규칙(ADR-008)이 실제로 걸린다. 딱 떨어지는 숫자만 쓰면 라운딩 결함이 영원히
 * 테스트를 통과한다.
 */
class SettlementFlowIntegrationTest extends AbstractPaymentIntegrationTest {

    private static final long SUPPLY_PER_UNIT = 1_000L;
    /** floor(19,900 × 7.5%) = floor(1,492.5) = 1,492 */
    private static final long COMMISSION = 1_492L;
    /** floor(19,900 × 3%) = floor(597.0) = 597 */
    private static final long PG_FEE = 597L;
    private static final long PLATFORM_REVENUE = DEAL_PRICE - SUPPLY_PER_UNIT - COMMISSION - PG_FEE;

    @Autowired
    private SettlementService settlementService;
    @Autowired
    private SettlementScheduler settlementScheduler;
    @Autowired
    private SettlementRepository settlements;
    @Autowired
    private RefundService refundService;
    @Autowired
    private PaymentFinalizer paymentFinalizer;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private TransactionTemplate transactions;

    @Test
    void 캠페인_종료부터_정산_완료까지_스케줄러_경로로_흐른다() {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        assertThat(outboxWorker.drain()).isEqualTo(1);
        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");

        closeCampaign(order.campaignId(), 8);
        settlementScheduler.runOnce();

        assertThat(campaignStatus(order.campaignId())).isEqualTo("SETTLED");
        List<SettlementRepository.Batch> batches = settlements.findBatchesOfCampaign(order.campaignId());
        assertThat(batches).hasSize(2);
        assertThat(batches).allSatisfy(batch -> {
            assertThat(batch.batchType()).isEqualTo(BatchType.SETTLEMENT);
            assertThat(batch.status()).isEqualTo(SettlementBatchStatus.COMPLETED);
            assertThat(batch.determinedAt()).isNotNull();
        });
        assertThat(batchOf(order.campaignId(), PayeeType.SUPPLIER).totalAmount()).isEqualTo(SUPPLY_PER_UNIT);
        assertThat(batchOf(order.campaignId(), PayeeType.INFLUENCER).totalAmount()).isEqualTo(COMMISSION);

        // 12.4 총합 항등식 — 정확 등식으로 성립해야 한다 (플랫폼 수익이 주문별 잔여액이므로).
        assertThat(SUPPLY_PER_UNIT + COMMISSION + PG_FEE + PLATFORM_REVENUE).isEqualTo(DEAL_PRICE);

        // LED-04: 지급 후 두 지급 예정금 계정 잔액은 0이고, 그만큼이 가상 현금으로 옮겨간다.
        assertThat(balance(order.campaignId(), "SUPPLIER_PAYABLE")).isZero();
        assertThat(balance(order.campaignId(), "INFLUENCER_PAYABLE")).isZero();
        assertThat(balance(order.campaignId(), "PAYOUT_CASH")).isEqualTo(SUPPLY_PER_UNIT + COMMISSION);
        // 지급 대상이 아닌 계정은 그대로 남는다 — 정산은 공급사·인플루언서에게만 나간다.
        assertThat(balance(order.campaignId(), "PG_FEE_PAYABLE")).isEqualTo(PG_FEE);
        assertThat(balance(order.campaignId(), "PLATFORM_REVENUE")).isEqualTo(PLATFORM_REVENUE);
        // 원장 균형 (S5): 이 캠페인의 모든 거래에서 차변 = 대변.
        assertThat(settlements.countUnbalancedTransactions(List.of(order.orderId()))).isZero();

        assertThat(count("ledger_transactions", "transaction_type='PAYOUT' AND campaign_id="
                + order.campaignId())).isEqualTo(2);
    }

    @Test
    void S6_정산을_재실행해도_주문_항목이_정상_정산_항목_2개에_포함되지_않는다() {
        OrderFixture order = order(10, 1);
        pay(order);
        outboxWorker.drain();
        closeCampaign(order.campaignId(), 8);

        settlementService.run(ADMIN, order.campaignId());
        SettlementService.RunResult second = settlementService.run(ADMIN, order.campaignId());

        // 캠페인이 이미 SETTLED라 확정 단계에서 걸린다 — 첫 번째 방어선.
        assertThat(second.outcome()).isEqualTo("SKIPPED");
        assertThat(settlements.findBatchesOfCampaign(order.campaignId())).hasSize(2);
        Long orderItemId = orderItemIdOf(order.orderId());
        assertThat(count("settlement_items", "order_item_id=" + orderItemId)).isEqualTo(2);
        assertThat(count("settlement_items",
                "order_item_id=" + orderItemId + " AND payee_type='SUPPLIER'")).isEqualTo(1);
        assertThat(count("settlement_items",
                "order_item_id=" + orderItemId + " AND payee_type='INFLUENCER'")).isEqualTo(1);

        // 두 번째 방어선은 DB다. 확정 단계를 우회해도 같은 수령 주체의 두 번째 항목은 들어가지 않는다
        // (ADR-009: 수령 주체가 다르면 다른 돈이므로 유니크는 수령 주체 축을 포함한다).
        Long supplierBatchId = batchOf(order.campaignId(), PayeeType.SUPPLIER).id();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO settlement_items(batch_id,payee_type,order_id,order_item_id,amount,created_at)
                VALUES(?,'SUPPLIER',?,?,?,now())
                """, supplierBatchId, order.orderId(), orderItemId, SUPPLY_PER_UNIT))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 미확정_결제가_남아_있으면_SETTLING에_진입하지_않고_CLOSED에_머문다() {
        OrderFixture order = order(10, 1);
        // PG가 성공했지만 응답이 유실되어 UNKNOWN으로 남은 결제 (PAY-03).
        pgClient.setMode(StubPgClient.Mode.SUCCEED_BUT_TIMEOUT);
        Long paymentId = pay(order).body().id();
        assertThat(paymentStatus(paymentId)).isEqualTo("UNKNOWN");

        closeCampaign(order.campaignId(), 8);
        SettlementService.RunResult result = settlementService.run(ADMIN, order.campaignId());

        assertThat(result.outcome()).isEqualTo("DEFERRED");
        assertThat(result.reason()).contains("미확정 결제 1건");
        assertThat(campaignStatus(order.campaignId())).isEqualTo("CLOSED");
        assertThat(settlements.findBatchesOfCampaign(order.campaignId())).isEmpty();
        assertThat(count("audit_logs",
                "action='SETTLING_DEFERRED' AND resource_id=" + order.campaignId())).isEqualTo(1);
    }

    @Test
    void 미처리_확정_이벤트가_남아_있으면_SETTLING에_진입하지_않는다() {
        OrderFixture order = order(10, 1);
        pay(order);
        // Outbox를 드레인하지 않는다 — 결제는 SUCCEEDED지만 주문은 아직 PENDING_PAYMENT다.
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");

        closeCampaign(order.campaignId(), 8);
        SettlementService.RunResult result = settlementService.run(ADMIN, order.campaignId());

        assertThat(result.outcome()).isEqualTo("DEFERRED");
        assertThat(result.reason()).contains("미처리 확정 이벤트 1건");
        assertThat(campaignStatus(order.campaignId())).isEqualTo("CLOSED");
    }

    @Test
    void FAILED_확정_이벤트도_재처리되어_PROCESSED가_될_때까지_SETTLING을_막는다() {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        jdbc.update("""
                UPDATE outbox_events SET status='FAILED', last_error='test', processed_at=now()
                 WHERE event_type='payment.finalized' AND aggregate_id=?
                """, paymentId);
        closeCampaign(order.campaignId(), 8);

        SettlementService.RunResult blocked = settlementService.run(ADMIN, order.campaignId());

        assertThat(blocked.outcome()).isEqualTo("DEFERRED");
        assertThat(blocked.reason()).contains("미처리 확정 이벤트 1건");
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(campaignStatus(order.campaignId())).isEqualTo("CLOSED");

        jdbc.update("""
                UPDATE outbox_events SET status='PENDING', attempts=0, available_at=now(), processed_at=NULL
                 WHERE event_type='payment.finalized' AND aggregate_id=?
                """, paymentId);
        assertThat(outboxWorker.drain()).isEqualTo(1);
        assertThat(settlementService.run(ADMIN, order.campaignId()).outcome()).isEqualTo("CREATED");
    }

    @Test
    void 결제_확정과_정산_확정이_경쟁해도_결제_주문을_동결_스냅숏에서_누락하지_않는다() throws Exception {
        OrderFixture order = order(10, 1);
        Long paymentId = jdbc.queryForObject("""
                INSERT INTO payments(order_id,status,amount,created_at,updated_at)
                VALUES(?,'PROCESSING',?,now(),now()) RETURNING id
                """, Long.class, order.orderId(), order.totalAmount());
        closeCampaign(order.campaignId(), 8);

        CountDownLatch finalizedBeforeCommit = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> finalize = executor.submit(() -> transactions.executeWithoutResult(status -> {
                // 실제 finalizer와 동일한 캠페인 장벽을 먼저 소유한 뒤, 확정과 이벤트를 아직 커밋하지 않는다.
                jdbc.queryForObject("SELECT id FROM campaigns WHERE id=? FOR UPDATE", Long.class,
                        order.campaignId());
                paymentFinalizer.succeed(paymentRepository.findPayment(paymentId).orElseThrow(),
                        "pg_settlement_race_" + paymentId, java.time.Instant.now(), "test");
                finalizedBeforeCommit.countDown();
                try {
                    if (!allowCommit.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("결제 확정 경쟁 테스트 대기 시간 초과");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(finalizedBeforeCommit.await(20, TimeUnit.SECONDS)).isTrue();

            Future<SettlementService.RunResult> determination =
                    executor.submit(() -> settlementService.run(ADMIN, order.campaignId()));
            try {
                // 정산은 같은 캠페인 장벽에서 기다려야 하며, 미커밋 SUCCEEDED를 건너뛰어서는 안 된다.
                Thread.sleep(200);
                assertThat(determination.isDone()).isFalse();
            } finally {
                allowCommit.countDown();
            }

            finalize.get(20, TimeUnit.SECONDS);
            SettlementService.RunResult first = determination.get(20, TimeUnit.SECONDS);
            assertThat(first.outcome()).isEqualTo("DEFERRED");
            assertThat(first.reason()).contains("미처리 확정 이벤트 1건");
        }

        assertThat(campaignStatus(order.campaignId())).isEqualTo("CLOSED");
        assertThat(outboxWorker.drain()).isEqualTo(1);
        assertThat(orderStatus(order.orderId())).isEqualTo("PAID");

        SettlementService.RunResult second = settlementService.run(ADMIN, order.campaignId());
        assertThat(second.outcome()).isEqualTo("CREATED");
        assertThat(settlements.findBatchesOfCampaign(order.campaignId())).hasSize(2);
    }

    @Test
    void 전액_환불된_캠페인은_배치_없이_SETTLED로_종결한다() {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        outboxWorker.drain();
        refundService.requestRefund(ADMIN, paymentId, UUID.randomUUID().toString(),
                new CreateRefundRequest("전액 환불"));
        drainUntilQuiet();
        assertThat(paymentStatus(paymentId)).isEqualTo("REFUNDED");

        closeCampaign(order.campaignId(), 8);
        SettlementService.RunResult result = settlementService.run(ADMIN, order.campaignId());

        // 0원 배치는 ledger_entry.amount > 0과 충돌하므로 만들지 않고, 공집합 판정도 하지 않는다 (SET-02).
        assertThat(result.outcome()).isEqualTo("SETTLED_WITHOUT_BATCH");
        assertThat(settlements.findBatchesOfCampaign(order.campaignId())).isEmpty();
        assertThat(campaignStatus(order.campaignId())).isEqualTo("SETTLED");
        assertThat(count("audit_logs",
                "action='SETTLED_WITHOUT_BATCH' AND resource_id=" + order.campaignId())).isEqualTo(1);
    }

    @Test
    void 거래_발생_시각_OPEN_불일치가_있으면_지급_전_대조에서_HELD로_전환한다() {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        outboxWorker.drain();
        openDiscrepancyOn(paymentId, order.orderId());

        closeCampaign(order.campaignId(), 8);
        settlementService.run(ADMIN, order.campaignId());

        List<SettlementRepository.Batch> batches = settlements.findBatchesOfCampaign(order.campaignId());
        assertThat(batches).hasSize(2);
        assertThat(batches).allSatisfy(batch -> {
            assertThat(batch.status()).isEqualTo(SettlementBatchStatus.HELD);
            assertThat(batch.holdReason()).contains("미해결 대사 불일치");
        });
        // 지급하지 않았으므로 원장에 PAYOUT 거래가 없고 지급 예정금이 그대로 남는다.
        assertThat(count("ledger_transactions",
                "transaction_type='PAYOUT' AND campaign_id=" + order.campaignId())).isZero();
        assertThat(balance(order.campaignId(), "SUPPLIER_PAYABLE")).isEqualTo(SUPPLY_PER_UNIT);
        assertThat(campaignStatus(order.campaignId())).isEqualTo("SETTLING");

        // 운영자가 불일치를 해소하고 보류를 풀면 재검증 → 지급까지 이어진다 (10.5의 HELD → PENDING).
        jdbc.update("UPDATE reconciliation_discrepancies SET status='RESOLVED' WHERE payment_id=?", paymentId);
        batches.forEach(batch -> settlementService.release(ADMIN, batch.id()));
        settlementScheduler.runOnce();

        assertThat(settlements.findBatchesOfCampaign(order.campaignId()))
                .allSatisfy(batch -> assertThat(batch.status()).isEqualTo(SettlementBatchStatus.COMPLETED));
        assertThat(campaignStatus(order.campaignId())).isEqualTo("SETTLED");
    }

    @Test
    void 유예기간이_지나지_않은_캠페인은_정산_스캔에_잡히지_않는다() {
        OrderFixture order = order(10, 1);
        pay(order);
        outboxWorker.drain();
        closeCampaign(order.campaignId(), 3);

        settlementScheduler.runOnce();

        assertThat(campaignStatus(order.campaignId())).isEqualTo("CLOSED");
        assertThat(settlements.findBatchesOfCampaign(order.campaignId())).isEmpty();
    }

    @Test
    void 항목이_여러_개인_주문도_항목_합계가_원장의_주문_금액과_정확히_일치한다() {
        // SKU 2개 × 1개씩 = 39,800원. 커미션 floor(39,800 × 7.5%) = 2,985원은 두 항목으로 나누어떨어지지
        // 않는다 (각 1,492.5). 잔여 흡수 규칙이 실제로 걸리는 픽스처다 (3.3, ADR-008).
        TwoItemOrder order = twoItemOrder();
        pay(order.orderId(), order.totalAmount());
        assertThat(outboxWorker.drain()).isEqualTo(1);
        closeCampaign(order.campaignId(), 8);

        settlementService.run(ADMIN, order.campaignId());

        long influencerTotal = batchOf(order.campaignId(), PayeeType.INFLUENCER).totalAmount();
        long supplierTotal = batchOf(order.campaignId(), PayeeType.SUPPLIER).totalAmount();
        assertThat(influencerTotal).isEqualTo(2_985L);
        assertThat(supplierTotal).isEqualTo(2_000L);

        // 항목별 금액은 나누어떨어지지 않아 1원이 갈리지만, 합계는 배치 금액과 정확히 같아야 한다.
        assertThat(itemAmounts(order.campaignId(), PayeeType.INFLUENCER))
                .containsExactly(1_492L, 1_493L)
                .satisfies(amounts -> assertThat(amounts.stream().mapToLong(Long::longValue).sum())
                        .isEqualTo(influencerTotal));
        assertThat(itemAmounts(order.campaignId(), PayeeType.SUPPLIER))
                .containsExactly(1_000L, 1_000L)
                .satisfies(amounts -> assertThat(amounts.stream().mapToLong(Long::longValue).sum())
                        .isEqualTo(supplierTotal));

        // 배치 금액의 원천은 원장이며, 지급 후 두 계정 잔액이 0이 되는 것이 그 증거다.
        assertThat(balance(order.campaignId(), "INFLUENCER_PAYABLE")).isZero();
        assertThat(balance(order.campaignId(), "SUPPLIER_PAYABLE")).isZero();
        assertThat(campaignStatus(order.campaignId())).isEqualTo("SETTLED");
    }

    // ---- 헬퍼 ----

    private List<Long> itemAmounts(Long campaignId, PayeeType payeeType) {
        return jdbc.queryForList("""
                SELECT si.amount FROM settlement_items si
                  JOIN settlement_batches b ON b.id = si.batch_id
                 WHERE b.campaign_id = ? AND si.payee_type = ?
                 ORDER BY si.order_item_id
                """, Long.class, campaignId, payeeType.name());
    }

    /**
     * SKU 2개짜리 주문. 기반 클래스의 픽스처는 SKU 1개라 항목별 배분이 아예 일어나지 않는다 —
     * 잔여 흡수 규칙을 검증하려면 항목이 둘 이상이어야 한다.
     */
    private TwoItemOrder twoItemOrder() {
        java.time.Instant now = java.time.Instant.now();
        java.sql.Timestamp ts = java.sql.Timestamp.from(now);
        long supplierId = jdbc.queryForObject("SELECT id FROM suppliers LIMIT 1", Long.class);
        long influencerId = jdbc.queryForObject("SELECT id FROM influencers LIMIT 1", Long.class);
        long productId = jdbc.queryForObject(
                "INSERT INTO products(supplier_id,name,created_at) VALUES(?,?,?) RETURNING id",
                Long.class, supplierId, "multi-item-" + java.util.UUID.randomUUID(), ts);
        long campaignId = jdbc.queryForObject("""
                INSERT INTO campaigns(name,slug,influencer_id,supplier_id,product_id,status,deal_price,
                    per_user_purchase_limit,starts_at,ends_at,created_at,updated_at)
                VALUES(?,?,?,?,?,'OPEN',?,100,?,?,?,?) RETURNING id
                """, Long.class, "multi-item-campaign", "multi-" + java.util.UUID.randomUUID(), influencerId,
                supplierId, productId, DEAL_PRICE, java.sql.Timestamp.from(now.minusSeconds(60)),
                java.sql.Timestamp.from(now.plusSeconds(3600)), ts, ts);
        long policyId = jdbc.queryForObject("""
                INSERT INTO campaign_policy_versions(campaign_id,version_no,commission_rate_bp,created_at)
                VALUES(?,1,750,?) RETURNING id
                """, Long.class, campaignId, ts);

        List<Long> productSkuIds = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            long skuId = jdbc.queryForObject(
                    "INSERT INTO product_skus(product_id,option_name,created_at) VALUES(?,?,?) RETURNING id",
                    Long.class, productId, "sku-" + java.util.UUID.randomUUID(), ts);
            long campaignSkuId = jdbc.queryForObject(
                    "INSERT INTO campaign_skus(campaign_id,product_sku_id) VALUES(?,?) RETURNING id",
                    Long.class, campaignId, skuId);
            jdbc.update("""
                    INSERT INTO campaign_inventories(campaign_sku_id,initial_quantity,available_quantity)
                    VALUES(?,10,10)
                    """, campaignSkuId);
            jdbc.update("""
                    INSERT INTO campaign_policy_version_items(policy_version_id,campaign_sku_id,supply_unit_price)
                    VALUES(?,?,1000)
                    """, policyId, campaignSkuId);
            productSkuIds.add(skuId);
        }

        com.groupdrop.order.OrderResponse order = orderService.createOrder(BUYER, campaignId, key(),
                new com.groupdrop.order.CreateOrderRequest(productSkuIds.stream()
                        .map(id -> new com.groupdrop.order.CreateOrderRequest.Item(id, 1)).toList()));
        return new TwoItemOrder(order.id(), campaignId, order.totalAmount());
    }

    private com.groupdrop.payment.PaymentService.Outcome pay(Long orderId, long amount) {
        return paymentService.requestPayment(BUYER, orderId, key(),
                new com.groupdrop.payment.CreatePaymentRequest(amount));
    }

    private record TwoItemOrder(Long orderId, Long campaignId, long totalAmount) { }


    private SettlementRepository.Batch batchOf(Long campaignId, PayeeType payeeType) {
        return settlements.findBatchesOfCampaign(campaignId).stream()
                .filter(batch -> batch.payeeType() == payeeType && batch.batchType() == BatchType.SETTLEMENT)
                .findFirst().orElseThrow();
    }

    private String campaignStatus(Long campaignId) {
        return jdbc.queryForObject("SELECT status FROM campaigns WHERE id=?", String.class, campaignId);
    }

    private Long orderItemIdOf(Long orderId) {
        return jdbc.queryForObject("SELECT id FROM order_items WHERE order_id=?", Long.class, orderId);
    }

    /** 계정 잔액(대변 − 차변)을 캠페인 범위로 좁혀 읽는다 — 전역 합계는 다른 테스트에 오염된다. */
    private long balance(Long campaignId, String accountCode) {
        Long balance = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN e.side='CREDIT' THEN e.amount ELSE -e.amount END), 0)
                  FROM ledger_entries e
                  JOIN ledger_transactions t ON t.id = e.transaction_id
                  JOIN ledger_accounts a ON a.id = e.account_id
                 WHERE t.campaign_id = ? AND a.code = ?
                """, Long.class, campaignId, accountCode);
        return balance == null ? 0L : balance;
    }

    private void openDiscrepancyOn(Long paymentId, Long orderId) {
        Long runId = jdbc.queryForObject("""
                INSERT INTO reconciliation_runs(status, min_age_minutes, started_at)
                VALUES('COMPLETED', 0, now()) RETURNING id
                """, Long.class);
        jdbc.update("""
                INSERT INTO reconciliation_discrepancies
                    (run_id, last_seen_run_id, discrepancy_type, payment_id, order_id, status,
                     detail, detected_at, updated_at)
                VALUES(?, ?, 'OCCURRED_AT_MISMATCH', ?, ?, 'OPEN',
                       '결제 발생 시각 차이 1001ms 테스트 주입', now(), now())
                """, runId, runId, paymentId, orderId);
    }

    private void drainUntilQuiet() {
        while (outboxWorker.drain() > 0) {
            // 환불 접수 → 실행 → 확정 후처리까지 연쇄 소진
        }
    }
}
