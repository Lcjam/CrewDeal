package com.groupdrop.campaign;

import com.groupdrop.product.ProductSku;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * campaign_skus 테이블 매핑 (V1__base_domain.sql). (campaign_id, product_sku_id) UNIQUE.
 */
@Entity
@Table(name = "campaign_skus")
public class CampaignSku {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_sku_id", nullable = false)
    private ProductSku productSku;

    protected CampaignSku() {
        // JPA
    }

    public CampaignSku(Campaign campaign, ProductSku productSku) {
        this.campaign = campaign;
        this.productSku = productSku;
    }

    public Long getId() {
        return id;
    }

    public Campaign getCampaign() {
        return campaign;
    }

    public ProductSku getProductSku() {
        return productSku;
    }
}
