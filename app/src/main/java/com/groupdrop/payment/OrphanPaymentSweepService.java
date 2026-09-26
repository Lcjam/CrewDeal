package com.groupdrop.payment;

import com.groupdrop.common.GroupdropProperties;
import com.groupdrop.common.IdempotencyRepository;
import com.groupdrop.common.Json;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PAY-03 고아 결제 스윕과 PAY-02 고착 멱등 선점 회수의 트랜잭션 경계. */
@Service
public class OrphanPaymentSweepService {

    private static final Logger log = LoggerFactory.getLogger(OrphanPaymentSweepService.class);
    private static final int BATCH_SIZE = 100;

    private final PaymentRepository payments;
    private final PaymentFinalizer finalizer;
    private final PaymentMetrics metrics;
    private final IdempotencyRepository idempotency;
    private final Json json;
    private final GroupdropProperties properties;
    private final Clock clock;

    public OrphanPaymentSweepService(PaymentRepository payments, PaymentFinalizer finalizer,
                                     PaymentMetrics metrics, IdempotencyRepository idempotency, Json json,
                                     GroupdropProperties properties, Clock clock) {
        this.payments = payments;
        this.finalizer = finalizer;
        this.metrics = metrics;
        this.idempotency = idempotency;
        this.json = json;
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

    /**
     * PAY-02 고착 선점 회수. PG 호출 중 요청 스레드가 죽거나 확정 트랜잭션이 롤백되면 결제 멱등 레코드가
     * IN_PROGRESS로 남아, 같은 키가 만료(24h)까지 "처리 중" 409를 받는다. 결제는 스윕·웹훅·조회·대사로 이미
     * 상태가 정해졌으므로, 요청 스레드가 했을 일(settle)과 같은 규칙으로 그 상태의 응답을 저장해 완료한다.
     *
     * <p>레코드를 지우지 않는 것이 핵심이다. 지우면 같은 키가 새 요청이 되어, 웹훅이 SUCCEEDED를 확정했지만
     * Outbox가 주문을 PAID로 바꾸기 전이면 두 번째 PG 승인이 나간다. 완료로 두면 같은 키는 재생만 받고 PG 호출은 0회다.
     * 스윕({@link #sweep})과 별도 트랜잭션이라 회수가 실패해도 결제 상태 전이는 롤백되지 않고, 스윕이 방금
     * 커밋한 UNKNOWN도 이 트랜잭션에서 보인다.
     */
    @Transactional
    public int reclaimStaleIdempotency() {
        Instant now = Instant.now(clock);
        Instant threshold = now.minus(properties.orphanProcessingThreshold());
        int reclaimed = 0;
        for (PaymentRepository.StaleIdempotencyClaim claim :
                payments.lockReclaimableIdempotencyClaims(threshold, now, BATCH_SIZE)) {
            PaymentRepository.PaymentSnapshot payment = payments.findPayment(claim.paymentId()).orElse(null);
            if (payment == null) {
                continue;
            }
            PaymentResponse body = PaymentResponse.from(payment);
            int httpStatus = PaymentResponse.httpStatusFor(payment.status());
            if (idempotency.completeIfInProgress(claim.scope(), claim.key(), PaymentService.RESOURCE_TYPE,
                    payment.id(), httpStatus, json.write(body), now)) {
                metrics.recordIdempotencyReclaimed();
                log.warn("결제 {}의 고착 멱등 선점을 {} {}로 완료했습니다.", payment.id(), httpStatus, payment.status());
                reclaimed++;
            }
        }
        return reclaimed;
    }
}
