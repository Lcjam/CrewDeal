package com.groupdrop.ledger;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 운영자 콘솔의 읽기 전용 원장 API. */
@RestController
public class LedgerAdminController {

    private final LedgerAdminService ledger;

    public LedgerAdminController(LedgerAdminService ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/api/admin/campaigns/{id}/ledger")
    public ResponseEntity<LedgerAdminService.CampaignLedgerResponse> campaign(
            Authentication authentication, @PathVariable("id") Long campaignId) {
        return ResponseEntity.ok(ledger.campaign(authentication.getName(), campaignId));
    }

    @GetMapping("/api/admin/orders/{orderId}/ledger")
    public ResponseEntity<List<LedgerAdminService.OrderPostingResponse>> order(
            Authentication authentication, @PathVariable Long orderId) {
        return ResponseEntity.ok(ledger.order(authentication.getName(), orderId));
    }

    @GetMapping("/api/admin/ledger/unbalanced")
    public ResponseEntity<List<LedgerAdminService.UnbalancedResponse>> unbalanced(Authentication authentication) {
        return ResponseEntity.ok(ledger.unbalanced(authentication.getName()));
    }
}
