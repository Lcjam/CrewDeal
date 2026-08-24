package com.groupdrop.settlement;

import com.groupdrop.common.AuditLogRepository;
import com.groupdrop.ledger.LedgerService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * SET-03 정산 후 환불의 회수 배치.
 *
 * <p>완료된 정산 데이터를 고치지 않는다. 대신 <b>음수 금액의 회수 배치</b>를 새로 만들고 LED-04의
 * 반대 분개로 지급 예정금의 음수 잔액(회수 채권)을 소거한다 — 원장이 불변이라는 규칙(LED-01)과
 * 같은 이유이며, "다음 정산에서 차감"하는 이월 방식은 캠페인 단위 정산에 다음 정산이 없을 수 있어
 * MVP에서 쓰지 않는다.
 *
 * <p>회수 대상 판정은 <b>{@code settlement_item}의 존재</b> 하나뿐이다 (SET-03). 지급 배치에 들어간 적이
 * 없는 주문(EXPIRED 출신 환불, 미확정 제외 건)의 환불은 LED-03 역분개만으로 끝난다 — 지급한 적 없는
 * 돈은 회수하지 않는다. 배치가 아직 지급되지 않았더라도 배치 금액은 동결되어 그대로 지급되므로,
 * 기획서대로 배치 상태와 무관하게 회수한다 (8.3 정산 확정 스냅숏 불변).
 *
 * <p>호출자의 트랜잭션 안에서 실행된다 — 역분개와 회수가 한 커밋이어야 "환불은 반영됐는데 회수는 없는"
 * 상태가 생기지 않는다.
 */
@Service
public class SettlementRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(SettlementRecoveryService.class);
    private static final String SOURCE = "settlement-recovery";

    private final SettlementRepository settlements;
    private final LedgerService ledger;
    private final AuditLogRepository auditLogs;
    private final SettlementMetrics metrics;
    private final Clock clock;

    public SettlementRecoveryService(SettlementRepository settlements, LedgerService ledger,
                                     AuditLogRepository auditLogs, SettlementMetrics metrics, Clock clock) {
        this.settlements = settlements;
        this.ledger = ledger;
        this.auditLogs = auditLogs;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * 환불 확정 후처리에서 호출한다.
     *
     * @return 생성한 회수 배치 ID. 회수 대상이 없거나 이미 회수된 재전달이면 비어 있다.
     */
    public List<Long> recoverForRefund(Long refundId, Long orderId) {
        List<SettlementRepository.SettledItem> settled = settlements.findSettledItemsOfOrder(orderId);
        if (settled.isEmpty()) {
            return List.of();
        }
        if (!ledger.hasRefundReversal(refundId)) {
            // 역분개가 없는 환불은 LED-02 원본이 없는 보상 환불(PAY-01)이다. 지급 예정금에 들어간 적이
            // 없으므로 회수할 채권도 없다. 페이로드 플래그가 아니라 원장에 묻는 이유는 재전달 때문이다.
            log.debug("환불 {}은 역분개가 없어 회수 대상이 아닙니다 (보상 환불).", refundId);
            return List.of();
        }

        Map<PayeeType, List<SettlementRepository.SettledItem>> byPayee = new EnumMap<>(PayeeType.class);
        for (SettlementRepository.SettledItem item : settled) {
            byPayee.computeIfAbsent(item.payeeType(), key -> new ArrayList<>()).add(item);
        }

        List<Long> recoveryBatchIds = new ArrayList<>();
        for (Map.Entry<PayeeType, List<SettlementRepository.SettledItem>> entry : byPayee.entrySet()) {
            Long batchId = recoverPayee(refundId, entry.getKey(), entry.getValue());
            if (batchId != null) {
                recoveryBatchIds.add(batchId);
            }
        }
        return recoveryBatchIds;
    }

    private Long recoverPayee(Long refundId, PayeeType payeeType,
                              List<SettlementRepository.SettledItem> items) {
        Instant now = Instant.now(clock);
        List<Long> adjustmentIds = new ArrayList<>();
        long total = 0L;
        for (SettlementRepository.SettledItem item : items) {
            Long adjustmentId = settlements
                    .insertAdjustmentIfAbsent(item.id(), item.batchId(), refundId, -item.amount(), now)
                    .orElse(null);
            if (adjustmentId == null) {
                // 같은 환불의 재전달. 이미 회수 채권이 있으므로 두 번 만들지 않는다 (13.4 at-least-once).
                continue;
            }
            adjustmentIds.add(adjustmentId);
            total += item.amount();
        }
        if (adjustmentIds.isEmpty()) {
            return null;
        }

        SettlementRepository.SettledItem sample = items.getFirst();
        // 회수 배치는 즉시 실행된다 (SET-03). 외부 호출은 없지만 10.5의 상태 전이표는 회수 배치에도
        // 적용되므로, 생성 상태 PENDING에서 조건부 UPDATE로 READY와 PROCESSING을 거쳐 완료한다.
        Long recoveryBatchId = settlements.insertBatch(sample.campaignId(), payeeType, sample.payeeId(),
                BatchType.RECOVERY, SettlementBatchStatus.PENDING, -total, now, now);
        if (!settlements.markReady(recoveryBatchId, now)) {
            throw new IllegalStateException("회수 배치 READY 전이에 실패했습니다: " + recoveryBatchId);
        }
        if (!settlements.claimForProcessing(recoveryBatchId, now)) {
            throw new IllegalStateException("회수 배치 PROCESSING 전이에 실패했습니다: " + recoveryBatchId);
        }
        settlements.attachRecoveryBatch(adjustmentIds, recoveryBatchId, now);
        ledger.recordRecovery(recoveryBatchId, sample.campaignId(), payeeType.payableAccount(), total, now, now);
        if (!settlements.markCompleted(recoveryBatchId, now)) {
            throw new IllegalStateException("회수 배치 완료 전이에 실패했습니다: " + recoveryBatchId);
        }
        auditLogs.record(SOURCE, "RECOVERY_COMPLETED", "SETTLEMENT_BATCH", recoveryBatchId,
                "환불 %d에 대해 %s 지급액 %d를 회수했습니다.".formatted(refundId, payeeType, total), now);
        metrics.recordRecovered();
        log.info("환불 {}에 대해 {} 회수 배치 {}를 실행했습니다 (금액 -{}).",
                refundId, payeeType, recoveryBatchId, total);
        return recoveryBatchId;
    }
}
