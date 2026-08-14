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
        @DefaultValue("5s") Duration reservationExpiryPollingInterval,
        /** 워커가 이벤트를 잡고 있는 동안 재선점을 막는 리스 시간. 워커 크래시 시 이 시간 뒤 회수된다. */
        @DefaultValue("1m") Duration messageLeaseDuration,
        /** 이벤트 처리 실패 후 재시도까지의 대기. */
        @DefaultValue("5s") Duration messageRetryBackoff,
        /**
         * 워커가 한 번에 청구하는 이벤트 수. 작을수록 폴링 왕복이 늘지만 다중 워커가 일감을 나눠 갖는다 —
         * 17.3 2계층 실증에서 한 인스턴스가 배치를 통째로 가져가 경쟁이 관찰되지 않는 것을 막는다.
         */
        @DefaultValue("50") int messageBatchSize,
        /** 멱등 키 수명 (PAY-02: 재고 예약 시간보다 충분히 긴 24시간). */
        @DefaultValue("24h") Duration idempotencyKeyTtl,
        @DefaultValue("1m") Duration orphanPaymentSweepInterval,
        @DefaultValue Pg pg,
        @DefaultValue Webhook webhook) {

    public record Pg(
            @DefaultValue("http://localhost:18080") String baseUrl,
            /** PG 호출 타임아웃. 초과는 실패가 아니라 UNKNOWN이다 (PAY-03). */
            @DefaultValue("3s") Duration requestTimeout,
            /**
             * 고아 스윕 임계 = requestTimeout × 이 배수 (PAY-03의 "PG 타임아웃의 2배").
             * 요청 스레드가 결과를 기록하지 못하고 죽은 경우만 잡도록 충분히 커야 한다.
             */
            @DefaultValue("2") int orphanThresholdMultiplier) {
    }

    public record Webhook(
            @DefaultValue("groupdrop-mock-pg-secret") String secret,
            /** 16.3: 수신 시각과 5분 이상 차이 나는 타임스탬프는 거부한다. */
            @DefaultValue("5m") Duration timestampTolerance) {
    }

    public Duration orphanProcessingThreshold() {
        return pg().requestTimeout().multipliedBy(pg().orphanThresholdMultiplier());
    }
}
