package com.groupdrop.order;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/api/campaigns/{campaignId}/orders")
    public ResponseEntity<OrderResponse> create(Authentication authentication, @PathVariable Long campaignId,
                                                 @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                 @RequestBody CreateOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.createOrder(authentication.getName(), campaignId, idempotencyKey, request));
    }

    @GetMapping("/api/orders")
    public ResponseEntity<List<OrderResponse>> list(Authentication authentication) {
        return ResponseEntity.ok(orderService.getOrders(authentication.getName()));
    }

    @GetMapping("/api/orders/{orderId}")
    public ResponseEntity<OrderResponse> get(Authentication authentication, @PathVariable Long orderId) {
        return ResponseEntity.ok(orderService.getOrder(authentication.getName(), orderId));
    }
}
