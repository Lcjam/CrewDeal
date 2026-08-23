package com.groupdrop.payment;

import java.time.Instant;

/**
 * 가상 PG 호출 경계. 이 인터페이스의 존재 이유는 테스트 대역이 아니라 트랜잭션 경계다 —
 * 호출은 반드시 DB 트랜잭션 밖에서 일어나야 한다 (ADR-003).
 */
public interface PgClient {

    ConfirmResult confirm(ConfirmCommand command);

    /**
     * REF-02 전액 환불. 가상 PG는 providerPaymentId 기준 멱등이므로(14.5), 응답이 유실된 환불의
     * 재시도는 같은 호출을 그대로 반복하는 것으로 해소한다 — 재호출이 두 번째 환불을 만들지 않는다.
     */
    RefundResult refund(RefundCommand command);

    record ConfirmCommand(String merchantPaymentId, Long orderId, long amount) { }

    record RefundCommand(String providerPaymentId, String merchantRefundId, long amount) { }

    /**
     * TIMEOUT은 "실패"가 아니라 "모름"이다. 전송 계층 오류·5xx는 PG에 결제가 남아 있을 수 있으므로
     * 전부 TIMEOUT으로 접는다 — 실패로 단정하면 PAY-03이 막으려는 오판이 그대로 발생한다.
     */
    record ConfirmResult(Outcome outcome, String providerPaymentId, Instant approvedAt,
                         String failureCode, String detail) {

        public static ConfirmResult succeeded(String providerPaymentId, Instant approvedAt) {
            return new ConfirmResult(Outcome.SUCCEEDED, providerPaymentId, approvedAt, null, null);
        }

        public static ConfirmResult failed(String failureCode, String detail) {
            return new ConfirmResult(Outcome.FAILED, null, null, failureCode, detail);
        }

        public static ConfirmResult timeout(String detail) {
            return new ConfirmResult(Outcome.TIMEOUT, null, null, null, detail);
        }
    }

    /**
     * 환불도 결제와 같은 3분기다. TIMEOUT은 "환불이 안 됐다"가 아니라 "됐는지 모른다"이므로
     * 환불 행은 {@code REQUESTED}(결과 불명 겸용)로 남고 워커가 재시도한다 (PAY-03, 10.3).
     */
    record RefundResult(Outcome outcome, String providerRefundId, Instant refundedAt,
                        String failureCode, String detail) {

        public static RefundResult succeeded(String providerRefundId, Instant refundedAt) {
            return new RefundResult(Outcome.SUCCEEDED, providerRefundId, refundedAt, null, null);
        }

        public static RefundResult failed(String failureCode, String detail) {
            return new RefundResult(Outcome.FAILED, null, null, failureCode, detail);
        }

        public static RefundResult timeout(String detail) {
            return new RefundResult(Outcome.TIMEOUT, null, null, null, detail);
        }
    }

    enum Outcome {
        SUCCEEDED,
        FAILED,
        TIMEOUT
    }
}
