package com.groupdrop.product;

import java.util.List;

public record CreateProductRequest(String name, List<SkuRequest> skus) {

    public record SkuRequest(String optionName) {
    }
}
