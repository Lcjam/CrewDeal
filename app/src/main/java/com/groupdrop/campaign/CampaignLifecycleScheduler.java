package com.groupdrop.campaign;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CampaignLifecycleScheduler {

    private final CampaignLifecycleService lifecycleService;

    public CampaignLifecycleScheduler(CampaignLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @Scheduled(fixedDelayString = "${groupdrop.campaign-lifecycle-polling-interval:1s}")
    public void transitionDueCampaigns() {
        lifecycleService.transitionDueCampaigns();
    }
}
