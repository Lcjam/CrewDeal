package com.groupdrop.mockpg;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 2주차 범위의 정상 성공 confirm. merchantPaymentId가 멱등성 경계다. */
@RestController
@RequestMapping("/mock-pg/payments")
public class PaymentConfirmController {

    private final ConcurrentMap<String, ConfirmResponse> payments = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Clock clock;

    public PaymentConfirmController(Clock clock) {
        this.clock = clock;
    }

    @PostMapping("/confirm")
    public ConfirmResponse confirm(@RequestBody ConfirmRequest request) {
        validate(request);
        return payments.compute(request.merchantPaymentId(), (merchantPaymentId, existing) -> {
            if (existing != null) {
                if (existing.amount() != request.amount()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "merchantPaymentId를 다른 금액에 재사용할 수 없습니다.");
                }
                return existing;
            }
            return new ConfirmResponse("pg_" + sequence.incrementAndGet(), merchantPaymentId,
                    request.amount(), "SUCCEEDED", Instant.now(clock));
        });
    }

    private void validate(ConfirmRequest request) {
        if (request == null || request.merchantPaymentId() == null || request.merchantPaymentId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "merchantPaymentId는 필수입니다.");
        }
        if (request.amount() == null || request.amount() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount는 0보다 커야 합니다.");
        }
    }

    public record ConfirmRequest(String merchantPaymentId, Long amount) { }

    public record ConfirmResponse(String providerPaymentId, String merchantPaymentId, long amount,
                                  String status, Instant approvedAt) { }
}
