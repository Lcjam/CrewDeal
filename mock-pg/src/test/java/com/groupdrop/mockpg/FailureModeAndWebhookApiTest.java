package com.groupdrop.mockpg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 3주차 범위: 장애 모드 4종(정상/거절/지연/성공-후-응답유실) + 웹훅 중복·역순·재발사(replay)를 실제 서버
 * (RANDOM_PORT)와 로컬 웹훅 수신 스텁 서버로 검증한다. 서명 문자열 조합(timestamp + "." + body)이
 * app 쪽 검증 로직과 맞아야 하므로 테스트에서도 동일한 방식으로 재계산해 비교한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FailureModeAndWebhookApiTest {

    private static final String DEFAULT_SECRET = "groupdrop-mock-pg-secret";

    private static HttpServer webhookStubServer;
    private static final CopyOnWriteArrayList<ReceivedWebhook> receivedWebhooks = new CopyOnWriteArrayList<>();

    @LocalServerPort
    private int port;

    private final RestClient client = RestClient.create();

    @DynamicPropertySource
    static void registerWebhookTarget(DynamicPropertyRegistry registry) throws IOException {
        webhookStubServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        webhookStubServer.createContext("/webhook", exchange -> {
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            String signature = exchange.getRequestHeaders().getFirst("X-PG-Signature");
            String timestamp = exchange.getRequestHeaders().getFirst("X-PG-Timestamp");
            receivedWebhooks.add(new ReceivedWebhook(body, signature, timestamp));
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        webhookStubServer.start();
        int stubPort = webhookStubServer.getAddress().getPort();
        registry.add("mockpg.webhook.target-url", () -> "http://localhost:" + stubPort + "/webhook");
        registry.add("mockpg.webhook.secret", () -> DEFAULT_SECRET);
    }

    @AfterAll
    static void stopStubServer() {
        if (webhookStubServer != null) {
            webhookStubServer.stop(0);
        }
    }

    @BeforeEach
    void resetState() {
        receivedWebhooks.clear();
        setFailureMode("NORMAL", null, null, null, null);
    }

    @Test
    void DECLINE_모드에서는_FAILED로_확정되고_GET_조회로_확인된다() {
        setFailureMode("DECLINE", null, null, null, null);
        String confirmJson = confirm("decline-1", 10000, "ord-decline-1");
        assertThat((String) JsonPath.read(confirmJson, "$.status")).isEqualTo("FAILED");
        String providerPaymentId = JsonPath.read(confirmJson, "$.providerPaymentId");

        String getJson = get(providerPaymentId);
        assertThat((String) JsonPath.read(getJson, "$.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(getJson, "$.providerPaymentId")).isEqualTo(providerPaymentId);
    }

    @Test
    void DELAY_모드는_지연_후_정상_성공한다() {
        setFailureMode("DELAY", 50L, null, null, null);
        long start = System.nanoTime();
        String confirmJson = confirm("delay-1", 2500, "ord-delay-1");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat((String) JsonPath.read(confirmJson, "$.status")).isEqualTo("SUCCEEDED");
        assertThat(elapsedMs).isGreaterThanOrEqualTo(50);
    }

    @Test
    void SUCCEED_BUT_TIMEOUT_은_내부적으로_SUCCEEDED로_확정되지만_정상_응답을_주지_않고_웹훅은_차단된다() {
        setFailureMode("SUCCEED_BUT_TIMEOUT", 100L, true, null, null);
        String merchantPaymentId = "timeout-1";
        String requestBody = confirmRequestBody(merchantPaymentId, 7000, "ord-timeout-1");

        try {
            client.post().uri(baseUrl("/mock-pg/payments/confirm"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .toBodilessEntity();
            fail("SUCCEED_BUT_TIMEOUT은 정상 응답을 주면 안 된다");
        } catch (RestClientResponseException expected) {
            // 기대한 동작: 지연 후 정상 응답 없이 끊긴다.
        }

        assertThat(receivedWebhooks).isEmpty();

        // 같은 merchantPaymentId로 재확인(멱등) — 이미 내부 확정이 끝났으므로 즉시 SUCCEEDED가 돌아와야 한다(재-hang 없음).
        String repeatJson = client.post().uri(baseUrl("/mock-pg/payments/confirm"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);
        assertThat((String) JsonPath.read(repeatJson, "$.status")).isEqualTo("SUCCEEDED");
        String providerPaymentId = JsonPath.read(repeatJson, "$.providerPaymentId");

        String getJson = get(providerPaymentId);
        assertThat((String) JsonPath.read(getJson, "$.status")).isEqualTo("SUCCEEDED");

        // 차단되어 있던 웹훅을 replay로 발사한다 (S4-a 전제).
        String replayJson = replay(null, null);
        assertThat(((Number) JsonPath.read(replayJson, "$.replayedCount")).intValue()).isEqualTo(1);
        assertThat(receivedWebhooks).hasSize(1);

        ReceivedWebhook webhook = receivedWebhooks.get(0);
        assertThat((String) JsonPath.read(webhook.body(), "$.status")).isEqualTo("SUCCEEDED");
        assertThat((String) JsonPath.read(webhook.body(), "$.providerPaymentId")).isEqualTo(providerPaymentId);
        assertThat((String) JsonPath.read(webhook.body(), "$.orderId")).isEqualTo("ord-timeout-1");
        verifySignature(webhook);
    }

    @Test
    void replay는_webhookDuplicateCount만큼_같은_eventId로_반복_발사한다() {
        setFailureMode("NORMAL", null, true, null, null); // 발사 차단, 대기열에만 적재
        confirm("dup-1", 3000, "ord-dup-1");
        assertThat(receivedWebhooks).isEmpty();

        setFailureMode("NORMAL", null, false, 3, null); // replay 시 동일 eventId로 3회 발사
        String replayJson = replay(null, "dup-1");
        assertThat(((Number) JsonPath.read(replayJson, "$.replayedCount")).intValue()).isEqualTo(1);

        assertThat(receivedWebhooks).hasSize(3);
        Set<String> eventIds = receivedWebhooks.stream()
                .map(w -> (String) JsonPath.read(w.body(), "$.eventId"))
                .collect(Collectors.toSet());
        assertThat(eventIds).hasSize(1);
        receivedWebhooks.forEach(w -> {
            assertThat((String) JsonPath.read(w.body(), "$.status")).isEqualTo("SUCCEEDED");
            verifySignature(w);
        });
    }

    @Test
    void webhookReverseOrder면_SUCCEEDED를_먼저_보내고_더_과거_시각의_PROCESSING을_다른_eventId로_나중에_보낸다() {
        setFailureMode("NORMAL", null, false, null, true);
        confirm("reverse-1", 8000, "ord-reverse-1");

        assertThat(receivedWebhooks).hasSize(2);
        ReceivedWebhook first = receivedWebhooks.get(0);
        ReceivedWebhook second = receivedWebhooks.get(1);

        assertThat((String) JsonPath.read(first.body(), "$.status")).isEqualTo("SUCCEEDED");
        assertThat((String) JsonPath.read(second.body(), "$.status")).isEqualTo("PROCESSING");

        String firstEventId = JsonPath.read(first.body(), "$.eventId");
        String secondEventId = JsonPath.read(second.body(), "$.eventId");
        assertThat(firstEventId).isNotEqualTo(secondEventId);

        Instant firstOccurredAt = Instant.parse((String) JsonPath.read(first.body(), "$.occurredAt"));
        Instant secondOccurredAt = Instant.parse((String) JsonPath.read(second.body(), "$.occurredAt"));
        assertThat(secondOccurredAt).isBefore(firstOccurredAt);
    }

    @Test
    void replay는_대상을_지정하면_해당_보류분만_발사한다() {
        setFailureMode("NORMAL", null, true, null, null);
        confirm("target-a", 1000, "ord-a");
        confirm("target-b", 2000, "ord-b");
        assertThat(receivedWebhooks).isEmpty();

        setFailureMode("NORMAL", null, false, null, null);

        String replayA = replay(null, "target-a");
        assertThat(((Number) JsonPath.read(replayA, "$.replayedCount")).intValue()).isEqualTo(1);
        assertThat(receivedWebhooks).hasSize(1);
        assertThat((String) JsonPath.read(receivedWebhooks.get(0).body(), "$.orderId")).isEqualTo("ord-a");

        String replayRest = replay(null, null);
        assertThat(((Number) JsonPath.read(replayRest, "$.replayedCount")).intValue()).isEqualTo(1);
        assertThat(receivedWebhooks).hasSize(2);
    }

    @Test
    void stats_엔드포인트는_confirm_수신_횟수와_웹훅_발송_횟수를_제공한다() {
        long confirmBefore = readLong(stats(), "$.confirmRequestCount");
        long webhookBefore = readLong(stats(), "$.webhookSentCount");

        confirm("stats-1", 1000, "ord-stats-1");

        long confirmAfter = readLong(stats(), "$.confirmRequestCount");
        long webhookAfter = readLong(stats(), "$.webhookSentCount");

        assertThat(confirmAfter).isEqualTo(confirmBefore + 1);
        assertThat(webhookAfter).isEqualTo(webhookBefore + 1);
    }

    private long readLong(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    private String confirm(String merchantPaymentId, long amount, String orderId) {
        return client.post().uri(baseUrl("/mock-pg/payments/confirm"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(confirmRequestBody(merchantPaymentId, amount, orderId))
                .retrieve()
                .body(String.class);
    }

    private String confirmRequestBody(String merchantPaymentId, long amount, String orderId) {
        return "{\"merchantPaymentId\":\"" + merchantPaymentId + "\",\"amount\":" + amount
                + ",\"orderId\":\"" + orderId + "\"}";
    }

    private String get(String providerPaymentId) {
        return client.get().uri(baseUrl("/mock-pg/payments/" + providerPaymentId))
                .retrieve()
                .body(String.class);
    }

    private void setFailureMode(String mode, Long delayMs, Boolean blockWebhook, Integer webhookDuplicateCount,
            Boolean webhookReverseOrder) {
        StringBuilder body = new StringBuilder("{\"mode\":\"").append(mode).append("\"");
        if (delayMs != null) {
            body.append(",\"delayMs\":").append(delayMs);
        }
        if (blockWebhook != null) {
            body.append(",\"blockWebhook\":").append(blockWebhook);
        }
        if (webhookDuplicateCount != null) {
            body.append(",\"webhookDuplicateCount\":").append(webhookDuplicateCount);
        }
        if (webhookReverseOrder != null) {
            body.append(",\"webhookReverseOrder\":").append(webhookReverseOrder);
        }
        body.append("}");

        client.post().uri(baseUrl("/mock-pg/test/failure-mode"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .toBodilessEntity();
    }

    private String replay(String providerPaymentId, String merchantPaymentId) {
        StringBuilder body = new StringBuilder("{");
        boolean hasField = false;
        if (providerPaymentId != null) {
            body.append("\"providerPaymentId\":\"").append(providerPaymentId).append("\"");
            hasField = true;
        }
        if (merchantPaymentId != null) {
            if (hasField) {
                body.append(",");
            }
            body.append("\"merchantPaymentId\":\"").append(merchantPaymentId).append("\"");
        }
        body.append("}");

        return client.post().uri(baseUrl("/mock-pg/test/webhooks/replay"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(String.class);
    }

    private String stats() {
        return client.get().uri(baseUrl("/mock-pg/test/stats"))
                .retrieve()
                .body(String.class);
    }

    private String baseUrl(String path) {
        return "http://localhost:" + port + path;
    }

    private void verifySignature(ReceivedWebhook webhook) {
        String expected = hmacHex(DEFAULT_SECRET, webhook.timestamp() + "." + webhook.body());
        assertThat(webhook.signature()).isEqualTo(expected);
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
            throw new IllegalStateException(e);
        }
    }

    private record ReceivedWebhook(String body, String signature, String timestamp) {
    }
}
