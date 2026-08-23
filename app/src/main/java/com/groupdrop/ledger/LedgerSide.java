package com.groupdrop.ledger;

/** 분개 방향. 역분개(LED-03)는 원본 분개의 방향을 뒤집은 새 거래다 (ADR-006). */
public enum LedgerSide {

    DEBIT,
    CREDIT;

    public LedgerSide opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
