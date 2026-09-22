package com.groupdrop.campaign;

import java.time.Instant;
import java.util.List;

/** 구매자 목록은 수수료율과 SKU 공급 단가를 노출하지 않는다. */
public record PublicCampaignListResponse(
        Long id, String name, String slug, CampaignStatus status,
        Long supplierId, String supplierName, Long productId, String productName,
        long dealPrice, Instant startsAt, Instant endsAt, int perUserPurchaseLimit, List<Sku> skus) {

    public record Sku(Long productSkuId, String optionName, int availableQuantity) { }
}
