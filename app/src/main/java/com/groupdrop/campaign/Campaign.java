package com.groupdrop.campaign;

import com.groupdrop.product.Product;
import com.groupdrop.user.Influencer;
import com.groupdrop.user.Supplier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * campaigns 테이블 매핑 (V1__base_domain.sql). ddl-auto=validate이므로 컬럼 정의를 DDL과 정확히 맞춘다.
 * 상태 전이는 절대 setter로 하지 않는다 — 항상 CampaignRepository의 조건부 UPDATE를 사용한다
 * (app/CLAUDE.md 코딩 규칙).
 */
@Entity
@Table(name = "campaigns")
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 100)
    private String slug;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "influencer_id", nullable = false)
    private Influencer influencer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CampaignStatus status;

    @Column(name = "deal_price", nullable = false)
    private long dealPrice;

    @Column(name = "per_user_purchase_limit", nullable = false)
    private int perUserPurchaseLimit;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Campaign() {
        // JPA
    }

    public Campaign(String name, String slug, Influencer influencer, Supplier supplier, Product product,
                     CampaignStatus status, long dealPrice, int perUserPurchaseLimit,
                     Instant startsAt, Instant endsAt, Instant now) {
        this.name = name;
        this.slug = slug;
        this.influencer = influencer;
        this.supplier = supplier;
        this.product = product;
        this.status = status;
        this.dealPrice = dealPrice;
        this.perUserPurchaseLimit = perUserPurchaseLimit;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public Influencer getInfluencer() {
        return influencer;
    }

    public Supplier getSupplier() {
        return supplier;
    }

    public Product getProduct() {
        return product;
    }

    public CampaignStatus getStatus() {
        return status;
    }

    public long getDealPrice() {
        return dealPrice;
    }

    public int getPerUserPurchaseLimit() {
        return perUserPurchaseLimit;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
