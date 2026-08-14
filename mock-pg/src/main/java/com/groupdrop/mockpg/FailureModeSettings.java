package com.groupdrop.mockpg;

/**
 * 현재 활성화된 장애 모드와 관련 파라미터. {@code /mock-pg/test/failure-mode} 호출마다 통째로 교체된다
 * (부분 병합이 아니므로 {@code mode}만 넘기면 나머지는 기본값으로 초기화된다 — 모드 리셋 용도).
 */
public record FailureModeSettings(
        FailureMode mode,
        long delayMs,
        boolean blockWebhook,
        int webhookDuplicateCount,
        boolean webhookReverseOrder) {

    public static FailureModeSettings normal() {
        return new FailureModeSettings(FailureMode.NORMAL, 0, false, 1, false);
    }
}
