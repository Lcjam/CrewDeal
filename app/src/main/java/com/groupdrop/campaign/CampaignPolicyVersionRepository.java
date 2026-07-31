package com.groupdrop.campaign;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignPolicyVersionRepository extends JpaRepository<CampaignPolicyVersion, Long> {

    Optional<CampaignPolicyVersion> findByCampaignIdAndVersionNo(Long campaignId, int versionNo);
}
