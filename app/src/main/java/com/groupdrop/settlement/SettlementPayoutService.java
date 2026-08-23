package com.groupdrop.settlement;

import com.groupdrop.common.AuditLogRepository;
import com.groupdrop.ledger.LedgerAccount;
import com.groupdrop.ledger.LedgerService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 정산 배치의 검증과 지급 실행 (SET-02, LED-04, 10.5).
 *
 * <p>이 클래스에 클래스 수준 {@code @Transactional}이 없는 것은 설계다. 지급은
 * 선점({@code PROCESSING}) · 실행 · 결과 기록을 <b>별개 트랜잭션</b>으로 나눈다 — 하나로 묶으면
 * 실패 시 {@code attempts} 증가와 실패 사유까지 함께 롤백되어 "실패한 적 없는 배치"가 되고,
 * 17.4가 검증하려는 {@code PROCESSING → FAILED} 전이 자체가 관측 불가능해진다.
 */
@Service
public class SettlementPayoutService {

    private static final Logger log = LoggerFactory.getLogger(SettlementPayoutService.class);
    private static final String SOURCE = "settlement-payout";

    private final SettlementRepository settlements;
    private final LedgerService ledger;
    private final AuditLogRepository auditLogs;
    private final SettlementFailureInjector failureInjector;
    private final SettlementMetrics metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SettlementPayoutService(SettlementRepository settlements, LedgerService ledger,
                                   AuditLogRepository auditLogs, SettlementFailureInjector failureInjector,
                                   SettlementMetrics metrics, TransactionTemplate transactions, Clock clock) {
        this.settlements = settlements;
        this.ledger = ledger;
        this.auditLogs = auditLogs;
        this.failureInjector = failureInjector;
        this.metrics = metrics;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 검증 대기·지급 대기 배치를 한 번에 진행시킨다. 스케줄러와 테스트가 같은 경로를 쓴다.
     *
     * @return 상태가 바뀐 배치 수
     */
    public int drain(int limit) {
        List<SettlementRepository.Batch> batches = settlements.findBatchesInStatus(
                List.of(SettlementBatchStatus.PENDING, SettlementBatchStatus.READY), limit);
        int progressed = 0;
        for (SettlementRepository.Batch batch : batches) {
            if (batch.batchType() != BatchType.SETTLEMENT) {
                continue;
            }
            if (batch.status() == SettlementBatchStatus.PENDING && !verify(batch.id())) {
                progressed++;
                continue;
            }
            if (payout(batch.id()) != Outcome.SKIPPED) {
                progressed++;
            }
        }
        updateBlockedGauges();
        return progressed;
    }

    /**
     * SET-02 지급 전 대조. 세 가지를 본다: 배치 구성(소속 주문의 원장 합 = 배치 금액), 원장 균형
     * (차변 = 대변), 미해결 대사 불일치.
     *
     * <p>계정 <b>총잔액</b>과 배치 합계를 비교하지 않는 이유는 기획서가 명시한 그대로다 —
     * {@code ops_hold} 등으로 정산에서 정당하게 제외된 분개가 가짜 {@code HELD}를 유발한다.
     *
     * @return 검증을 통과해 READY가 되었으면 true, HELD로 갔으면 false
     */
    public boolean verify(Long batchId) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            SettlementRepository.Batch batch = settlements.findBatch(batchId).orElse(null);
            if (batch == null || batch.status() != SettlementBatchStatus.PENDING) {
                return Boolean.FALSE;
            }
            Instant now = Instant.now(clock);
            List<Long> orderIds = settlements.findBatchOrderIds(batchId);
            LedgerAccount account = batch.payeeType().payableAccount();

            long ledgerSum = settlements.sumLedgerPayable(orderIds, account, batch.determinedAt());
            if (ledgerSum != batch.totalAmount()) {
                return hold(batch, "배치 구성 불일치 — 원장 합 %d ≠ 배치 금액 %d"
                        .formatted(ledgerSum, batch.totalAmount()), now);
            }
            long unbalanced = settlements.countUnbalancedTransactions(orderIds);
            if (unbalanced > 0) {
                return hold(batch, "원장 불균형 거래 %d건".formatted(unbalanced), now);
            }
            long discrepancies = settlements.countOpenDiscrepancies(batch.campaignId());
            if (discrepancies > 0) {
                return hold(batch, "미해결 대사 불일치 %d건".formatted(discrepancies), now);
            }

            settlements.markReady(batchId, now);
            auditLogs.record(SOURCE, "SETTLEMENT_READY", "SETTLEMENT_BATCH", batchId,
                    "지급 전 대조를 통과했습니다 (주문 %d건, 금액 %d)".formatted(orderIds.size(),
                            batch.totalAmount()), now);
            return Boolean.TRUE;
        }));
    }

    private Boolean hold(SettlementRepository.Batch batch, String reason, Instant now) {
        settlements.markHeld(batch.id(), reason, now);
        auditLogs.record(SOURCE, "SETTLEMENT_HELD", "SETTLEMENT_BATCH", batch.id(), reason, now);
        metrics.recordHeld();
        log.warn("정산 배치 {}를 HELD로 전환했습니다 — {}", batch.id(), reason);
        return Boolean.FALSE;
    }

    /**
     * {@code READY·FAILED → PROCESSING → COMPLETED·FAILED} (10.5). 외부 호출이 없는 내부 가상 지급이므로
     * 실패는 주입으로만 발생한다 (17.4).
     */
    public Outcome payout(Long batchId) {
        Instant claimedAt = Instant.now(clock);
        if (!Boolean.TRUE.equals(transactions.execute(status ->
                settlements.claimForProcessing(batchId, claimedAt)))) {
            return Outcome.SKIPPED;
        }

        // ── PROCESSING 커밋 이후 ── 여기서 실패해도 배치는 PROCESSING으로 남아 있다가 아래에서 FAILED가 된다.
        if (failureInjector.shouldFail()) {
            return recordFailure(batchId, failureInjector.failureCode(), "테스트 주입 지급 실패 (17.4)");
        }

        try {
            return Boolean.TRUE.equals(transactions.execute(status -> {
                Instant now = Instant.now(clock);
                SettlementRepository.Batch batch = settlements.findBatch(batchId).orElseThrow();
                ledger.recordPayout(batch.id(), batch.campaignId(), batch.payeeType().payableAccount(),
                        batch.totalAmount(), now, now);
                if (!settlements.markCompleted(batchId, now)) {
                    throw new IllegalStateException("지급 완료 전이에 실패했습니다: " + batchId);
                }
                auditLogs.record(SOURCE, "SETTLEMENT_COMPLETED", "SETTLEMENT_BATCH", batchId,
                        "가상 지급을 실행했습니다 (금액 %d)".formatted(batch.totalAmount()), now);
                settlements.settleCampaignIfAllBatchesCompleted(batch.campaignId(), now);
                return Boolean.TRUE;
            })) ? completed(batchId) : Outcome.SKIPPED;
        } catch (RuntimeException exception) {
            log.error("정산 배치 {} 지급에 실패했습니다.", batchId, exception);
            return recordFailure(batchId, "PAYOUT_ERROR", exception.toString());
        }
    }

    private Outcome completed(Long batchId) {
        metrics.recordCompleted();
        log.info("정산 배치 {}의 지급을 완료했습니다.", batchId);
        return Outcome.COMPLETED;
    }

    private Outcome recordFailure(Long batchId, String failureCode, String reason) {
        transactions.executeWithoutResult(status -> {
            Instant now = Instant.now(clock);
            settlements.markFailed(batchId, failureCode, reason, now);
            auditLogs.record(SOURCE, "SETTLEMENT_FAILED", "SETTLEMENT_BATCH", batchId,
                    "%s: %s".formatted(failureCode, reason), now);
        });
        metrics.recordFailed();
        return Outcome.FAILED;
    }

    /** 운영자 재시도 (14.4). {@code FAILED → PROCESSING}은 10.5의 허용 전이다. */
    public Outcome retry(Long batchId) {
        return payout(batchId);
    }

    /** 운영자 보류 (14.4). {@code PROCESSING} 중 개입은 허용하지 않는다 (10.5). */
    public boolean holdByOperator(Long batchId, String reason, String actor) {
        Instant now = Instant.now(clock);
        boolean held = settlements.markHeld(batchId, reason, now);
        if (held) {
            auditLogs.record(actor, "SETTLEMENT_HELD_BY_OPERATOR", "SETTLEMENT_BATCH", batchId, reason, now);
            metrics.recordHeld();
        }
        return held;
    }

    /** 운영자의 불일치 해소 후 재검증 대기로 되돌린다 ({@code HELD → PENDING}, 10.5). */
    public boolean releaseHold(Long batchId, String actor) {
        Instant now = Instant.now(clock);
        boolean released = settlements.releaseHold(batchId, now);
        if (released) {
            auditLogs.record(actor, "SETTLEMENT_HOLD_RELEASED", "SETTLEMENT_BATCH", batchId,
                    "보류를 해제하고 재검증 대기로 되돌렸습니다.", now);
        }
        return released;
    }

    public void updateBlockedGauges() {
        metrics.updateBlocked(settlements.countUnrecoveredAdjustments(), settlements.countFailedOrHeldBatches());
    }

    public enum Outcome {
        COMPLETED,
        FAILED,
        /** 이 호출이 선점하지 못했다 (다른 실행이 처리 중이거나 상태가 대상이 아니다). */
        SKIPPED
    }
}
