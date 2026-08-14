package com.groupdrop.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

class CommonInfrastructureTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ClockConfig.class);

    @Test
    void ApiException은_RFC9457_ProblemDetails로_반환한다() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();

        mockMvc.perform(get("/test/problem").header(RequestIdFilter.HEADER_NAME, "request-123"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("입력값이 잘못되었습니다."))
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.instance").value("/test/problem"))
                .andExpect(jsonPath("$.requestId").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string(RequestIdFilter.HEADER_NAME, "request-123"));
    }

    @Test
    void 검증_실패와_잘못된_JSON도_RFC9457_ProblemDetails로_반환한다() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/test/validation")
                        .contentType("application/json").content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/test/validation")
                        .contentType("application/json").content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    void requestId는_수용_또는_생성되고_MDC는_요청_후_정리된다() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest suppliedRequest = new MockHttpServletRequest("GET", "/api/test");
        suppliedRequest.addHeader(RequestIdFilter.HEADER_NAME, "trace-from-client");
        MockHttpServletResponse suppliedResponse = new MockHttpServletResponse();

        filter.doFilter(suppliedRequest, suppliedResponse, (request, response) ->
                assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo("trace-from-client"));

        assertThat(suppliedResponse.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("trace-from-client");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();

        MockHttpServletResponse generatedResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/test"), generatedResponse, (request, response) -> { });
        assertThat(generatedResponse.getHeader(RequestIdFilter.HEADER_NAME)).isNotBlank();
    }

    @Test
    void 기본_시간_정책과_Clock_zone이_기획서_기본값을_사용한다() {
        contextRunner.run(context -> {
            GroupdropProperties properties = context.getBean(GroupdropProperties.class);
            Clock clock = context.getBean(Clock.class);

            assertThat(properties.timeZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
            assertThat(properties.reservationDuration()).isEqualTo(Duration.ofMinutes(10));
            assertThat(properties.reconciliationInterval()).isEqualTo(Duration.ofMinutes(30));
            assertThat(properties.settlementGracePeriod()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.inboxPollingInterval()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.outboxPollingInterval()).isEqualTo(Duration.ofSeconds(5));
            assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
        });
    }

    @Test
    void 고정_Clock을_주입하면_기본_Clock_생성이_양보된다() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneId.of("UTC"));

        contextRunner.withBean(Clock.class, () -> fixedClock).run(context ->
                assertThat(context.getBean(Clock.class)).isSameAs(fixedClock));
    }

    @Test
    void 시간_정책은_환경변수와_동일한_relaxed_binding으로_재설정할_수_있다() {
        contextRunner.withPropertyValues("groupdrop.reservation-duration=15s")
                .run(context -> assertThat(context.getBean(GroupdropProperties.class).reservationDuration())
                        .isEqualTo(Duration.ofSeconds(15)));
    }

    @Test
    void SpringSecurity_오류도_ProblemDetails_형식이다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        SecurityConfig.writeProblem(response, request, HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"type\":\"about:blank\"")
                .contains("\"status\":401")
                .contains("\"code\":\"AUTHENTICATION_REQUIRED\"");
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/test/problem")
        void problem() {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "입력값이 잘못되었습니다.");
        }

        @PostMapping("/test/validation")
        void validation(@Valid @RequestBody ValidationRequest request) {
        }
    }

    record ValidationRequest(@NotBlank String name) {
    }
}
