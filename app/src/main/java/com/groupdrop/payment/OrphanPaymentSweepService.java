package com.groupdrop.payment;

import com.groupdrop.common.GroupdropProperties;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PAY-03 고아 결제 스윕의 트랜잭션 경계. */
@Service
public class OrphanPaymentSweepService {

    private static final Logger log = LoggerFactory.getLogger(OrphanPaymentSweepService.class);
    private static final int BATCH_SIZE = 100;

    private final PaymentRepository payments;
    private final PaymentFinalizer finalizer;
    private final PaymentMetrics metrics;
    private final GroupdropProperties properties;
    private final Clock clock;

    public OrphanPaymentSweepService(PaymentRepository payments, PaymentFinalizer finalizer,
                                     PaymentMetrics metrics, GroupdropProperties properties, Clock clock) {
        this.payments = payments;
        this.finalizer = finalizer;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /** 상태 전이와 payment.finalized Outbox 적재는 반드시 같은 트랜잭션으로 커밋한다 (13.4). */
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
