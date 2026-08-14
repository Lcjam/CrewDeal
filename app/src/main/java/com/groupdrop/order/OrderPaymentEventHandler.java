package com.groupdrop.order;

import com.groupdrop.campaign.CampaignAvailabilityNotifier;
import com.groupdrop.common.AuditLogRepository;
import com.groupdrop.common.Json;
import com.groupdrop.outbox.OutboxHandler;
import com.groupdrop.outbox.OutboxRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * payment.finalized 소비자 (13.4). 결제가 어느 경로로 확정됐든 주문·예약 후처리는 여기 하나다.
 *
 * <p>at-least-once 전달이므로 모든 효과는 조건부 UPDATE이고, 두 번째 전달은 전이 0건으로 조용히 끝난다.
 */
@Component
public class OrderPaymentEventHandler implements OutboxHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentEventHandler.class);

    private final OrderRepository orders;
    private final AuditLogRepository auditLogs;
    private final CampaignAvailabilityNotifier availabilityNotifier;
    private final Json json;
    private final Clock clock;

    public OrderPaymentEventHandler(OrderRepository orders, AuditLogRepository auditLogs,
                                    CampaignAvailabilityNotifier availabilityNotifier, Json json, Clock clock) {
        this.orders = orders;
        this.auditLogs = auditLogs;
        this.availabilityNotifier = availabilityNotifier;
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
        if (orders.markPaid(orderId, now)) {
            confirmReservations(orderId, now);
            return;
        }

        String status = orders.findStatus(orderId).orElse(null);
        if ("PAID".equals(status)) {
            return; // 이미 반영된 이벤트의 재전달
        }
        if ("EXPIRED".equals(status) && orders.markRefundingFromExpired(orderId, now)) {
            // 11.6 경쟁: 만료가 이겨 재고가 이미 풀렸는데 결제는 성공했다.
            // 3주차 범위는 여기까지(경쟁 감지 + REFUNDING 전환). 자동 환불 실행은 4주차다.
            orders.flagOpsHold(orderId, "결제 성공과 예약 만료가 경쟁해 자동 환불 대상입니다 (11.6).", now);
            auditLogs.record("outbox", "ORDER_EXPIRED_BUT_PAID", "ORDER", orderId,
                    "결제 %d 성공이 만료 이후 확인되어 REFUNDING으로 전환했습니다.".formatted(payload.paymentId()), now);
            log.warn("주문 {}이 만료된 뒤 결제 {}의 성공이 확인되어 REFUNDING으로 전환했습니다.", orderId, payload.paymentId());
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
        for (OrderRepository.ExpiredReservation reservation : orders.expireReservations(orderId, now)) {
            if (!orders.restoreInventory(reservation.inventoryId(), reservation.quantity())) {
                throw new IllegalStateException("예약 재고 복구 불변식 위반: inventoryId=" + reservation.inventoryId());
            }
        }
        if (!orders.decrementPurchaseCounter(
                expired.campaignId(), expired.buyerId(), expired.totalQuantity(), now)) {
            throw new IllegalStateException("구매 카운터 복구 불변식 위반: orderId=" + orderId);
        }
        refreshAfterCommit(expired.campaignId());
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
