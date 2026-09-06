package com.groupdrop.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupdrop.common.GroupdropProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * ADR-002가 금지한 오판을 막는다: "4xx면 PG가 거절한 것"이라는 추론은 프록시·게이트웨이가 끼면
 * 성립하지 않는다. 408·425·429는 요청이 PG 코어에 도달했는지 알 수 없는 응답이므로 {@code FAILED}가
 * 아니라 {@code TIMEOUT}(UNKNOWN)으로 접어야 하고, 그래야 웹훅·조회·대사가 확정을 이어받는다 (PAY-03).
 */
class HttpPgClientErrorMappingTest {

    private HttpServer server;
    private HttpPgClient client;
    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void startStubPg() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        GroupdropProperties properties = new Binder(new MapConfigurationPropertySource(
                Map.of("groupdrop.pg.base-url", "http://localhost:" + server.getAddress().getPort())))
                .bind("groupdrop", GroupdropProperties.class).get();
        client = new HttpPgClient(properties);
    }

    @AfterEach
    void stopStubPg() {
        server.stop(0);
    }

    @Test
    void 결제_승인의_429_408_425는_실패가_아니라_TIMEOUT으로_접힌다() {
        for (int code : new int[] {408, 425, 429}) {
            status.set(code);
            PgClient.ConfirmResult result = client.confirm(
                    new PgClient.ConfirmCommand("mpay_1", 1L, 19_900L));
            assertThat(result.outcome())
                    .as("HTTP %d는 PG 도달 여부를 알 수 없으므로 TIMEOUT이어야 한다 (ADR-002)", code)
                    .isEqualTo(PgClient.Outcome.TIMEOUT);
        }
    }

    @Test
    void 결제_승인의_일반_4xx는_그대로_FAILED다() {
        status.set(400);
        assertThat(client.confirm(new PgClient.ConfirmCommand("mpay_1", 1L, 19_900L)).outcome())
                .isEqualTo(PgClient.Outcome.FAILED);
    }

    @Test
    void 환불의_429도_TIMEOUT으로_접힌다() {
        status.set(429);
        assertThat(client.refund(new PgClient.RefundCommand("ppay_1", "mref_1", 19_900L)).outcome())
                .isEqualTo(PgClient.Outcome.TIMEOUT);
    }
}
