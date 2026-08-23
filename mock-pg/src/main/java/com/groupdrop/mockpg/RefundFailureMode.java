package com.groupdrop.mockpg;

/** 가상 PG 환불 처리 시 주입 가능한 장애 모드 (기획서 14.5, 18장 4주차 범위). 결제 모드({@link FailureMode})와
 * 독립적으로 설정한다 — "결제는 정상 성공, 그 결제의 환불만 응답 유실"을 재현해야 하기 때문이다. */
public enum RefundFailureMode {
    /** 정상 성공. */
    NORMAL,
    /** 환불은 내부적으로 REFUNDED로 확정되지만 호출자에게 정상 응답을 주지 않는다 (PAY-03과 같은 UNKNOWN 패턴). */
    SUCCEED_BUT_TIMEOUT
}
