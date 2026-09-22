package com.groupdrop.payment;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * 결과 불명(UNKNOWN)은 202로 응답한다. 200(성공 확정)이나 4xx(실패 확정)로 답하면
     * 클라이언트가 확정되지 않은 결과를 확정으로 오해하기 때문이다 (PAY-03).
     */
    @PostMapping("/api/orders/{orderId}/payments")
    public ResponseEntity<PaymentResponse> pay(Authentication authentication, @PathVariable Long orderId,
                                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                                               @RequestBody CreatePaymentRequest request) {
        PaymentService.Outcome outcome =
                paymentService.requestPayment(authentication.getName(), orderId, idempotencyKey, request);
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.body());
    }

    @GetMapping("/api/payments/{paymentId}")
    public ResponseEntity<PaymentResponse> get(Authentication authentication, @PathVariable Long paymentId) {
        return ResponseEntity.ok(paymentService.getPayment(authentication.getName(), paymentId));
    }

    @GetMapping("/api/orders/{orderId}/payments")
    public ResponseEntity<java.util.List<PaymentResponse>> list(Authentication authentication, @PathVariable Long orderId) {
        return ResponseEntity.ok(paymentService.listOrderPayments(authentication.getName(), orderId));
    }
}
