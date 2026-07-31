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
import java.time.Instant;

/**
 * campaign_policy_versions 테이블 매핑 (V1__base_domain.sql). 정산 정책 스냅숏 (CAM-04).
 * MVP는 캠페인당 버전 1개 — 이번 주 구현 범위는 생성 시 version_no=1만 만든다.
 */
@Entity
@Table(name = "campaign_policy_versions")
public class CampaignPolicyVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "commission_rate_bp", nullable = false)
    private int commissionRateBp;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CampaignPolicyVersion() {
        // JPA
    }

    public CampaignPolicyVersion(Campaign campaign, int versionNo, int commissionRateBp, Instant createdAt) {
        this.campaign = campaign;
        this.versionNo = versionNo;
        this.commissionRateBp = commissionRateBp;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Campaign getCampaign() {
        return campaign;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public int getCommissionRateBp() {
        return commissionRateBp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
