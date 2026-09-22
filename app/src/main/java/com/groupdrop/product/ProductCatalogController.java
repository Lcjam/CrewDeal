package com.groupdrop.product;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 인플루언서의 캠페인 개설용 상품 선택 목록. */
@RestController
public class ProductCatalogController {

    private final ProductService productService;

    public ProductCatalogController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/api/products")
    public ResponseEntity<java.util.List<ProductListResponse>> products(Authentication authentication) {
        return ResponseEntity.ok(productService.products(authentication.getName()));
    }
}
