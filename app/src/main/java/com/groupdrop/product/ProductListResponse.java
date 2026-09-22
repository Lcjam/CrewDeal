package com.groupdrop.product;

import java.util.List;

/** 03-프론트엔드-계획 v1 1단계의 상품 목록 계약. */
public record ProductListResponse(Long supplierId, String supplierName, Long id, String name, List<Sku> skus) {

    public record Sku(Long id, String optionName) { }
}
