package com.groupdrop.campaign;

import java.time.Instant;
import java.util.List;

/** 03-프론트엔드-계획 v1 1단계의 역할별 캠페인 목록 계약. */
public record CampaignListResponse(
        Long id, String name, String slug, CampaignStatus status,
        Long supplierId, String supplierName, Long productId, String productName,
        long dealPrice, Instant startsAt, Instant endsAt, int perUserPurchaseLimit,
        Integer commissionRateBp, String rejectionReason, List<Sku> skus) {

    public record Sku(Long productSkuId, String optionName, int availableQuantity) { }
}
