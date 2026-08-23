package com.groupdrop.settlement;

/**
 * 정산 배치 상태 (10.5). 전이는 전부 조건부 UPDATE로 수행하며, 이 열거형은 값 집합을 코드에 고정하기
 * 위한 것이다 — 허용 전이는 {@link SettlementRepository}의 각 UPDATE가 WHERE 절로 표현한다.
 *
 * <p>{@code PROCESSING} 중 운영자 보류는 허용하지 않는다 (10.5) — hold API는 PENDING·READY에만 동작한다.
 */
public enum SettlementBatchStatus {

    PENDING,
    READY,
    PROCESSING,
    COMPLETED,
    FAILED,
    HELD
}
