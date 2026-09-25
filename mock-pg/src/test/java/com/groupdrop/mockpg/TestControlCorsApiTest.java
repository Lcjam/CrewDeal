package com.groupdrop.mockpg;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 콘솔의 장애 주입 패널(프런트 계획 7.2, 9)이 {@code /mock-pg/test/**}를 브라우저에서 직접 호출할 수
 * 있도록 연 CORS 설정({@link MockPgCorsConfig})을 실제 서버(RANDOM_PORT)로 검증한다.
 *
 * <p>{@code HttpURLConnection} 기반 클라이언트(예: {@code RestClient}의 기본 팩터리)는 {@code Origin}을
 * 제한 헤더로 취급해 조용히 버리므로, {@code Origin}을 그대로 보낼 수 있는 {@link HttpClient}를 쓴다.
 *
 * <p>이 테스트는 웹훅을 보내지 않으므로 {@code FailureModeAndWebhookApiTest}와 달리 웹훅 수신 스텁
 * 서버나 {@code mockpg.webhook.target-url} 동적 프로퍼티가 필요 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestControlCorsApiTest {

    @LocalServerPort
    private int port;

    private static final HttpClient client = HttpClient.newHttpClient();

    @AfterAll
    static void closeClient() {
        client.close();
    }

    @Test
    void 허용된_origin의_프리플라이트는_200과_그_origin을_그대로_돌려준다() throws IOException, InterruptedException {
        for (String origin : new String[] {"http://localhost:8080", "http://127.0.0.1:8080", "http://localhost:18080"}) {
            HttpResponse<Void> response = preflight("/mock-pg/test/failure-mode", origin, "POST", "content-type");
            assertThat(response.statusCode()).as("origin=%s", origin).isEqualTo(200);
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
                    .as("origin=%s", origin).hasValue(origin);
        }
    }

    @Test
    void 허용되지_않은_origin의_프리플라이트는_403이고_ACAO_헤더가_없다() throws IOException, InterruptedException {
        HttpResponse<Void> response = preflight("/mock-pg/test/failure-mode", "http://evil.example", "POST", "content-type");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void 실제_패널이_보내는_Content_Type와_X_Request_Id_조합_프리플라이트는_200이지만_ACAH에_X_Request_Id가_없다()
            throws IOException, InterruptedException {
        // app의 api.js(app/src/main/resources/static/console/common/api.js)는 실제로 Content-Type과
        // X-Request-Id를 함께 요청 헤더로 싣는다. 이 조합을 보내면 서버는 403이 아니라 200으로 응답하고
        // Access-Control-Allow-Headers에 허용 목록(Content-Type)만 돌려준다 — X-Request-Id를 실은 본
        // 요청을 막는 건 서버가 아니라, 이 프리플라이트 응답을 읽은 브라우저다. 그래서 여기서는 "ACAH에
        // X-Request-Id가 없다"를 단정해 그 실측 동작을 고정한다.
        HttpResponse<Void> response = preflight("/mock-pg/test/failure-mode", "http://localhost:8080", "POST",
                "content-type,x-request-id");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers"))
                .hasValueSatisfying(value -> assertThat(value).containsIgnoringCase("content-type")
                        .doesNotContainIgnoringCase("x-request-id"));
    }

    @Test
    void 단독_X_Request_Id_헤더만_요청하는_프리플라이트는_403이다() throws IOException, InterruptedException {
        HttpResponse<Void> response = preflight("/mock-pg/test/failure-mode", "http://localhost:8080", "POST", "x-request-id");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void 허용되지_않은_메서드를_요청하는_프리플라이트는_거절된다() throws IOException, InterruptedException {
        HttpResponse<Void> response = preflight("/mock-pg/test/failure-mode", "http://localhost:8080", "DELETE", "content-type");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void 허용된_origin의_실제_요청은_200이고_ACAO는_있지만_ACAC_헤더는_없다() throws IOException, InterruptedException {
        HttpResponse<String> response = getWithOrigin("/mock-pg/test/stats", "http://localhost:8080");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:8080");
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
    }

    @Test
    void 허용되지_않은_origin의_실제_요청은_403이다() throws IOException, InterruptedException {
        HttpResponse<String> response = getWithOrigin("/mock-pg/test/stats", "http://evil.example");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void 경로_밖_confirm의_프리플라이트는_ACAO_헤더가_없다() throws IOException, InterruptedException {
        HttpResponse<Void> response = preflight("/mock-pg/payments/confirm", "http://localhost:8080", "POST", "content-type");
        // CORS 매핑이 없는 경로라 상태 코드는 Spring MVC 기본 OPTIONS 처리라는 구현 세부에 좌우되므로
        // 단정하지 않음. 실측: 200 (Allow 헤더만 붙는 기본 처리) — 핵심은 CORS 응답 헤더가 전혀 없다는 점이다.
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void 경로_밖_reconciliation_transactions의_실제_요청은_ACAO_헤더가_없다() throws IOException, InterruptedException {
        HttpResponse<String> response = getWithOrigin("/mock-pg/reconciliation/transactions", "http://localhost:8080");
        // createdBefore를 생략하면 전체 조회라 예외 없이 결정적으로 200이 온다.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void 흉내낸_서브도메인_origin은_거절된다() throws IOException, InterruptedException {
        HttpResponse<Void> response = preflight("/mock-pg/test/failure-mode", "http://localhost.evil.example:8080", "POST",
                "content-type");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void 스킴이_다른_origin은_거절된다() throws IOException, InterruptedException {
        HttpResponse<Void> response = preflight("/mock-pg/test/failure-mode", "https://localhost:8080", "POST", "content-type");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    /**
     * 포트를 생략한 origin({@code http://localhost})이 {@code [*]} 패턴에 매칭되는지 실측해 고정한다.
     * Spring의 {@code allowedOriginPatterns} 문서상 {@code [*]}는 "포트 생략(기본 포트) 포함 모든 포트"를
     * 뜻하므로 허용될 것으로 기대한다.
     */
    @Test
    void 포트를_생략한_origin의_처리를_실측한다() throws IOException, InterruptedException {
        HttpResponse<Void> response = preflight("/mock-pg/test/failure-mode", "http://localhost", "POST", "content-type");
        // 실측: [*] 패턴은 포트 생략(기본 포트)도 포함해 허용한다 (200 + ACAO: http://localhost).
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost");
    }

    @Test
    void 비허용_origin의_실제_replay_요청은_403이고_보류_웹훅_개수가_변하지_않는다() throws IOException, InterruptedException {
        // replay는 대상 미지정 시 보류분 전체를 발사한다(TestControlController) — CORS가 핸들러 진입
        // 이전에 차단하지 못하면 임의 오리진이 보류 웹훅을 통째로 재발사시킬 수 있다는 뜻이라, 여기서
        // "핸들러가 아예 실행되지 않는다"까지 확인한다.
        long pendingBefore = webhookPendingCount();
        HttpResponse<String> response = postWithOrigin("/mock-pg/test/webhooks/replay", "http://evil.example");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(webhookPendingCount()).isEqualTo(pendingBefore);
    }

    @Test
    void origin이_문자열_null인_실제_replay_요청도_403이다() throws IOException, InterruptedException {
        // 브라우저는 file:// 등 opaque origin일 때 문자열 그대로 "null"을 Origin 헤더로 보낸다.
        // 허용 패턴에 없으므로 이것도 거절되어야 한다.
        HttpResponse<String> response = postWithOrigin("/mock-pg/test/webhooks/replay", "null");
        assertThat(response.statusCode()).isEqualTo(403);
    }

    private long webhookPendingCount() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl("/mock-pg/test/stats"))).GET().build();
        String body = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        return ((Number) JsonPath.read(body, "$.webhookPendingCount")).longValue();
    }

    private HttpResponse<String> postWithOrigin(String path, String origin) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(path)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<Void> preflight(String path, String origin, String requestMethod, String requestHeaders)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(path)))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", requestMethod)
                .header("Access-Control-Request-Headers", requestHeaders)
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private HttpResponse<String> getWithOrigin(String path, String origin) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(path)))
                .GET()
                .header("Origin", origin)
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl(String path) {
        return "http://localhost:" + port + path;
    }
}
