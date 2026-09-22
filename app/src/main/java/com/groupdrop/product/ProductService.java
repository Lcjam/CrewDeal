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
    private final ProductListRepository productLists;
    private final Clock clock;

    public ProductService(UserRepository userRepository,
                           SupplierRepository supplierRepository,
                           ProductRepository productRepository,
                           ProductSkuRepository productSkuRepository,
                           ProductListRepository productLists,
                           Clock clock) {
        this.userRepository = userRepository;
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
        this.productSkuRepository = productSkuRepository;
        this.productLists = productLists;
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

    @Transactional(readOnly = true)
    public List<ProductListResponse> products(String requesterEmail) {
        User user = requireUser(requesterEmail);
        if (user.getRole() != UserRole.INFLUENCER && user.getRole() != UserRole.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", "이 조회를 수행할 권한이 없습니다.");
        }
        return group(productLists.find(null));
    }

    @Transactional(readOnly = true)
    public List<ProductListResponse> myProducts(String requesterEmail) {
        User user = requireUser(requesterEmail);
        if (user.getRole() != UserRole.SUPPLIER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", "이 조회를 수행할 권한이 없습니다.");
        }
        Long supplierId = supplierRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SUPPLIER_NOT_FOUND", "공급사 정보를 찾을 수 없습니다."))
                .getId();
        return group(productLists.find(supplierId));
    }

    private User requireUser(String requesterEmail) {
        return userRepository.findByEmail(requesterEmail)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
    }

    private List<ProductListResponse> group(List<ProductListRepository.Row> rows) {
        java.util.Map<Long, List<ProductListRepository.Row>> byProduct = new java.util.LinkedHashMap<>();
        for (ProductListRepository.Row row : rows) {
            byProduct.computeIfAbsent(row.id(), ignored -> new java.util.ArrayList<>()).add(row);
        }
        return byProduct.values().stream().map(sameProduct -> {
            ProductListRepository.Row first = sameProduct.getFirst();
            return new ProductListResponse(first.supplierId(), first.supplierName(), first.id(), first.name(),
                    sameProduct.stream().filter(row -> row.skuId() != null)
                            .map(row -> new ProductListResponse.Sku(row.skuId(), row.optionName())).toList());
        }).toList();
    }
}
