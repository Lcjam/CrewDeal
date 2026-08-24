package com.groupdrop.campaign;

import java.time.Instant;
import java.util.List;

/** 14.1의 구매자용 캠페인 현황 응답. */
public record CampaignStatusResponse(
        Long campaignId,
        CampaignStatus status,
        Instant startsAt,
        Instant endsAt,
        int remainingPurchaseQuantity,
        List<SkuStatus> skus) {

    public record SkuStatus(Long productSkuId, String optionName, int availableQuantity) {
    }
}
