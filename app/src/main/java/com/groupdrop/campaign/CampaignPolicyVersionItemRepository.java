package com.groupdrop.campaign;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignPolicyVersionItemRepository extends JpaRepository<CampaignPolicyVersionItem, Long> {

    List<CampaignPolicyVersionItem> findByPolicyVersionId(Long policyVersionId);
}
