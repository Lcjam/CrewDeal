package com.groupdrop.mockpg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 5주차 범위: 대사용 거래 목록 조회와 S7의 임의 거래 주입 (14.5).
 *
 * <p>주입 엔드포인트가 confirm 경로를 타지 않는 것이 요점이다 — S7의 목적이 "내부 기록과 다른 PG 거래"를
 * 만드는 것이므로, 멱등·웹훅·장애 모드가 개입하면 원하는 불일치를 만들 수 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReconciliationApiTest {

    @LocalServerPort
    private int port;

    private final RestClient client = RestClient.create();

    @Test
    void 승인된_결제는_대사_목록에_거래로_나타난다() {
        String merchantPaymentId = "recon-" + UUID.randomUUID();
        String providerPaymentId = JsonPath.read(confirm(merchantPaymentId, 19_900L, "ord-recon-1"),
                "$.providerPaymentId");

        Map<String, Object> transaction = findTransaction(providerPaymentId);

        assertThat(transaction.get("merchantPaymentId")).isEqualTo(merchantPaymentId);
        assertThat(transaction.get("orderId")).isEqualTo("ord-recon-1");
        assertThat(((Number) transaction.get("amount")).longValue()).isEqualTo(19_900L);
        assertThat(transaction.get("status")).isEqualTo("SUCCEEDED");
        assertThat((String) transaction.get("processedAt")).isNotBlank();
        assertThat(((Number) transaction.get("refundedAmount")).longValue()).isZero();
    }

    @Test
    void 환불된_결제는_같은_거래에_환불_금액이_붙어_나온다() {
        String merchantPaymentId = "recon-refund-" + UUID.randomUUID();
        String providerPaymentId = JsonPath.read(confirm(merchantPaymentId, 12_000L, "ord-recon-2"),
                "$.providerPaymentId");
        client.post().uri(baseUrl("/mock-pg/payments/" + providerPaymentId + "/refund"))
                .retrieve().body(String.class);

        Map<String, Object> transaction = findTransaction(providerPaymentId);

        // 결제와 환불을 따로 주면 대사 쪽에서 다시 조립해야 하고, 그 규칙이 양쪽에 이중으로 존재하게 된다.
        assertThat(((Number) transaction.get("refundedAmount")).longValue()).isEqualTo(12_000L);
        assertThat((String) transaction.get("providerRefundId")).isNotBlank();
        assertThat((String) transaction.get("refundedAt")).isNotBlank();
    }

    @Test
    void S7_임의_거래를_주입하면_내부에_없는_거래가_목록에_나타난다() {
        String orderId = String.valueOf(System.nanoTime());
        String injected = client.post().uri(baseUrl("/mock-pg/test/transactions"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"orderId\":" + orderId + ",\"amount\":31337,\"status\":\"SUCCEEDED\"}")
                .retrieve().body(String.class);

        String providerPaymentId = JsonPath.read(injected, "$.providerPaymentId");
        Map<String, Object> transaction = findTransaction(providerPaymentId);

        assertThat(((Number) transaction.get("amount")).longValue()).isEqualTo(31_337L);
        assertThat(transaction.get("orderId")).isEqualTo(orderId);
        assertThat(transaction.get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    void 주입은_기존_거래를_덮어써_금액_불일치를_만들_수_있다() {
        String merchantPaymentId = "recon-overwrite-" + UUID.randomUUID();
        String providerPaymentId = JsonPath.read(confirm(merchantPaymentId, 19_900L, "ord-recon-3"),
                "$.providerPaymentId");

        client.post().uri(baseUrl("/mock-pg/test/transactions"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"providerPaymentId\":\"" + providerPaymentId + "\",\"merchantPaymentId\":\""
                        + merchantPaymentId + "\",\"orderId\":3,\"amount\":99999,\"status\":\"SUCCEEDED\","
                        + "\"refundedAmount\":5000}")
                .retrieve().body(String.class);

        Map<String, Object> transaction = findTransaction(providerPaymentId);
        assertThat(((Number) transaction.get("amount")).longValue()).isEqualTo(99_999L);
        assertThat(((Number) transaction.get("refundedAmount")).longValue()).isEqualTo(5_000L);
    }

    @Test
    void createdBefore_이후에_처리된_거래는_목록에서_빠진다() {
        String merchantPaymentId = "recon-cutoff-" + UUID.randomUUID();
        String providerPaymentId = JsonPath.read(confirm(merchantPaymentId, 7_000L, "ord-recon-4"),
                "$.providerPaymentId");

        // 최소 경과 시간(REC-01)을 PG 쪽에서 잘라 주는 파라미터다.
        List<Map<String, Object>> transactions = transactions(Instant.now().minusSeconds(600));

        assertThat(transactions).noneMatch(t -> providerPaymentId.equals(t.get("providerPaymentId")));
    }

    @Test
    void 잘못된_주입_요청은_400으로_거절한다() {
        assertThatThrownBy(() -> client.post().uri(baseUrl("/mock-pg/test/transactions"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\":1000}")
                .retrieve().body(String.class))
                .isInstanceOf(RestClientResponseException.class)
                .satisfies(exception -> assertThat(((RestClientResponseException) exception)
                        .getStatusCode().value()).isEqualTo(400));

        assertThatThrownBy(() -> client.post().uri(baseUrl("/mock-pg/test/transactions"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"orderId\":1,\"amount\":1000,\"status\":\"WEIRD\"}")
                .retrieve().body(String.class))
                .isInstanceOf(RestClientResponseException.class);
    }

    // ---- 헬퍼 ----

    private Map<String, Object> findTransaction(String providerPaymentId) {
        return transactions(null).stream()
                .filter(transaction -> providerPaymentId.equals(transaction.get("providerPaymentId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("대사 목록에 거래가 없습니다: " + providerPaymentId));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> transactions(Instant createdBefore) {
        String uri = baseUrl("/mock-pg/reconciliation/transactions")
                + (createdBefore == null ? "" : "?createdBefore=" + createdBefore);
        String json = client.get().uri(uri).retrieve().body(String.class);
        return (List<Map<String, Object>>) JsonPath.read(json, "$.transactions");
    }

    private String confirm(String merchantPaymentId, long amount, String orderId) {
        return client.post().uri(baseUrl("/mock-pg/payments/confirm"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"merchantPaymentId\":\"" + merchantPaymentId + "\",\"amount\":" + amount
                        + ",\"orderId\":\"" + orderId + "\"}")
                .retrieve()
                .body(String.class);
    }

    private String baseUrl(String path) {
        return "http://localhost:" + port + path;
    }
}
