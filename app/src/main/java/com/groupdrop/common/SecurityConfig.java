package com.groupdrop.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * 세션 기반 로그인 + 시드 계정 (기획서 16.3). JSON 로그인(AuthController)이 폼 로그인을 대체한다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                     SecurityContextRepository securityContextRepository) throws Exception {
        http
                // 브라우저 폼이 없는 API 데모 범위 — 쿠키 세션이지만 CSRF 토큰을 다룰 폼/JS 클라이언트가 없어 비활성화
                .csrf(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .authorizeHttpRequests(auth -> auth
                        // 웹훅은 세션이 아니라 HMAC 서명으로 인증한다 (PAY-04, 16.3)
                        .requestMatchers("/api/webhooks/payments").permitAll()
                        .requestMatchers("/api/auth/login", "/actuator/health", "/actuator/info",
                                "/actuator/prometheus", "/actuator/metrics/**").permitAll()
                .anyRequest().authenticated())
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint((request, response, authException) ->
                                writeProblem(response, request, HttpStatus.UNAUTHORIZED,
                                        "AUTHENTICATION_REQUIRED", "인증이 필요합니다."))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeProblem(response, request, HttpStatus.FORBIDDEN,
                                        "ACCESS_DENIED", "이 작업을 수행할 권한이 없습니다.")));
        return http.build();
    }

    static void writeProblem(HttpServletResponse response, HttpServletRequest request,
                             HttpStatus status, String code, String detail) throws IOException {
        ProblemDetail problem = ProblemDetails.of(status, code, detail, request);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"%s","title":"%s","status":%d,"detail":"%s","instance":"%s","code":"%s"}
                """.formatted(
                json(problem.getType().toString()), json(problem.getTitle()), problem.getStatus(),
                json(problem.getDetail()), json(problem.getInstance().toString()), json(code)));
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
