package com.groupdrop.refund;

import com.groupdrop.common.Json;
import com.groupdrop.order.OrderRepository;
import com.groupdrop.outbox.OutboxRepository;
import com.groupdrop.payment.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 환불 <b>접수</b>의 단일 지점 (REF-02, PAY-01 보상, 11.6 자동 환불).
 *
 * <p>접수는 세 가지가 한 커밋이어야 한다 — {@code refunds} 행 생성, 결제·주문 상태 전이,
 * {@code refund.requested} Outbox 이벤트. PG 환불 호출은 여기서 하지 않는다 (13.4):
 * 웹훅·조회·대사는 <b>확정</b> 파이프라인이지 <b>실행</b> 파이프라인이 아니므로, PG에 나간 적 없는
 * 환불은 실행 워커만이 되살릴 수 있다. 접수 커밋과 PG 호출 사이에서 크래시해도 이벤트가 남는다.
 *
 * <p>호출자의 트랜잭션 안에서 실행되어야 한다.
 */
@Component
public class RefundInitiator {

    private static final Logger log = LoggerFactory.getLogger(RefundInitiator.class);

    public static final String EVENT_REFUND_REQUESTED = "refund.requested";

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final RefundMetrics metrics;
    private final Json json;
    private final Clock clock;

    public RefundInitiator(RefundRepository refunds, PaymentRepository payments, OrderRepository orders,
                           OutboxRepository outbox, RefundMetrics metrics, Json json, Clock clock) {
        this.refunds = refunds;
        this.payments = payments;
        this.orders = orders;
        this.outbox = outbox;
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
    }

    /**
     * 성공 결제의 전액 환불 접수 (REF-02, 11.6 자동 환불 공용).
     *
     * <p>주문 전이는 10.2의 두 경로({@code PAID → REFUNDING}, {@code EXPIRED → REFUNDING})를 차례로
     * 시도한다. 11.6 자동 환불 경로에서는 주문이 이미 {@code REFUNDING}으로 넘어와 있어 둘 다 0건이며,
     * 그것이 정상이다 — 상태를 미리 조회해 분기하지 않고 조건부 UPDATE의 결과로 판정한다.
     */
    public Long initiate(Long paymentId, Long orderId, long amount, String reason, String source) {
        Instant now = Instant.now(clock);
        if (!payments.markRefunding(paymentId, now)) {
            // 선조회를 통과했더라도 다른 요청이 먼저 접수했을 수 있다. 조건부 UPDATE의 0건이 그 판정이며,
            // 호출자(요청 스레드는 409, 워커는 재시도)가 각자의 방식으로 처리한다.
            throw new RefundNotAcceptableException(paymentId);
        }
        if (!orders.markRefundingFromPaid(orderId, now)) {
            orders.markRefundingFromExpired(orderId, now);
        }
        return append(paymentId, orderId, amount, false, reason, source, now);
    }

    /**
     * PAY-01 이중 결제 패자의 보상 환불. 결제는 {@code SUPERSEDED} 종착 상태를 유지하고(10.3),
     * 주문도 건드리지 않는다 — 그 주문의 유효 결제는 승자 쪽이고 정상적으로 {@code PAID}다.
     */
    public Long initiateCompensation(Long paymentId, Long orderId, long amount, String reason, String source) {
        Instant now = Instant.now(clock);
        return append(paymentId, orderId, amount, true, reason, source, now);
    }

    private Long append(Long paymentId, Long orderId, long amount, boolean compensation, String reason,
                        String source, Instant now) {
        Long refundId = refunds.insertRequested(paymentId, orderId, amount, compensation, reason, now);
        String payload = json.write(new RefundRequestedPayload(refundId, paymentId, orderId, amount,
                compensation, now.toString()));
        outbox.append(EVENT_REFUND_REQUESTED, "REFUND", refundId, payload, now);
        metrics.recordRequested(source);
        log.info("환불 {}을 접수했습니다 (결제 {}, 주문 {}, 보상={}, 출처={}).",
                refundId, paymentId, orderId, compensation, source);
        return refundId;
    }

    /** 결제가 이미 환불 접수됐거나 성공 상태가 아니다. 경쟁의 결과이지 버그가 아니다. */
    public static class RefundNotAcceptableException extends RuntimeException {

        public RefundNotAcceptableException(Long paymentId) {
            super("성공 상태가 아닌 결제는 환불할 수 없습니다: " + paymentId);
        }
    }

    public record RefundRequestedPayload(Long refundId, Long paymentId, Long orderId, long amount,
                                         boolean compensation, String occurredAt) { }
}
