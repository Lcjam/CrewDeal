package com.groupdrop.campaign;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * campaign_policy_version_items 테이블 매핑 (V1__base_domain.sql). SKU별 공급 단가 스냅숏 (D-006).
 */
@Entity
@Table(name = "campaign_policy_version_items")
public class CampaignPolicyVersionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_version_id", nullable = false)
    private CampaignPolicyVersion policyVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_sku_id", nullable = false)
    private CampaignSku campaignSku;

    @Column(name = "supply_unit_price", nullable = false)
    private long supplyUnitPrice;

    protected CampaignPolicyVersionItem() {
        // JPA
    }

    public CampaignPolicyVersionItem(CampaignPolicyVersion policyVersion, CampaignSku campaignSku, long supplyUnitPrice) {
        this.policyVersion = policyVersion;
        this.campaignSku = campaignSku;
        this.supplyUnitPrice = supplyUnitPrice;
    }

    public Long getId() {
        return id;
    }

    public CampaignPolicyVersion getPolicyVersion() {
        return policyVersion;
    }

    public CampaignSku getCampaignSku() {
        return campaignSku;
    }

    public long getSupplyUnitPrice() {
        return supplyUnitPrice;
    }
}
