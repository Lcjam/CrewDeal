package com.groupdrop.mockpg;

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

    public TestControlController(FailureModeState failureModeState, PendingWebhookQueue pendingWebhookQueue,
            WebhookSender webhookSender, MockPgStats stats) {
        this.failureModeState = failureModeState;
        this.pendingWebhookQueue = pendingWebhookQueue;
        this.webhookSender = webhookSender;
        this.stats = stats;
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
        FailureModeSettings settings = new FailureModeSettings(
                mode,
                request.delayMs() != null ? request.delayMs() : 0,
                request.blockWebhook() != null && request.blockWebhook(),
                request.webhookDuplicateCount() != null ? request.webhookDuplicateCount() : 1,
                request.webhookReverseOrder() != null && request.webhookReverseOrder());
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

    @GetMapping("/stats")
    public StatsResponse stats() {
        return new StatsResponse(stats.confirmRequestCount(), stats.webhookSentCount(),
                pendingWebhookQueue.pendingCount());
    }

    public record FailureModeRequest(String mode, Long delayMs, Boolean blockWebhook,
            Integer webhookDuplicateCount, Boolean webhookReverseOrder) {
    }

    public record ReplayRequest(String providerPaymentId, String merchantPaymentId) {
    }

    public record ReplayResult(int replayedCount) {
    }

    public record StatsResponse(long confirmRequestCount, long webhookSentCount, long webhookPendingCount) {
    }
}
