package com.groupdrop.mockpg;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 장애 주입·웹훅 재발사·통계 조회를 위한 테스트 제어 API (14.5). 재시작 없이 런타임에 모드를 바꾼다.
 */
@RestController
@RequestMapping("/mock-pg/test")
public class TestControlController {

    private final FailureModeState failureModeState;
    private final PendingWebhookQueue pendingWebhookQueue;
    private final WebhookSender webhookSender;
    private final MockPgStats stats;
    private final PaymentStore paymentStore;
    private final RefundStore refundStore;
    private final Clock clock;

    public TestControlController(FailureModeState failureModeState, PendingWebhookQueue pendingWebhookQueue,
            WebhookSender webhookSender, MockPgStats stats, PaymentStore paymentStore, RefundStore refundStore,
            Clock clock) {
        this.failureModeState = failureModeState;
        this.pendingWebhookQueue = pendingWebhookQueue;
        this.webhookSender = webhookSender;
        this.stats = stats;
        this.paymentStore = paymentStore;
        this.refundStore = refundStore;
        this.clock = clock;
    }

    /** 모드를 통째로 교체한다. {@code {"mode":"NORMAL"}}만 보내면 나머지 파라미터가 기본값으로 초기화된다. */
    @PostMapping("/failure-mode")
    public FailureModeSettings setFailureMode(@RequestBody FailureModeRequest request) {
        if (request == null || request.mode() == null || request.mode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode는 필수입니다.");
        }
        FailureMode mode;
        try {
            mode = FailureMode.valueOf(request.mode().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "알 수 없는 mode: " + request.mode());
        }
        // refundMode는 결제 mode와 독립된 축이다 — 생략 시 NORMAL로 초기화된다(통째 교체, 부분 병합 아님).
        RefundFailureMode refundMode = RefundFailureMode.NORMAL;
        if (request.refundMode() != null && !request.refundMode().isBlank()) {
            try {
                refundMode = RefundFailureMode.valueOf(request.refundMode().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "알 수 없는 refundMode: " + request.refundMode());
            }
        }
        FailureModeSettings settings = new FailureModeSettings(
                mode,
                request.delayMs() != null ? request.delayMs() : 0,
                request.blockWebhook() != null && request.blockWebhook(),
                request.webhookDuplicateCount() != null ? request.webhookDuplicateCount() : 1,
                request.webhookReverseOrder() != null && request.webhookReverseOrder(),
                refundMode);
        failureModeState.replace(settings);
        return settings;
    }

    /** 대기열의 보류 웹훅을 재발사한다. 대상 미지정 시 보류분 전체를 발사한다. */
    @PostMapping("/webhooks/replay")
    public ReplayResult replayWebhooks(@RequestBody(required = false) ReplayRequest request) {
        String providerPaymentId = request != null ? request.providerPaymentId() : null;
        String merchantPaymentId = request != null ? request.merchantPaymentId() : null;
        List<WebhookEvent> toReplay = pendingWebhookQueue.drainMatching(providerPaymentId, merchantPaymentId);
        toReplay.forEach(webhookSender::deliverNow);
        return new ReplayResult(toReplay.size());
    }

    /**
     * S7 대사 테스트용 임의 거래 주입 (14.5). confirm 경로를 우회해 저장소에 직접 심는다 —
     * 목적이 "내부 기록과 다른 PG 거래"를 만드는 것이라, 멱등·웹훅·장애 모드가 개입하면
     * 원하는 불일치를 만들 수 없다.
     */
    @PostMapping("/transactions")
    public InjectedTransaction injectTransaction(@RequestBody InjectTransactionRequest request) {
        if (request == null || request.orderId() == null || request.amount() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderId와 amount는 필수입니다.");
        }
        String status = request.status() == null ? "SUCCEEDED" : request.status().trim().toUpperCase();
        if (!"SUCCEEDED".equals(status) && !"FAILED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status는 SUCCEEDED 또는 FAILED만 가능합니다.");
        }
        Instant processedAt = parse(request.processedAt(), Instant.now(clock));
        String providerPaymentId = request.providerPaymentId() == null || request.providerPaymentId().isBlank()
                ? paymentStore.nextProviderPaymentId() : request.providerPaymentId().trim();
        String merchantPaymentId = request.merchantPaymentId() == null || request.merchantPaymentId().isBlank()
                ? "injected_" + providerPaymentId : request.merchantPaymentId().trim();

        PaymentRecord record = paymentStore.inject(new PaymentRecord(providerPaymentId, merchantPaymentId,
                String.valueOf(request.orderId()), request.amount(), status, processedAt));
        String providerRefundId = null;
        if (request.refundedAmount() != null && request.refundedAmount() > 0) {
            providerRefundId = refundStore.nextProviderRefundId();
            refundStore.inject(new RefundRecord(providerRefundId, providerPaymentId,
                    "injected_" + providerRefundId, request.refundedAmount(), "REFUNDED", processedAt));
        }
        return new InjectedTransaction(record.providerPaymentId(), record.merchantPaymentId(),
                record.orderId(), record.amount(), record.status(), record.processedAt(), providerRefundId);
    }

    private Instant parse(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "processedAt은 ISO-8601이어야 합니다.");
        }
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        return new StatsResponse(stats.confirmRequestCount(), stats.webhookSentCount(),
                pendingWebhookQueue.pendingCount(), stats.refundRequestCount(), stats.refundExecutedCount());
    }

    public record FailureModeRequest(String mode, Long delayMs, Boolean blockWebhook,
            Integer webhookDuplicateCount, Boolean webhookReverseOrder, String refundMode) {
    }

    public record ReplayRequest(String providerPaymentId, String merchantPaymentId) {
    }

    public record ReplayResult(int replayedCount) {
    }

    public record StatsResponse(long confirmRequestCount, long webhookSentCount, long webhookPendingCount,
            long refundRequestCount, long refundExecutedCount) {
    }

    public record InjectTransactionRequest(String providerPaymentId, String merchantPaymentId, Long orderId,
            Long amount, String status, String processedAt, Long refundedAmount) {
    }

    public record InjectedTransaction(String providerPaymentId, String merchantPaymentId, String orderId,
            long amount, String status, Instant processedAt, String providerRefundId) {
    }
}
