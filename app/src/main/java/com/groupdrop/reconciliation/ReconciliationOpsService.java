package com.groupdrop.reconciliation;

import com.groupdrop.common.ApiException;
import com.groupdrop.common.AuditLogRepository;
import com.groupdrop.common.StatusFilter;
import com.groupdrop.payment.PaymentRepository;
import com.groupdrop.outbox.OutboxRepository;
import com.groupdrop.outbox.InboxRepository;
import com.groupdrop.payment.PaymentRecoveryService;
import com.groupdrop.payment.PaymentResponse;
import com.groupdrop.settlement.SettlementRepository;
import com.groupdrop.user.User;
import com.groupdrop.user.UserRepository;
import com.groupdrop.user.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * REC-02 운영자 재처리와 16.4 운영 요약.
 *
 * <p>자동 복구와 운영자 조작은 전부 감사 로그에 남긴다 (REC-02) — 대사가 스스로 고친 것과 사람이 고친 것을
 * 나중에 구분할 수 없으면, 사고 조사에서 "누가 이 상태를 만들었는가"에 답할 수 없다.
 */
@Service
public class ReconciliationOpsService {

    private static final int DEFAULT_LIST_LIMIT = 100;
    private static final Set<String> PAYMENT_STATUSES = Set.of("READY", "PROCESSING", "SUCCEEDED", "FAILED", "UNKNOWN", "SUPERSEDED", "REFUNDING", "REFUNDED");
    private static final Set<String> OUTBOX_STATUSES = Set.of("PENDING", "PROCESSED", "FAILED");
    private static final Set<String> INBOX_STATUSES = Set.of("PENDING", "PROCESSED", "IGNORED", "FAILED");

    private final ReconciliationRepository reconciliations;
    private final ReconciliationService reconciliationService;
    private final PaymentRecoveryService paymentRecovery;
    private final SettlementRepository settlements;
    private final OutboxRepository outbox;
    private final InboxRepository inbox;
    private final PaymentRepository payments;
    private final AuditLogRepository auditLogs;
    private final UserRepository users;
    private final Clock clock;

    public ReconciliationOpsService(ReconciliationRepository reconciliations,
                                    ReconciliationService reconciliationService,
                                    PaymentRecoveryService paymentRecovery, SettlementRepository settlements,
                                    OutboxRepository outbox, InboxRepository inbox,
                                    PaymentRepository payments,
                                    AuditLogRepository auditLogs,
                                    UserRepository users, Clock clock) {
        this.reconciliations = reconciliations;
        this.reconciliationService = reconciliationService;
        this.paymentRecovery = paymentRecovery;
        this.settlements = settlements;
        this.outbox = outbox;
        this.inbox = inbox;
        this.payments = payments;
        this.auditLogs = auditLogs;
        this.users = users;
        this.clock = clock;
    }

    public ReconciliationRepository.Run run(String requesterEmail, Integer minAgeMinutes) {
        requireAdmin(requesterEmail);
        int minAge = minAgeMinutes == null ? -1 : minAgeMinutes;
        if (minAge < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MIN_AGE_INVALID", "minAgeMinutes는 0 이상이어야 합니다.");
        }
        Instant now = Instant.now(clock);
        auditLogs.record(requesterEmail, "RECONCILIATION_TRIGGERED", "RECONCILIATION_RUN", null,
                "운영자가 대사를 실행했습니다 (minAgeMinutes=%d).".formatted(minAge), now);
        try {
            return reconciliationService.run(minAge);
        } catch (ReconciliationRepository.ReconciliationAlreadyRunningException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "RECONCILIATION_ALREADY_RUNNING",
                    "이미 실행 중인 대사가 있습니다.");
        }
    }

    public ReconciliationRepository.Run findRun(String requesterEmail, Long runId) {
        requireAdmin(requesterEmail);
        return reconciliations.findRun(runId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RECONCILIATION_RUN_NOT_FOUND",
                        "대사 실행 기록을 찾을 수 없습니다."));
    }

    public List<ReconciliationRepository.Discrepancy> discrepancies(String requesterEmail, String status) {
        requireAdmin(requesterEmail);
        return reconciliations.findDiscrepancies(status, DEFAULT_LIST_LIMIT);
    }

    public List<PaymentRepository.AdminPayment> payments(String requesterEmail, String status, Long campaignId) {
        requireAdmin(requesterEmail);
        List<String> statuses = StatusFilter.parse(status, PAYMENT_STATUSES, List.of("UNKNOWN"));
        boolean oldestFirst = statuses.stream().allMatch(value -> Set.of("READY", "PROCESSING", "UNKNOWN").contains(value));
        return payments.findAdminPayments(statuses, campaignId, oldestFirst, DEFAULT_LIST_LIMIT);
    }

    public List<ReconciliationRepository.Run> runs(String requesterEmail) {
        requireAdmin(requesterEmail);
        return reconciliations.findRecentRuns(DEFAULT_LIST_LIMIT);
    }

    public List<OutboxRepository.Event> outboxEvents(String requesterEmail, String status) {
        requireAdmin(requesterEmail);
        return outbox.findEvents(StatusFilter.parse(status, OUTBOX_STATUSES, List.of("PENDING", "FAILED")), DEFAULT_LIST_LIMIT);
    }

    public List<InboxRepository.Event> inboxEvents(String requesterEmail, String status) {
        requireAdmin(requesterEmail);
        return inbox.findEvents(StatusFilter.parse(status, INBOX_STATUSES, List.of("PENDING", "FAILED", "IGNORED")), DEFAULT_LIST_LIMIT);
    }

    /**
     * REC-02 재처리. 결제가 걸린 불일치는 PG를 다시 조회해 내부 상태를 복구하고, 확정되었으면 그 자리에서
     * 해소로 닫는다. 확정되지 않으면 열어 둔다 — 조작했다는 이유로 닫으면 목록만 깨끗해지고 문제는 남는다.
     */
    public ReconciliationRepository.Discrepancy retry(String requesterEmail, Long discrepancyId) {
        requireAdmin(requesterEmail);
        ReconciliationRepository.Discrepancy discrepancy = requireDiscrepancy(discrepancyId);
        Instant now = Instant.now(clock);
        if (discrepancy.paymentId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "DISCREPANCY_NOT_RETRYABLE",
                    "내부 결제가 연결되지 않은 불일치는 자동 재처리할 수 없습니다: " + discrepancy.type());
        }
        PaymentResponse after = paymentRecovery.resolveByProviderQuery(discrepancy.paymentId());
        auditLogs.record(requesterEmail, "DISCREPANCY_RETRIED", "RECONCILIATION_DISCREPANCY", discrepancyId,
                "PG 재조회 결과 결제 %d 상태=%s".formatted(discrepancy.paymentId(), after.status()), now);
        if (discrepancy.type() == DiscrepancyType.UNRESOLVED_INTERNAL && isFinal(after.status())) {
            reconciliations.resolve(discrepancyId, "운영자 재처리로 확정: " + after.status(), now);
        }
        return requireDiscrepancy(discrepancyId);
    }

    /** 해결 메모 기록 (REC-02). 사람이 판단해 닫는 경로이며, 메모 없이는 닫지 못하게 한다. */
    public ReconciliationRepository.Discrepancy resolve(String requesterEmail, Long discrepancyId, String note) {
        requireAdmin(requesterEmail);
        requireDiscrepancy(discrepancyId);
        if (note == null || note.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RESOLUTION_NOTE_REQUIRED", "해결 메모는 필수입니다.");
        }
        Instant now = Instant.now(clock);
        if (!reconciliations.resolve(discrepancyId, note, now)) {
            throw new ApiException(HttpStatus.CONFLICT, "DISCREPANCY_NOT_OPEN", "미해결 상태가 아닙니다.");
        }
        auditLogs.record(requesterEmail, "DISCREPANCY_RESOLVED", "RECONCILIATION_DISCREPANCY", discrepancyId,
                note, now);
        return requireDiscrepancy(discrepancyId);
    }

    /** REC-02 결제 동기화 (14.4). ORD-03로 이관된 건의 수동 해소 경로이기도 하다. */
    public PaymentResponse syncPayment(String requesterEmail, Long paymentId) {
        requireAdmin(requesterEmail);
        PaymentResponse after = paymentRecovery.resolveByProviderQuery(paymentId);
        auditLogs.record(requesterEmail, "PAYMENT_SYNCED", "PAYMENT", paymentId,
                "PG 재조회 결과 상태=" + after.status(), Instant.now(clock));
        return after;
    }

    /** REC-02 실패 Outbox 이벤트 재처리. FAILED 이외 상태를 덮어쓰지 않는다. */
    public EventRetryResponse retryOutboxEvent(String requesterEmail, Long eventId) {
        requireAdmin(requesterEmail);
        Instant now = Instant.now(clock);
        try {
            if (!outbox.retryFailed(eventId, now)) {
                throw new ApiException(HttpStatus.CONFLICT, "OUTBOX_EVENT_NOT_FAILED",
                        "FAILED 상태의 Outbox 이벤트만 재처리할 수 있습니다.");
            }
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "OUTBOX_EVENT_ALREADY_PENDING",
                    "같은 유형과 집계의 PENDING Outbox 이벤트가 이미 있습니다.");
        }
        auditLogs.record(requesterEmail, "OUTBOX_EVENT_RETRIED", "OUTBOX_EVENT", eventId,
                "실패 이벤트를 PENDING으로 되돌렸습니다.", now);
        return new EventRetryResponse("OUTBOX", eventId, "PENDING");
    }

    /** REC-02 실패 Inbox 이벤트 재처리. 원 provider_event_id는 유지한다. */
    public EventRetryResponse retryInboxEvent(String requesterEmail, Long eventId) {
        requireAdmin(requesterEmail);
        Instant now = Instant.now(clock);
        if (!inbox.retryFailed(eventId, now)) {
            throw new ApiException(HttpStatus.CONFLICT, "INBOX_EVENT_NOT_FAILED",
                    "FAILED 상태의 Inbox 이벤트만 재처리할 수 있습니다.");
        }
        auditLogs.record(requesterEmail, "INBOX_EVENT_RETRIED", "INBOX_EVENT", eventId,
                "실패 이벤트를 PENDING으로 되돌렸습니다.", now);
        return new EventRetryResponse("INBOX", eventId, "PENDING");
    }

    /**
     * 16.4의 핵심 운영 수치 4종. Grafana가 잘려도 증거가 남도록 API로도 제공한다.
     */
    public OpsSummary summary(String requesterEmail) {
        requireAdmin(requesterEmail);
        OutboxRepository.PendingStats pending = outbox.pendingStats();
        long oldestAgeSeconds = pending.oldestCreatedAt() == null ? 0L
                : Math.max(0L, Instant.now(clock).getEpochSecond() - pending.oldestCreatedAt().getEpochSecond());
        return new OpsSummary(reconciliations.countUnknownPayments(), pending.count(), oldestAgeSeconds,
                reconciliations.countOpen(), settlements.countFailedOrHeldBatches(),
                settlements.countUnrecoveredAdjustments());
    }

    private ReconciliationRepository.Discrepancy requireDiscrepancy(Long id) {
        return reconciliations.findDiscrepancy(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DISCREPANCY_NOT_FOUND",
                        "불일치를 찾을 수 없습니다."));
    }

    private boolean isFinal(String status) {
        return switch (status) {
            case "SUCCEEDED", "FAILED", "REFUNDED", "SUPERSEDED" -> true;
            default -> false;
        };
    }

    private void requireAdmin(String email) {
        User user = users.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_USER_NOT_FOUND",
                        "사용자를 찾을 수 없습니다."));
        if (user.getRole() != UserRole.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", "운영자만 수행할 수 있습니다.");
        }
    }

    /**
     * @param unknownPaymentCount    미확정 UNKNOWN 결제 수
     * @param oldestOutboxAgeSeconds 가장 오래 대기 중인 Outbox 이벤트의 대기 시간
     * @param openDiscrepancyCount   미해결 대사 불일치 건수
     * @param blockedSettlementCount 실패 또는 보류된 정산 건수
     */
    public record OpsSummary(long unknownPaymentCount, long pendingOutboxCount, long oldestOutboxAgeSeconds,
                             long openDiscrepancyCount, long blockedSettlementCount,
                             long unrecoveredAdjustmentCount) { }

    public record RunReconciliationRequest(Integer minAgeMinutes) { }

    public record ResolveDiscrepancyRequest(String note) { }

    public record EventRetryResponse(String channel, Long eventId, String status) { }
}
