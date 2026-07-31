package com.groupdrop.campaign;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignInventoryRepository extends JpaRepository<CampaignInventory, Long> {

    List<CampaignInventory> findByCampaignSkuIn(List<CampaignSku> campaignSkus);
}
