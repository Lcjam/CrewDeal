package com.groupdrop.campaign;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/** 원 트랜잭션 연결을 반환한 뒤 CAM-03 표시 전이를 실행하도록 작업만 비동기 제출한다. */
@Component
public class CampaignAvailabilityNotifier {

    private static final Logger log = LoggerFactory.getLogger(CampaignAvailabilityNotifier.class);

    private final TaskExecutor taskExecutor;
    private final CampaignLifecycleService lifecycleService;

    public CampaignAvailabilityNotifier(@Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
                                        CampaignLifecycleService lifecycleService) {
        this.taskExecutor = taskExecutor;
        this.lifecycleService = lifecycleService;
    }

    public void refreshAsync(Long campaignId) {
        taskExecutor.execute(() -> {
            try {
                lifecycleService.refreshAvailabilityDisplay(campaignId);
            } catch (RuntimeException exception) {
                log.warn("캠페인 재고 표시 상태 후처리에 실패했습니다. campaignId={}", campaignId, exception);
            }
        });
    }
}
