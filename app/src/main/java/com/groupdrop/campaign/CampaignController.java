package com.groupdrop.campaign;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 캠페인 생성·승인 흐름 API (CAM-01, CAM-02, 14.1). 재고 현황·구매 가능 수량 조회(GET .../status)는
 * 2주차 범위라 이번 주 구현에서 제외한다.
 */
@RestController
public class CampaignController {

    private final CampaignService campaignService;

    public CampaignController(CampaignService campaignService) {
        this.campaignService = campaignService;
    }

    @PostMapping("/api/campaigns")
    public ResponseEntity<CampaignResponse> create(Authentication authentication,
                                                     @RequestBody CreateCampaignRequest request) {
        CampaignResponse response = campaignService.createCampaign(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/api/campaigns/{campaignId}/submit")
    public ResponseEntity<CampaignResponse> submit(Authentication authentication, @PathVariable Long campaignId) {
        return ResponseEntity.ok(campaignService.submitCampaign(authentication.getName(), campaignId));
    }

    @PostMapping("/api/admin/campaigns/{campaignId}/approve")
    public ResponseEntity<CampaignResponse> approve(Authentication authentication, @PathVariable Long campaignId) {
        return ResponseEntity.ok(campaignService.approveCampaign(authentication.getName(), campaignId));
    }

    @PostMapping("/api/admin/campaigns/{campaignId}/reject")
    public ResponseEntity<CampaignResponse> reject(Authentication authentication,
                                                     @PathVariable Long campaignId,
                                                     @RequestBody RejectCampaignRequest request) {
        return ResponseEntity.ok(campaignService.rejectCampaign(authentication.getName(), campaignId, request));
    }

    @PostMapping("/api/admin/campaigns/{campaignId}/cancel")
    public ResponseEntity<CampaignResponse> cancel(Authentication authentication, @PathVariable Long campaignId) {
        return ResponseEntity.ok(campaignService.cancelCampaign(authentication.getName(), campaignId));
    }

    @GetMapping("/api/campaigns/{campaignId}")
    public ResponseEntity<CampaignResponse> get(@PathVariable Long campaignId) {
        return ResponseEntity.ok(campaignService.getCampaign(campaignId));
    }

    @GetMapping("/api/campaigns/slug/{slug}")
    public ResponseEntity<CampaignResponse> getBySlug(@PathVariable String slug) {
        return ResponseEntity.ok(campaignService.getCampaignBySlug(slug));
    }
}
