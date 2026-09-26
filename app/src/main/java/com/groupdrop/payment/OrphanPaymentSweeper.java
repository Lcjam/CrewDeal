package com.groupdrop.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
    private final OrphanPaymentSweepService sweepService;

    public OrphanPaymentSweeper(OrphanPaymentSweepService sweepService) {
        this.sweepService = sweepService;
    }

    @Scheduled(fixedDelayString = "${groupdrop.orphan-payment-sweep-interval}")
    public void scheduledSweep() {
        try {
            sweepService.sweep();
        } catch (RuntimeException exception) {
            log.error("고아 결제 스윕에 실패했습니다.", exception);
        }
        // 스윕이 커밋한 UNKNOWN을 보고 회수하도록 스윕 뒤에 별도 트랜잭션으로 돈다. 스윕이 실패해도
        // 웹훅·조회로 이미 확정된 결제의 고착 선점은 회수할 수 있어야 한다.
        try {
            sweepService.reclaimStaleIdempotency();
        } catch (RuntimeException exception) {
            log.error("고착 멱등 선점 회수에 실패했습니다.", exception);
        }
    }

}
