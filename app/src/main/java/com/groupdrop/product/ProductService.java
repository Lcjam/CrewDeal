package com.groupdrop.product;

import com.groupdrop.common.ApiException;
import com.groupdrop.user.Supplier;
import com.groupdrop.user.SupplierRepository;
import com.groupdrop.user.User;
import com.groupdrop.user.UserRepository;
import com.groupdrop.user.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 등록 (기획서 5장 — 캠페인 상품 사전 등록). 공급사 본인 소유로만 생성한다.
 */
@Service
public class ProductService {

    private final UserRepository userRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final ProductSkuRepository productSkuRepository;
    private final Clock clock;

    public ProductService(UserRepository userRepository,
                           SupplierRepository supplierRepository,
                           ProductRepository productRepository,
                           ProductSkuRepository productSkuRepository,
                           Clock clock) {
        this.userRepository = userRepository;
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
        this.productSkuRepository = productSkuRepository;
        this.clock = clock;
    }

    @Transactional
    public ProductResponse createProduct(String requesterEmail, CreateProductRequest request) {
        User user = userRepository.findByEmail(requesterEmail)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));

        if (user.getRole() != UserRole.SUPPLIER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", "공급사만 상품을 등록할 수 있습니다.");
        }

        Supplier supplier = supplierRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "SUPPLIER_PROFILE_NOT_FOUND",
                        "공급사 프로필이 존재하지 않습니다."));

        if (request.name() == null || request.name().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PRODUCT_NAME_REQUIRED", "상품명은 필수입니다.");
        }
        if (request.skus() == null || request.skus().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PRODUCT_SKUS_REQUIRED", "SKU는 1개 이상이어야 합니다.");
        }
        Set<String> optionNames = new HashSet<>();
        for (CreateProductRequest.SkuRequest sku : request.skus()) {
            if (sku.optionName() == null || sku.optionName().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PRODUCT_SKU_OPTION_NAME_REQUIRED",
                        "SKU 옵션명은 필수입니다.");
            }
            if (!optionNames.add(sku.optionName())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PRODUCT_SKU_OPTION_DUPLICATE",
                        "중복된 SKU 옵션명입니다: " + sku.optionName());
            }
        }

        Instant now = Instant.now(clock);
        Product product = productRepository.save(new Product(supplier, request.name(), now));
        List<ProductSku> skus = request.skus().stream()
                .map(sku -> productSkuRepository.save(new ProductSku(product, sku.optionName(), now)))
                .toList();

        return ProductResponse.from(product, skus);
    }
}
