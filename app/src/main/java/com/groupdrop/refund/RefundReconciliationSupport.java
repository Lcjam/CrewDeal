package com.groupdrop.refund;

import com.groupdrop.common.AuditLogRepository;
import com.groupdrop.common.Json;
import com.groupdrop.outbox.OutboxRepository;
import com.groupdrop.payment.PgClient;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * REC-01의 미완 환불 해소를 위해 {@code refund} 패키지가 대사 쪽에 열어 주는 창구.
 *
 * <p>대사가 {@code refunds} 테이블을 직접 만지지 않게 하려고 둔 경계다. 확정 처리는 실행 워커의
 * 경로를 그대로 재사용하고, 재발행은 <b>미처리 {@code refund.requested} 이벤트가 없을 때만</b> 한다 —
 * 중복 발행하면 워커 2개가 같은 환불에 대해 PG 환불을 동시 호출한다 (REC-01).
 */
@Component
public class RefundReconciliationSupport {

    private static final Logger log = LoggerFactory.getLogger(RefundReconciliationSupport.class);
    private static final String SOURCE = "reconciliation";

    private final RefundRepository refunds;
    private final RefundExecutionWorker worker;
    private final RefundInitiator initiator;
    private final OutboxRepository outbox;
    private final AuditLogRepository auditLogs;
    private final Json json;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RefundReconciliationSupport(RefundRepository refunds, RefundExecutionWorker worker,
                                       RefundInitiator initiator, OutboxRepository outbox,
                                       AuditLogRepository auditLogs, Json json,
                                       TransactionTemplate transactions, Clock clock) {
        this.refunds = refunds;
        this.worker = worker;
        this.initiator = initiator;
        this.outbox = outbox;
        this.auditLogs = auditLogs;
        this.json = json;
        this.transactions = transactions;
        this.clock = clock;
    }

    public List<RefundRepository.RefundSnapshot> findStaleRequested(Instant threshold, int limit) {
        return refunds.findStaleRequested(threshold, limit);
    }

    /**
     * REC-01 {@code DUPLICATE_PAYMENT}의 자동 환불. 이미 유효한 환불이 있으면 아무것도 하지 않는다 —
     * 대사는 주기적으로 반복 실행되므로, 멱등하지 않으면 매 실행마다 환불을 접수한다.
     *
     * @return 이 호출이 보상 환불을 접수했으면 환불 ID, 이미 있으면 비어 있음
     */
    public java.util.Optional<Long> initiateCompensationIfAbsent(Long paymentId, Long orderId, long amount,
                                                                 String reason) {
        if (refunds.hasEffectiveRefund(paymentId)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(transactions.execute(status -> {
            if (refunds.hasEffectiveRefund(paymentId)) {
                return null;
            }
            return initiator.initiateCompensation(paymentId, orderId, amount, reason, SOURCE);
        }));
    }

    /** PG에 환불 기록이 있는 경우의 확정. 실행 워커의 확정 경로를 그대로 탄다. */
    public boolean completeFromProvider(RefundRepository.RefundSnapshot refund, String providerRefundId,
                                        Instant refundedAt) {
        Instant now = Instant.now(clock);
        transactions.executeWithoutResult(status -> {
            worker.complete(refund, PgClient.RefundResult.succeeded(providerRefundId,
                    refundedAt == null ? now : refundedAt));
            auditLogs.record(SOURCE, "REFUND_RESOLVED_BY_RECONCILIATION", "REFUND", refund.id(),
                    "PG에 환불 기록이 있어 확정했습니다: " + providerRefundId, now);
        });
        log.info("대사로 환불 {}을 확정했습니다 (providerRefundId={}).", refund.id(), providerRefundId);
        return true;
    }

    /**
     * PG에 환불 기록이 없는 경우의 재발행.
     *
     * @return 재발행했으면 true. 미처리 이벤트가 이미 있으면 손대지 않고 false.
     */
    public boolean republishRequestIfNoPending(RefundRepository.RefundSnapshot refund) {
        if (outbox.hasPending(RefundInitiator.EVENT_REFUND_REQUESTED, refund.id())) {
            log.debug("환불 {}은 미처리 이벤트가 남아 있어 재발행하지 않습니다 (워커 대기 중).", refund.id());
            return false;
        }
        Instant now = Instant.now(clock);
        transactions.executeWithoutResult(status -> {
            String payload = json.write(new RefundInitiator.RefundRequestedPayload(refund.id(),
                    refund.paymentId(), refund.orderId(), refund.amount(), refund.compensation(),
                    now.toString()));
            outbox.append(RefundInitiator.EVENT_REFUND_REQUESTED, "REFUND", refund.id(), payload, now);
            auditLogs.record(SOURCE, "REFUND_REQUEST_REPUBLISHED", "REFUND", refund.id(),
                    "PG에 환불 기록이 없고 미처리 이벤트도 없어 실행 이벤트를 재발행했습니다.", now);
        });
        log.info("대사가 환불 {}의 실행 이벤트를 재발행했습니다.", refund.id());
        return true;
    }
}
