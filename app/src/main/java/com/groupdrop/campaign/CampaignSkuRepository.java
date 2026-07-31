package com.groupdrop.campaign;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignSkuRepository extends JpaRepository<CampaignSku, Long> {

    List<CampaignSku> findByCampaignId(Long campaignId);
}
