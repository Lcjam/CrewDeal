package com.groupdrop.ledger;

/**
 * 원장 계정 코드 (LED-02·03·04). 값은 V4 마이그레이션의 {@code ledger_accounts.code}와 일치해야 한다.
 */
public enum LedgerAccount {

    /** PG 미수금 — 결제로 PG에 쌓인 채권. */
    PG_RECEIVABLE,
    /** 공급사 지급 예정금. */
    SUPPLIER_PAYABLE,
    /** 인플루언서 지급 예정금 (커미션). */
    INFLUENCER_PAYABLE,
    /** PG 수수료 예정금. */
    PG_FEE_PAYABLE,
    /** 플랫폼 수익 — 주문 단위 잔여액 (ADR-008). */
    PLATFORM_REVENUE,
    /** 지급 완료 (가상 현금) — 5주차 정산 지급·회수에서 사용 (LED-04). */
    PAYOUT_CASH
}
