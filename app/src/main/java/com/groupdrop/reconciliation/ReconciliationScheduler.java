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
 * <p>인스턴스마다 이 스케줄러가 돌지만 실행은 한 번에 하나다. {@code RUNNING} 대사는 부분 유니크
 * 인덱스({@code ux_reconciliation_single_running}, V6)로 한 행만 허용되고, 늦게 시작한 쪽은
 * {@link ReconciliationRepository.ReconciliationAlreadyRunningException}을 받아 이번 주기를 건너뛴다.
 * 불일치 유니크와 조건부 UPDATE만으로는 겹친 두 실행이 서로의 재검출을 "사라짐"으로 닫는 것을 막지
 * 못했다. 자동 해소는 조회 범위(cutoff) 안의 건만 닫고, 회수된 옛 실행의 늦은 쓰기는 반영하지
 * 않는다 ({@code ReconciliationRepository}).
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
