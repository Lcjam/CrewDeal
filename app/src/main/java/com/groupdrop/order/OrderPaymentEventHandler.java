package com.groupdrop.order;

import com.groupdrop.campaign.CampaignAvailabilityNotifier;
import com.groupdrop.common.AuditLogRepository;
import com.groupdrop.common.Json;
import com.groupdrop.ledger.LedgerService;
import com.groupdrop.outbox.OutboxHandler;
import com.groupdrop.outbox.OutboxRepository;
import com.groupdrop.refund.RefundInitiator;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * payment.finalized 소비자 (13.4). 결제가 어느 경로로 확정됐든 주문·예약·원장 후처리는 여기 하나다.
 * 13.4의 효과 목록(주문 상태 전이, 예약 확정·해제, 결제 원장 기록)이 이 클래스의 책임 범위다.
 *
 * <p>at-least-once 전달이므로 모든 효과는 조건부 UPDATE이고, 두 번째 전달은 전이 0건으로 조용히 끝난다.
 * 원장 분개의 멱등은 참조 유니크가 맡는다 (LED-02).
 */
@Component
public class OrderPaymentEventHandler implements OutboxHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentEventHandler.class);

    private final OrderRepository orders;
    private final AuditLogRepository auditLogs;
    private final CampaignAvailabilityNotifier availabilityNotifier;
    private final LedgerService ledger;
    private final RefundInitiator refundInitiator;
    private final Json json;
    private final Clock clock;

    public OrderPaymentEventHandler(OrderRepository orders, AuditLogRepository auditLogs,
                                    CampaignAvailabilityNotifier availabilityNotifier, LedgerService ledger,
                                    RefundInitiator refundInitiator, Json json, Clock clock) {
        this.orders = orders;
        this.auditLogs = auditLogs;
        this.availabilityNotifier = availabilityNotifier;
        this.ledger = ledger;
        this.refundInitiator = refundInitiator;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String eventType() {
        return "payment.finalized";
    }

    @Override
    @Transactional
    public void handle(OutboxRepository.ClaimedEvent event) {
        Payload payload = json.read(event.payload(), Payload.class);
        Instant now = Instant.now(clock);
        if ("SUCCEEDED".equals(payload.status())) {
            applySuccess(payload, now);
        } else {
            applyFailure(payload, now);
        }
    }

    private void applySuccess(Payload payload, Instant now) {
        Long orderId = payload.orderId();
        // LED-02는 주문 상태와 무관하게 기록한다. 만료 경쟁(11.6)에서 환불로 가는 결제도 PG에서는
        // 실제로 돈이 움직였고, 그 사실을 원장에 남긴 뒤 LED-03으로 되돌리는 것이 원장의 규칙이다.
        ledger.recordPayment(payload.paymentId(), orderId, payload.amount(),
                parseOccurredAt(payload.occurredAt(), now), now);

        if (orders.markPaid(orderId, now)) {
            confirmReservations(orderId, now);
            return;
        }

        String status = orders.findStatus(orderId).orElse(null);
        if ("PAID".equals(status)) {
            return; // 이미 반영된 이벤트의 재전달
        }
        if ("EXPIRED".equals(status)) {
            // 11.6 경쟁: 만료가 이겨 재고가 이미 풀렸는데 결제는 성공했다. 재고를 되찾지 않고 자동 환불한다.
            // ops_hold는 여기서 세우지 않는다 — 환불이 성공하면 운영자가 할 일이 없고, 실패했을 때만
            // 실행 워커가 이관 플래그를 세운다 (10.2).
            Long refundId = refundInitiator.initiateExpiredOrder(payload.paymentId(), orderId, payload.amount(),
                    "예약 만료 후 결제 성공 확인에 따른 자동 환불 (11.6)", "expiry-race");
            auditLogs.record("outbox", "ORDER_EXPIRED_BUT_PAID", "ORDER", orderId,
                    "결제 %d 성공이 만료 이후 확인되어 REFUNDING 전환 + 자동 환불 %d를 접수했습니다."
                            .formatted(payload.paymentId(), refundId), now);
            log.warn("주문 {}이 만료된 뒤 결제 {}의 성공이 확인되어 자동 환불 {}을 접수했습니다.",
                    orderId, payload.paymentId(), refundId);
            return;
        }
        auditLogs.record("outbox", "ORDER_TRANSITION_SKIPPED", "ORDER", orderId,
                "결제 %d 성공을 반영할 수 없는 주문 상태입니다: %s".formatted(payload.paymentId(), status), now);
    }

    private void confirmReservations(Long orderId, Instant now) {
        for (OrderRepository.ConfirmedReservation reservation : orders.confirmReservations(orderId, now)) {
            if (!orders.commitInventory(reservation.inventoryId(), reservation.quantity())) {
                throw new IllegalStateException("예약 확정 재고 불변식 위반: inventoryId=" + reservation.inventoryId());
            }
        }
    }

    /**
     * ORD-03·8.2-7: 결제 실패가 곧 예약 해제는 아니다. 만료 시각 전이라면 예약을 유지해 재시도를 허용하고,
     * 만료 시각이 지났을 때만 순수 만료와 같은 경로로 해제한다.
     */
    private void applyFailure(Payload payload, Instant now) {
        Long orderId = payload.orderId();
        OrderRepository.ExpiredOrder expired = orders.expireOrder(orderId, now).orElse(null);
        if (expired == null) {
            log.debug("주문 {}은 아직 만료 시각 전이므로 결제 실패 후에도 예약을 유지합니다.", orderId);
            return;
        }
        if (!orders.decrementPurchaseCounter(
                expired.campaignId(), expired.buyerId(), expired.totalQuantity(), now)) {
            throw new IllegalStateException("구매 카운터 복구 불변식 위반: orderId=" + orderId);
        }
        for (OrderRepository.ExpiredReservation reservation : orders.expireReservations(orderId, now)) {
            if (!orders.restoreInventory(reservation.inventoryId(), reservation.quantity())) {
                throw new IllegalStateException("예약 재고 복구 불변식 위반: inventoryId=" + reservation.inventoryId());
            }
        }
        refreshAfterCommit(expired.campaignId());
    }

    private Instant parseOccurredAt(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private void refreshAfterCommit(Long campaignId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                availabilityNotifier.refreshAsync(campaignId);
            }
        });
    }

    private record Payload(Long paymentId, Long orderId, String status, long amount, String occurredAt) { }
}
