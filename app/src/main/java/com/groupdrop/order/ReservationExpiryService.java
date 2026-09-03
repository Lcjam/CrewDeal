package com.groupdrop.order;

import com.groupdrop.campaign.CampaignAvailabilityNotifier;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** ORD-03의 결제가 없는 2주차 순수 예약 만료. */
@Service
public class ReservationExpiryService {

    private static final int BATCH_SIZE = 100;

    private final OrderRepository orderRepository;
    private final CampaignAvailabilityNotifier availabilityNotifier;
    private final Clock clock;

    public ReservationExpiryService(OrderRepository orderRepository,
                                    CampaignAvailabilityNotifier availabilityNotifier, Clock clock) {
        this.orderRepository = orderRepository;
        this.availabilityNotifier = availabilityNotifier;
        this.clock = clock;
    }

    @Transactional
    public int expireDueReservations() {
        Instant now = Instant.now(clock);
        Set<Long> campaignsToRefresh = new LinkedHashSet<>();
        int expiredCount = 0;
        for (Long orderId : orderRepository.lockExpirableOrderIds(now, BATCH_SIZE)) {
            OrderRepository.ExpiredOrder order = orderRepository.expireOrder(orderId, now).orElse(null);
            if (order == null) {
                continue;
            }
            if (!orderRepository.decrementPurchaseCounter(
                    order.campaignId(), order.buyerId(), order.totalQuantity(), now)) {
                throw new IllegalStateException("구매 카운터 복구 불변식 위반: orderId=" + orderId);
            }
            for (OrderRepository.ExpiredReservation reservation : orderRepository.expireReservations(orderId, now)) {
                if (!orderRepository.restoreInventory(reservation.inventoryId(), reservation.quantity())) {
                    throw new IllegalStateException("예약 재고 복구 불변식 위반: inventoryId=" + reservation.inventoryId());
                }
            }
            campaignsToRefresh.add(order.campaignId());
            expiredCount++;
        }
        if (!campaignsToRefresh.isEmpty()) {
            afterCommit(campaignsToRefresh);
        }
        return expiredCount;
    }

    private void afterCommit(Set<Long> campaignIds) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (Long campaignId : campaignIds) {
                    availabilityNotifier.refreshAsync(campaignId);
                }
            }
        });
    }
}
