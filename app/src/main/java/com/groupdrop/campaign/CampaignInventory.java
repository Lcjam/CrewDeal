package com.groupdrop.campaign;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * campaign_inventories 테이블 매핑 (V1__base_domain.sql). campaign_sku_id 1:1.
 * 재고 예약·차감(ORD-02)은 2주차 범위 — 이번 주는 캠페인 생성 시 초기 행만 만든다.
 * 재고 수량에 대한 상태 변경은 절대 setter로 하지 않는다 — 조건부 UPDATE만 허용한다.
 */
@Entity
@Table(name = "campaign_inventories")
public class CampaignInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_sku_id", nullable = false, unique = true)
    private CampaignSku campaignSku;

    @Column(name = "initial_quantity", nullable = false)
    private int initialQuantity;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Column(name = "sold_quantity", nullable = false)
    private int soldQuantity;

    protected CampaignInventory() {
        // JPA
    }

    public CampaignInventory(CampaignSku campaignSku, int initialQuantity) {
        this.campaignSku = campaignSku;
        this.initialQuantity = initialQuantity;
        this.availableQuantity = initialQuantity;
        this.reservedQuantity = 0;
        this.soldQuantity = 0;
    }

    public Long getId() {
        return id;
    }

    public CampaignSku getCampaignSku() {
        return campaignSku;
    }

    public int getInitialQuantity() {
        return initialQuantity;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }

    public int getSoldQuantity() {
        return soldQuantity;
    }
}
