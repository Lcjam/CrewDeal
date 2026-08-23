package com.groupdrop.ledger;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예상 정산액 조회 API. 14.3의 인플루언서 대시보드가 기준이고, 공급사 쪽은 6.3의 "예상 공급 대금 조회"에
 * 대응하는 대칭 엔드포인트다. 확정 정산 내역(`/settlements`)은 정산 배치가 생기는 5주차 범위다.
 */
@RestController
public class ExpectedSettlementController {

    private final ExpectedSettlementService expectedSettlements;

    public ExpectedSettlementController(ExpectedSettlementService expectedSettlements) {
        this.expectedSettlements = expectedSettlements;
    }

    @GetMapping("/api/influencers/me/campaigns/{campaignId}/dashboard")
    public ResponseEntity<ExpectedSettlementService.InfluencerDashboardResponse> influencerDashboard(
            Authentication authentication, @PathVariable Long campaignId) {
        return ResponseEntity.ok(expectedSettlements.influencerDashboard(authentication.getName(), campaignId));
    }

    @GetMapping("/api/suppliers/me/campaigns/{campaignId}/expected-settlement")
    public ResponseEntity<ExpectedSettlementService.SupplierExpectedSettlementResponse> supplierExpected(
            Authentication authentication, @PathVariable Long campaignId) {
        return ResponseEntity.ok(
                expectedSettlements.supplierExpectedSettlement(authentication.getName(), campaignId));
    }

    @GetMapping("/api/admin/campaigns/{campaignId}/settlement-preview")
    public ResponseEntity<ExpectedSettlementService.CampaignSettlementBreakdown> adminBreakdown(
            Authentication authentication, @PathVariable Long campaignId) {
        return ResponseEntity.ok(expectedSettlements.breakdown(authentication.getName(), campaignId));
    }
}
