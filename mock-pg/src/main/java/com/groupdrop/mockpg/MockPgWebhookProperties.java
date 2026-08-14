package com.groupdrop.mockpg;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 웹훅 대상 URL·서명 시크릿 외부화 (mockpg.webhook.*).
 * 기본값은 로컬 개발/테스트 전용 플레이스홀더이며 실제 운영 자격증명이 아니다 — app 쪽과 동일한 기본값을
 * 공유해야 로컬에서 별도 설정 없이 서명 검증이 맞아떨어진다. 운영 배포 시에는 프로퍼티로 반드시 재정의한다.
 */
@ConfigurationProperties(prefix = "mockpg.webhook")
public class MockPgWebhookProperties {

    private static final String LOCAL_DEV_DEFAULT_SECRET = "groupdrop-mock-pg-secret";

    private String targetUrl = "http://localhost:8080/api/webhooks/payments";
    private String secret = LOCAL_DEV_DEFAULT_SECRET;

    public String getTargetUrl() {
        return targetUrl;
    }

    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }
}
