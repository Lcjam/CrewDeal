package com.groupdrop.settlement;

/**
 * 배치 종류. 회수 배치는 {@code settlement_items}를 갖지 않고 {@code settlement_adjustments}만 담으며
 * 금액이 음수다 (SET-03, 13.1).
 */
public enum BatchType {

    SETTLEMENT,
    RECOVERY
}
