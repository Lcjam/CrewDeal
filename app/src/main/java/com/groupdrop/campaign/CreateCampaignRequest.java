package com.groupdrop.campaign;

import java.time.Instant;
import java.util.List;

public record CreateCampaignRequest(
        String name,
        String slug,
        Long supplierId,
        Long productId,
        Long dealPrice,
        Instant startsAt,
        Instant endsAt,
        Integer perUserPurchaseLimit,
        Integer commissionRateBp,
        List<SkuAllocation> skus) {

    public record SkuAllocation(Long productSkuId, Integer allocatedQuantity, Long supplyUnitPrice) {
    }
}
