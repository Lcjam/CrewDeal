package com.groupdrop.settlement;

import com.groupdrop.common.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 14.3·14.4의 정산 API. */
@RestController
public class SettlementController {

    private final SettlementService settlements;

    public SettlementController(SettlementService settlements) {
        this.settlements = settlements;
    }

    @GetMapping("/api/admin/settlements")
    public ResponseEntity<List<SettlementService.AdminSettlementResponse>> list(
            Authentication authentication, @RequestParam(required = false) Long campaignId,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(settlements.adminBatches(authentication.getName(), campaignId, status));
    }

    @PostMapping("/api/admin/settlements")
    public ResponseEntity<SettlementService.RunResult> run(
            Authentication authentication,
            @RequestBody SettlementService.RunSettlementRequest request) {
        if (request == null || request.campaignId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_ID_REQUIRED", "campaignId는 필수입니다.");
        }
        return ResponseEntity.ok(settlements.run(authentication.getName(), request.campaignId()));
    }

    @GetMapping("/api/admin/settlements/{batchId}")
    public ResponseEntity<SettlementService.SettlementBatchDetailResponse> batch(
            Authentication authentication, @PathVariable Long batchId) {
        return ResponseEntity.ok(settlements.batch(authentication.getName(), batchId));
    }

    @PostMapping("/api/admin/settlements/{batchId}/retry")
    public ResponseEntity<SettlementService.SettlementBatchResponse> retry(
            Authentication authentication, @PathVariable Long batchId) {
        return ResponseEntity.ok(settlements.retry(authentication.getName(), batchId));
    }

    @PostMapping("/api/admin/settlements/{batchId}/hold")
    public ResponseEntity<SettlementService.SettlementBatchResponse> hold(
            Authentication authentication, @PathVariable Long batchId,
            @RequestBody(required = false) SettlementService.HoldSettlementRequest request) {
        return ResponseEntity.ok(settlements.hold(authentication.getName(), batchId,
                request == null ? null : request.reason()));
    }

    /** 10.5의 {@code HELD → PENDING}. 14.4 초안에는 없지만 hold의 역연산이 없으면 보류가 종점이 된다. */
    @PostMapping("/api/admin/settlements/{batchId}/release")
    public ResponseEntity<SettlementService.SettlementBatchResponse> release(
            Authentication authentication, @PathVariable Long batchId) {
        return ResponseEntity.ok(settlements.release(authentication.getName(), batchId));
    }

    @GetMapping("/api/admin/settlement-adjustments")
    public ResponseEntity<List<SettlementService.UnrecoveredAdjustmentResponse>> adjustments(
            Authentication authentication) {
        return ResponseEntity.ok(settlements.unrecoveredAdjustments(authentication.getName()));
    }

    @GetMapping("/api/influencers/me/settlements")
    public ResponseEntity<List<SettlementService.SettlementBatchResponse>> influencerSettlements(
            Authentication authentication) {
        return ResponseEntity.ok(settlements.myInfluencerSettlements(authentication.getName()));
    }

    @GetMapping("/api/suppliers/me/settlements")
    public ResponseEntity<List<SettlementService.SettlementBatchResponse>> supplierSettlements(
            Authentication authentication) {
        return ResponseEntity.ok(settlements.mySupplierSettlements(authentication.getName()));
    }
}
