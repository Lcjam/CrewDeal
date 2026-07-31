package com.groupdrop.campaign;

import com.groupdrop.common.ApiException;
import com.groupdrop.product.Product;
import com.groupdrop.product.ProductRepository;
import com.groupdrop.product.ProductSku;
import com.groupdrop.product.ProductSkuRepository;
import com.groupdrop.user.Influencer;
import com.groupdrop.user.InfluencerRepository;
import com.groupdrop.user.Supplier;
import com.groupdrop.user.SupplierRepository;
import com.groupdrop.user.User;
import com.groupdrop.user.UserRepository;
import com.groupdrop.user.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 캠페인 생성·승인 흐름 (CAM-01, CAM-02). 상태 전이는 CampaignRepository의 조건부 UPDATE로만
 * 수행하고, 엔티티 setter로 전이하지 않는다 (app/CLAUDE.md 코딩 규칙).
 */
@Service
public class CampaignService {

    /** PG 수수료 고정 비율 3% (기획서 5장), 마진 게이트의 상수항. */
    private static final int PG_FEE_BP = 300;
    private static final int BP_SCALE = 10000;
    private static final int POLICY_VERSION_NO = 1;

    private final UserRepository userRepository;
    private final InfluencerRepository influencerRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final ProductSkuRepository productSkuRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignSkuRepository campaignSkuRepository;
    private final CampaignInventoryRepository campaignInventoryRepository;
    private final CampaignPolicyVersionRepository campaignPolicyVersionRepository;
    private final CampaignPolicyVersionItemRepository campaignPolicyVersionItemRepository;
    private final Clock clock;

    public CampaignService(UserRepository userRepository,
                            InfluencerRepository influencerRepository,
                            SupplierRepository supplierRepository,
                            ProductRepository productRepository,
                            ProductSkuRepository productSkuRepository,
                            CampaignRepository campaignRepository,
                            CampaignSkuRepository campaignSkuRepository,
                            CampaignInventoryRepository campaignInventoryRepository,
                            CampaignPolicyVersionRepository campaignPolicyVersionRepository,
                            CampaignPolicyVersionItemRepository campaignPolicyVersionItemRepository,
                            Clock clock) {
        this.userRepository = userRepository;
        this.influencerRepository = influencerRepository;
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
        this.productSkuRepository = productSkuRepository;
        this.campaignRepository = campaignRepository;
        this.campaignSkuRepository = campaignSkuRepository;
        this.campaignInventoryRepository = campaignInventoryRepository;
        this.campaignPolicyVersionRepository = campaignPolicyVersionRepository;
        this.campaignPolicyVersionItemRepository = campaignPolicyVersionItemRepository;
        this.clock = clock;
    }

    @Transactional
    public CampaignResponse createCampaign(String requesterEmail, CreateCampaignRequest request) {
        User user = requireUser(requesterEmail);
        if (user.getRole() != UserRole.INFLUENCER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", "인플루언서만 캠페인을 생성할 수 있습니다.");
        }
        Influencer influencer = influencerRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "INFLUENCER_PROFILE_NOT_FOUND",
                        "인플루언서 프로필이 존재하지 않습니다."));

        validateBasicFields(request);

        if (campaignRepository.existsBySlug(request.slug())) {
            throw new ApiException(HttpStatus.CONFLICT, "CAMPAIGN_SLUG_DUPLICATE", "이미 사용 중인 슬러그입니다: " + request.slug());
        }

        Supplier supplier = supplierRepository.findById(request.supplierId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_SUPPLIER_NOT_FOUND",
                        "공급사를 찾을 수 없습니다: " + request.supplierId()));

        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_PRODUCT_NOT_FOUND",
                        "상품을 찾을 수 없습니다: " + request.productId()));

        if (!product.getSupplier().getId().equals(request.supplierId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_PRODUCT_NOT_OWNED_BY_SUPPLIER",
                    "상품이 지정한 공급사 소유가 아닙니다.");
        }

        Map<Long, ProductSku> productSkusById = resolveProductSkus(request, product);
        validateMarginGate(request);

        Instant now = Instant.now(clock);
        Campaign campaign = new Campaign(
                request.name(), request.slug(), influencer, supplier, product,
                CampaignStatus.DRAFT, request.dealPrice(), request.perUserPurchaseLimit(),
                request.startsAt(), request.endsAt(), now);
        try {
            campaign = campaignRepository.saveAndFlush(campaign);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CAMPAIGN_SLUG_DUPLICATE", "이미 사용 중인 슬러그입니다: " + request.slug());
        }

        CampaignPolicyVersion policyVersion = campaignPolicyVersionRepository.save(
                new CampaignPolicyVersion(campaign, POLICY_VERSION_NO, request.commissionRateBp(), now));

        for (CreateCampaignRequest.SkuAllocation skuAllocation : request.skus()) {
            ProductSku productSku = productSkusById.get(skuAllocation.productSkuId());
            CampaignSku campaignSku = campaignSkuRepository.save(new CampaignSku(campaign, productSku));
            campaignInventoryRepository.save(new CampaignInventory(campaignSku, skuAllocation.allocatedQuantity()));
            campaignPolicyVersionItemRepository.save(
                    new CampaignPolicyVersionItem(policyVersion, campaignSku, skuAllocation.supplyUnitPrice()));
        }

        return toResponse(campaign);
    }

    @Transactional
    public CampaignResponse submitCampaign(String requesterEmail, Long campaignId) {
        User user = requireUser(requesterEmail);
        Campaign campaign = requireCampaign(campaignId);

        Influencer influencer = influencerRepository.findByUserId(user.getId()).orElse(null);
        boolean isOwner = user.getRole() == UserRole.INFLUENCER
                && influencer != null
                && influencer.getId().equals(campaign.getInfluencer().getId());
        if (!isOwner) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_NOT_OWNER", "캠페인 소유 인플루언서만 제출할 수 있습니다.");
        }

        applyTransition(campaignId, CampaignStatus.DRAFT, CampaignStatus.REVIEWING);
        return toResponse(requireCampaign(campaignId));
    }

    @Transactional
    public CampaignResponse approveCampaign(String requesterEmail, Long campaignId) {
        requireAdmin(requesterEmail);
        requireCampaign(campaignId);
        applyTransition(campaignId, CampaignStatus.REVIEWING, CampaignStatus.SCHEDULED);
        return toResponse(requireCampaign(campaignId));
    }

    @Transactional
    public CampaignResponse rejectCampaign(String requesterEmail, Long campaignId, RejectCampaignRequest request) {
        requireAdmin(requesterEmail);
        requireCampaign(campaignId);
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_REJECT_REASON_REQUIRED", "반려 사유는 필수입니다.");
        }

        Instant now = Instant.now(clock);
        int updated = campaignRepository.rejectTransition(
                campaignId, CampaignStatus.REVIEWING, CampaignStatus.DRAFT, request.reason(), now);
        if (updated == 0) {
            Campaign current = requireCampaign(campaignId);
            throw new ApiException(HttpStatus.CONFLICT, "CAMPAIGN_INVALID_TRANSITION",
                    "REVIEWING 상태에서만 반려할 수 있습니다. 현재 상태: " + current.getStatus());
        }
        return toResponse(requireCampaign(campaignId));
    }

    @Transactional(readOnly = true)
    public CampaignResponse getCampaign(Long campaignId) {
        return toResponse(requireCampaign(campaignId));
    }

    @Transactional(readOnly = true)
    public CampaignResponse getCampaignBySlug(String slug) {
        Campaign campaign = campaignRepository.findBySlug(slug)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CAMPAIGN_NOT_FOUND", "캠페인을 찾을 수 없습니다: " + slug));
        return toResponse(campaign);
    }

    private void applyTransition(Long campaignId, CampaignStatus from, CampaignStatus to) {
        Instant now = Instant.now(clock);
        int updated = campaignRepository.transitionStatus(campaignId, from, to, now);
        if (updated == 0) {
            Campaign current = requireCampaign(campaignId);
            throw new ApiException(HttpStatus.CONFLICT, "CAMPAIGN_INVALID_TRANSITION",
                    from + " 상태에서만 " + to + "(으)로 전이할 수 있습니다. 현재 상태: " + current.getStatus());
        }
    }

    private void requireAdmin(String requesterEmail) {
        User user = requireUser(requesterEmail);
        if (user.getRole() != UserRole.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", "운영자만 수행할 수 있습니다.");
        }
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
    }

    private Campaign requireCampaign(Long campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CAMPAIGN_NOT_FOUND", "캠페인을 찾을 수 없습니다: " + campaignId));
    }

    private void validateBasicFields(CreateCampaignRequest request) {
        if (isBlank(request.name())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_NAME_REQUIRED", "캠페인명은 필수입니다.");
        }
        if (isBlank(request.slug())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_SLUG_REQUIRED", "슬러그는 필수입니다.");
        }
        if (request.supplierId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_SUPPLIER_ID_REQUIRED", "공급사는 필수입니다.");
        }
        if (request.productId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_PRODUCT_ID_REQUIRED", "상품은 필수입니다.");
        }
        if (request.dealPrice() == null || request.dealPrice() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_INVALID_DEAL_PRICE", "공구 가격은 0보다 커야 합니다.");
        }
        if (request.startsAt() == null || request.endsAt() == null || !request.endsAt().isAfter(request.startsAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_INVALID_DATE_RANGE", "종료 시각은 시작 시각보다 이후여야 합니다.");
        }
        if (request.perUserPurchaseLimit() == null || request.perUserPurchaseLimit() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_INVALID_PURCHASE_LIMIT", "구매자별 최대 구매 수량은 0보다 커야 합니다.");
        }
        if (request.commissionRateBp() == null || request.commissionRateBp() < 0 || request.commissionRateBp() > BP_SCALE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_INVALID_COMMISSION_RATE", "수수료율은 0~10000bp 범위여야 합니다.");
        }
        if (request.skus() == null || request.skus().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_SKUS_REQUIRED", "판매 SKU는 1개 이상이어야 합니다.");
        }
        Set<Long> seenSkuIds = new HashSet<>();
        for (CreateCampaignRequest.SkuAllocation sku : request.skus()) {
            if (sku.productSkuId() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_SKU_PRODUCT_SKU_ID_REQUIRED", "SKU ID는 필수입니다.");
            }
            if (!seenSkuIds.add(sku.productSkuId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_DUPLICATE_SKU", "중복된 SKU입니다: " + sku.productSkuId());
            }
            if (sku.allocatedQuantity() == null || sku.allocatedQuantity() < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_INVALID_ALLOCATED_QUANTITY",
                        "SKU " + sku.productSkuId() + "의 할당 재고는 0 이상이어야 합니다.");
            }
            if (sku.supplyUnitPrice() == null || sku.supplyUnitPrice() < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_INVALID_SUPPLY_UNIT_PRICE",
                        "SKU " + sku.productSkuId() + "의 공급 단가는 0 이상이어야 합니다.");
            }
        }
    }

    private Map<Long, ProductSku> resolveProductSkus(CreateCampaignRequest request, Product product) {
        Map<Long, ProductSku> byId = new HashMap<>();
        List<Long> notInProduct = new ArrayList<>();
        for (CreateCampaignRequest.SkuAllocation sku : request.skus()) {
            ProductSku productSku = productSkuRepository.findById(sku.productSkuId()).orElse(null);
            if (productSku == null || !productSku.getProduct().getId().equals(product.getId())) {
                notInProduct.add(sku.productSkuId());
                continue;
            }
            byId.put(sku.productSkuId(), productSku);
        }
        if (!notInProduct.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_SKU_NOT_IN_PRODUCT",
                    "지정한 상품에 속하지 않는 SKU입니다: " + notInProduct);
        }
        return byId;
    }

    /**
     * SKU별 연속(절사 전) 마진 게이트 (CAM-01). 캠페인 합계가 아니라 SKU 단위로 검증한다.
     * dealPrice × (10000 − commissionRateBp − 300) > supplyUnitPrice × 10000, 정수(long) 연산.
     */
    private void validateMarginGate(CreateCampaignRequest request) {
        long marginFactor = (long) BP_SCALE - request.commissionRateBp() - PG_FEE_BP;
        List<Long> failing = new ArrayList<>();
        for (CreateCampaignRequest.SkuAllocation sku : request.skus()) {
            long lhs = request.dealPrice() * marginFactor;
            long rhs = sku.supplyUnitPrice() * (long) BP_SCALE;
            if (lhs <= rhs) {
                failing.add(sku.productSkuId());
            }
        }
        if (!failing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CAMPAIGN_MARGIN_GATE_FAILED",
                    "마진 게이트를 통과하지 못한 SKU: " + failing);
        }
    }

    private CampaignResponse toResponse(Campaign campaign) {
        List<CampaignSku> campaignSkus = campaignSkuRepository.findByCampaignId(campaign.getId());
        Map<Long, CampaignInventory> inventoryByCampaignSkuId = new HashMap<>();
        for (CampaignInventory inventory : campaignInventoryRepository.findByCampaignSkuIn(campaignSkus)) {
            inventoryByCampaignSkuId.put(inventory.getCampaignSku().getId(), inventory);
        }

        List<CampaignResponse.SkuInfo> skuInfos = campaignSkus.stream()
                .map(campaignSku -> {
                    CampaignInventory inventory = inventoryByCampaignSkuId.get(campaignSku.getId());
                    return new CampaignResponse.SkuInfo(
                            campaignSku.getProductSku().getId(),
                            campaignSku.getProductSku().getOptionName(),
                            inventory == null ? null : inventory.getInitialQuantity());
                })
                .toList();

        Integer commissionRateBp = campaignPolicyVersionRepository
                .findByCampaignIdAndVersionNo(campaign.getId(), POLICY_VERSION_NO)
                .map(CampaignPolicyVersion::getCommissionRateBp)
                .orElse(null);

        return new CampaignResponse(
                campaign.getId(),
                campaign.getName(),
                campaign.getSlug(),
                campaign.getStatus(),
                campaign.getSupplier().getId(),
                campaign.getProduct().getId(),
                campaign.getDealPrice(),
                campaign.getStartsAt(),
                campaign.getEndsAt(),
                campaign.getPerUserPurchaseLimit(),
                commissionRateBp,
                skuInfos);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
