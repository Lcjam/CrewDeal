package com.groupdrop.campaign;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** CAM-03의 시간·재고 표시 전이를 조건부 UPDATE로만 수행한다. */
@Service
public class CampaignLifecycleService {

    private final CampaignRepository campaignRepository;
    private final Clock clock;

    public CampaignLifecycleService(CampaignRepository campaignRepository, Clock clock) {
        this.campaignRepository = campaignRepository;
        this.clock = clock;
    }

    @Transactional
    public void transitionDueCampaigns() {
        Instant now = Instant.now(clock);
        campaignRepository.openDueCampaigns(now);
        campaignRepository.markAllEmptyOpenCampaignsSoldOut(now);
        campaignRepository.reopenAllAvailableSoldOutCampaigns(now);
        campaignRepository.closeEndedCampaigns(now);
    }

    /** 재고 트랜잭션 커밋 뒤 별도 트랜잭션에서 표시 상태만 최선 노력으로 맞춘다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refreshAvailabilityDisplay(Long campaignId) {
        Instant now = Instant.now(clock);
        campaignRepository.markSoldOutWhenEmpty(campaignId, now);
        campaignRepository.reopenWhenAvailable(campaignId, now);
    }
}
