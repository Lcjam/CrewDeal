package com.groupdrop.payment;

import com.groupdrop.TestcontainersConfiguration;
import com.groupdrop.order.CreateOrderRequest;
import com.groupdrop.order.OrderResponse;
import com.groupdrop.order.OrderService;
import com.groupdrop.outbox.InboxWorker;
import com.groupdrop.outbox.OutboxWorker;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 결제 통합 테스트 공통 기반. 모든 스케줄러를 사실상 정지시키고 워커를 직접 호출하는 이유는
 * "폴링이 언젠가 처리하겠지"에 의존한 어서션이 곧 플레이키 테스트이기 때문이다.
 */
@Import({TestcontainersConfiguration.class, StubPgClientConfiguration.class})
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "groupdrop.campaign-lifecycle-polling-interval=1h",
        "groupdrop.reservation-expiry-polling-interval=1h",
        "groupdrop.outbox-polling-interval=1h",
        "groupdrop.inbox-polling-interval=1h",
        "groupdrop.orphan-payment-sweep-interval=1h",
        "groupdrop.settlement-polling-interval=1h",
        "groupdrop.reconciliation-interval=1h",
        // 17.4 정산 지급 실패 주입. 프로퍼티가 꺼져 있으면 주입 자체가 예외이므로 테스트 컨텍스트에서만 켠다.
        // 하위 클래스가 @SpringBootTest를 다시 선언하면 이 목록이 통째로 대체되므로 여기 한 곳에 모은다
        // (컨텍스트 캐시를 하나로 유지하는 효과도 있다).
        "groupdrop.settlement-failure-injection-enabled=true"
})
public abstract class AbstractPaymentIntegrationTest {

    protected static final String BUYER = "buyer1@groupdrop.test";
    protected static final String ADMIN = "admin@groupdrop.test";
    protected static final String INFLUENCER = "influencer@groupdrop.test";
    protected static final String SUPPLIER = "supplier@groupdrop.test";
    /** 3.3이 강제하는 "나누어떨어지지 않는 금액". 19,900 × 7.5% = 1,492.5원이므로 절사 규칙이 실제로 걸린다. */
    protected static final long DEAL_PRICE = 19_900L;

    @Autowired
    protected OrderService orderService;
    @Autowired
    protected PaymentService paymentService;
    @Autowired
    protected OutboxWorker outboxWorker;
    @Autowired
    protected InboxWorker inboxWorker;
    @Autowired
    protected StubPgClient pgClient;
    @Autowired
    protected WebhookSignatureVerifier verifier;
    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected Clock clock;

    /**
     * 테스트들이 같은 DB를 공유하므로, 앞선 테스트가 남긴 미처리 이벤트를 먼저 비운다.
     * 그래야 각 테스트의 "이벤트 1건" 어서션이 전역 잔여물에 오염되지 않는다.
     */
    @BeforeEach
    protected void resetPgClientAndDrainWorkers() {
        pgClient.reset();
        while (inboxWorker.drain() > 0) {
            // 남은 이벤트 소진
        }
        while (outboxWorker.drain() > 0) {
            // 남은 이벤트 소진
        }
        pgClient.reset();
        // 대사(REC-01)는 전역 스캔이고 PG 대역은 테스트마다 초기화되므로, 앞선 테스트의 결제가 남긴
        // 불일치가 다음 테스트로 흘러간다. 그 잔여물이 지급 전 대조를 막아 가짜 HELD를 만들기 때문에
        // 이벤트와 같은 이유로 여기서 비운다 (실행 이력은 남긴다).
        jdbc.update("DELETE FROM reconciliation_discrepancies");
    }

    /** 주문 1건(수량 quantity)을 만들고 결제 준비 상태로 돌려준다. */
    protected OrderFixture order(int inventory, int quantity) {
        Instant now = Instant.now();
        long supplierId = jdbc.queryForObject("SELECT id FROM suppliers LIMIT 1", Long.class);
        long influencerId = jdbc.queryForObject("SELECT id FROM influencers LIMIT 1", Long.class);
        long productId = jdbc.queryForObject(
                "INSERT INTO products(supplier_id,name,created_at) VALUES(?,?,?) RETURNING id",
                Long.class, supplierId, "payment-test-" + UUID.randomUUID(), Timestamp.from(now));
        long skuId = jdbc.queryForObject(
                "INSERT INTO product_skus(product_id,option_name,created_at) VALUES(?,?,?) RETURNING id",
                Long.class, productId, "sku-" + UUID.randomUUID(), Timestamp.from(now));
        long campaignId = jdbc.queryForObject("""
                INSERT INTO campaigns(name,slug,influencer_id,supplier_id,product_id,status,deal_price,
                    per_user_purchase_limit,starts_at,ends_at,created_at,updated_at)
                VALUES(?,?,?,?,?,'OPEN',?,100,?,?,?,?) RETURNING id
                """, Long.class, "payment-campaign", "payment-" + UUID.randomUUID(), influencerId, supplierId,
                productId, DEAL_PRICE, Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.plusSeconds(3600)),
                Timestamp.from(now), Timestamp.from(now));
        long campaignSkuId = jdbc.queryForObject(
                "INSERT INTO campaign_skus(campaign_id,product_sku_id) VALUES(?,?) RETURNING id",
                Long.class, campaignId, skuId);
        long inventoryId = jdbc.queryForObject("""
                INSERT INTO campaign_inventories(campaign_sku_id,initial_quantity,available_quantity)
                VALUES(?,?,?) RETURNING id
                """, Long.class, campaignSkuId, inventory, inventory);
        long policyId = jdbc.queryForObject("""
                INSERT INTO campaign_policy_versions(campaign_id,version_no,commission_rate_bp,created_at)
                VALUES(?,1,750,?) RETURNING id
                """, Long.class, campaignId, Timestamp.from(now));
        jdbc.update("""
                INSERT INTO campaign_policy_version_items(policy_version_id,campaign_sku_id,supply_unit_price)
                VALUES(?,?,1000)
                """, policyId, campaignSkuId);

        OrderResponse order = orderService.createOrder(BUYER, campaignId, key(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Item(skuId, quantity))));
        return new OrderFixture(order.id(), campaignId, inventoryId, order.totalAmount());
    }

    protected PaymentService.Outcome pay(OrderFixture order) {
        return paymentService.requestPayment(BUYER, order.orderId(), key(),
                new CreatePaymentRequest(order.totalAmount()));
    }

    protected String key() {
        return UUID.randomUUID().toString();
    }

    protected MockHttpSession loginSession(String email) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"groupdrop123!\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    protected String paymentStatus(Long paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId);
    }

    protected String orderStatus(Long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId);
    }

    protected long count(String table, String where) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Long.class);
    }

    protected List<Integer> inventoryOf(long inventoryId) {
        return jdbc.queryForObject("""
                SELECT ARRAY[initial_quantity,available_quantity,reserved_quantity,sold_quantity]
                  FROM campaign_inventories WHERE id=?
                """, (rs, rowNum) -> List.of((Integer[]) rs.getArray(1).getArray()), inventoryId);
    }

    /**
     * 캠페인을 종료 상태로 만들고 종료 시각을 과거로 돌린다. 정산 유예기간(7일) 경과를
     * 시계 조작 없이 재현하기 위한 픽스처다 (SET-02).
     */
    protected void closeCampaign(Long campaignId, long daysAgo) {
        jdbc.update("""
                UPDATE campaigns
                   SET status = 'CLOSED', closed_at = now() - make_interval(days => ?), updated_at = now()
                 WHERE id = ?
                """, (int) daysAgo, campaignId);
    }

    /** 결제 생성 시각을 과거로 돌린다. 고아 스윕 임계 경과를 시계 조작 없이 재현하기 위한 픽스처다. */
    protected void backdatePayment(Long paymentId, long seconds) {
        jdbc.update("UPDATE payments SET created_at = created_at - make_interval(secs => ?) WHERE id=?",
                (double) seconds, paymentId);
    }

    /** PAY-04 페이로드. 요청 JSON은 문자열로 만든다 (Boot 4의 Jackson 3에 클래식 ObjectMapper 빈이 없다). */
    protected String webhookPayload(String eventId, String providerPaymentId, Long orderId, String status,
                                    long amount, String occurredAt) {
        return """
                {"eventId":"%s","providerPaymentId":"%s","orderId":"%d","status":"%s","amount":%d,\
                "occurredAt":"%s"}"""
                .formatted(eventId, providerPaymentId, orderId, status, amount, occurredAt);
    }

    protected String webhookPayload(String eventId, String providerPaymentId, Long orderId, String status,
                                    long amount) {
        return webhookPayload(eventId, providerPaymentId, orderId, status, amount,
                java.time.OffsetDateTime.now().toString());
    }

    /** 서명·타임스탬프를 붙여 실제 HTTP 경로로 웹훅을 보낸다. */
    protected org.springframework.test.web.servlet.ResultActions postWebhook(String body) throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        return postWebhook(body, verifier.sign(timestamp, body), timestamp);
    }

    protected org.springframework.test.web.servlet.ResultActions postWebhook(String body, String signature,
                                                                             String timestamp) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/webhooks/payments")
                .header("X-PG-Signature", signature)
                .header("X-PG-Timestamp", timestamp)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(body));
    }

    protected record OrderFixture(Long orderId, Long campaignId, Long inventoryId, long totalAmount) { }
}
