package com.groupdrop.refund;

import com.groupdrop.common.AuditLogRepository;
import com.groupdrop.common.Json;
import com.groupdrop.order.OrderRepository;
import com.groupdrop.outbox.OutboxHandler;
import com.groupdrop.outbox.OutboxRepository;
import com.groupdrop.payment.PaymentRepository;
import com.groupdrop.payment.PgClient;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code refund.requested} 실행 워커 (13.4). Outbox의 교과서적 용도이자 리스 기반 2단계 소비
 * (청구 → 처리 → 완료)의 존재 이유다 — 외부 호출을 하는 핸들러가 청구 트랜잭션을 물고 있으면 안 된다.
 *
 * <p>이 클래스에 {@code @Transactional}이 없는 것은 실수가 아니라 설계다 (ADR-003). PG 호출은
 * 트랜잭션 밖에서 하고, 그 결과 반영만 {@link TransactionTemplate}으로 짧게 연다.
 *
 * <p>결과 3분기는 결제와 같다. 성공은 확정, <b>명시적</b> 실패만 실패, 타임아웃은 실패가 아니라
 * "모름"이므로 환불 행을 {@code REQUESTED}로 둔 채 예외를 던져 Outbox 재시도에 맡긴다 (PAY-03).
 * 가상 PG가 providerPaymentId 기준 멱등이므로(14.5) 재시도가 두 번째 환불을 만들지 않는다.
 */
@Component
public class RefundExecutionWorker implements OutboxHandler {

    private static final Logger log = LoggerFactory.getLogger(RefundExecutionWorker.class);

    static final String EVENT_REFUND_COMPLETED = "refund.completed";
    private static final String SOURCE = "refund-worker";

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final AuditLogRepository auditLogs;
    private final PgClient pgClient;
    private final RefundMetrics metrics;
    private final TransactionTemplate transactions;
    private final Json json;
    private final Clock clock;

    public RefundExecutionWorker(RefundRepository refunds, PaymentRepository payments, OrderRepository orders,
                                 OutboxRepository outbox, AuditLogRepository auditLogs, PgClient pgClient,
                                 RefundMetrics metrics, TransactionTemplate transactions, Json json, Clock clock) {
        this.refunds = refunds;
        this.payments = payments;
        this.orders = orders;
        this.outbox = outbox;
        this.auditLogs = auditLogs;
        this.pgClient = pgClient;
        this.metrics = metrics;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String eventType() {
        return RefundInitiator.EVENT_REFUND_REQUESTED;
    }

    @Override
    public void handle(OutboxRepository.ClaimedEvent event) {
        RefundInitiator.RefundRequestedPayload payload =
                json.read(event.payload(), RefundInitiator.RefundRequestedPayload.class);
        RefundRepository.RefundSnapshot refund = refunds.find(payload.refundId())
                .orElseThrow(() -> new IllegalStateException("환불을 찾을 수 없습니다: " + payload.refundId()));
        if (!"REQUESTED".equals(refund.status())) {
            log.debug("환불 {}은 이미 {}로 종결되어 실행을 건너뜁니다.", refund.id(), refund.status());
            return;
        }
        if (refund.providerPaymentId() == null) {
            // PG 식별자가 없는 결제는 환불할 대상을 지목할 수 없다. 추측해서 호출하면 남의 결제를 환불한다.
            throw new IllegalStateException("PG 결제 식별자가 없어 환불을 실행할 수 없습니다: refund=" + refund.id());
        }

        // ── 트랜잭션 밖 ── PG 환불 호출 (ADR-003)
        PgClient.RefundResult result = metrics.timePgRefund(() -> pgClient.refund(
                new PgClient.RefundCommand(refund.providerPaymentId(), merchantRefundId(refund), refund.amount())));

        switch (result.outcome()) {
            case SUCCEEDED -> transactions.executeWithoutResult(status -> complete(refund, result));
            case FAILED -> transactions.executeWithoutResult(status -> fail(refund, result));
            case TIMEOUT -> {
                metrics.recordUnresolved();
                log.warn("환불 {}의 결과를 확정하지 못했습니다. REQUESTED를 유지하고 재시도합니다: {}",
                        refund.id(), result.detail());
                throw new RefundNotResolvedException(refund.id(), result.detail());
            }
        }
    }

    /**
     * 패키지 가시성인 이유: 대사(REC-01)의 미완 환불 해소가 이 경로를 그대로 재사용해야 하기 때문이다.
     * 확정 로직을 복사해 두 벌로 만들면 두 경로가 서로 다르게 낡는다.
     */
    void complete(RefundRepository.RefundSnapshot refund, PgClient.RefundResult result) {
        Instant now = Instant.now(clock);
        Instant refundedAt = result.refundedAt() == null ? now : result.refundedAt();
        if (!refunds.markCompleted(refund.id(), result.providerRefundId(), refundedAt, now)) {
            log.debug("환불 {}은 다른 실행이 이미 확정했습니다.", refund.id());
            return;
        }
        if (!refund.compensation()) {
            if (!payments.markRefunded(refund.paymentId(), now)) {
                throw new IllegalStateException("환불 완료 결제 전이에 실패했습니다: " + refund.paymentId());
            }
            orders.markRefunded(refund.orderId(), now);
        }
        // 13.4: 역분개(LED-03)와 회수 배치는 refund.completed 소비자의 몫이다. 여기서 하지 않는 이유는
        // 실행(외부 호출)과 확정 후처리(내부 분개)를 같은 트랜잭션에 묶지 않기 위해서다.
        String payload = json.write(new RefundCompletedPayload(refund.id(), refund.paymentId(),
                refund.orderId(), refund.amount(), refund.compensation(), now.toString()));
        outbox.append(EVENT_REFUND_COMPLETED, "REFUND", refund.id(), payload, now);
        metrics.recordCompleted(refund.compensation() ? "compensation" : "standard");
        log.info("환불 {}을 완료했습니다 (결제 {}, providerRefundId={}).",
                refund.id(), refund.paymentId(), result.providerRefundId());
    }

    /**
     * PG가 환불 실패를 <b>명시</b>한 경우에만 도달한다 (10.3의 {@code REFUNDING → SUCCEEDED}).
     * 주문 복귀는 10.2에 따라 {@code PAID} 출신에만 허용하고, 만료 출신은 {@code REFUNDING}에 둔 채
     * 운영자 이관 플래그를 세워 정산에서 제외한다 — 재고가 이미 방출됐으므로 {@code PAID}로 되돌리면
     * 물건 없는 주문이 정산 대상에 들어간다.
     */
    private void fail(RefundRepository.RefundSnapshot refund, PgClient.RefundResult result) {
        Instant now = Instant.now(clock);
        if (!refunds.markFailed(refund.id(), result.failureCode(), result.detail(), now)) {
            return;
        }
        metrics.recordFailed(result.failureCode());

        if (refund.compensation()) {
            // 이중 결제 패자의 보상 환불 실패. 주문은 승자 결제로 정상이므로 건드리지 않고,
            // PG에 남은 중복 승인만 운영자가 처리하도록 감사 로그로 남긴다 (PAY-01).
            auditLogs.record(SOURCE, "COMPENSATION_REFUND_FAILED", "PAYMENT", refund.paymentId(),
                    "이중 결제 보상 환불이 실패했습니다. 수동 처리 대상입니다: " + result.detail(), now);
            log.error("이중 결제 보상 환불 {}이 실패했습니다. PG에 중복 승인이 남아 있습니다.", refund.id());
            return;
        }

        if (!payments.markRefundFailed(refund.paymentId(), now)) {
            throw new IllegalStateException("환불 실패 결제 복귀에 실패했습니다: " + refund.paymentId());
        }
        if (refunds.hasConfirmedReservation(refund.orderId())) {
            orders.markPaidFromRefunding(refund.orderId(), now);
        } else {
            orders.flagOpsHold(refund.orderId(),
                    "만료 출신 주문의 환불이 실패했습니다. PAID 복귀와 정산 편입을 금지합니다 (10.2).", now);
            auditLogs.record(SOURCE, "REFUND_FAILED_OPS_HOLD", "ORDER", refund.orderId(),
                    "환불 %d 실패로 운영자 이관했습니다: %s".formatted(refund.id(), result.detail()), now);
        }
        auditLogs.record(SOURCE, "REFUND_FAILED", "REFUND", refund.id(),
                "PG가 환불 실패를 명시했습니다: %s / %s".formatted(result.failureCode(), result.detail()), now);
    }

    /** PG 쪽 환불 식별자. 환불 행 ID로 고정해 재시도가 같은 값을 보내게 한다. */
    private String merchantRefundId(RefundRepository.RefundSnapshot refund) {
        return "mrf_" + refund.id();
    }

    /** 재시도 대상임을 이름으로 드러내는 예외. Outbox 워커가 백오프 후 재시도한다. */
    static class RefundNotResolvedException extends RuntimeException {

        RefundNotResolvedException(Long refundId, String detail) {
            super("환불 %d의 결과가 확정되지 않았습니다: %s".formatted(refundId, detail));
        }
    }

    public record RefundCompletedPayload(Long refundId, Long paymentId, Long orderId, long amount,
                                         boolean compensation, String occurredAt) { }
}
