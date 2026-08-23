package com.groupdrop.payment;

import com.groupdrop.common.ApiException;
import com.groupdrop.common.Json;
import com.groupdrop.outbox.InboxRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * PAY-04. 수신 스레드는 서명 검증과 Inbox 적재까지만 하고 즉시 200을 반환한다 —
 * 실제 상태 전이는 InboxWorker가 비동기로 수행한다. 여기서 처리까지 하면
 * 처리 지연이 곧 PG 재전송 폭주가 된다.
 *
 * <p>본문을 문자열로 받는 이유는 서명 대상이 역직렬화 결과가 아니라 원본 바이트이기 때문이다.
 */
@RestController
public class PaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    static final String EVENT_TYPE = "payment.status.changed";

    private final WebhookSignatureVerifier verifier;
    private final InboxRepository inbox;
    private final PaymentMetrics metrics;
    private final Json json;
    private final Clock clock;

    public PaymentWebhookController(WebhookSignatureVerifier verifier, InboxRepository inbox,
                                    PaymentMetrics metrics, Json json, Clock clock) {
        this.verifier = verifier;
        this.inbox = inbox;
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
    }

    @PostMapping("/api/webhooks/payments")
    @Transactional
    public ResponseEntity<WebhookAck> receive(@RequestHeader(value = "X-PG-Signature", required = false) String signature,
                                              @RequestHeader(value = "X-PG-Timestamp", required = false) String timestamp,
                                              @RequestBody String rawBody) {
        WebhookSignatureVerifier.Verdict verdict = verifier.verify(signature, timestamp, rawBody);
        if (!verdict.valid()) {
            metrics.recordWebhookRejected();
            log.warn("웹훅을 거부했습니다: {}", verdict);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_INVALID",
                    "웹훅 서명 검증에 실패했습니다: " + verdict);
        }

        PaymentWebhookPayload payload;
        try {
            payload = json.read(rawBody, PaymentWebhookPayload.class);
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEBHOOK_PAYLOAD_INVALID",
                    "웹훅 본문을 해석할 수 없습니다.");
        }
        if (payload.eventId() == null || payload.eventId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEBHOOK_EVENT_ID_REQUIRED", "eventId는 필수입니다.");
        }

        // 중복 수신은 오류가 아니다. UNIQUE가 두 번째 적재를 막고, 응답은 그대로 200이어야
        // PG가 재전송을 멈춘다 (11.4).
        boolean accepted = inbox.receive(payload.eventId(), EVENT_TYPE, rawBody, Instant.now(clock));
        if (!accepted) {
            metrics.recordWebhookDuplicate();
        }
        return ResponseEntity.ok(new WebhookAck(payload.eventId(), accepted ? "ACCEPTED" : "DUPLICATE"));
    }

    public record WebhookAck(String eventId, String result) { }
}
