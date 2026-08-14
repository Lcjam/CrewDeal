package com.groupdrop.common;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 도메인 로직은 시각을 항상 이 Clock 빈에서 얻는다.
 * LocalDateTime.now() 등 직접 호출 금지 — 시각 제어 테스트의 전제 (기획서 22장).
 */
@Configuration
@EnableConfigurationProperties(GroupdropProperties.class)
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock(GroupdropProperties properties) {
        return Clock.system(properties.timeZone());
    }
}
