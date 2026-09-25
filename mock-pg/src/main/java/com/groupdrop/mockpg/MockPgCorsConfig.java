package com.groupdrop.mockpg;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 콘솔의 장애 주입 패널(프런트 계획 7.2, 9)이 브라우저에서 {@code /mock-pg/test/**}만 직접 호출할 수
 * 있도록 CORS를 연다. mock-pg는 Spring Security를 쓰지 않는 로컬 데모·테스트 서버라
 * {@link WebMvcConfigurer#addCorsMappings}만으로 충분하다.
 *
 * <p>범위를 {@code /mock-pg/test/**}로만 좁힌 이유: 이 경로 묶음(장애 모드 설정·웹훅 재발사·통계 조회
 * — {@link TestControlController})은 테스트 제어용이라 브라우저에서 직접 건드려도 안전하다. 반면
 * {@code /mock-pg/payments/confirm}, {@code /mock-pg/reconciliation/**}, actuator는 앱이
 * 서버-대-서버로만 호출하는 경로라 브라우저에 열 이유가 없다.
 *
 * <p>허용 헤더를 {@code Content-Type} 하나로 제한한 이유: 패널의 fetch는
 * {@code {credentials:'omit', headers:{'Content-Type':'application/json'}}}만 보내면 된다.
 * app의 {@code api.js}가 붙이는 {@code X-Request-Id}는 허용 목록에 없으므로, mock-pg 호출을 실수로
 * {@code api.js}로 보내면 브라우저가 프리플라이트 단계에서 차단한다. 서버는 프리플라이트에 200과
 * {@code Access-Control-Allow-Headers: content-type}만 돌려주고, 본 요청을 막는 쪽은 브라우저다.
 *
 * <p>자격증명(쿠키·인증 헤더)은 허용하지 않는다 — {@link TestControlController}에는 인증이 전혀 없고,
 * mock-pg 자체가 배포 대상이 아닌 로컬 데모·테스트 서버이기 때문에 자격증명을 주고받을 이유가 없다.
 *
 * <p>프리플라이트 캐시 수명({@code maxAge})을 따로 지정하지 않았으므로 Spring 기본값인 1800초(30분)가
 * 적용된다 — 이 설정을 바꾼 뒤에도 브라우저가 최대 30분까지 이전 프리플라이트 응답을 캐시해 새 규칙이
 * 곧바로 반영되지 않을 수 있다.
 */
@Configuration
public class MockPgCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/mock-pg/test/**")
                .allowedOriginPatterns("http://localhost:[*]", "http://127.0.0.1:[*]")
                .allowedMethods("GET", "POST")
                .allowedHeaders("Content-Type");
    }
}
