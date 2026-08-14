package com.groupdrop.mockpg;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * PAY-04 결제 웹훅을 서명해 발송한다. 대상 URL이 죽어있어도(테스트 기본 환경) confirm 처리 자체를
 * 실패시키지 않는다 — 발송 실패는 로그만 남기고 삼킨다.
 */
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);
    private static final DateTimeFormatter OCCURRED_AT_FORMAT = DateTimeFormatter.ISO_INSTANT;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);

    private final Clock clock;
    private final FailureModeState failureModeState;
    private final PendingWebhookQueue pendingWebhookQueue;
    private final MockPgWebhookProperties properties;
    private final MockPgStats stats;
    private final AtomicLong eventSequence = new AtomicLong();
    private final RestClient restClient;

    public WebhookSender(Clock clock, FailureModeState failureModeState, PendingWebhookQueue pendingWebhookQueue,
            MockPgWebhookProperties properties, MockPgStats stats) {
        this.clock = clock;
        this.failureModeState = failureModeState;
        this.pendingWebhookQueue = pendingWebhookQueue;
        this.properties = properties;
        this.stats = stats;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(HTTP_TIMEOUT);
        requestFactory.setReadTimeout(HTTP_TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * 결제가 새로 확정된 시점(confirm 최초 처리)에 호출한다. {@code blockWebhook}이면 즉시 보내지 않고
     * 대기열에 넣어 {@code webhooks/replay}로만 발사되게 한다.
     */
    public void notifyPaymentDecision(String merchantPaymentId, String providerPaymentId, String orderId,
            long amount, String status, Instant occurredAt) {
        WebhookEvent event = new WebhookEvent(nextEventId(), providerPaymentId, orderId, status, amount, occurredAt);
        if (failureModeState.current().blockWebhook()) {
            pendingWebhookQueue.enqueue(merchantPaymentId, providerPaymentId, event);
        } else {
            deliverNow(event);
        }
    }

    /**
     * 이벤트를 실제로 발사한다. 현재 활성 설정의 {@code webhookDuplicateCount}만큼 같은 eventId로 반복
     * 전송하고, {@code webhookReverseOrder}가 켜져 있으면 SUCCEEDED 발송 뒤 더 과거 시각의 PROCESSING
     * 이벤트를(다른 eventId로) 추가로 보내 순서 역전을 재현한다. replay 경로와 즉시 발송 경로가 공유한다.
     */
    public void deliverNow(WebhookEvent event) {
        FailureModeSettings settings = failureModeState.current();
        int duplicateCount = Math.max(0, settings.webhookDuplicateCount());
        for (int i = 0; i < duplicateCount; i++) {
            send(event);
        }
        if (settings.webhookReverseOrder() && "SUCCEEDED".equals(event.status())) {
            WebhookEvent processingEvent = new WebhookEvent(nextEventId(), event.providerPaymentId(),
                    event.orderId(), "PROCESSING", event.amount(), event.occurredAt().minusSeconds(5));
            send(processingEvent);
        }
    }

    private void send(WebhookEvent event) {
        String body = toJson(event);
        String timestamp = String.valueOf(Instant.now(clock).getEpochSecond());
        String signature = hmacHex(properties.getSecret(), timestamp + "." + body);
        stats.incrementWebhookSent();
        try {
            restClient.post()
                    .uri(properties.getTargetUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-PG-Signature", signature)
                    .header("X-PG-Timestamp", timestamp)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("웹훅 발송 실패: target={}, eventId={}, status={}, reason={}",
                    properties.getTargetUrl(), event.eventId(), event.status(), e.toString());
        }
    }

    private String nextEventId() {
        return "evt_" + eventSequence.incrementAndGet();
    }

    private String toJson(WebhookEvent event) {
        return "{"
                + "\"eventId\":\"" + escape(event.eventId()) + "\","
                + "\"providerPaymentId\":\"" + escape(event.providerPaymentId()) + "\","
                + "\"orderId\":\"" + escape(event.orderId()) + "\","
                + "\"status\":\"" + escape(event.status()) + "\","
                + "\"amount\":" + event.amount() + ","
                + "\"occurredAt\":\"" + OCCURRED_AT_FORMAT.format(event.occurredAt()) + "\""
                + "}";
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String hmacHex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("웹훅 서명 생성 실패", e);
        }
    }
}
