package com.groupdrop.order;

import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        Long campaignId,
        String status,
        long totalAmount,
        int totalQuantity,
        Instant expiresAt,
        List<Item> items) {

    public record Item(Long id, Long productSkuId, int quantity, long unitPrice, long lineAmount,
                       String reservationStatus) {
    }
}
