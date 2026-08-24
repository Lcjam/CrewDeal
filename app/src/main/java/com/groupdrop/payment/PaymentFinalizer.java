package com.groupdrop.payment;

import com.groupdrop.common.AuditLogRepository;
import com.groupdrop.common.CampaignTransactionBarrier;
import com.groupdrop.common.Json;
import com.groupdrop.outbox.OutboxRepository;
import com.groupdrop.refund.RefundInitiator;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 결제 확정의 단일 코드 경로 (13.4). 동기 응답·웹훅·PG 조회·스윕 어디서 확정되든 여기를 지나며,
 * 여기서만 payment.finalized를 발행한다. 행복 경로 전용 후처리를 따로 두지 않는 이유는
 * 이중 코드 경로가 곧 이중 정합성 규칙이 되기 때문이다.
 *
 * <p>호출자의 트랜잭션 안에서 실행되어야 한다 — 상태 전이와 이벤트 적재가 한 커밋이어야
 * Outbox가 유실 없는 후속 처리를 보장한다.
 */
@Component
public class PaymentFinalizer {

    private static final Logger log = LoggerFactory.getLogger(PaymentFinalizer.class);

    static final String EVENT_PAYMENT_FINALIZED = "payment.finalized";

    private final PaymentRepository payments;
    private final CampaignTransactionBarrier campaignBarrier;
    private final OutboxRepository outbox;
    private final AuditLogRepository auditLogs;
    private final RefundInitiator refundInitiator;
    private final PaymentMetrics metrics;
    private final Json json;
    private final Clock clock;

    public PaymentFinalizer(PaymentRepository payments, CampaignTransactionBarrier campaignBarrier,
                            OutboxRepository outbox, AuditLogRepository auditLogs,
                            RefundInitiator refundInitiator, PaymentMetrics metrics, Json json, Clock clock) {
        this.payments = payments;
        this.campaignBarrier = campaignBarrier;
        this.outbox = outbox;
        this.auditLogs = auditLogs;
        this.refundInitiator = refundInitiator;
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @return 이 호출이 확정을 성사시켰으면 true. false는 오류가 아니라 "다른 경로가 먼저 확정했다"는 뜻이다.
     */
    public Result succeed(PaymentRepository.PaymentSnapshot payment, String providerPaymentId,
                          Instant approvedAt, String source) {
        campaignBarrier.requireByOrderId(payment.orderId());
        Instant now = Instant.now(clock);
        Instant approved = approvedAt == null ? now : approvedAt;

        // 부분 유니크(13.2)에 부딪히면 트랜잭션이 통째로 죽으므로 먼저 승자 유무를 본다.
        // 이 선조회와 UPDATE 사이의 경쟁은 제약이 막고, 롤백된 시도는 재시도에서 이 분기를 탄다.
        if (payments.existsSucceededForOrder(payment.orderId(), payment.id())) {
            boolean superseded = payments.markSuperseded(payment.id(), providerPaymentId, approved,
                    "같은 주문에 이미 유효한 성공 결제가 있습니다.", now);
            if (superseded) {
                metrics.recordSuperseded();
                compensate(payment, providerPaymentId, source, now);
                auditLogs.record(source, "PAYMENT_SUPERSEDED", "PAYMENT", payment.id(),
                        "이중 결제 패자로 종결했습니다. providerPaymentId=" + providerPaymentId, now);
            }
            return superseded ? Result.SUPERSEDED : Result.ALREADY_SETTLED;
        }

        if (!payments.markSucceeded(payment.id(), providerPaymentId, approved, now)) {
            return Result.ALREADY_SETTLED;
        }
        appendFinalizedEvent(payment, "SUCCEEDED", now);
        metrics.recordSucceeded(source);
        return Result.APPLIED;
    }

    public Result fail(PaymentRepository.PaymentSnapshot payment, String failureCode,
                       String failureReason, String source) {
        campaignBarrier.requireByOrderId(payment.orderId());
        Instant now = Instant.now(clock);
        if (!payments.markFailed(payment.id(), failureCode, failureReason, now)) {
            return Result.ALREADY_SETTLED;
        }
        appendFinalizedEvent(payment, "FAILED", now);
        metrics.recordFailed(source, failureCode);
        return Result.APPLIED;
    }

    /**
     * PG 호출 기록이 없는 READY 고아의 확정 (PAY-03). PG에 기록 자체가 없으므로 FAILED가 안전하며,
     * 방치하면 영구 MISSING_PROVIDER 불일치로 정산을 HELD시킨다. 13.4에 따라 이 경로도 이벤트를 낸다.
     */
    public Result failReadyOrphan(PaymentRepository.PaymentSnapshot payment, String source) {
        campaignBarrier.requireByOrderId(payment.orderId());
        Instant now = Instant.now(clock);
        if (!payments.markReadyOrphanFailed(payment.id(), now)) {
            return Result.ALREADY_SETTLED;
        }
        appendFinalizedEvent(payment, "FAILED", now);
        metrics.recordFailed(source, "ORPHAN_READY");
        return Result.APPLIED;
    }

    /**
     * PAY-01 이중 결제 보상. 보상 환불의 접수({@code refunds} 행 + {@code refund.requested})는
     * {@code SUPERSEDED} 전이와 <b>같은 트랜잭션</b>이어야 한다 — 전이만 커밋되고 접수가 유실되면
     * PG에 승인된 채 아무도 되살리지 않는 결제가 남는다. PG 환불 호출은 실행 워커의 몫이다 (13.4).
     *
     * <p>패자 결제는 수익 분해(LED-02)에 진입한 적이 없으므로 원장에는 기록하지 않는다.
     */
    private void compensate(PaymentRepository.PaymentSnapshot payment, String providerPaymentId,
                            String source, Instant now) {
        if (providerPaymentId == null) {
            // PG 식별자를 모르면 환불 대상을 지목할 수 없다. 추측하지 않고 운영자 확인 대상으로 남긴다.
            auditLogs.record(source, "COMPENSATION_REFUND_SKIPPED", "PAYMENT", payment.id(),
                    "PG 결제 식별자가 없어 보상 환불을 접수하지 못했습니다.", now);
            log.error("결제 {}의 보상 환불을 접수하지 못했습니다 — PG 식별자가 없습니다.", payment.id());
            return;
        }
        Long refundId = refundInitiator.initiateCompensation(payment.id(), payment.orderId(), payment.amount(),
                "이중 결제 패자의 자동 보상 환불 (PAY-01)", "duplicate-payment");
        log.warn("결제 {}를 이중 결제 패자(SUPERSEDED)로 종결하고 보상 환불 {}을 접수했습니다.",
                payment.id(), refundId);
    }

    private void appendFinalizedEvent(PaymentRepository.PaymentSnapshot payment, String status, Instant now) {
        String payload = json.write(new PaymentFinalizedPayload(payment.id(), payment.orderId(), status,
                payment.amount(), now.toString()));
        outbox.append(EVENT_PAYMENT_FINALIZED, "PAYMENT", payment.id(), payload, now);
    }

    public enum Result {
        /** 이 호출이 상태를 바꾸고 이벤트를 발행했다. */
        APPLIED,
        /** 다른 경로가 이미 확정했다. 멱등하게 무시한다. */
        ALREADY_SETTLED,
        /** 이중 결제 패자로 종결했다 (PAY-01). */
        SUPERSEDED
    }

    public record PaymentFinalizedPayload(Long paymentId, Long orderId, String status, long amount,
                                          String occurredAt) { }
}
