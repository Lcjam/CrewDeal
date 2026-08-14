package com.groupdrop.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.groupdrop.TestcontainersConfiguration;
import com.groupdrop.user.Influencer;
import com.groupdrop.user.InfluencerRepository;
import com.groupdrop.user.Supplier;
import com.groupdrop.user.SupplierRepository;
import com.groupdrop.user.User;
import com.groupdrop.user.UserRepository;
import com.groupdrop.user.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 상품 등록 + 캠페인 생성·승인 흐름 통합 테스트 (CAM-01, CAM-02).
 * Jackson 3 환경(classic ObjectMapper 빈 없음)이라 요청 JSON은 문자열로 직접 구성한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CampaignFlowApiTest {

    private static final String SEED_PASSWORD = "groupdrop123!";
    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InfluencerRepository influencerRepository;
    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private Clock clock;
    @Autowired
    private CampaignRepository campaignRepository;
    @Autowired
    private CampaignSkuRepository campaignSkuRepository;
    @Autowired
    private CampaignInventoryRepository campaignInventoryRepository;
    @Autowired
    private CampaignPolicyVersionRepository campaignPolicyVersionRepository;
    @Autowired
    private CampaignPolicyVersionItemRepository campaignPolicyVersionItemRepository;

    // ---- 1. 공급사 상품 등록 ----

    @Test
    void 공급사가_상품과_SKU_2개를_등록하면_201() throws Exception {
        MockHttpSession supplierSession = login("supplier@groupdrop.test");

        String requestJson = """
                {"name":"테스트 티셔츠","skus":[{"optionName":"블랙/M"},{"optionName":"화이트/L"}]}""";

        mockMvc.perform(post("/api/suppliers/me/products")
                        .session(supplierSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("테스트 티셔츠"))
                .andExpect(jsonPath("$.skus.length()").value(2))
                .andExpect(jsonPath("$.skus[0].optionName").value("블랙/M"))
                .andExpect(jsonPath("$.skus[1].optionName").value("화이트/L"));
    }

    @Test
    void 공급사가_아니면_상품을_등록할_수_없고_중복_SKU_옵션은_거부된다() throws Exception {
        String requestJson = """
                {"name":"권한 테스트 상품","skus":[{"optionName":"단일 옵션"}]}""";

        mockMvc.perform(post("/api/suppliers/me/products")
                        .session(login("buyer1@groupdrop.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        String duplicateOptions = """
                {"name":"중복 옵션 상품","skus":[{"optionName":"블랙/M"},{"optionName":"블랙/M"}]}""";
        mockMvc.perform(post("/api/suppliers/me/products")
                        .session(login("supplier@groupdrop.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateOptions))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODUCT_SKU_OPTION_DUPLICATE"));
    }

    // ---- 2. 캠페인 생성 시 재고·정책 버전 생성 ----

    @Test
    void 인플루언서가_캠페인을_생성하면_재고와_정책버전이_함께_생성된다() throws Exception {
        ProductFixture product = createProduct(uniqueName("정책버전상품"));
        Long supplierId = supplierId();

        String slug = uniqueSlug("policy-version");
        String requestJson = campaignJson(
                "정책버전 캠페인", slug, supplierId, product.productId(), 19900, 750,
                "[{\"productSkuId\":" + product.sku1Id() + ",\"allocatedQuantity\":10,\"supplyUnitPrice\":17810}]");

        MvcResult result = mockMvc.perform(post("/api/campaigns")
                        .session(login("influencer@groupdrop.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();

        Long campaignId = readLong(result, "$.id");

        List<CampaignSku> campaignSkus = campaignSkuRepository.findByCampaignId(campaignId);
        assertThat(campaignSkus).hasSize(1);

        List<CampaignInventory> inventories = campaignInventoryRepository.findByCampaignSkuIn(campaignSkus);
        assertThat(inventories).hasSize(1);
        CampaignInventory inventory = inventories.get(0);
        assertThat(inventory.getInitialQuantity()).isEqualTo(10);
        assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
        assertThat(inventory.getReservedQuantity()).isEqualTo(0);
        assertThat(inventory.getSoldQuantity()).isEqualTo(0);

        CampaignPolicyVersion policyVersion = campaignPolicyVersionRepository
                .findByCampaignIdAndVersionNo(campaignId, 1)
                .orElseThrow();
        assertThat(policyVersion.getCommissionRateBp()).isEqualTo(750);

        List<CampaignPolicyVersionItem> items = campaignPolicyVersionItemRepository
                .findByPolicyVersionId(policyVersion.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getSupplyUnitPrice()).isEqualTo(17810L);
    }

    // ---- 3. 마진 게이트 경계값 ----

    @Test
    void 마진게이트_경계값_17810은_통과하고_17811은_400() throws Exception {
        ProductFixture product = createProduct(uniqueName("마진게이트상품"));
        Long supplierId = supplierId();

        String passSlug = uniqueSlug("margin-pass");
        String passJson = campaignJson(
                "마진통과 캠페인", passSlug, supplierId, product.productId(), 19900, 750,
                "[{\"productSkuId\":" + product.sku1Id() + ",\"allocatedQuantity\":10,\"supplyUnitPrice\":17810}]");
        mockMvc.perform(post("/api/campaigns")
                        .session(login("influencer@groupdrop.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passJson))
                .andExpect(status().isCreated());

        String failSlug = uniqueSlug("margin-fail");
        String failJson = campaignJson(
                "마진실패 캠페인", failSlug, supplierId, product.productId(), 19900, 750,
                "[{\"productSkuId\":" + product.sku1Id() + ",\"allocatedQuantity\":10,\"supplyUnitPrice\":17811}]");
        mockMvc.perform(post("/api/campaigns")
                        .session(login("influencer@groupdrop.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(failJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_MARGIN_GATE_FAILED"));
    }

    @Test
    void CAM_01_필수값과_가격_기간_재고_수락조건을_검증한다() throws Exception {
        ProductFixture product = createProduct(uniqueName("수락조건상품"));
        String validJson = campaignJson(
                "수락조건 캠페인", uniqueSlug("acceptance"), supplierId(), product.productId(), 19900, 750,
                "[{\"productSkuId\":" + product.sku1Id() + ",\"allocatedQuantity\":10,\"supplyUnitPrice\":17810}]");

        assertCampaignBadRequest(
                validJson.replace("\"name\":\"수락조건 캠페인\"", "\"name\":\"\""),
                "CAMPAIGN_NAME_REQUIRED");
        assertCampaignBadRequest(
                validJson.replaceFirst("\"slug\":\"[^\"]+\"", "\"slug\":\"\""),
                "CAMPAIGN_SLUG_REQUIRED");
        assertCampaignBadRequest(
                validJson.replace("\"dealPrice\":19900", "\"dealPrice\":0"),
                "CAMPAIGN_INVALID_DEAL_PRICE");
        assertCampaignBadRequest(
                validJson.replace("\"endsAt\":\"2026-08-10T00:00:00Z\"",
                        "\"endsAt\":\"2026-08-01T00:00:00Z\""),
                "CAMPAIGN_INVALID_DATE_RANGE");
        assertCampaignBadRequest(
                validJson.replace("\"allocatedQuantity\":10", "\"allocatedQuantity\":-1"),
                "CAMPAIGN_INVALID_ALLOCATED_QUANTITY");
        assertCampaignBadRequest(
                validJson.replaceFirst("\"skus\":\\[.*]", "\"skus\":[]"),
                "CAMPAIGN_SKUS_REQUIRED");
    }

    @Test
    void 마진_계산이_long_범위를_넘으면_400으로_거부한다() throws Exception {
        ProductFixture product = createProduct(uniqueName("큰금액상품"));
        String requestJson = campaignJson(
                "큰 금액 캠페인", uniqueSlug("amount-overflow"), supplierId(), product.productId(),
                Long.MAX_VALUE, 750,
                "[{\"productSkuId\":" + product.sku1Id() + ",\"allocatedQuantity\":1,\"supplyUnitPrice\":0}]");

        mockMvc.perform(post("/api/campaigns")
                        .session(login("influencer@groupdrop.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_AMOUNT_TOO_LARGE"));
    }

    // ---- 4. 슬러그 중복 ----

    @Test
    void 슬러그_중복이면_409() throws Exception {
        ProductFixture product = createProduct(uniqueName("슬러그상품"));
        Long supplierId = supplierId();
        String slug = uniqueSlug("dup-slug");

        String firstJson = campaignJson(
                "첫 캠페인", slug, supplierId, product.productId(), 19900, 750,
                "[{\"productSkuId\":" + product.sku1Id() + ",\"allocatedQuantity\":10,\"supplyUnitPrice\":17810}]");
        mockMvc.perform(post("/api/campaigns")
                        .session(login("influencer@groupdrop.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstJson))
                .andExpect(status().isCreated());

        String secondJson = campaignJson(
                "두번째 캠페인", slug, supplierId, product.productId(), 19900, 750,
                "[{\"productSkuId\":" + product.sku2Id() + ",\"allocatedQuantity\":5,\"supplyUnitPrice\":17810}]");
        mockMvc.perform(post("/api/campaigns")
                        .session(login("influencer@groupdrop.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_SLUG_DUPLICATE"));
    }

    // ---- 5. 전체 승인 흐름 ----

    @Test
    void 생성_제출_승인_흐름_DRAFT_REVIEWING_SCHEDULED() throws Exception {
        Long campaignId = createDraftCampaign(uniqueSlug("approve-flow"));

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/submit")
                        .session(login("influencer@groupdrop.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWING"));

        mockMvc.perform(post("/api/admin/campaigns/" + campaignId + "/approve")
                        .session(login("admin@groupdrop.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"));

        assertThat(campaignRepository.findById(campaignId).orElseThrow().getStatus())
                .isEqualTo(CampaignStatus.SCHEDULED);
    }

    // ---- 6. 반려 흐름 ----

    @Test
    void 반려하면_REVIEWING에서_DRAFT로_돌아가고_사유가_저장된다() throws Exception {
        Long campaignId = createDraftCampaign(uniqueSlug("reject-flow"));

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/submit")
                        .session(login("influencer@groupdrop.test")))
                .andExpect(status().isOk());

        String rejectJson = "{\"reason\":\"수수료율 재협의 필요\"}";
        mockMvc.perform(post("/api/admin/campaigns/" + campaignId + "/reject")
                        .session(login("admin@groupdrop.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rejectJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        Campaign campaign = campaignRepository.findById(campaignId).orElseThrow();
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(campaign.getRejectionReason()).isEqualTo("수수료율 재협의 필요");
    }

    // ---- 7. 전이표 위반 ----

    @Test
    void DRAFT_상태에서_approve_호출시_409() throws Exception {
        Long campaignId = createDraftCampaign(uniqueSlug("invalid-transition"));

        mockMvc.perform(post("/api/admin/campaigns/" + campaignId + "/approve")
                        .session(login("admin@groupdrop.test")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_INVALID_TRANSITION"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("DRAFT")));
    }

    @Test
    void 운영자가_아니면_캠페인을_승인할_수_없다() throws Exception {
        Long campaignId = createDraftCampaign(uniqueSlug("non-admin-approve"));
        mockMvc.perform(post("/api/campaigns/" + campaignId + "/submit")
                        .session(login("influencer@groupdrop.test")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/campaigns/" + campaignId + "/approve")
                        .session(login("buyer1@groupdrop.test")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
    }

    // ---- 8. 인가 실패 ----

    @Test
    void 비소유_인플루언서의_submit은_403() throws Exception {
        Long campaignId = createDraftCampaign(uniqueSlug("non-owner-submit"));
        MockHttpSession otherInfluencerSession = createAndLoginSecondInfluencer();

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/submit")
                        .session(otherInfluencerSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_NOT_OWNER"));
    }

    @Test
    void BUYER의_캠페인_생성은_403() throws Exception {
        ProductFixture product = createProduct(uniqueName("바이어금지상품"));
        Long supplierId = supplierId();

        String requestJson = campaignJson(
                "바이어 캠페인", uniqueSlug("buyer-forbidden"), supplierId, product.productId(), 19900, 750,
                "[{\"productSkuId\":" + product.sku1Id() + ",\"allocatedQuantity\":10,\"supplyUnitPrice\":17810}]");

        mockMvc.perform(post("/api/campaigns")
                        .session(login("buyer1@groupdrop.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
    }

    // ---- 9. SKU 단위 마진 게이트 (캠페인 합계 아님) ----

    @Test
    void 고마진_저마진_SKU_조합에서_저마진_SKU_때문에_400() throws Exception {
        ProductFixture product = createProduct(uniqueName("혼합마진상품"));
        Long supplierId = supplierId();

        // sku1: 공급단가 1,000원(고마진), sku2: 17,811원(마진게이트 실패 경계 초과)
        String requestJson = campaignJson(
                "혼합마진 캠페인", uniqueSlug("mixed-margin"), supplierId, product.productId(), 19900, 750,
                "["
                        + "{\"productSkuId\":" + product.sku1Id() + ",\"allocatedQuantity\":10,\"supplyUnitPrice\":1000},"
                        + "{\"productSkuId\":" + product.sku2Id() + ",\"allocatedQuantity\":10,\"supplyUnitPrice\":17811}"
                        + "]");

        mockMvc.perform(post("/api/campaigns")
                        .session(login("influencer@groupdrop.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_MARGIN_GATE_FAILED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(String.valueOf(product.sku2Id()))))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(product.sku1Id() + ","))));
    }

    // ---- GET 조회 (구현 범위에 포함된 엔드포인트이므로 최소 스모크 테스트) ----

    @Test
    void 캠페인을_id와_slug로_조회할_수_있다() throws Exception {
        String slug = uniqueSlug("get-campaign");
        Long campaignId = createDraftCampaign(slug);
        MockHttpSession session = login("influencer@groupdrop.test");

        mockMvc.perform(get("/api/campaigns/" + campaignId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.skus.length()").value(1))
                .andExpect(jsonPath("$.skus[0].allocatedQuantity").value(10));

        mockMvc.perform(get("/api/campaigns/slug/" + slug).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(campaignId));
    }

    // ---- 헬퍼 ----

    private void assertCampaignBadRequest(String requestJson, String expectedCode) throws Exception {
        mockMvc.perform(post("/api/campaigns")
                        .session(login("influencer@groupdrop.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    private Long createDraftCampaign(String slug) throws Exception {
        ProductFixture product = createProduct(uniqueName("초안상품"));
        Long supplierId = supplierId();
        String requestJson = campaignJson(
                "초안 캠페인", slug, supplierId, product.productId(), 19900, 750,
                "[{\"productSkuId\":" + product.sku1Id() + ",\"allocatedQuantity\":10,\"supplyUnitPrice\":17810}]");

        MvcResult result = mockMvc.perform(post("/api/campaigns")
                        .session(login("influencer@groupdrop.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andReturn();
        return readLong(result, "$.id");
    }

    private ProductFixture createProduct(String name) throws Exception {
        MockHttpSession supplierSession = login("supplier@groupdrop.test");
        String requestJson = "{\"name\":\"" + name + "\",\"skus\":[{\"optionName\":\"옵션A\"},{\"optionName\":\"옵션B\"}]}";

        MvcResult result = mockMvc.perform(post("/api/suppliers/me/products")
                        .session(supplierSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andReturn();

        Long productId = readLong(result, "$.id");
        Long sku1Id = readLong(result, "$.skus[0].id");
        Long sku2Id = readLong(result, "$.skus[1].id");
        return new ProductFixture(productId, sku1Id, sku2Id);
    }

    private Long supplierId() {
        User supplierUser = userRepository.findByEmail("supplier@groupdrop.test").orElseThrow();
        Supplier supplier = supplierRepository.findByUserId(supplierUser.getId()).orElseThrow();
        return supplier.getId();
    }

    private MockHttpSession createAndLoginSecondInfluencer() throws Exception {
        String email = "influencer2-" + uniqueToken() + "@groupdrop.test";
        User user = userRepository.save(new User(
                email, passwordEncoder.encode(SEED_PASSWORD), "second-influencer", UserRole.INFLUENCER,
                Instant.now(clock)));
        influencerRepository.save(new Influencer(user, "second-influencer", Instant.now(clock)));
        return login(email);
    }

    private MockHttpSession login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + SEED_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String campaignJson(String name, String slug, Long supplierId, Long productId, long dealPrice,
                                 int commissionRateBp, String skusJson) {
        return "{"
                + "\"name\":\"" + name + "\","
                + "\"slug\":\"" + slug + "\","
                + "\"supplierId\":" + supplierId + ","
                + "\"productId\":" + productId + ","
                + "\"dealPrice\":" + dealPrice + ","
                + "\"startsAt\":\"2026-08-01T00:00:00Z\","
                + "\"endsAt\":\"2026-08-10T00:00:00Z\","
                + "\"perUserPurchaseLimit\":2,"
                + "\"commissionRateBp\":" + commissionRateBp + ","
                + "\"skus\":" + skusJson
                + "}";
    }

    private Long readLong(MvcResult result, String path) throws Exception {
        Object value = JsonPath.read(result.getResponse().getContentAsString(), path);
        return ((Number) value).longValue();
    }

    private String uniqueSlug(String prefix) {
        return prefix + "-" + uniqueToken();
    }

    private String uniqueName(String prefix) {
        return prefix + "-" + uniqueToken();
    }

    private String uniqueToken() {
        return Long.toString(SEQUENCE.incrementAndGet()) + "-" + System.nanoTime();
    }

    private record ProductFixture(Long productId, Long sku1Id, Long sku2Id) {
    }
}
