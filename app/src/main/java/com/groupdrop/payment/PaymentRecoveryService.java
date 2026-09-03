package com.groupdrop.payment;

import com.groupdrop.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PAY-03의 조회 경로 해소. 가상 PG의 confirm은 merchantPaymentId 기준 멱등이므로(14.5),
 * 같은 merchantPaymentId로 다시 호출하는 것이 곧 "조회"다 — 재호출이 새 결제를 만들지 않는다.
 *
 * <p>웹훅이 오지 않는 경우의 복구선이며, 정기 대사(REC-01)는 5주차에 이 서비스를 재사용한다.
 */
@Service
public class PaymentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryService.class);
    private static final String SOURCE = "provider-query";

    private final PaymentRepository payments;
    private final PaymentFinalizer finalizer;
    private final PgClient pgClient;
    private final PaymentMetrics metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PaymentRecoveryService(PaymentRepository payments, PaymentFinalizer finalizer, PgClient pgClient,
                                  PaymentMetrics metrics, TransactionTemplate transactions, Clock clock) {
        this.payments = payments;
        this.finalizer = finalizer;
        this.pgClient = pgClient;
        this.metrics = metrics;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * @return 조회 결과로 확정된 결제 상태. 확정하지 못하면 UNKNOWN을 유지한 채 그대로 돌려준다.
     */
    public PaymentResponse resolveByProviderQuery(Long paymentId) {
        PaymentRepository.PaymentSnapshot payment = payments.findPayment(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "결제를 찾을 수 없습니다."));
        if (!"UNKNOWN".equals(payment.status()) && !"PROCESSING".equals(payment.status())) {
            return PaymentResponse.from(payment);
        }
        String merchantPaymentId = payments.findLatestMerchantPaymentId(paymentId)
                .orElseThrow(() -> new IllegalStateException("PG 호출 기록이 없는 결제는 조회할 수 없습니다: " + paymentId));

        Long attemptId = transactions.execute(status -> payments.insertAttempt(
                paymentId, merchantPaymentId, payment.amount(), Instant.now(clock)));

        // ── 트랜잭션 밖 ── (ADR-003)
        PgClient.ConfirmResult result = metrics.timePgConfirm(() -> pgClient.confirm(
                new PgClient.ConfirmCommand(merchantPaymentId, payment.orderId(), payment.amount())));

        transactions.executeWithoutResult(status -> {
            Instant now = Instant.now(clock);
            PaymentRepository.PaymentSnapshot current = payments.findPayment(paymentId).orElseThrow();
            switch (result.outcome()) {
                case SUCCEEDED -> {
                    payments.finishAttempt(attemptId, "SUCCEEDED", null, now);
                    finalizer.succeed(current, result.providerPaymentId(), result.approvedAt(), SOURCE);
                }
                case FAILED -> {
                    payments.finishAttempt(attemptId, "FAILED", result.detail(), now);
                    finalizer.fail(current, result.providerPaymentId(), result.failureCode(), result.detail(), SOURCE);
                }
                case TIMEOUT -> {
                    payments.finishAttempt(attemptId, "TIMEOUT", result.detail(), now);
                    log.warn("결제 {} 조회도 결과를 확정하지 못했습니다. UNKNOWN을 유지합니다.", paymentId);
                }
            }
        });

        return PaymentResponse.from(payments.findPayment(paymentId).orElseThrow());
    }
}
