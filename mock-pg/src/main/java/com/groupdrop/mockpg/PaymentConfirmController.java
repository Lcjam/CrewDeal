package com.groupdrop.mockpg;

import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * confirm은 서버가 생성해 전달하는 merchantPaymentId 기준 멱등이다(14.5). 현재 활성 장애 모드
 * ({@link FailureModeState})에 따라 정상 성공·거절·지연·성공-후-응답유실 중 하나로 동작한다.
 */
@RestController
@RequestMapping("/mock-pg/payments")
public class PaymentConfirmController {

    private final Clock clock;
    private final PaymentStore paymentStore;
    private final FailureModeState failureModeState;
    private final WebhookSender webhookSender;
    private final MockPgStats stats;

    public PaymentConfirmController(Clock clock, PaymentStore paymentStore, FailureModeState failureModeState,
            WebhookSender webhookSender, MockPgStats stats) {
        this.clock = clock;
        this.paymentStore = paymentStore;
        this.failureModeState = failureModeState;
        this.webhookSender = webhookSender;
        this.stats = stats;
    }

    @PostMapping("/confirm")
    public ConfirmResponse confirm(@RequestBody ConfirmRequest request) {
        stats.incrementConfirmRequests();
        validate(request);
        String merchantPaymentId = request.merchantPaymentId();
        String orderId = String.valueOf(request.orderId());
        long amount = request.amount();

        var existing = paymentStore.findByMerchantId(merchantPaymentId);
        if (existing.isPresent()) {
            PaymentRecord record = existing.get();
            if (record.amount() != amount) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "merchantPaymentId를 다른 금액에 재사용할 수 없습니다.");
            }
            return toResponse(record);
        }

        FailureModeSettings settings = failureModeState.current();
        String providerPaymentId = paymentStore.nextProviderPaymentId();

        return switch (settings.mode()) {
            case DECLINE -> finalizeAndRespond(providerPaymentId, merchantPaymentId, orderId, amount, "FAILED");
            case DELAY -> {
                sleep(settings.delayMs());
                yield finalizeAndRespond(providerPaymentId, merchantPaymentId, orderId, amount, "SUCCEEDED");
            }
            case SUCCEED_BUT_TIMEOUT -> {
                // 확정과 웹훅 통지는 즉시 수행하고, 그 다음에 응답을 붙잡았다가 정상 응답 없이 끊는다 —
                // 호출자 입장에서는 "성공했는지 알 수 없는" 상태(PAY-03 UNKNOWN)를 재현한다.
                finalizeAndRespond(providerPaymentId, merchantPaymentId, orderId, amount, "SUCCEEDED");
                sleep(settings.delayMs());
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                        "PG 응답 유실 시뮬레이션(SUCCEED_BUT_TIMEOUT) — 내부적으로는 SUCCEEDED로 확정되었다.");
            }
            case NORMAL -> finalizeAndRespond(providerPaymentId, merchantPaymentId, orderId, amount, "SUCCEEDED");
        };
    }

    @GetMapping("/{providerPaymentId}")
    public ConfirmResponse get(@PathVariable String providerPaymentId) {
        PaymentRecord record = paymentStore.findByProviderId(providerPaymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "결제를 찾을 수 없습니다: " + providerPaymentId));
        return toResponse(record);
    }

    private ConfirmResponse finalizeAndRespond(String providerPaymentId, String merchantPaymentId, String orderId,
            long amount, String status) {
        Instant processedAt = Instant.now(clock);
        PaymentRecord candidate = new PaymentRecord(providerPaymentId, merchantPaymentId, orderId, amount, status,
                processedAt);
        PaymentStore.InsertResult result = paymentStore.insertIfAbsent(merchantPaymentId, candidate);
        if (result.created()) {
            webhookSender.notifyPaymentDecision(merchantPaymentId, result.record().providerPaymentId(), orderId,
                    amount, status, processedAt);
        }
        return toResponse(result.record());
    }

    private void sleep(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "지연 처리 중 인터럽트되었습니다.");
        }
    }

    private ConfirmResponse toResponse(PaymentRecord record) {
        return new ConfirmResponse(record.providerPaymentId(), record.merchantPaymentId(), record.orderId(),
                record.amount(), record.status(), record.processedAt());
    }

    private void validate(ConfirmRequest request) {
        if (request == null || request.merchantPaymentId() == null || request.merchantPaymentId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "merchantPaymentId는 필수입니다.");
        }
        if (request.amount() == null || request.amount() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount는 0보다 커야 합니다.");
        }
        if (request.orderId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderId는 필수입니다.");
        }
    }

    /** orderId는 문자열·숫자 어느 쪽으로 와도 받아 문자열로 정규화한다. */
    public record ConfirmRequest(String merchantPaymentId, Long amount, Object orderId) {
    }

    public record ConfirmResponse(String providerPaymentId, String merchantPaymentId, String orderId, long amount,
            String status, Instant processedAt) {
    }
}
