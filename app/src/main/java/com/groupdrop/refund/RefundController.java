package com.groupdrop.refund;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RefundController {

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    /**
     * REF-02 전체 환불 (운영자 전용). PG 호출은 실행 워커가 하므로 이 응답은 <b>접수</b> 확인인 202다 —
     * 200으로 답하면 아직 PG에 나가지도 않은 환불을 완료로 오해하게 된다 (13.4).
     */
    @PostMapping("/api/payments/{paymentId}/refunds")
    public ResponseEntity<RefundResponse> refund(Authentication authentication, @PathVariable Long paymentId,
                                                 @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                 @RequestBody(required = false) CreateRefundRequest request) {
        RefundService.Outcome outcome =
                refundService.requestRefund(authentication.getName(), paymentId, idempotencyKey, request);
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.body());
    }

    @GetMapping("/api/payments/{paymentId}/refunds")
    public ResponseEntity<List<RefundResponse>> list(Authentication authentication, @PathVariable Long paymentId) {
        return ResponseEntity.ok(refundService.listRefunds(authentication.getName(), paymentId));
    }
}
