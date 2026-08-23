package com.groupdrop.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupdrop.payment.AbstractPaymentIntegrationTest;
import com.groupdrop.refund.CreateRefundRequest;
import com.groupdrop.refund.RefundService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SET-03 정산 후 환불의 회수 배치 (11.7)와 S8.
 *
 * <p>회수 판정 기준은 {@code settlement_item}의 존재 하나뿐이다 — 지급된 적 없는 돈은 회수하지 않는다.
 */
class SettlementRecoveryIntegrationTest extends AbstractPaymentIntegrationTest {

    private static final long SUPPLY_PER_UNIT = 1_000L;
    private static final long COMMISSION = 1_492L;

    @Autowired
    private SettlementService settlementService;
    @Autowired
    private SettlementRepository settlements;
    @Autowired
    private RefundService refundService;

    @Test
    void S8_정산_완료_후_환불하면_회수_배치가_자동으로_생성되고_지급_예정금이_0이_된다() {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        assertThat(outboxWorker.drain()).isEqualTo(1);
        closeCampaign(order.campaignId(), 8);
        settlementService.run(ADMIN, order.campaignId());
        assertThat(campaignStatus(order.campaignId())).isEqualTo("SETTLED");
        assertThat(balance(order.campaignId(), "SUPPLIER_PAYABLE")).isZero();

        Long refundId = refundService.requestRefund(ADMIN, paymentId, UUID.randomUUID().toString(),
                new CreateRefundRequest("정산 후 환불")).body().id();
        drainUntilQuiet();

        assertThat(jdbc.queryForObject("SELECT status FROM refunds WHERE id=?", String.class, refundId))
                .isEqualTo("COMPLETED");

        // 확정된 배치 금액은 수정하지 않는다 (8.3 정산 확정 스냅숏 불변).
        List<SettlementRepository.Batch> batches = settlements.findBatchesOfCampaign(order.campaignId());
        assertThat(batches.stream().filter(b -> b.batchType() == BatchType.SETTLEMENT))
                .allSatisfy(batch -> assertThat(batch.status()).isEqualTo(SettlementBatchStatus.COMPLETED));
        assertThat(settlementAmount(order.campaignId(), PayeeType.SUPPLIER)).isEqualTo(SUPPLY_PER_UNIT);

        // 수령 주체마다 음수 금액의 회수 배치가 하나씩, 즉시 완료 상태로 생긴다.
        List<SettlementRepository.Batch> recoveries = batches.stream()
                .filter(batch -> batch.batchType() == BatchType.RECOVERY).toList();
        assertThat(recoveries).hasSize(2);
        assertThat(recoveries).allSatisfy(batch -> {
            assertThat(batch.status()).isEqualTo(SettlementBatchStatus.COMPLETED);
            assertThat(batch.totalAmount()).isNegative();
        });
        assertThat(recoveryAmount(order.campaignId(), PayeeType.SUPPLIER)).isEqualTo(-SUPPLY_PER_UNIT);
        assertThat(recoveryAmount(order.campaignId(), PayeeType.INFLUENCER)).isEqualTo(-COMMISSION);

        // S8: 해당 수령 주체의 지급 예정금 잔액 0, 미회수 잔액 0.
        assertThat(balance(order.campaignId(), "SUPPLIER_PAYABLE")).isZero();
        assertThat(balance(order.campaignId(), "INFLUENCER_PAYABLE")).isZero();
        assertThat(balance(order.campaignId(), "PAYOUT_CASH")).isZero();
        assertThat(unrecoveredCount(order.campaignId())).isZero();
        assertThat(settlementService.unrecoveredAdjustments(ADMIN))
                .noneMatch(adjustment -> adjustment.campaignId().equals(order.campaignId()));

        // 조정은 환불당 수령 주체별 1건이고 전부 회수 배치에 귀속되어 있다 (13.2).
        assertThat(count("settlement_adjustments", "refund_id=" + refundId)).isEqualTo(2);
        assertThat(count("settlement_adjustments",
                "refund_id=" + refundId + " AND recovery_batch_id IS NOT NULL")).isEqualTo(2);
        assertThat(count("ledger_transactions", "transaction_type='RECOVERY' AND campaign_id="
                + order.campaignId())).isEqualTo(2);
        assertThat(settlements.countUnbalancedTransactions(List.of(order.orderId()))).isZero();
    }

    @Test
    void 회수_배치_생성은_환불_이벤트가_재전달되어도_한_번만_일어난다() {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        outboxWorker.drain();
        closeCampaign(order.campaignId(), 8);
        settlementService.run(ADMIN, order.campaignId());

        Long refundId = refundService.requestRefund(ADMIN, paymentId, UUID.randomUUID().toString(),
                new CreateRefundRequest("정산 후 환불")).body().id();
        drainUntilQuiet();

        // 13.4의 at-least-once. 같은 refund.completed를 다시 흘려도 조정·회수가 두 벌이 되면 안 된다.
        republishRefundCompleted(refundId, paymentId, order.orderId());
        drainUntilQuiet();

        assertThat(count("settlement_adjustments", "refund_id=" + refundId)).isEqualTo(2);
        assertThat(count("settlement_batches", "campaign_id=" + order.campaignId()
                + " AND batch_type='RECOVERY'")).isEqualTo(2);
        assertThat(count("ledger_transactions", "transaction_type='RECOVERY' AND campaign_id="
                + order.campaignId())).isEqualTo(2);
        assertThat(balance(order.campaignId(), "SUPPLIER_PAYABLE")).isZero();
    }

    @Test
    void 지급_배치에_포함된_적_없는_주문의_환불은_회수_대상이_아니다() {
        OrderFixture order = order(10, 1);
        Long paymentId = pay(order).body().id();
        outboxWorker.drain();

        // 정산 전에 환불한다 — settlement_item이 존재한 적이 없다.
        Long refundId = refundService.requestRefund(ADMIN, paymentId, UUID.randomUUID().toString(),
                new CreateRefundRequest("정산 전 환불")).body().id();
        drainUntilQuiet();

        assertThat(count("settlement_adjustments", "refund_id=" + refundId)).isZero();
        assertThat(count("settlement_batches", "campaign_id=" + order.campaignId())).isZero();
        // LED-03 역분개만으로 종결한다 — 지급한 적 없는 돈은 회수하지 않는다 (SET-03).
        assertThat(count("ledger_transactions",
                "transaction_type='REFUND' AND reference_id=" + refundId)).isEqualTo(1);
        assertThat(balance(order.campaignId(), "SUPPLIER_PAYABLE")).isZero();
    }

    // ---- 헬퍼 ----

    private long settlementAmount(Long campaignId, PayeeType payeeType) {
        return amountOf(campaignId, payeeType, BatchType.SETTLEMENT);
    }

    private long recoveryAmount(Long campaignId, PayeeType payeeType) {
        return amountOf(campaignId, payeeType, BatchType.RECOVERY);
    }

    private long amountOf(Long campaignId, PayeeType payeeType, BatchType batchType) {
        return settlements.findBatchesOfCampaign(campaignId).stream()
                .filter(batch -> batch.payeeType() == payeeType && batch.batchType() == batchType)
                .mapToLong(SettlementRepository.Batch::totalAmount).sum();
    }

    private long unrecoveredCount(Long campaignId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM settlement_adjustments a
                  JOIN settlement_batches b ON b.id = a.batch_id
                 WHERE b.campaign_id = ? AND a.recovery_batch_id IS NULL
                """, Long.class, campaignId);
        return count == null ? 0L : count;
    }

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

    private String campaignStatus(Long campaignId) {
        return jdbc.queryForObject("SELECT status FROM campaigns WHERE id=?", String.class, campaignId);
    }

    private void republishRefundCompleted(Long refundId, Long paymentId, Long orderId) {
        jdbc.update("""
                INSERT INTO outbox_events
                    (event_type, aggregate_type, aggregate_id, payload, status, attempts, available_at, created_at)
                VALUES('refund.completed','REFUND',?,?,'PENDING',0, now(), now())
                """, refundId, """
                {"refundId":%d,"paymentId":%d,"orderId":%d,"amount":%d,"compensation":false,"occurredAt":"%s"}"""
                .formatted(refundId, paymentId, orderId, DEAL_PRICE, java.time.Instant.now()));
    }

    private void drainUntilQuiet() {
        while (outboxWorker.drain() > 0) {
            // 접수 → 실행 → 확정 후처리(역분개 + 회수) 연쇄 소진
        }
    }
}
