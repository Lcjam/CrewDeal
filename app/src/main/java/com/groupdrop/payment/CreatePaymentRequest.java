package com.groupdrop.payment;

/** 클라이언트가 보낸 금액은 신뢰하지 않고 서버 계산 주문 금액과의 일치 검증에만 쓴다 (PAY-01). */
public record CreatePaymentRequest(Long amount) { }
