package com.groupdrop.common;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 시스템 전역 시간 정책. 향후 예약·대사·정산·메시지 워커는 이 값을 주입받아 사용한다.
 * 환경변수는 Spring Boot의 relaxed binding에 따라 GROUPDROP_RESERVATION_DURATION 등으로 덮어쓸 수 있다.
 */
@ConfigurationProperties("groupdrop")
public record GroupdropProperties(
        @DefaultValue("Asia/Seoul") ZoneId timeZone,
        @DefaultValue("10m") Duration reservationDuration,
        @DefaultValue("30m") Duration reconciliationInterval,
        @DefaultValue("7d") Duration settlementGracePeriod,
        @DefaultValue("5s") Duration inboxPollingInterval,
        @DefaultValue("5s") Duration outboxPollingInterval,
        @DefaultValue("1s") Duration campaignLifecyclePollingInterval,
        @DefaultValue("5s") Duration reservationExpiryPollingInterval) {
}
