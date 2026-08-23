package com.groupdrop.ledger;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 결제·환불 분개 (LED-02, LED-03).
 *
 * <p>호출자의 트랜잭션 안에서 실행되어야 한다 — 분개와 원 상태 변경이 한 커밋이어야
 * "결제는 확정됐는데 원장에는 없는" 상태가 생기지 않는다.
 *
 * <p>라운딩은 ADR-008 하나뿐이다: 커미션과 PG 수수료는 <b>원 단위 절사</b>, 플랫폼 수익은
 * <b>잔여액</b>. 잔여 방식이라 주문 단위에서 차변·대변이 항상 정확히 일치하고, 정산액이 주문별
 * 원장 금액의 합으로 정의되므로 집계 라운딩 불일치가 구조적으로 발생하지 않는다.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private static final String TYPE_PAYMENT = "PAYMENT";
    private static final String TYPE_REFUND = "REFUND";
    private static final String REF_PAYMENT = "PAYMENT";
    private static final String REF_REFUND = "REFUND";

    /** PG 수수료 고정 비율 3% (기획서 5장). bp 정수 연산으로 계산한다. */
    private static final int PG_FEE_BP = 300;
    private static final int BP_SCALE = 10_000;

    private final LedgerRepository ledger;

    public LedgerService(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    /**
     * LED-02 결제 원장. 이미 같은 결제의 거래가 있으면 아무것도 하지 않는다 (이벤트 재전달).
     *
     * @return 이 호출이 분개를 기록했으면 true
     */
    public boolean recordPayment(Long paymentId, Long orderId, long paidAmount, Instant occurredAt, Instant now) {
        LedgerRepository.OrderPricing pricing = ledger.findOrderPricing(orderId)
                .orElseThrow(() -> new IllegalStateException("주문 가격 정보를 찾을 수 없습니다: " + orderId));
        if (pricing.totalAmount() != paidAmount) {
            // 12.2: 성공한 결제 금액 = 서버가 계산한 주문 결제 금액. 어긋나면 분개하지 않고 멈춘다 —
            // 틀린 금액을 원장에 넣으면 불변이라 지울 수 없다.
            throw new IllegalStateException("결제 금액과 주문 금액이 다릅니다: payment=%d order=%d"
                    .formatted(paidAmount, pricing.totalAmount()));
        }

        Breakdown breakdown = breakdownOf(pricing);
        Long transactionId = ledger.insertTransactionIfAbsent(TYPE_PAYMENT, REF_PAYMENT, paymentId,
                pricing.campaignId(), orderId, occurredAt, now).orElse(null);
        if (transactionId == null) {
            log.debug("결제 {}의 원장 거래가 이미 있어 분개를 건너뜁니다.", paymentId);
            return false;
        }

        post(transactionId, LedgerAccount.PG_RECEIVABLE, LedgerSide.DEBIT, breakdown.paidAmount());
        post(transactionId, LedgerAccount.SUPPLIER_PAYABLE, LedgerSide.CREDIT, breakdown.supplierPayable());
        post(transactionId, LedgerAccount.INFLUENCER_PAYABLE, LedgerSide.CREDIT, breakdown.influencerCommission());
        post(transactionId, LedgerAccount.PG_FEE_PAYABLE, LedgerSide.CREDIT, breakdown.pgFee());
        post(transactionId, LedgerAccount.PLATFORM_REVENUE, LedgerSide.CREDIT, breakdown.platformRevenue());
        return true;
    }

    /**
     * LED-03 환불 역분개. 금액을 다시 계산하지 않고 <b>원본 분개를 읽어 방향만 뒤집는다</b> —
     * 재계산하면 정책 스냅숏이 바뀌었을 때 결제와 환불이 어긋나 잔액이 영구히 남는다 (ADR-006).
     *
     * @return 이 호출이 역분개를 기록했으면 true. 원본 거래가 없으면(PAY-01 보상 환불 등) false.
     */
    public boolean recordRefundReversal(Long refundId, Long paymentId, Instant occurredAt, Instant now) {
        Long paymentTransactionId = ledger.findTransactionId(TYPE_PAYMENT, REF_PAYMENT, paymentId).orElse(null);
        if (paymentTransactionId == null) {
            log.info("결제 {}의 원장 거래가 없어 환불 {}의 역분개를 생략합니다 (수익 분해에 진입한 적 없는 결제).",
                    paymentId, refundId);
            return false;
        }
        List<LedgerRepository.Posting> original = ledger.findPostings(paymentTransactionId);
        if (original.isEmpty()) {
            throw new IllegalStateException("원장 거래 " + paymentTransactionId + "에 분개가 없습니다.");
        }

        // 역분개는 원본 거래와 같은 캠페인·주문에 귀속되어야 정산 집계가 서로를 상쇄한다.
        LedgerRepository.Scope scope = ledger.findTransactionScope(paymentTransactionId);
        Long transactionId = ledger.insertTransactionIfAbsent(TYPE_REFUND, REF_REFUND, refundId,
                scope.campaignId(), scope.orderId(), occurredAt, now).orElse(null);
        if (transactionId == null) {
            log.debug("환불 {}의 원장 거래가 이미 있어 역분개를 건너뜁니다.", refundId);
            return false;
        }

        for (LedgerRepository.Posting posting : original) {
            LedgerRepository.Posting reversed = posting.reversed();
            ledger.insertEntry(transactionId, reversed.account(), reversed.side(), reversed.amount());
        }
        return true;
    }

    /** S5: 모든 원장 거래에서 차변 합계 = 대변 합계 (LED-01, 12.3). */
    public List<LedgerRepository.Unbalanced> findUnbalancedTransactions() {
        return ledger.findUnbalancedTransactions();
    }

    /** ADR-008의 라운딩 규칙. 절사 두 번 + 잔여 한 번이며, 잔여는 정의상 차대를 정확히 맞춘다. */
    Breakdown breakdownOf(LedgerRepository.OrderPricing pricing) {
        long paidAmount = pricing.totalAmount();
        long supplierPayable = pricing.supplyTotal();
        long commission = Math.floorDiv(paidAmount * pricing.commissionRateBp(), BP_SCALE);
        long pgFee = Math.floorDiv(paidAmount * PG_FEE_BP, BP_SCALE);
        long platformRevenue = paidAmount - supplierPayable - commission - pgFee;
        if (platformRevenue < 0) {
            // CAM-01 마진 게이트가 SKU 단위 연속 마진 양수를 보장하므로 도달할 수 없는 경로다.
            // 도달했다면 게이트가 뚫린 것이므로 분개하지 않고 멈춘다 (원장은 되돌릴 수 없다).
            throw new IllegalStateException("플랫폼 잔여가 음수입니다. 마진 게이트(CAM-01) 위반: order total="
                    + paidAmount + " supplier=" + supplierPayable + " commission=" + commission + " fee=" + pgFee);
        }
        return new Breakdown(paidAmount, supplierPayable, commission, pgFee, platformRevenue);
    }

    private void post(Long transactionId, LedgerAccount account, LedgerSide side, long amount) {
        if (amount == 0) {
            // 13.2의 ledger_entry.amount > 0. 0원 분개는 정보가 없고 제약에 막힌다.
            return;
        }
        ledger.insertEntry(transactionId, account, side, amount);
    }

    record Breakdown(long paidAmount, long supplierPayable, long influencerCommission, long pgFee,
                     long platformRevenue) { }
}
