package com.groupdrop.common;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 도메인 로직은 시각을 항상 이 Clock 빈에서 얻는다.
 * LocalDateTime.now() 등 직접 호출 금지 — 시각 제어 테스트의 전제 (기획서 22장).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(@Value("${groupdrop.time-zone}") String timeZone) {
        return Clock.system(ZoneId.of(timeZone));
    }
}
