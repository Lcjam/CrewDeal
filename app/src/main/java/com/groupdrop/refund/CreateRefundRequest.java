package com.groupdrop.refund;

/**
 * REF-02는 전액 환불만 지원하므로(4.2의 부분 환불 제외) 금액 필드가 없다.
 * 환불 금액은 서버가 결제 성공 금액으로 확정한다 (12.2).
 */
public record CreateRefundRequest(String reason) { }
