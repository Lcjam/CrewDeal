package com.groupdrop.reconciliation;

import com.groupdrop.common.AuditLogRepository;
import com.groupdrop.common.GroupdropProperties;
import com.groupdrop.ledger.LedgerRepository;
import com.groupdrop.ledger.LedgerService;
import com.groupdrop.payment.PaymentRecoveryService;
import com.groupdrop.payment.PaymentRepository;
import com.groupdrop.payment.PaymentResponse;
import com.groupdrop.payment.PgClient;
import com.groupdrop.refund.RefundReconciliationSupport;
import com.groupdrop.refund.RefundRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * REC-01 결제 대사.
 *
 * <p>세 단계다. ① <b>해소</b> — 최소 경과 시간이 지난 비최종 결제·미완 환불을 PG 조회로 확정한다
 * (S4-b가 이 단계를 검증한다). ② <b>매칭·분류</b> — PG 거래 목록과 내부 결제를 맞춰 8개 유형으로
 * 나눈다. ③ <b>원장 재검산</b> — 차변 = 대변 전수 검증 결과를 지표로 낸다 (S5, 16.4).
 *
 * <p>해소를 분류보다 먼저 하는 이유는 순서가 결과를 바꾸기 때문이다. 먼저 분류하면 방금 확정될 수 있었던
 * {@code UNKNOWN} 결제가 그 실행에서 불일치로 등록되고, 정산은 그 불일치 때문에 HELD된다.
 *
 * <p>이 클래스에 {@code @Transactional}이 없는 것은 설계다 — 대사는 PG를 여러 번 호출하며,
 * 그 호출이 트랜잭션 안에 들어가면 안 된다 (ADR-003). 각 해소·등록은 짧은 트랜잭션으로 따로 커밋된다.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final String SOURCE = "reconciliation";
    private static final int STALE_REFUND_LIMIT = 200;
    private static final Duration OCCURRED_AT_TOLERANCE = Duration.ofSeconds(1);

    private final ReconciliationRepository reconciliations;
    private final PaymentRepository payments;
    private final PaymentRecoveryService paymentRecovery;
    private final RefundReconciliationSupport refunds;
    private final LedgerService ledger;
    private final AuditLogRepository auditLogs;
    private final ReconciliationMetrics metrics;
    private final PgClient pgClient;
    private final GroupdropProperties properties;
    private final Clock clock;

    public ReconciliationService(ReconciliationRepository reconciliations, PaymentRepository payments,
                                 PaymentRecoveryService paymentRecovery, RefundReconciliationSupport refunds,
                                 LedgerService ledger, AuditLogRepository auditLogs,
                                 ReconciliationMetrics metrics, PgClient pgClient,
                                 GroupdropProperties properties, Clock clock) {
        this.reconciliations = reconciliations;
        this.payments = payments;
        this.paymentRecovery = paymentRecovery;
        this.refunds = refunds;
        this.ledger = ledger;
        this.auditLogs = auditLogs;
        this.metrics = metrics;
        this.pgClient = pgClient;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param minAgeMinutes 대사 대상의 최소 경과 시간. 진행 중 거래의 가짜 불일치를 막는 값이며,
     *                      S4-b는 0으로 실행한다 (REC-01).
     */
    public ReconciliationRepository.Run run(int minAgeMinutes) {
        Instant startedAt = Instant.now(clock);
        Instant cutoff = startedAt.minus(Duration.ofMinutes(minAgeMinutes));
        int staleRuns = reconciliations.failStaleRuns(
                startedAt.minus(properties.reconciliationInterval().multipliedBy(2)), startedAt);
        if (staleRuns > 0) {
            auditLogs.record(SOURCE, "STALE_RECONCILIATION_RECOVERED", "RECONCILIATION_RUN", null,
                    "중단된 RUNNING 대사 %d건을 FAILED로 회수했습니다.".formatted(staleRuns), startedAt);
        }
        Long runId = reconciliations.startRun(minAgeMinutes, startedAt);
        metrics.recordRun();
        try {
            int resolved = resolveNonFinalPayments(runId, cutoff);

            requireActive(runId);
            List<PgClient.ProviderTransaction> providerTransactions = pgClient.listTransactions(cutoff);
            requireActive(runId);
            resolved += resolveStaleRefunds(providerTransactions, cutoff);

            requireActive(runId);
            List<ReconciliationRepository.InternalPayment> internals =
                    reconciliations.findReconcilablePayments(cutoff);
            int mismatches = classify(runId, providerTransactions, internals);

            requireActive(runId);
            List<LedgerRepository.Unbalanced> unbalanced = ledger.findUnbalancedTransactions();
            Instant finishedAt = Instant.now(clock);
            List<Long> autoResolved = reconciliations.resolveDisappeared(runId, cutoff, finishedAt);
            for (Long discrepancyId : autoResolved) {
                metrics.recordResolved();
                auditLogs.record(SOURCE, "DISCREPANCY_AUTO_RESOLVED", "RECONCILIATION_DISCREPANCY",
                        discrepancyId, "대사 범위 안에서 조건이 재검출되지 않아 자동 해소했습니다.", finishedAt);
            }
            resolved += autoResolved.size();
            if (!reconciliations.completeRun(runId, providerTransactions.size(), internals.size(), mismatches,
                    resolved, unbalanced.size(), finishedAt)) {
                throw new ReconciliationRepository.ReconciliationLeaseLostException(runId);
            }
            metrics.updateGauges(unbalanced.size(), reconciliations.countOpen());
            if (!unbalanced.isEmpty()) {
                log.error("원장 재검산에서 불균형 거래 {}건을 발견했습니다: {}", unbalanced.size(), unbalanced);
                auditLogs.record(SOURCE, "LEDGER_UNBALANCED", "RECONCILIATION_RUN", runId,
                        "불균형 거래 %d건: %s".formatted(unbalanced.size(), unbalanced), finishedAt);
            }
            log.info("대사 {} 완료 — PG {}건, 내부 {}건, 불일치 {}건, 해소 {}건, 원장 불균형 {}건",
                    runId, providerTransactions.size(), internals.size(), mismatches, resolved,
                    unbalanced.size());
            return reconciliations.findRun(runId).orElseThrow();
        } catch (RuntimeException exception) {
            reconciliations.failRun(runId, exception.toString(), Instant.now(clock));
            metrics.recordRunFailed();
            log.error("대사 {} 실행에 실패했습니다.", runId, exception);
            throw exception;
        }
    }

    private void requireActive(Long runId) {
        if (!reconciliations.isRunning(runId)) {
            throw new ReconciliationRepository.ReconciliationLeaseLostException(runId);
        }
    }

    // ── ① 해소 ──────────────────────────────────────────────────────────────────────

    /**
     * 비최종 상태({@code PROCESSING}·{@code UNKNOWN})를 PG 조회로 확정한다. 조회 경로는 3주차의
     * {@link PaymentRecoveryService}를 그대로 쓴다 — 확정 로직이 두 벌이 되면 두 경로가 다르게 낡는다.
     *
     * <p>주문 전이는 여기서 하지 않는다. 확정은 {@code payment.finalized}를 발행하고, 주문 상태는
     * 워커의 몫이다 (13.4) — S4-b의 어서션이 "대사 직후 결제 SUCCEEDED, 다음 폴링에 주문 PAID"인 이유다.
     */
    private int resolveNonFinalPayments(Long runId, Instant cutoff) {
        int resolved = 0;
        for (ReconciliationRepository.InternalPayment payment : reconciliations.findNonFinalPayments(cutoff)) {
            Instant now = Instant.now(clock);
            try {
                PaymentResponse after = paymentRecovery.resolveByProviderQuery(payment.id());
                if (isFinal(after.status())) {
                    resolved++;
                    metrics.recordResolved();
                    auditLogs.record(SOURCE, "PAYMENT_RESOLVED", "PAYMENT", payment.id(),
                            "%s → %s".formatted(payment.status(), after.status()), now);
                    continue;
                }
                registerUnresolved(runId, payment, "PG 조회로도 확정되지 않았습니다.", now);
            } catch (RuntimeException exception) {
                // PG 호출 기록이 없는 결제 등. 추측해서 확정하지 않고 운영자 목록에 올린다.
                registerUnresolved(runId, payment, exception.getMessage(), now);
            }
        }
        return resolved;
    }

    private void registerUnresolved(Long runId, ReconciliationRepository.InternalPayment payment,
                                    String detail, Instant now) {
        reconciliations.upsertOpen(runId, DiscrepancyType.UNRESOLVED_INTERNAL, payment.id(), payment.orderId(),
                payment.providerPaymentId(), payment.status(), null, payment.amount(), null, detail,
                payment.createdAt(), now);
        metrics.recordMismatch(DiscrepancyType.UNRESOLVED_INTERNAL);
    }

    /**
     * 최소 경과 시간이 지난 미완 {@code REQUESTED} 환불의 해소 (REC-01).
     *
     * <p>PG에 환불 기록이 있으면 확정하고, 없으면 <b>미처리 {@code refund.requested} 이벤트가 없을 때만</b>
     * 재발행한다. 미처리 이벤트가 있으면 워커가 들고 있는 중이므로 손대지 않는다 — 중복 발행은
     * 워커 2개가 같은 환불에 대해 PG 환불을 동시 호출하게 만든다.
     */
    private int resolveStaleRefunds(List<PgClient.ProviderTransaction> providerTransactions, Instant cutoff) {
        Map<String, PgClient.ProviderTransaction> byProviderPaymentId = new HashMap<>();
        for (PgClient.ProviderTransaction transaction : providerTransactions) {
            byProviderPaymentId.put(transaction.providerPaymentId(), transaction);
        }
        int resolved = 0;
        for (RefundRepository.RefundSnapshot refund : refunds.findStaleRequested(cutoff, STALE_REFUND_LIMIT)) {
            PgClient.ProviderTransaction transaction = refund.providerPaymentId() == null ? null
                    : byProviderPaymentId.get(refund.providerPaymentId());
            if (transaction != null && transaction.refunded()) {
                refunds.completeFromProvider(refund, transaction.providerRefundId(), transaction.refundedAt());
                resolved++;
                metrics.recordResolved();
            } else if (refunds.republishRequestIfNoPending(refund)) {
                resolved++;
            }
        }
        return resolved;
    }

    // ── ② 매칭과 분류 ───────────────────────────────────────────────────────────────

    /**
     * 매칭 키는 {@code providerPaymentId} 1차, 미매칭 건은 {@code orderId} 2차 (REC-01).
     *
     * @return 이번 실행에서 검출한 불일치 수
     */
    private int classify(Long runId, List<PgClient.ProviderTransaction> providerTransactions,
                         List<ReconciliationRepository.InternalPayment> internals) {
        Map<String, PgClient.ProviderTransaction> byProviderId = new LinkedHashMap<>();
        Map<String, List<PgClient.ProviderTransaction>> byOrderId = new LinkedHashMap<>();
        for (PgClient.ProviderTransaction transaction : providerTransactions) {
            byProviderId.put(transaction.providerPaymentId(), transaction);
            if (transaction.orderId() != null) {
                byOrderId.computeIfAbsent(transaction.orderId(), key -> new ArrayList<>()).add(transaction);
            }
        }

        Instant now = Instant.now(clock);
        Set<String> matched = new HashSet<>();
        int mismatches = 0;
        for (ReconciliationRepository.InternalPayment internal : internals) {
            PgClient.ProviderTransaction transaction = match(internal, byProviderId, byOrderId, matched);
            if (transaction == null) {
                if (internal.claimsProviderSuccess()) {
                    reconciliations.upsertOpen(runId, DiscrepancyType.MISSING_PROVIDER, internal.id(),
                            internal.orderId(), internal.providerPaymentId(), internal.status(), null,
                            internal.amount(), null, "PG에 매칭되는 거래가 없습니다.",
                            internal.createdAt(), now);
                    metrics.recordMismatch(DiscrepancyType.MISSING_PROVIDER);
                    mismatches++;
                }
                continue;
            }
            matched.add(transaction.providerPaymentId());
            mismatches += compare(runId, internal, transaction, now);
        }

        mismatches += reportMissingInternal(runId, providerTransactions, matched, now);
        mismatches += reportDuplicates(runId, byOrderId, internals, now);
        return mismatches;
    }

    private PgClient.ProviderTransaction match(ReconciliationRepository.InternalPayment internal,
                                               Map<String, PgClient.ProviderTransaction> byProviderId,
                                               Map<String, List<PgClient.ProviderTransaction>> byOrderId,
                                               Set<String> matched) {
        if (internal.providerPaymentId() != null) {
            return byProviderId.get(internal.providerPaymentId());
        }
        // 2차 키. 이미 다른 내부 결제에 붙은 거래는 후보에서 뺀다 — 재시도로 한 주문에 결제가 여러 개일 수 있다.
        return byOrderId.getOrDefault(String.valueOf(internal.orderId()), List.of()).stream()
                .filter(transaction -> !matched.contains(transaction.providerPaymentId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 비교 필드는 REC-01이 정한 넷이다 — 결제 금액, 환불 금액, 결제 상태, 거래 발생 시각.
     * 성공 결제의 승인 시각과 완료 환불의 완료 시각은 각각 비교하며, 차이가 1초를 초과하거나 한쪽만
     * 없으면 {@link DiscrepancyType#OCCURRED_AT_MISMATCH}로 기록한다 (D-031).
     *
     * <p>{@code SUPERSEDED} + PG의 성공·환불 쌍은 이중 결제 보상이 끝난 정상 매칭이다 (PAY-01).
     */
    private int compare(Long runId, ReconciliationRepository.InternalPayment internal,
                        PgClient.ProviderTransaction transaction, Instant now) {
        int mismatches = 0;
        if (internal.amount() != transaction.amount()) {
            reconciliations.upsertOpen(runId, DiscrepancyType.AMOUNT_MISMATCH, internal.id(),
                    internal.orderId(), transaction.providerPaymentId(), internal.status(),
                    transaction.status(), internal.amount(), transaction.amount(),
                    "결제 금액이 다릅니다.", occurrence(internal, transaction), now);
            metrics.recordMismatch(DiscrepancyType.AMOUNT_MISMATCH);
            mismatches++;
        }
        if (internal.isFinal() && internalSucceeded(internal) != transaction.succeeded()) {
            reconciliations.upsertOpen(runId, DiscrepancyType.STATUS_MISMATCH, internal.id(),
                    internal.orderId(), transaction.providerPaymentId(), internal.status(),
                    transaction.status(), internal.amount(), transaction.amount(),
                    "결제 상태가 다릅니다 (양쪽 모두 최종 상태).", occurrence(internal, transaction), now);
            metrics.recordMismatch(DiscrepancyType.STATUS_MISMATCH);
            mismatches++;
        }
        // 환불 진행 중(REFUNDING)은 비교하지 않는다. 아직 확정되지 않은 값을 비교하면 정상 흐름이 불일치가 된다.
        if (!"REFUNDING".equals(internal.status()) && internal.refundedAmount() != transaction.refundedAmount()) {
            reconciliations.upsertOpen(runId, DiscrepancyType.REFUND_MISMATCH, internal.id(),
                    internal.orderId(), transaction.providerPaymentId(), internal.status(),
                    transaction.status(), internal.refundedAmount(), transaction.refundedAmount(),
                    "환불 금액이 다릅니다.", occurrence(internal, transaction), now);
            metrics.recordMismatch(DiscrepancyType.REFUND_MISMATCH);
            mismatches++;
        }
        List<String> occurredAtDifferences = new ArrayList<>();
        if (internalSucceeded(internal) && transaction.succeeded()
                && occurredAtMismatch(internal.approvedAt(), transaction.processedAt())) {
            occurredAtDifferences.add(occurredAtDetail(
                    "결제", internal.approvedAt(), transaction.processedAt()));
        }
        if (internal.refundedAmount() > 0 && transaction.refundedAmount() > 0
                && occurredAtMismatch(internal.refundCompletedAt(), transaction.refundedAt())) {
            occurredAtDifferences.add(occurredAtDetail(
                    "환불", internal.refundCompletedAt(), transaction.refundedAt()));
        }
        if (!occurredAtDifferences.isEmpty()) {
            reconciliations.upsertOpen(runId, DiscrepancyType.OCCURRED_AT_MISMATCH, internal.id(),
                    internal.orderId(), transaction.providerPaymentId(), internal.status(), transaction.status(),
                    internal.amount(), transaction.amount(), String.join(" ", occurredAtDifferences),
                    latest(internal.approvedAt(), internal.refundCompletedAt(), transaction.processedAt(),
                            transaction.refundedAt(), internal.createdAt()), now);
            metrics.recordMismatch(DiscrepancyType.OCCURRED_AT_MISMATCH);
            mismatches++;
        }
        return mismatches;
    }

    private boolean occurredAtMismatch(Instant internal, Instant provider) {
        if (internal == null || provider == null) {
            return true;
        }
        return Duration.between(internal, provider).abs().compareTo(OCCURRED_AT_TOLERANCE) > 0;
    }

    private String occurredAtDetail(String event, Instant internal, Instant provider) {
        String differenceMillis = internal == null || provider == null
                ? "UNKNOWN"
                : String.valueOf(Duration.between(internal, provider).abs().toMillis());
        return "%s 발생 시각 불일치(internal=%s, provider=%s, differenceMillis=%s, toleranceMillis=%d)."
                .formatted(event, internal, provider, differenceMillis, OCCURRED_AT_TOLERANCE.toMillis());
    }

    private Instant latest(Instant... values) {
        Instant latest = null;
        for (Instant value : values) {
            if (value != null && (latest == null || value.isAfter(latest))) {
                latest = value;
            }
        }
        return latest;
    }

    private int reportMissingInternal(Long runId, List<PgClient.ProviderTransaction> providerTransactions,
                                      Set<String> matched, Instant now) {
        int mismatches = 0;
        for (PgClient.ProviderTransaction transaction : providerTransactions) {
            if (matched.contains(transaction.providerPaymentId())) {
                continue;
            }
            reconciliations.upsertOpen(runId, DiscrepancyType.MISSING_INTERNAL, null,
                    parseOrderId(transaction.orderId()), transaction.providerPaymentId(), null,
                    transaction.status(), null, transaction.amount(),
                    "PG에만 존재하는 거래입니다 (merchantPaymentId=%s).".formatted(transaction.merchantPaymentId()),
                    transaction.processedAt(), now);
            metrics.recordMismatch(DiscrepancyType.MISSING_INTERNAL);
            mismatches++;
        }
        return mismatches;
    }

    /**
     * 같은 {@code orderId}로 PG에 {@code SUCCEEDED}가 2건 이상인 경우 (REC-01).
     *
     * <p>보상 대상은 <b>내부 승자가 아닌 건</b>이다. 내부 승자가 이미 있으면 대사는 재선정하지 않고
     * PAY-01의 결과를 따른다 — PG 승인 시각으로 다시 고르면 응답 지연 시 둘이 갈린다.
     * PG에서 이미 환불된 패자는 보상이 끝난 것이므로 불일치가 아니다.
     */
    private int reportDuplicates(Long runId, Map<String, List<PgClient.ProviderTransaction>> byOrderId,
                                 List<ReconciliationRepository.InternalPayment> internals, Instant now) {
        Map<Long, ReconciliationRepository.InternalPayment> winners = new HashMap<>();
        for (ReconciliationRepository.InternalPayment internal : internals) {
            if (internalSucceeded(internal) && !"SUPERSEDED".equals(internal.status())) {
                winners.put(internal.orderId(), internal);
            }
        }

        int mismatches = 0;
        for (Map.Entry<String, List<PgClient.ProviderTransaction>> entry : byOrderId.entrySet()) {
            List<PgClient.ProviderTransaction> succeeded = entry.getValue().stream()
                    .filter(PgClient.ProviderTransaction::succeeded).toList();
            if (succeeded.size() < 2) {
                continue;
            }
            Long orderId = parseOrderId(entry.getKey());
            ReconciliationRepository.InternalPayment winner = orderId == null ? null : winners.get(orderId);
            for (PgClient.ProviderTransaction loser : succeeded) {
                if (winner != null && loser.providerPaymentId().equals(winner.providerPaymentId())) {
                    continue;
                }
                if (loser.refunded()) {
                    continue;
                }
                String note = compensate(loser, orderId, now);
                reconciliations.upsertOpen(runId, DiscrepancyType.DUPLICATE_PAYMENT, null, orderId,
                        loser.providerPaymentId(), winner == null ? null : winner.status(), loser.status(),
                        null, loser.amount(), note, loser.processedAt(), now);
                metrics.recordMismatch(DiscrepancyType.DUPLICATE_PAYMENT);
                mismatches++;
            }
        }
        return mismatches;
    }

    /** 패자의 내부 기록이 있으면 보상 환불을 접수한다. 없으면 우리가 환불할 대상을 지목할 수 없다. */
    private String compensate(PgClient.ProviderTransaction loser, Long orderId, Instant now) {
        PaymentRepository.PaymentSnapshot internal =
                payments.findByProviderPaymentId(loser.providerPaymentId()).orElse(null);
        if (internal == null) {
            return "PG 중복 승인이지만 내부 기록이 없어 자동 환불 대상을 지목할 수 없습니다. 운영자 확인이 필요합니다.";
        }
        Long refundId = refunds.initiateCompensationIfAbsent(internal.id(), internal.orderId(),
                internal.amount(), "대사가 발견한 이중 결제의 자동 보상 환불 (REC-01)").orElse(null);
        if (refundId == null) {
            return "이미 접수된 보상 환불이 있습니다. 실행 워커의 완료를 기다립니다.";
        }
        auditLogs.record(SOURCE, "DUPLICATE_PAYMENT_COMPENSATED", "PAYMENT", internal.id(),
                "PG 중복 승인 %s에 대해 보상 환불 %d를 접수했습니다.".formatted(loser.providerPaymentId(), refundId),
                now);
        return "보상 환불 %d를 자동 접수했습니다.".formatted(refundId);
    }

    private boolean internalSucceeded(ReconciliationRepository.InternalPayment internal) {
        return switch (internal.status()) {
            case "SUCCEEDED", "REFUNDING", "REFUNDED", "SUPERSEDED" -> true;
            default -> false;
        };
    }

    private Instant occurrence(ReconciliationRepository.InternalPayment internal,
                               PgClient.ProviderTransaction transaction) {
        return transaction.processedAt() == null ? internal.createdAt() : transaction.processedAt();
    }

    private boolean isFinal(String status) {
        return switch (status) {
            case "SUCCEEDED", "FAILED", "REFUNDED", "SUPERSEDED" -> true;
            default -> false;
        };
    }

    private Long parseOrderId(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
