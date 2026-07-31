package com.groupdrop.campaign;

import java.time.Instant;
import java.util.List;

public record CampaignResponse(
        Long id,
        String name,
        String slug,
        CampaignStatus status,
        Long supplierId,
        Long productId,
        Long dealPrice,
        Instant startsAt,
        Instant endsAt,
        Integer perUserPurchaseLimit,
        Integer commissionRateBp,
        List<SkuInfo> skus) {

    public record SkuInfo(Long productSkuId, String optionName, Integer allocatedQuantity) {
    }
}
