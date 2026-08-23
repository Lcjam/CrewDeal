package com.groupdrop.settlement;

import com.groupdrop.ledger.LedgerAccount;

/**
 * 정산 수령 주체 (13.3의 PAYEE). MVP는 캠페인당 공급사·인플루언서 각 1명이다 (5장).
 *
 * <p>PG 수수료와 플랫폼 수익은 지급 대상이 아니므로 여기에 없다 — 지급 배치가 생기지 않고,
 * 따라서 정산 후에도 해당 계정 잔액은 남는다 (LED-04).
 */
public enum PayeeType {

    SUPPLIER(LedgerAccount.SUPPLIER_PAYABLE),
    INFLUENCER(LedgerAccount.INFLUENCER_PAYABLE);

    private final LedgerAccount payableAccount;

    PayeeType(LedgerAccount payableAccount) {
        this.payableAccount = payableAccount;
    }

    /** 이 수령 주체의 지급 예정금 계정. 정산 금액의 원천이자 지급·회수 분개의 상대 계정이다. */
    public LedgerAccount payableAccount() {
        return payableAccount;
    }
}
