package com.groupdrop.product;

import java.util.List;

public record ProductResponse(Long id, String name, List<SkuResponse> skus) {

    public record SkuResponse(Long id, String optionName) {

        static SkuResponse from(ProductSku sku) {
            return new SkuResponse(sku.getId(), sku.getOptionName());
        }
    }

    static ProductResponse from(Product product, List<ProductSku> skus) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                skus.stream().map(SkuResponse::from).toList());
    }
}
