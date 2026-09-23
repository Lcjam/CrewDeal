package com.groupdrop.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupdrop.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * `server.servlet.session.cookie.same-site: strict` (프론트엔드 계획 §2·5.1)는 내장 서버 설정이라
 * MockMvc(목 서블릿, {@code MockHttpSession})에서는 {@code Set-Cookie} 헤더 자체가 나가지 않는다.
 * 실제 내장 서버로 띄워(RANDOM_PORT) HTTP 레벨에서만 검증할 수 있어 별도 테스트로 분리한다
 * ({@code FailureModeAndWebhookApiTest}의 RestClient + RANDOM_PORT 방식을 따른다).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsoleSessionCookieSameSiteTest {

    @LocalServerPort
    private int port;

    private final RestClient client = RestClient.create();

    @Test
    void 로그인_응답의_세션_쿠키는_SameSite_Strict다() {
        ResponseEntity<String> response = client.post()
                .uri("http://localhost:" + port + "/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"email\":\"admin@groupdrop.test\",\"password\":\"groupdrop123!\"}")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> setCookieHeaders = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookieHeaders).isNotNull().isNotEmpty();

        String sessionCookie = setCookieHeaders.stream()
                .filter(header -> header.startsWith("JSESSIONID="))
                .findFirst()
                .orElse(null);
        assertThat(sessionCookie)
                .as("Set-Cookie 헤더 중 JSESSIONID: %s", setCookieHeaders)
                .isNotNull();
        assertThat(sessionCookie).containsIgnoringCase("SameSite=Strict");
    }
}
