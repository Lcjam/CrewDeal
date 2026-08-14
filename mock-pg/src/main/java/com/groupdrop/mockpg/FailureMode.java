package com.groupdrop.mockpg;

/** 가상 PG confirm 처리 시 주입 가능한 장애 모드 (기획서 14.5, 18장 3주차 범위). */
public enum FailureMode {
    /** 정상 성공. */
    NORMAL,
    /** 결제 거절 — FAILED로 즉시 확정. */
    DECLINE,
    /** 응답 지연 후 정상 성공. */
    DELAY,
    /** 결제는 내부적으로 SUCCEEDED로 확정되지만 호출자에게 정상 응답을 주지 않는다 (PAY-03 UNKNOWN 시나리오). */
    SUCCEED_BUT_TIMEOUT
}
