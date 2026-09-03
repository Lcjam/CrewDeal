package com.groupdrop.settlement;

import com.groupdrop.common.AuditLogRepository;
import com.groupdrop.common.CampaignTransactionBarrier;
import com.groupdrop.common.GroupdropProperties;
import com.groupdrop.ledger.LedgerAccount;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SET-01·SET-02 정산 대상 확정. 캠페인 종료 + 유예기간 경과 후 {@code CLOSED → SETTLING}으로 넘기면서
 * 대상·금액·확정 시각을 <b>1회</b> 동결한다.
 *
 * <p>금액의 원천은 원장 하나다 (ADR-008). SET-01의 집계식으로 다시 계산하지 않으며, 그 식은
 * 검증용 불변식으로만 쓴다 — 원천이 둘이면 라운딩 차이만으로 정상 캠페인이 HELD에 빠진다.
 *
 * <p>진입 전제 두 가지(Outbox 드레인, 미확정 결제 0건)를 만족하지 못하면 캠페인은 {@code CLOSED}에
 * 머문다. "일단 제외하고 HELD"가 아니라 "진입하지 않는다"인 이유는, 늦게 확정된 대금이 동결 스냅숏
 * 밖에서 영구히 고립되는 것을 막기 위해서다 (SET-02).
 */
@Service
public class SettlementDeterminationService {

    private static final Logger log = LoggerFactory.getLogger(SettlementDeterminationService.class);
    private static final String SOURCE = "settlement-determination";

    private final SettlementRepository settlements;
    private final CampaignTransactionBarrier campaignBarrier;
    private final AuditLogRepository auditLogs;
    private final SettlementMetrics metrics;
    private final GroupdropProperties properties;
    private final Clock clock;

    public SettlementDeterminationService(SettlementRepository settlements,
                                          CampaignTransactionBarrier campaignBarrier,
                                          AuditLogRepository auditLogs,
                                          SettlementMetrics metrics, GroupdropProperties properties, Clock clock) {
        this.settlements = settlements;
        this.campaignBarrier = campaignBarrier;
        this.auditLogs = auditLogs;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 한 캠페인의 정산 대상을 확정한다. 스케줄러와 운영자 API가 같은 경로를 쓴다.
     *
     * <p>전체가 한 트랜잭션인 이유: 캠페인 전이와 배치·항목 생성이 갈라지면 "SETTLING인데 배치가 없는"
     * 캠페인이 남고, 그 상태는 "전액 환불되어 배치가 0개"와 구분할 수 없다.
     */
    @Transactional
    public Result determine(Long campaignId) {
        if (campaignBarrier.lockByCampaignId(campaignId).isEmpty()) {
            return Result.notFound(campaignId);
        }
        SettlementRepository.CampaignSettlementContext context = settlements.findCampaignContext(campaignId)
                .orElse(null);
        if (context == null) {
            return Result.notFound(campaignId);
        }
        if (!"CLOSED".equals(context.status())) {
            return Result.skipped(campaignId, "캠페인 상태가 CLOSED가 아닙니다: " + context.status());
        }

        Instant now = Instant.now(clock);
        Instant graceCutoff = now.minus(properties.settlementGracePeriod());
        if (context.closedAt() == null || context.closedAt().isAfter(graceCutoff)) {
            String reason = "정산 유예기간이 아직 경과하지 않았습니다.";
            auditLogs.record(SOURCE, "SETTLING_DEFERRED", "CAMPAIGN", campaignId, reason, now);
            metrics.recordDeferred();
            log.info("캠페인 {}의 정산 진입을 보류합니다 — {}", campaignId, reason);
            return Result.deferred(campaignId, reason);
        }

        long pendingEvents = settlements.countUnprocessedFinalizationEvents(campaignId);
        long unfinalized = settlements.countUnfinalizedPayments(campaignId);
        if (pendingEvents > 0 || unfinalized > 0) {
            String reason = "미처리 확정 이벤트 %d건, 미확정 결제 %d건".formatted(pendingEvents, unfinalized);
            auditLogs.record(SOURCE, "SETTLING_DEFERRED", "CAMPAIGN", campaignId, reason, now);
            metrics.recordDeferred();
            log.info("캠페인 {}의 정산 진입을 보류합니다 — {}", campaignId, reason);
            return Result.deferred(campaignId, reason);
        }

        if (!settlements.enterSettling(campaignId, now)) {
            // 다른 인스턴스가 먼저 전이시켰다. 배치 생성도 그쪽 트랜잭션의 몫이다.
            return Result.skipped(campaignId, "다른 실행이 이미 SETTLING으로 전이했습니다.");
        }

        List<Long> batchIds = new ArrayList<>();
        for (PayeeType payeeType : PayeeType.values()) {
            Long batchId = createBatch(context, payeeType, now);
            if (batchId != null) {
                batchIds.add(batchId);
            }
        }

        if (batchIds.isEmpty()) {
            // 전액 환불된 캠페인 등. 0원 배치는 ledger_entry.amount > 0과 충돌하므로 만들지 않고,
            // 공집합에 대한 "전부 COMPLETED" 판정도 미정의이므로 여기서 곧장 SETTLED로 보낸다 (SET-02).
            settlements.settleCampaignIfAllBatchesCompleted(campaignId, now);
            auditLogs.record(SOURCE, "SETTLED_WITHOUT_BATCH", "CAMPAIGN", campaignId,
                    "정산 대상 금액이 0이라 배치 없이 SETTLED로 종결했습니다.", now);
            log.info("캠페인 {}은 정산 대상 금액이 0이라 배치 없이 SETTLED로 종결했습니다.", campaignId);
            return Result.settledWithoutBatch(campaignId);
        }

        auditLogs.record(SOURCE, "SETTLEMENT_DETERMINED", "CAMPAIGN", campaignId,
                "정산 배치 %d개를 생성했습니다: %s".formatted(batchIds.size(), batchIds), now);
        metrics.recordDetermined(batchIds.size());
        return Result.created(campaignId, batchIds);
    }

    private Long createBatch(SettlementRepository.CampaignSettlementContext context, PayeeType payeeType,
                             Instant determinedAt) {
        LedgerAccount account = payeeType.payableAccount();
        List<SettlementRepository.OrderPayable> payables =
                settlements.findPayableOrders(context.campaignId(), account, determinedAt);
        long total = payables.stream().mapToLong(SettlementRepository.OrderPayable::amount).sum();
        if (total <= 0) {
            // 정산 대상이 0건인 수령 주체의 배치는 생성하지 않는다 (SET-02).
            return null;
        }

        Long payeeId = payeeType == PayeeType.SUPPLIER ? context.supplierId() : context.influencerId();
        Long batchId = settlements.insertBatch(context.campaignId(), payeeType, payeeId, BatchType.SETTLEMENT,
                SettlementBatchStatus.PENDING, total, determinedAt, determinedAt);
        for (SettlementRepository.OrderPayable payable : payables) {
            insertItems(batchId, payeeType, payable, determinedAt);
        }
        return batchId;
    }

    /**
     * 주문 금액을 항목 단위로 배분한다. 항목별 금액은 표시·회수 단위이고, <b>합계는 반드시 원장의
     * 주문 금액과 정확히 일치</b>해야 한다 — 그래서 마지막 항목이 잔여를 흡수한다 (ADR-008의 잔여 방식).
     *
     * <p>공급사는 항목별 공급 단가 × 수량이 원장 금액의 정확한 분해라 잔여가 항상 0이고, 인플루언서
     * 커미션은 주문 단위 절사값이라 정확한 분해가 존재하지 않으므로 상품 금액 비율로 나눈다.
     *
     * <p>배분 결과가 0원인 항목은 행을 만들지 않는다 ({@code settlement_items.amount > 0}). 지급된 적이
     * 없으므로 SET-03의 회수 대상도 아니며, 두 규칙이 같은 방향을 가리킨다.
     */
    private void insertItems(Long batchId, PayeeType payeeType, SettlementRepository.OrderPayable payable,
                             Instant now) {
        List<SettlementRepository.OrderItemBasis> bases = settlements.findOrderItemBases(payable.orderId());
        if (bases.isEmpty()) {
            throw new IllegalStateException("정산 대상 주문에 항목이 없습니다: " + payable.orderId());
        }
        long basisTotal = bases.stream().mapToLong(basis -> basisOf(payeeType, basis)).sum();
        long assigned = 0L;
        for (int i = 0; i < bases.size(); i++) {
            SettlementRepository.OrderItemBasis basis = bases.get(i);
            boolean last = i == bases.size() - 1;
            long share = last ? payable.amount() - assigned
                    : (basisTotal <= 0 ? 0L
                       : Math.floorDiv(payable.amount() * basisOf(payeeType, basis), basisTotal));
            assigned += share;
            if (share > 0) {
                settlements.insertItem(batchId, payeeType, payable.orderId(), basis.orderItemId(), share, now);
            }
        }
    }

    private long basisOf(PayeeType payeeType, SettlementRepository.OrderItemBasis basis) {
        return payeeType == PayeeType.SUPPLIER ? basis.supplyAmount() : basis.lineAmount();
    }

    /** 확정 결과. 실패가 아닌 "진입하지 않음"(DEFERRED)을 별개 값으로 두는 것이 이 API의 요점이다. */
    public record Result(Long campaignId, Outcome outcome, List<Long> batchIds, String reason) {

        static Result created(Long campaignId, List<Long> batchIds) {
            return new Result(campaignId, Outcome.CREATED, batchIds, null);
        }

        static Result settledWithoutBatch(Long campaignId) {
            return new Result(campaignId, Outcome.SETTLED_WITHOUT_BATCH, List.of(), null);
        }

        static Result deferred(Long campaignId, String reason) {
            return new Result(campaignId, Outcome.DEFERRED, List.of(), reason);
        }

        static Result skipped(Long campaignId, String reason) {
            return new Result(campaignId, Outcome.SKIPPED, List.of(), reason);
        }

        static Result notFound(Long campaignId) {
            return new Result(campaignId, Outcome.NOT_FOUND, List.of(), "캠페인을 찾을 수 없습니다.");
        }

        public enum Outcome {
            CREATED,
            SETTLED_WITHOUT_BATCH,
            /** 진입 전제 미충족. CLOSED에 머물며 운영자 확인 대상이다. */
            DEFERRED,
            SKIPPED,
            NOT_FOUND
        }
    }
}
