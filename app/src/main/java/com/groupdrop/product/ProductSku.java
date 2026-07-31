package com.groupdrop.product;

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
 * product_skus 테이블 매핑 (V1__base_domain.sql). (product_id, option_name) UNIQUE.
 */
@Entity
@Table(name = "product_skus")
public class ProductSku {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "option_name", nullable = false, length = 200)
    private String optionName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProductSku() {
        // JPA
    }

    public ProductSku(Product product, String optionName, Instant createdAt) {
        this.product = product;
        this.optionName = optionName;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public String getOptionName() {
        return optionName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
