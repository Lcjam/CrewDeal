package com.groupdrop.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupdrop.order.CreateOrderRequest;
import com.groupdrop.order.OrderResponse;
import com.groupdrop.payment.AbstractPaymentIntegrationTest;
import com.groupdrop.payment.CreatePaymentRequest;
import com.groupdrop.refund.CreateRefundRequest;
import com.groupdrop.refund.RefundService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/**
 * 원장 (LED-01·02·03, S5).
 *
 * <p>금액 픽스처는 3.3이 요구한 대로 나누어떨어지지 않는다 — 공구가 19,900원 × 수수료율 7.5%는
 * 커미션이 1,492.5원이라 절사가 반드시 일어난다. 딱 떨어지는 값만 쓰면 라운딩 결함이 영원히
 * 테스트를 통과한다.
 */
class LedgerIntegrationTest extends AbstractPaymentIntegrationTest {

    private static final String ADMIN = "admin@groupdrop.test";

    /** 19,900 × 7.5% = 1,492.5 → 절사 1,492 */
    private static final long COMMISSION_1 = 1_492L;
    /** 19,900 × 3% = 597 */
    private static final long PG_FEE_1 = 597L;
    private static final long SUPPLY_1 = 1_000L;
    /** 잔여액 = 19,900 − 1,000 − 1,492 − 597 */
    private static final long PLATFORM_1 = 16_811L;

    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private ExpectedSettlementService expectedSettlements;

    @Test
    void LED_02_결제_확정_이벤트가_계정별로_분해된_원장_거래를_만든다() {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();

        assertThat(outboxWorker.drain()).isEqualTo(1);

        Long transactionId = paymentTransactionId(paymentId);
        assertThat(entryOf(transactionId, "PG_RECEIVABLE", "DEBIT")).isEqualTo(DEAL_PRICE);
        assertThat(entryOf(transactionId, "SUPPLIER_PAYABLE", "CREDIT")).isEqualTo(SUPPLY_1);
        assertThat(entryOf(transactionId, "INFLUENCER_PAYABLE", "CREDIT")).isEqualTo(COMMISSION_1);
        assertThat(entryOf(transactionId, "PG_FEE_PAYABLE", "CREDIT")).isEqualTo(PG_FEE_1);
        assertThat(entryOf(transactionId, "PLATFORM_REVENUE", "CREDIT")).isEqualTo(PLATFORM_1);
        // 절사한 두 항의 나머지가 플랫폼 잔여로 흡수되어 차대가 정확히 맞는다 (ADR-008).
        assertThat(SUPPLY_1 + COMMISSION_1 + PG_FEE_1 + PLATFORM_1).isEqualTo(DEAL_PRICE);
        assertThat(ledgerService.findUnbalancedTransactions()).isEmpty();
    }

    @Test
    void 결제_확정_이벤트가_재전달되어도_원장_거래는_1건이다() {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        assertThat(outboxWorker.drain()).isEqualTo(1);

        // 참조 유니크가 두 번째 분개를 막는다 — 애플리케이션 선조회에 기대지 않는다.
        boolean recorded = ledgerService.recordPayment(paymentId, order.orderId(), order.totalAmount(),
                Instant.now(), Instant.now());

        assertThat(recorded).isFalse();
        assertThat(count("ledger_transactions",
                "transaction_type='PAYMENT' AND reference_id=" + paymentId)).isEqualTo(1);
        assertThat(count("ledger_entries", "transaction_id=" + paymentTransactionId(paymentId))).isEqualTo(5);
    }

    @Test
    void LED_03_환불은_기존_원장을_수정하지_않고_역분개를_추가한다() {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        assertThat(outboxWorker.drain()).isEqualTo(1);
        Long paymentTransactionId = paymentTransactionId(paymentId);

        Long refundId = requestRefund(paymentId);
        drainAll();

        Long refundTransactionId = jdbc.queryForObject("""
                SELECT id FROM ledger_transactions WHERE transaction_type='REFUND' AND reference_id=?
                """, Long.class, refundId);
        // 원본 거래는 그대로 남아 있다 (LED-01: 완료된 거래는 수정·삭제하지 않는다).
        assertThat(count("ledger_entries", "transaction_id=" + paymentTransactionId)).isEqualTo(5);
        assertThat(entryOf(refundTransactionId, "PG_RECEIVABLE", "CREDIT")).isEqualTo(DEAL_PRICE);
        assertThat(entryOf(refundTransactionId, "SUPPLIER_PAYABLE", "DEBIT")).isEqualTo(SUPPLY_1);
        assertThat(entryOf(refundTransactionId, "INFLUENCER_PAYABLE", "DEBIT")).isEqualTo(COMMISSION_1);
        assertThat(entryOf(refundTransactionId, "PG_FEE_PAYABLE", "DEBIT")).isEqualTo(PG_FEE_1);
        assertThat(entryOf(refundTransactionId, "PLATFORM_REVENUE", "DEBIT")).isEqualTo(PLATFORM_1);

        // 결제·환불 쌍이 소거되어 캠페인 잔액이 전부 0이다 (11.8의 기대 결과).
        Map<String, Long> balances = campaignBalances(order.campaignId());
        assertThat(balances.values()).allMatch(value -> value == 0L);
    }

    @Test
    void S5_결제와_환불이_섞인_거래에서도_차변_합계와_대변_합계가_일치한다() {
        OrderFixture refunded = order(10, 1);
        Long refundedPaymentId = pay(refunded).body().id();
        OrderFixture kept = order(10, 2);
        Long keptPaymentId = pay(kept).body().id();
        drainAll();

        requestRefund(refundedPaymentId);
        drainAll();

        assertThat(ledgerService.findUnbalancedTransactions()).isEmpty();

        // 남은 주문(수량 2)의 분해도 절사 + 잔여 규칙을 따른다: 39,800 × 7.5% = 2,985 / 3% = 1,194
        Long keptTransactionId = paymentTransactionId(keptPaymentId);
        assertThat(entryOf(keptTransactionId, "INFLUENCER_PAYABLE", "CREDIT")).isEqualTo(2_985L);
        assertThat(entryOf(keptTransactionId, "PG_FEE_PAYABLE", "CREDIT")).isEqualTo(1_194L);
        assertThat(entryOf(keptTransactionId, "SUPPLIER_PAYABLE", "CREDIT")).isEqualTo(2_000L);
        assertThat(entryOf(keptTransactionId, "PLATFORM_REVENUE", "CREDIT")).isEqualTo(33_621L);
    }

    @Test
    void 원장_거래와_분개는_수정도_삭제도_할_수_없다() {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        assertThat(outboxWorker.drain()).isEqualTo(1);
        Long transactionId = paymentTransactionId(paymentId);

        // 12.3: 보정이 필요하면 반대 거래를 추가한다. DB가 그 규율을 강제한다 (ADR-006).
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount=1 WHERE transaction_id=?",
                transactionId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries WHERE transaction_id=?",
                transactionId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_transactions WHERE id=?",
                transactionId)).isInstanceOf(DataAccessException.class);
        assertThat(count("ledger_entries", "transaction_id=" + transactionId)).isEqualTo(5);
    }

    // ---- 예상 정산액 조회 (6.2, 6.3) ----

    @Test
    void 예상_정산액은_원장_잔액에서_읽고_환불분이_이미_빠져_있다() {
        OrderFixture order = order(10, 1);
        Long keptPaymentId = pay(order).body().id();
        drainAll();

        // 같은 캠페인에 두 번째 주문을 만들어 환불한다.
        Long refundedPaymentId = payAnotherOrderOn(order);
        drainAll();
        requestRefund(refundedPaymentId);
        drainAll();

        ExpectedSettlementService.CampaignSettlementBreakdown breakdown =
                expectedSettlements.breakdown(ADMIN, order.campaignId());

        assertThat(breakdown.settledOrderCount()).isEqualTo(1);
        assertThat(breakdown.refundedOrderCount()).isEqualTo(1);
        assertThat(breakdown.netSalesAmount()).isEqualTo(DEAL_PRICE);
        assertThat(breakdown.supplierPayable()).isEqualTo(SUPPLY_1);
        assertThat(breakdown.influencerCommission()).isEqualTo(COMMISSION_1);
        assertThat(breakdown.pgFee()).isEqualTo(PG_FEE_1);
        assertThat(breakdown.platformRevenue()).isEqualTo(PLATFORM_1);
        // 12.4의 총합 항등식은 정확 등식으로 성립해야 한다.
        assertThat(breakdown.supplierPayable() + breakdown.influencerCommission()
                + breakdown.pgFee() + breakdown.platformRevenue()).isEqualTo(breakdown.netSalesAmount());

        assertThat(keptPaymentId).isNotNull();
    }

    // ---- 헬퍼 ----

    private Long requestRefund(Long paymentId) {
        return refundService.requestRefund(ADMIN, paymentId, UUID.randomUUID().toString(),
                new CreateRefundRequest("테스트 환불")).body().id();
    }

    /** 같은 캠페인의 다른 주문을 결제한다 (구매 제한 100이므로 여유가 있다). */
    private Long payAnotherOrderOn(OrderFixture existing) {
        Long skuId = jdbc.queryForObject("""
                SELECT cs.product_sku_id FROM campaign_skus cs WHERE cs.campaign_id=? LIMIT 1
                """, Long.class, existing.campaignId());
        OrderResponse response = orderService.createOrder(BUYER, existing.campaignId(), key(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Item(skuId, 1))));
        return paymentService.requestPayment(BUYER, response.id(), key(),
                new CreatePaymentRequest(response.totalAmount())).body().id();
    }

    private void drainAll() {
        while (outboxWorker.drain() > 0) {
            // refund.requested → refund.completed까지 연쇄 처리
        }
    }

    private Long paymentTransactionId(Long paymentId) {
        return jdbc.queryForObject("""
                SELECT id FROM ledger_transactions WHERE transaction_type='PAYMENT' AND reference_id=?
                """, Long.class, paymentId);
    }

    private long entryOf(Long transactionId, String accountCode, String side) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(e.amount), 0) FROM ledger_entries e
                  JOIN ledger_accounts a ON a.id = e.account_id
                 WHERE e.transaction_id = ? AND a.code = ? AND e.side = ?
                """, Long.class, transactionId, accountCode, side);
    }

    private Map<String, Long> campaignBalances(Long campaignId) {
        Map<String, Long> balances = new LinkedHashMap<>();
        jdbc.query("""
                SELECT a.code, SUM(CASE WHEN e.side='CREDIT' THEN e.amount ELSE -e.amount END) AS balance
                  FROM ledger_entries e
                  JOIN ledger_accounts a ON a.id = e.account_id
                  JOIN ledger_transactions t ON t.id = e.transaction_id
                 WHERE t.campaign_id = ? GROUP BY a.code
                """, (rs, rowNum) -> balances.put(rs.getString("code"), rs.getLong("balance")), campaignId);
        return balances;
    }
}
