package com.groupdrop.mockpg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 4주차 범위: 환불 API(providerPaymentId 기준 멱등, 동시 요청 포함 실행 최대 1회)와
 * 환불 전용 장애 모드(refundMode=SUCCEED_BUT_TIMEOUT)를 실제 서버(RANDOM_PORT)로 검증한다(14.5).
 * 웹훅 발송 대상이 없어도(로컬 웹훅 스텁 미기동) confirm 자체는 실패하지 않는다(WebhookSender가 발송
 * 실패를 삼킨다) — 이 테스트는 웹훅 수신 여부를 검증하지 않으므로 스텁 서버를 별도로 띄우지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefundApiTest {

    @LocalServerPort
    private int port;

    private final RestClient client = RestClient.create();

    @BeforeEach
    void resetFailureMode() {
        setFailureMode("NORMAL", null, "NORMAL");
    }

    @Test
    void 정상_환불은_200과_REFUNDED_상태를_반환한다() {
        String providerPaymentId = confirmAndGetProviderPaymentId("refund-normal-1", 19900, "ord-refund-1");

        String refundJson = refund(providerPaymentId, "mrf-1", null);
        assertThat((String) JsonPath.read(refundJson, "$.status")).isEqualTo("REFUNDED");
        assertThat((String) JsonPath.read(refundJson, "$.providerPaymentId")).isEqualTo(providerPaymentId);
        assertThat((String) JsonPath.read(refundJson, "$.merchantRefundId")).isEqualTo("mrf-1");
        assertThat(((Number) JsonPath.read(refundJson, "$.amount")).longValue()).isEqualTo(19900L);
        assertThat((String) JsonPath.read(refundJson, "$.providerRefundId")).isNotBlank();
        assertThat((String) JsonPath.read(refundJson, "$.refundedAt")).isNotBlank();
    }

    @Test
    void 본문_없이_환불해도_전액_환불로_성공한다() {
        String providerPaymentId = confirmAndGetProviderPaymentId("refund-nobody-1", 5000, "ord-refund-nobody-1");

        String refundJson = client.post().uri(baseUrl("/mock-pg/payments/" + providerPaymentId + "/refund"))
                .retrieve()
                .body(String.class);
        assertThat((String) JsonPath.read(refundJson, "$.status")).isEqualTo("REFUNDED");
        assertThat(((Number) JsonPath.read(refundJson, "$.amount")).longValue()).isEqualTo(5000L);
    }

    @Test
    void 같은_providerPaymentId_재호출은_같은_providerRefundId를_반환하고_실행_카운트는_증가하지_않는다() {
        String providerPaymentId = confirmAndGetProviderPaymentId("refund-idem-1", 12000, "ord-refund-idem-1");

        String first = refund(providerPaymentId, "mrf-idem", null);
        String firstProviderRefundId = JsonPath.read(first, "$.providerRefundId");
        long executedBefore = readLong(stats(), "$.refundExecutedCount");

        String second = refund(providerPaymentId, "mrf-idem", null);
        String secondProviderRefundId = JsonPath.read(second, "$.providerRefundId");

        long executedAfter = readLong(stats(), "$.refundExecutedCount");

        assertThat(secondProviderRefundId).isEqualTo(firstProviderRefundId);
        assertThat(executedAfter).isEqualTo(executedBefore);
    }

    @Test
    void 동시_요청_10건이_들어와도_환불_실행은_1회다() throws InterruptedException {
        String providerPaymentId = confirmAndGetProviderPaymentId("refund-concurrent-1", 8800,
                "ord-refund-concurrent-1");
        long executedBefore = readLong(stats(), "$.refundExecutedCount");

        int threadCount = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        List<String> providerRefundIds = new ArrayList<>();
        Object lock = new Object();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    String json = refund(providerPaymentId, "mrf-concurrent", null);
                    String id = JsonPath.read(json, "$.providerRefundId");
                    synchronized (lock) {
                        providerRefundIds.add(id);
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(failures.get()).isZero();
        assertThat(providerRefundIds).hasSize(threadCount);
        Set<String> distinctIds = providerRefundIds.stream().collect(Collectors.toSet());
        assertThat(distinctIds).hasSize(1);

        long executedAfter = readLong(stats(), "$.refundExecutedCount");
        assertThat(executedAfter).isEqualTo(executedBefore + 1);
    }

    @Test
    void refundMode가_SUCCEED_BUT_TIMEOUT이면_첫_호출은_504이고_재호출은_200_REFUNDED다() {
        String providerPaymentId = confirmAndGetProviderPaymentId("refund-timeout-1", 15000,
                "ord-refund-timeout-1");
        setFailureMode("NORMAL", 50L, "SUCCEED_BUT_TIMEOUT");

        try {
            client.post().uri(baseUrl("/mock-pg/payments/" + providerPaymentId + "/refund"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(refundRequestBody("mrf-timeout", null))
                    .retrieve()
                    .toBodilessEntity();
            fail("refundMode=SUCCEED_BUT_TIMEOUT은 첫 호출에 정상 응답을 주면 안 된다");
        } catch (RestClientResponseException expected) {
            assertThat(expected.getStatusCode().value()).isEqualTo(504);
        }

        // 재호출은 장애 모드와 무관하게 즉시 저장된 결과를 반환해야 한다 (앱 재시도 해소 경로 전제).
        String repeatJson = refund(providerPaymentId, "mrf-timeout", null);
        assertThat((String) JsonPath.read(repeatJson, "$.status")).isEqualTo("REFUNDED");
        assertThat((String) JsonPath.read(repeatJson, "$.providerPaymentId")).isEqualTo(providerPaymentId);
    }

    @Test
    void 없는_providerPaymentId_환불은_404다() {
        try {
            client.post().uri(baseUrl("/mock-pg/payments/pg_nonexistent/refund"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(refundRequestBody(null, null))
                    .retrieve()
                    .toBodilessEntity();
            fail("존재하지 않는 providerPaymentId 환불은 404여야 한다");
        } catch (RestClientResponseException expected) {
            assertThat(expected.getStatusCode().value()).isEqualTo(404);
        }
    }

    @Test
    void FAILED_결제_환불은_409다() {
        setFailureMode("DECLINE", null, "NORMAL");
        String confirmJson = confirm("refund-declined-1", 3000, "ord-refund-declined-1");
        String providerPaymentId = JsonPath.read(confirmJson, "$.providerPaymentId");
        assertThat((String) JsonPath.read(confirmJson, "$.status")).isEqualTo("FAILED");

        setFailureMode("NORMAL", null, "NORMAL");
        try {
            client.post().uri(baseUrl("/mock-pg/payments/" + providerPaymentId + "/refund"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(refundRequestBody(null, null))
                    .retrieve()
                    .toBodilessEntity();
            fail("FAILED 결제 환불은 409여야 한다");
        } catch (RestClientResponseException expected) {
            assertThat(expected.getStatusCode().value()).isEqualTo(409);
        }
    }

    @Test
    void 결제_금액과_다른_amount로_환불하면_409다() {
        String providerPaymentId = confirmAndGetProviderPaymentId("refund-amount-mismatch-1", 10000,
                "ord-refund-amount-mismatch-1");
        try {
            client.post().uri(baseUrl("/mock-pg/payments/" + providerPaymentId + "/refund"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(refundRequestBody(null, 9999L))
                    .retrieve()
                    .toBodilessEntity();
            fail("결제 금액과 다른 amount는 409여야 한다");
        } catch (RestClientResponseException expected) {
            assertThat(expected.getStatusCode().value()).isEqualTo(409);
        }
    }

    private String confirmAndGetProviderPaymentId(String merchantPaymentId, long amount, String orderId) {
        String confirmJson = confirm(merchantPaymentId, amount, orderId);
        return JsonPath.read(confirmJson, "$.providerPaymentId");
    }

    private String confirm(String merchantPaymentId, long amount, String orderId) {
        return client.post().uri(baseUrl("/mock-pg/payments/confirm"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"merchantPaymentId\":\"" + merchantPaymentId + "\",\"amount\":" + amount
                        + ",\"orderId\":\"" + orderId + "\"}")
                .retrieve()
                .body(String.class);
    }

    private String refund(String providerPaymentId, String merchantRefundId, Long amount) {
        return client.post().uri(baseUrl("/mock-pg/payments/" + providerPaymentId + "/refund"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(refundRequestBody(merchantRefundId, amount))
                .retrieve()
                .body(String.class);
    }

    private String refundRequestBody(String merchantRefundId, Long amount) {
        StringBuilder body = new StringBuilder("{");
        boolean hasField = false;
        if (merchantRefundId != null) {
            body.append("\"merchantRefundId\":\"").append(merchantRefundId).append("\"");
            hasField = true;
        }
        if (amount != null) {
            if (hasField) {
                body.append(",");
            }
            body.append("\"amount\":").append(amount);
        }
        body.append("}");
        return body.toString();
    }

    private void setFailureMode(String mode, Long delayMs, String refundMode) {
        StringBuilder body = new StringBuilder("{\"mode\":\"").append(mode).append("\"");
        if (delayMs != null) {
            body.append(",\"delayMs\":").append(delayMs);
        }
        if (refundMode != null) {
            body.append(",\"refundMode\":\"").append(refundMode).append("\"");
        }
        body.append("}");

        client.post().uri(baseUrl("/mock-pg/test/failure-mode"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .toBodilessEntity();
    }

    private String stats() {
        return client.get().uri(baseUrl("/mock-pg/test/stats"))
                .retrieve()
                .body(String.class);
    }

    private long readLong(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    private String baseUrl(String path) {
        return "http://localhost:" + port + path;
    }
}
