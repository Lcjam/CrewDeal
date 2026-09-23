package com.groupdrop.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 시연 콘솔 디렉토리 URL 보정 (프론트엔드 계획 §5.1).
 *
 * <p>프로젝트에 지금까지 {@link WebMvcConfigurer}가 없었고, welcome page 처리는 루트 {@code /}만
 * 커버한다. 그래서 {@code /console}·{@code /console/}로 들어오면 매핑되는 정적 리소스가 없어 404 →
 * {@code /error}로 흘러 비로그인 상태에서 401 JSON이 뜬다. 파일명을 명시한 {@code /console/index.html}로
 * 리다이렉트해 이 문제를 없앤다. 이 컨트롤러 자체는 {@code /console/**}로 이미 permitAll이므로
 * (see {@link SecurityConfig}) 별도 보안 설정이 필요 없다.
 */
@Configuration
public class ConsoleWebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/console", "/console/index.html");
        registry.addRedirectViewController("/console/", "/console/index.html");
    }
}
