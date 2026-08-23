package com.groupdrop.mockpg;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * refund는 providerPaymentId 기준 멱등이며, 결제 장애 모드와 독립된 {@code refundMode}를 따른다.
 */
@RestController
@RequestMapping("/mock-pg/payments")
public class PaymentConfirmController {

    private final Clock clock;
    private final PaymentStore paymentStore;
    private final RefundStore refundStore;
    private final FailureModeState failureModeState;
    private final WebhookSender webhookSender;
    private final MockPgStats stats;

    public PaymentConfirmController(Clock clock, PaymentStore paymentStore, RefundStore refundStore,
            FailureModeState failureModeState, WebhookSender webhookSender, MockPgStats stats) {
        this.clock = clock;
        this.paymentStore = paymentStore;
        this.refundStore = refundStore;
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

    /**
     * providerPaymentId 기준 멱등 환불(14.5). 전액 환불만 지원한다(REF-02) — amount를 지정했는데 결제
     * 금액과 다르면 409. 환불 실행 자체는 {@link RefundStore#executeOnce}가 원자적으로 최대 1회만
     * 보장하므로, 여기서는 이미 실행된 건을 먼저 걸러내는 빠른 경로만 둔다(진짜 원자성 보장은 스토어 몫).
     */
    @PostMapping("/{providerPaymentId}/refund")
    public ResponseEntity<RefundResponse> refund(@PathVariable String providerPaymentId,
            @RequestBody(required = false) RefundRequest request) {
        stats.incrementRefundRequests();

        // 이미 환불이 확정된 건은 장애 모드와 무관하게 즉시 기존 결과를 반환한다 —
        // 앱 쪽 환불 워커의 재시도 해소 경로 전제(mock-pg/CLAUDE.md).
        Optional<RefundRecord> existing = refundStore.find(providerPaymentId);
        if (existing.isPresent()) {
            return ResponseEntity.ok(toRefundResponse(existing.get()));
        }

        PaymentRecord payment = paymentStore.findByProviderId(providerPaymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "결제를 찾을 수 없습니다: " + providerPaymentId));
        if (!"SUCCEEDED".equals(payment.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "SUCCEEDED 상태의 결제만 환불할 수 있습니다: " + payment.status());
        }
        Long requestedAmount = request != null ? request.amount() : null;
        if (requestedAmount != null && requestedAmount != payment.amount()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "부분 환불은 지원하지 않습니다. 결제 금액과 다른 amount는 허용되지 않습니다.");
        }
        String merchantRefundId = request != null ? request.merchantRefundId() : null;

        RefundStore.ExecutionResult result = refundStore.executeOnce(providerPaymentId, () -> new RefundRecord(
                refundStore.nextProviderRefundId(), providerPaymentId, merchantRefundId, payment.amount(),
                "REFUNDED", Instant.now(clock)));

        if (result.created()) {
            stats.incrementRefundExecuted();
            if (failureModeState.current().refundMode() == RefundFailureMode.SUCCEED_BUT_TIMEOUT) {
                // confirm의 SUCCEED_BUT_TIMEOUT과 같은 패턴: 내부적으로는 REFUNDED로 이미 확정 저장했지만
                // 이번 호출자에게는 정상 응답을 주지 않는다. 재호출은 위 fast path로 즉시 200을 받는다.
                sleep(failureModeState.current().delayMs());
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                        "PG 환불 응답 유실 시뮬레이션(SUCCEED_BUT_TIMEOUT) — 내부적으로는 REFUNDED로 확정되었다.");
            }
        }
        return ResponseEntity.ok(toRefundResponse(result.record()));
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

    private RefundResponse toRefundResponse(RefundRecord record) {
        return new RefundResponse(record.providerRefundId(), record.providerPaymentId(), record.merchantRefundId(),
                record.amount(), record.status(), record.refundedAt());
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
            String status, Instant approvedAt) {
    }

    /** 본문 전체가 선택 사항이다 — 생략해도 전액 환불로 동작한다. */
    public record RefundRequest(String merchantRefundId, Long amount) {
    }

    public record RefundResponse(String providerRefundId, String providerPaymentId, String merchantRefundId,
            long amount, String status, Instant refundedAt) {
    }
}
