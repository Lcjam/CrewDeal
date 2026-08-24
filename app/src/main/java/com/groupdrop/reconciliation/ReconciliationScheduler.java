package com.groupdrop.reconciliation;

import com.groupdrop.common.GroupdropProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 정기 대사 (REC-01). 최소 경과 시간은 프로퍼티에서 오고, 테스트·운영자 수동 실행은 API로 재정의한다
 * (S4-b는 0).
 *
 * <p>다중 인스턴스에서 동시에 돌아도 안전하다 — 불일치 등록은 유형·대상당 1행 유니크로 접히고,
 * 해소 단계의 상태 전이는 전부 조건부 UPDATE이며 PG 조회는 멱등이다 (14.5).
 */
@Component
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliations;
    private final GroupdropProperties properties;

    public ReconciliationScheduler(ReconciliationService reconciliations, GroupdropProperties properties) {
        this.reconciliations = reconciliations;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${groupdrop.reconciliation-interval:30m}")
    public void run() {
        try {
            reconciliations.run((int) properties.reconciliationMinAge().toMinutes());
        } catch (ReconciliationRepository.ReconciliationAlreadyRunningException exception) {
            log.info("다른 인스턴스가 대사를 실행 중이어서 이번 주기를 건너뜁니다.");
        } catch (RuntimeException exception) {
            log.error("정기 대사에 실패했습니다.", exception);
        }
    }
}
