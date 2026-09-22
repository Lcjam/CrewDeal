package com.groupdrop.reconciliation;

import com.groupdrop.payment.PaymentResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 14.4의 대사·운영 API. */
@RestController
public class ReconciliationController {

    private final ReconciliationOpsService ops;

    public ReconciliationController(ReconciliationOpsService ops) {
        this.ops = ops;
    }

    /** {@code minAgeMinutes}는 대사 대상의 최소 경과 시간이다. 기본 30, 테스트(S4-b)는 0으로 보낸다. */
    @PostMapping("/api/admin/reconciliations")
    public ResponseEntity<ReconciliationRepository.Run> run(
            Authentication authentication,
            @RequestBody(required = false) ReconciliationOpsService.RunReconciliationRequest request) {
        Integer minAge = request == null || request.minAgeMinutes() == null ? 30 : request.minAgeMinutes();
        return ResponseEntity.ok(ops.run(authentication.getName(), minAge));
    }

    @GetMapping("/api/admin/reconciliations/{runId}")
    public ResponseEntity<ReconciliationRepository.Run> run(Authentication authentication,
                                                            @PathVariable Long runId) {
        return ResponseEntity.ok(ops.findRun(authentication.getName(), runId));
    }

    @GetMapping("/api/admin/reconciliations")
    public ResponseEntity<List<ReconciliationRepository.Run>> runs(Authentication authentication) {
        return ResponseEntity.ok(ops.runs(authentication.getName()));
    }

    @GetMapping("/api/admin/payments")
    public ResponseEntity<List<com.groupdrop.payment.PaymentRepository.AdminPayment>> payments(
            Authentication authentication, @RequestParam(required = false) String status,
            @RequestParam(required = false) Long campaignId) {
        return ResponseEntity.ok(ops.payments(authentication.getName(), status, campaignId));
    }

    @GetMapping("/api/admin/outbox-events")
    public ResponseEntity<List<com.groupdrop.outbox.OutboxRepository.Event>> outboxEvents(
            Authentication authentication, @RequestParam(required = false) String status) {
        return ResponseEntity.ok(ops.outboxEvents(authentication.getName(), status));
    }

    @GetMapping("/api/admin/inbox-events")
    public ResponseEntity<List<com.groupdrop.outbox.InboxRepository.Event>> inboxEvents(
            Authentication authentication, @RequestParam(required = false) String status) {
        return ResponseEntity.ok(ops.inboxEvents(authentication.getName(), status));
    }

    @GetMapping("/api/admin/reconciliation-discrepancies")
    public ResponseEntity<List<ReconciliationRepository.Discrepancy>> discrepancies(
            Authentication authentication, @RequestParam(required = false) String status) {
        return ResponseEntity.ok(ops.discrepancies(authentication.getName(), status));
    }

    @PostMapping("/api/admin/reconciliation-discrepancies/{id}/retry")
    public ResponseEntity<ReconciliationRepository.Discrepancy> retry(Authentication authentication,
                                                                      @PathVariable Long id) {
        return ResponseEntity.ok(ops.retry(authentication.getName(), id));
    }

    /** 해결 메모 기록 (REC-02). 14.4 초안에는 없지만 REC-02가 명시적으로 요구하는 조작이다. */
    @PostMapping("/api/admin/reconciliation-discrepancies/{id}/resolve")
    public ResponseEntity<ReconciliationRepository.Discrepancy> resolve(
            Authentication authentication, @PathVariable Long id,
            @RequestBody(required = false) ReconciliationOpsService.ResolveDiscrepancyRequest request) {
        return ResponseEntity.ok(ops.resolve(authentication.getName(), id,
                request == null ? null : request.note()));
    }

    @PostMapping("/api/admin/payments/{paymentId}/sync")
    public ResponseEntity<PaymentResponse> sync(Authentication authentication, @PathVariable Long paymentId) {
        return ResponseEntity.ok(ops.syncPayment(authentication.getName(), paymentId));
    }

    @PostMapping("/api/admin/outbox-events/{eventId}/retry")
    public ResponseEntity<ReconciliationOpsService.EventRetryResponse> retryOutboxEvent(
            Authentication authentication, @PathVariable Long eventId) {
        return ResponseEntity.ok(ops.retryOutboxEvent(authentication.getName(), eventId));
    }

    @PostMapping("/api/admin/inbox-events/{eventId}/retry")
    public ResponseEntity<ReconciliationOpsService.EventRetryResponse> retryInboxEvent(
            Authentication authentication, @PathVariable Long eventId) {
        return ResponseEntity.ok(ops.retryInboxEvent(authentication.getName(), eventId));
    }

    @GetMapping("/api/admin/ops/summary")
    public ResponseEntity<ReconciliationOpsService.OpsSummary> summary(Authentication authentication) {
        return ResponseEntity.ok(ops.summary(authentication.getName()));
    }
}
