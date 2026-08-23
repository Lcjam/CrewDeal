package com.groupdrop.settlement;

import com.groupdrop.common.GroupdropProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 정산 스캔 워커. 캠페인 {@code CLOSED → SETTLING} 전이는 이벤트가 아니라 시간 조건이므로
 * Outbox로 흘리지 않고 스캔한다 (13.4).
 *
 * <p>분산 락 없이 다중 인스턴스에서 안전한 근거는 두 가지뿐이다: {@code CLOSED → SETTLING}과
 * {@code READY → PROCESSING}이 조건부 UPDATE라는 것, 그리고 지급 분개의 멱등을 원장의 참조 유니크가
 * 보장한다는 것.
 */
@Component
public class SettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(SettlementScheduler.class);
    private static final int SCAN_LIMIT = 50;

    private final SettlementDeterminationService determination;
    private final SettlementPayoutService payouts;
    private final SettlementRepository settlements;
    private final GroupdropProperties properties;
    private final Clock clock;

    public SettlementScheduler(SettlementDeterminationService determination, SettlementPayoutService payouts,
                               SettlementRepository settlements, GroupdropProperties properties, Clock clock) {
        this.determination = determination;
        this.payouts = payouts;
        this.settlements = settlements;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${groupdrop.settlement-polling-interval:10s}")
    public void run() {
        try {
            runOnce();
        } catch (RuntimeException exception) {
            log.error("정산 스캔에 실패했습니다.", exception);
        }
    }

    /** 테스트가 시각을 제어하며 직접 호출한다. 확정된 캠페인 수 + 진행된 배치 수를 반환한다. */
    public int runOnce() {
        Instant now = Instant.now(clock);
        Instant graceCutoff = now.minus(properties.settlementGracePeriod());
        List<Long> due = settlements.findCampaignsDueForSettlement(graceCutoff, SCAN_LIMIT);
        int progressed = 0;
        for (Long campaignId : due) {
            SettlementDeterminationService.Result result = determination.determine(campaignId);
            if (result.outcome() != SettlementDeterminationService.Result.Outcome.SKIPPED) {
                progressed++;
            }
        }
        return progressed + payouts.drain(SCAN_LIMIT);
    }
}
