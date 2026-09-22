package com.groupdrop.settlement;

import com.groupdrop.common.ApiException;
import com.groupdrop.common.StatusFilter;
import com.groupdrop.user.InfluencerRepository;
import com.groupdrop.user.SupplierRepository;
import com.groupdrop.user.User;
import com.groupdrop.user.UserRepository;
import com.groupdrop.user.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 운영 API의 진입점 (14.3, 14.4). 상태를 바꾸는 로직은 확정·지급 서비스에 있고, 여기서는
 * 권한 확인과 응답 조립만 한다.
 */
@Service
public class SettlementService {

    private static final int LIST_LIMIT = 100;
    private static final Set<String> BATCH_STATUSES = Set.of("PENDING", "READY", "PROCESSING", "COMPLETED",
            "FAILED", "HELD");

    private final SettlementRepository settlements;
    private final SettlementDeterminationService determination;
    private final SettlementPayoutService payouts;
    private final UserRepository users;
    private final InfluencerRepository influencers;
    private final SupplierRepository suppliers;

    public SettlementService(SettlementRepository settlements, SettlementDeterminationService determination,
                             SettlementPayoutService payouts, UserRepository users,
                             InfluencerRepository influencers, SupplierRepository suppliers) {
        this.settlements = settlements;
        this.determination = determination;
        this.payouts = payouts;
        this.users = users;
        this.influencers = influencers;
        this.suppliers = suppliers;
    }

    /**
     * 운영자 수동 정산 실행 (14.4). 확정 → 검증 → 지급까지 한 번에 진행한다.
     *
     * <p>S6의 재실행 시도가 지나는 경로이며, 두 번째 호출은 캠페인이 이미 SETTLING·SETTLED라
     * 확정 단계에서 걸러진다. 설령 걸러지지 않더라도 `settlement_items(payee_type, order_item_id)`
     * 유니크가 두 번째 정상 항목을 DB에서 막는다 — 방어선이 둘인 것은 중복이 아니라 의도다.
     */
    public RunResult run(String requesterEmail, Long campaignId) {
        requireAdmin(requesterEmail);
        SettlementDeterminationService.Result result = determination.determine(campaignId);
        List<Long> batchIds = result.batchIds();
        for (Long batchId : batchIds) {
            payouts.verify(batchId);
            payouts.payout(batchId);
        }
        payouts.updateBlockedGauges();
        return new RunResult(campaignId, result.outcome().name(), result.reason(),
                settlements.findBatchesOfCampaign(campaignId).stream().map(SettlementBatchResponse::from).toList());
    }

    /** 운영자 콘솔 목록. 상태 필터와 표시용 캠페인명만 추가하고 배치 상태는 변경하지 않는다. */
    @Transactional(readOnly = true)
    public List<AdminSettlementResponse> adminBatches(String requesterEmail, Long campaignId, String status) {
        requireAdmin(requesterEmail);
        List<String> statuses = StatusFilter.parse(status, BATCH_STATUSES, List.copyOf(BATCH_STATUSES));
        return settlements.findAdminBatches(campaignId, statuses, LIST_LIMIT).stream()
                .map(item -> AdminSettlementResponse.from(item.batch(), item.campaignName())).toList();
    }

    @Transactional(readOnly = true)
    public SettlementBatchDetailResponse batch(String requesterEmail, Long batchId) {
        requireAdmin(requesterEmail);
        SettlementRepository.Batch batch = requireBatch(batchId);
        List<SettlementRepository.BatchItem> items = settlements.findBatchItems(batchId);
        return new SettlementBatchDetailResponse(SettlementBatchResponse.from(batch),
                items.stream().map(item -> new SettlementItemResponse(item.id(), item.orderId(),
                        item.orderItemId(), item.amount())).toList());
    }

    public SettlementBatchResponse retry(String requesterEmail, Long batchId) {
        requireAdmin(requesterEmail);
        SettlementRepository.Batch batch = requireBatch(batchId);
        if (batch.status() != SettlementBatchStatus.FAILED && batch.status() != SettlementBatchStatus.READY) {
            throw new ApiException(HttpStatus.CONFLICT, "SETTLEMENT_NOT_RETRYABLE",
                    "재시도할 수 있는 상태가 아닙니다: " + batch.status());
        }
        payouts.retry(batchId);
        return SettlementBatchResponse.from(requireBatch(batchId));
    }

    public SettlementBatchResponse hold(String requesterEmail, Long batchId, String reason) {
        requireAdmin(requesterEmail);
        requireBatch(batchId);
        if (!payouts.holdByOperator(batchId, reason == null ? "운영자 보류" : reason, requesterEmail)) {
            // 10.5: PROCESSING 중 보류는 허용하지 않는다.
            throw new ApiException(HttpStatus.CONFLICT, "SETTLEMENT_NOT_HOLDABLE",
                    "PENDING·READY·FAILED 배치만 보류할 수 있습니다.");
        }
        return SettlementBatchResponse.from(requireBatch(batchId));
    }

    public SettlementBatchResponse release(String requesterEmail, Long batchId) {
        requireAdmin(requesterEmail);
        requireBatch(batchId);
        if (!payouts.releaseHold(batchId, requesterEmail)) {
            throw new ApiException(HttpStatus.CONFLICT, "SETTLEMENT_NOT_HELD", "HELD 배치만 해제할 수 있습니다.");
        }
        return SettlementBatchResponse.from(requireBatch(batchId));
    }

    /** SET-03 미회수 잔액 조회 (14.4). 판정 기준은 {@code recovery_batch_id IS NULL}이다 (13.2). */
    @Transactional(readOnly = true)
    public List<UnrecoveredAdjustmentResponse> unrecoveredAdjustments(String requesterEmail) {
        requireAdmin(requesterEmail);
        return settlements.findUnrecoveredAdjustments().stream()
                .map(a -> new UnrecoveredAdjustmentResponse(a.id(), a.campaignId(), a.payeeType().name(),
                        a.payeeId(), a.batchId(), a.refundId(), a.amount(), a.createdAt()))
                .toList();
    }

    /** 14.3 인플루언서 확정 정산 내역. 컷 4가 발동해도 남는 최소 형태다. */
    @Transactional(readOnly = true)
    public List<SettlementBatchResponse> myInfluencerSettlements(String requesterEmail) {
        User user = requireUser(requesterEmail, UserRole.INFLUENCER);
        Long influencerId = influencers.findByUserId(user.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INFLUENCER_NOT_FOUND",
                        "인플루언서 정보를 찾을 수 없습니다.")).getId();
        return settlements.findBatchesOfPayee(PayeeType.INFLUENCER, influencerId).stream()
                .map(SettlementBatchResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<SettlementBatchResponse> mySupplierSettlements(String requesterEmail) {
        User user = requireUser(requesterEmail, UserRole.SUPPLIER);
        Long supplierId = suppliers.findByUserId(user.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SUPPLIER_NOT_FOUND",
                        "공급사 정보를 찾을 수 없습니다.")).getId();
        return settlements.findBatchesOfPayee(PayeeType.SUPPLIER, supplierId).stream()
                .map(SettlementBatchResponse::from).toList();
    }

    private SettlementRepository.Batch requireBatch(Long batchId) {
        return settlements.findBatch(batchId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SETTLEMENT_BATCH_NOT_FOUND",
                        "정산 배치를 찾을 수 없습니다."));
    }

    private void requireAdmin(String email) {
        requireUser(email, UserRole.ADMIN);
    }

    private User requireUser(String email, UserRole role) {
        User user = users.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_USER_NOT_FOUND",
                        "사용자를 찾을 수 없습니다."));
        if (user.getRole() != role) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", "이 작업을 수행할 권한이 없습니다.");
        }
        return user;
    }

    public record RunResult(Long campaignId, String outcome, String reason,
                            List<SettlementBatchResponse> batches) { }

    public record SettlementBatchResponse(Long id, Long campaignId, String payeeType, Long payeeId,
                                          String batchType, String status, long totalAmount,
                                          Instant determinedAt, int attempts, String failureCode,
                                          String failureReason, String holdReason, Instant completedAt) {

        static SettlementBatchResponse from(SettlementRepository.Batch batch) {
            return new SettlementBatchResponse(batch.id(), batch.campaignId(), batch.payeeType().name(),
                    batch.payeeId(), batch.batchType().name(), batch.status().name(), batch.totalAmount(),
                    batch.determinedAt(), batch.attempts(), batch.failureCode(), batch.failureReason(),
                    batch.holdReason(), batch.completedAt());
        }
    }

    public record AdminSettlementResponse(Long id, Long campaignId, String campaignName, String payeeType,
                                          Long payeeId, String batchType, String status, long totalAmount,
                                          Instant determinedAt, int attempts, String failureCode,
                                          String failureReason, String holdReason, Instant completedAt) {

        static AdminSettlementResponse from(SettlementRepository.Batch batch, String campaignName) {
            return new AdminSettlementResponse(batch.id(), batch.campaignId(), campaignName,
                    batch.payeeType().name(), batch.payeeId(), batch.batchType().name(), batch.status().name(),
                    batch.totalAmount(), batch.determinedAt(), batch.attempts(), batch.failureCode(),
                    batch.failureReason(), batch.holdReason(), batch.completedAt());
        }
    }

    public record SettlementItemResponse(Long id, Long orderId, Long orderItemId, long amount) { }

    public record SettlementBatchDetailResponse(SettlementBatchResponse batch,
                                                List<SettlementItemResponse> items) { }

    public record UnrecoveredAdjustmentResponse(Long id, Long campaignId, String payeeType, Long payeeId,
                                                Long batchId, Long refundId, long amount, Instant createdAt) { }

    public record RunSettlementRequest(Long campaignId) { }

    public record HoldSettlementRequest(String reason) { }
}
