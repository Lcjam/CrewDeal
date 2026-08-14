package com.groupdrop.payment;

import com.groupdrop.common.GroupdropProperties;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PAY-03 고아 결제 스윕. 요청 스레드의 타임아웃 감지만으로는 부족하다 —
 * 스레드가 결과를 기록하지 못하고 죽은 경우(11.3)를 잡는 것이 이 워커의 존재 이유다.
 *
 * <p>두 종류를 다르게 다룬다. PG 호출 기록이 있는 PROCESSING은 결과를 모르므로 UNKNOWN이고,
 * 호출 기록조차 없는 READY는 PG에 아무것도 없으므로 FAILED로 확정해도 안전하다.
 */
@Component
public class OrphanPaymentSweeper {

    private static final Logger log = LoggerFactory.getLogger(OrphanPaymentSweeper.class);
    private static final int BATCH_SIZE = 100;

    private final PaymentRepository payments;
    private final PaymentFinalizer finalizer;
    private final PaymentMetrics metrics;
    private final GroupdropProperties properties;
    private final Clock clock;

    public OrphanPaymentSweeper(PaymentRepository payments, PaymentFinalizer finalizer,
                                PaymentMetrics metrics, GroupdropProperties properties, Clock clock) {
        this.payments = payments;
        this.finalizer = finalizer;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${groupdrop.orphan-payment-sweep-interval}")
    public void scheduledSweep() {
        try {
            sweep();
        } catch (RuntimeException exception) {
            log.error("고아 결제 스윕에 실패했습니다.", exception);
        }
    }

    @Transactional
    public int sweep() {
        Instant now = Instant.now(clock);
        Instant threshold = now.minus(properties.orphanProcessingThreshold());
        int swept = 0;

        for (PaymentRepository.OrphanCandidate candidate : payments.findStaleProcessing(threshold, BATCH_SIZE)) {
            if (payments.markUnknown(candidate.id(), now)) {
                metrics.recordOrphanSwept("processing");
                log.warn("고아 PROCESSING 결제 {}를 UNKNOWN으로 전이했습니다.", candidate.id());
                swept++;
            }
        }

        for (PaymentRepository.OrphanCandidate candidate :
                payments.findStaleReadyWithoutAttempt(threshold, BATCH_SIZE)) {
            PaymentRepository.PaymentSnapshot payment = payments.findPayment(candidate.id()).orElse(null);
            if (payment == null) {
                continue;
            }
            if (finalizer.failReadyOrphan(payment, "sweep") == PaymentFinalizer.Result.APPLIED) {
                metrics.recordOrphanSwept("ready");
                log.warn("PG 호출 기록이 없는 READY 결제 {}를 FAILED로 확정했습니다.", candidate.id());
                swept++;
            }
        }
        return swept;
    }
}
