package com.groupdrop.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
    @Autowired
    private JdbcTemplate jdbc;

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

    @Test
    void 목록_API는_역할과_표시용_필드를_지킨다() throws Exception {
        ProductFixture product = createProduct(uniqueName("목록상품"));
        Long campaignId = createDraftCampaign(uniqueSlug("list-campaign"));
        jdbc.update("UPDATE campaigns SET status = 'SOLD_OUT' WHERE id = ?", campaignId);
        jdbc.update("UPDATE campaigns SET starts_at = ?, ends_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now(clock).minusSeconds(60)),
                java.sql.Timestamp.from(Instant.now(clock).plusSeconds(3600)), campaignId);
        jdbc.update("UPDATE campaign_inventories SET available_quantity = 0 WHERE campaign_sku_id IN "
                + "(SELECT id FROM campaign_skus WHERE campaign_id = ?)", campaignId);
        Long draftId = createDraftCampaign(uniqueSlug("hidden-draft"));
        Long reviewingId = createDraftCampaign(uniqueSlug("hidden-reviewing"));
        jdbc.update("UPDATE campaigns SET status = 'REVIEWING' WHERE id = ?", reviewingId);
        Long closedId = createDraftCampaign(uniqueSlug("list-closed"));
        jdbc.update("UPDATE campaigns SET status = 'CLOSED' WHERE id = ?", closedId);

        mockMvc.perform(get("/api/campaigns").session(login("buyer1@groupdrop.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + campaignId + ")].status", hasItem("SOLD_OUT")))
                .andExpect(jsonPath("$[?(@.id == " + campaignId + ")].skus[0].availableQuantity", hasItem(0)))
                .andExpect(jsonPath("$[?(@.id == " + campaignId + ")].commissionRateBp").doesNotExist());
        mockMvc.perform(get("/api/campaigns").session(login("buyer1@groupdrop.test")))
                .andExpect(jsonPath("$[?(@.id == " + draftId + ")]").isEmpty())
                .andExpect(jsonPath("$[?(@.id == " + reviewingId + ")]").isEmpty());
        mockMvc.perform(get("/api/campaigns").param("status", "SOLD_OUT,CLOSED").session(login("buyer1@groupdrop.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + campaignId + ")].status", hasItem("SOLD_OUT")))
                .andExpect(jsonPath("$[?(@.id == " + closedId + ")].status", hasItem("CLOSED")));

        mockMvc.perform(get("/api/campaigns").param("status", "DRAFT").session(login("buyer1@groupdrop.test")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_FILTER"));

        mockMvc.perform(get("/api/influencers/me/campaigns").session(login("supplier@groupdrop.test")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/influencers/me/campaigns").session(login("influencer@groupdrop.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + draftId + ")].status", hasItem("DRAFT")));
        mockMvc.perform(get("/api/admin/campaigns").session(login("buyer1@groupdrop.test")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/products").session(login("influencer@groupdrop.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + product.productId() + ")].supplierId", hasItem(supplierId().intValue())))
                .andExpect(jsonPath("$[?(@.id == " + product.productId() + ")].skus[0].optionName", hasItem("옵션A")));
        mockMvc.perform(get("/api/suppliers/me/products").session(login("supplier@groupdrop.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + product.productId() + ")].supplierName").exists());
        mockMvc.perform(get("/api/products").session(login("buyer1@groupdrop.test")))
                .andExpect(status().isForbidden());

        String email = "profile-missing-" + uniqueToken() + "@groupdrop.test";
        userRepository.save(new User(email, passwordEncoder.encode(SEED_PASSWORD), "missing", UserRole.INFLUENCER,
                Instant.now(clock)));
        mockMvc.perform(get("/api/influencers/me/campaigns").session(login(email)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INFLUENCER_NOT_FOUND"));

        String supplierEmail = "supplier-missing-" + uniqueToken() + "@groupdrop.test";
        userRepository.save(new User(supplierEmail, passwordEncoder.encode(SEED_PASSWORD), "missing", UserRole.SUPPLIER,
                Instant.now(clock)));
        mockMvc.perform(get("/api/suppliers/me/campaigns").session(login(supplierEmail)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));
    }

    @Test
    void 운영자_캠페인_목록은_최신순_100건과_빈_필터를_지킨다() throws Exception {
        Long supplierId = supplierId();
        Long influencerId = influencerRepository.findByUserId(
                userRepository.findByEmail("influencer@groupdrop.test").orElseThrow().getId()).orElseThrow().getId();
        Long productId = createProduct(uniqueName("목록한도상품")).productId();
        for (int index = 0; index < 101; index++) {
            jdbc.update("""
                    INSERT INTO campaigns(name,slug,influencer_id,supplier_id,product_id,status,deal_price,
                        per_user_purchase_limit,starts_at,ends_at,created_at,updated_at)
                    VALUES(?,?,?,?,?,'DRAFT',19900,2,now(),now() + interval '1 day',now(),now())
                    """, "목록 한도 " + index, uniqueSlug("list-limit"), influencerId, supplierId, productId);
        }

        Long latestId = jdbc.queryForObject("SELECT max(id) FROM campaigns WHERE name LIKE '목록 한도 %'", Long.class);
        mockMvc.perform(get("/api/admin/campaigns").param("status", "DRAFT").session(login("admin@groupdrop.test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(100))
                .andExpect(jsonPath("$[0].id").value(latestId));
        mockMvc.perform(get("/api/admin/campaigns").param("status", "CANCELLED").session(login("admin@groupdrop.test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
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

    @Test
    void 상태_조회는_SKU별_남은_재고와_판매_기간_상태_및_카운터_기반_구매_가능_수량을_반환한다() throws Exception {
        ProductFixture product = createProduct(uniqueName("상태조회상품"));
        Long campaignId = createDraftCampaignWithSkus(
                uniqueSlug("campaign-status"), product, 5, 3, 5);
        Long buyerId = userRepository.findByEmail("buyer1@groupdrop.test").orElseThrow().getId();

        jdbc.update("""
                UPDATE campaign_inventories ci
                   SET available_quantity = CASE cs.product_sku_id
                       WHEN ? THEN 2
                       WHEN ? THEN 0
                   END
                  FROM campaign_skus cs
                 WHERE ci.campaign_sku_id = cs.id AND cs.campaign_id = ?
                """, product.sku1Id(), product.sku2Id(), campaignId);
        jdbc.update("""
                INSERT INTO campaign_user_purchase_counters
                    (campaign_id, user_id, quantity, created_at, updated_at)
                VALUES (?, ?, 3, ?, ?)
                """, campaignId, buyerId, java.sql.Timestamp.from(Instant.now(clock)),
                java.sql.Timestamp.from(Instant.now(clock)));

        mockMvc.perform(get("/api/campaigns/" + campaignId + "/status")
                        .session(login("buyer1@groupdrop.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignId").value(campaignId))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.startsAt").value("2026-08-01T00:00:00Z"))
                .andExpect(jsonPath("$.endsAt").value("2026-08-10T00:00:00Z"))
                .andExpect(jsonPath("$.remainingPurchaseQuantity").value(2))
                .andExpect(jsonPath("$.skus.length()").value(2))
                .andExpect(jsonPath("$.skus[0].productSkuId").value(product.sku1Id()))
                .andExpect(jsonPath("$.skus[0].availableQuantity").value(2))
                .andExpect(jsonPath("$.skus[1].productSkuId").value(product.sku2Id()))
                .andExpect(jsonPath("$.skus[1].availableQuantity").value(0));
    }

    @Test
    void 상태_조회는_카운터가_한도를_넘어도_음수_구매_가능_수량을_반환하지_않는다() throws Exception {
        Long campaignId = createDraftCampaign(uniqueSlug("campaign-status-limit"));
        Long buyerId = userRepository.findByEmail("buyer1@groupdrop.test").orElseThrow().getId();

        jdbc.update("""
                INSERT INTO campaign_user_purchase_counters
                    (campaign_id, user_id, quantity, created_at, updated_at)
                VALUES (?, ?, 3, ?, ?)
                """, campaignId, buyerId, java.sql.Timestamp.from(Instant.now(clock)),
                java.sql.Timestamp.from(Instant.now(clock)));

        mockMvc.perform(get("/api/campaigns/" + campaignId + "/status")
                        .session(login("buyer1@groupdrop.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingPurchaseQuantity").value(0));
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

    private Long createDraftCampaignWithSkus(String slug, ProductFixture product, int firstQuantity,
                                               int secondQuantity, int perUserPurchaseLimit) throws Exception {
        String requestJson = "{"
                + "\"name\":\"상태 조회 캠페인\","
                + "\"slug\":\"" + slug + "\","
                + "\"supplierId\":" + supplierId() + ","
                + "\"productId\":" + product.productId() + ","
                + "\"dealPrice\":19900,"
                + "\"startsAt\":\"2026-08-01T00:00:00Z\","
                + "\"endsAt\":\"2026-08-10T00:00:00Z\","
                + "\"perUserPurchaseLimit\":" + perUserPurchaseLimit + ","
                + "\"commissionRateBp\":750,"
                + "\"skus\":["
                + "{\"productSkuId\":" + product.sku1Id() + ",\"allocatedQuantity\":" + firstQuantity
                + ",\"supplyUnitPrice\":17810},"
                + "{\"productSkuId\":" + product.sku2Id() + ",\"allocatedQuantity\":" + secondQuantity
                + ",\"supplyUnitPrice\":17810}]}";

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
