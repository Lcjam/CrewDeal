package com.groupdrop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.groupdrop.TestcontainersConfiguration;
import com.groupdrop.common.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "groupdrop.campaign-lifecycle-polling-interval=1h",
        "groupdrop.reservation-expiry-polling-interval=1h"
})
@AutoConfigureMockMvc
class OrderIntegrationTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private ReservationExpiryService expiryService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;

    @Test
    void 주문_성공은_주문_항목_예약_재고_구매카운터를_원자적으로_반영한다() throws Exception {
        CampaignFixture fixture = campaign(5, 4, 10);

        OrderResponse response = orderService.createOrder("buyer1@groupdrop.test", fixture.campaignId(),
                key(), new CreateOrderRequest(List.of(
                        new CreateOrderRequest.Item(fixture.sku2Id(), 3),
                        new CreateOrderRequest.Item(fixture.sku1Id(), 2))));

        assertThat(response.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(response.totalQuantity()).isEqualTo(5);
        assertThat(response.totalAmount()).isEqualTo(99_500);
        assertThat(response.items()).hasSize(2).allMatch(item -> item.reservationStatus().equals("ACTIVE"));
        // 요청 순서와 무관하게 SKU 잠금 순서는 하나여야 한다. 역순 주문 간 데드락을 막는다.
        assertThat(response.items()).extracting(OrderResponse.Item::productSkuId)
                .containsExactly(fixture.sku1Id(), fixture.sku2Id());
        assertThat(count("orders", "id = " + response.id())).isEqualTo(1);
        assertThat(count("order_items", "order_id = " + response.id())).isEqualTo(2);
        assertThat(count("stock_reservations sr JOIN order_items oi ON oi.id=sr.order_item_id",
                "oi.order_id = " + response.id() + " AND sr.status='ACTIVE'")).isEqualTo(2);
        assertInventory(fixture.inventory1Id(), 5, 3, 2, 0);
        assertInventory(fixture.inventory2Id(), 4, 1, 3, 0);
        assertThat(counter(fixture.campaignId(), buyerId("buyer1@groupdrop.test"))).isEqualTo(5);

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "inventory_reservation_success_total")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "inventory_update_duration_seconds")));
    }

    @Test
    void 다중_SKU_중_하나가_부족하면_주문과_모든_변경을_롤백한다() {
        CampaignFixture fixture = campaign(5, 0, 10);

        assertThatThrownBy(() -> orderService.createOrder("buyer1@groupdrop.test", fixture.campaignId(),
                key(), new CreateOrderRequest(List.of(
                        new CreateOrderRequest.Item(fixture.sku1Id(), 2),
                        new CreateOrderRequest.Item(fixture.sku2Id(), 1)))))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("INVENTORY_SOLD_OUT"));

        assertThat(count("orders", "campaign_id = " + fixture.campaignId())).isZero();
        assertThat(count("order_items oi JOIN orders o ON o.id=oi.order_id",
                "o.campaign_id = " + fixture.campaignId())).isZero();
        assertThat(count("stock_reservations sr JOIN campaign_inventories ci ON ci.id=sr.campaign_inventory_id "
                + "JOIN campaign_skus cs ON cs.id=ci.campaign_sku_id",
                "cs.campaign_id = " + fixture.campaignId())).isZero();
        assertInventory(fixture.inventory1Id(), 5, 5, 0, 0);
        assertInventory(fixture.inventory2Id(), 0, 0, 0, 0);
        assertThat(count("campaign_user_purchase_counters", "campaign_id = " + fixture.campaignId())).isZero();
    }

    @RepeatedTest(5)
    void 동일_사용자의_동시_주문은_구매_제한을_넘지_않는다() throws Exception {
        int limit = 10;
        int quantity = 3;
        CampaignFixture fixture = campaign(100, 0, limit);
        List<Result> results = concurrentOrders(fixture,
                java.util.Collections.nCopies(12, "buyer1@groupdrop.test"), quantity);
        long successes = results.stream().filter(Result::success).count();

        assertThat(results).allMatch(result -> result.success() || result.code().equals("PURCHASE_LIMIT_EXCEEDED"));
        assertThat(successes).isEqualTo(limit / quantity);
        assertThat(counter(fixture.campaignId(), buyerId("buyer1@groupdrop.test"))).isLessThanOrEqualTo(limit);
        assertThat(count("orders", "campaign_id = " + fixture.campaignId())).isEqualTo(successes);
        assertInventory(fixture.inventory1Id(), 100, 100 - (int) successes * quantity,
                (int) successes * quantity, 0);
    }

    @RepeatedTest(5)
    void 동시_주문은_재고를_초과_예약하지_않는다() throws Exception {
        CampaignFixture fixture = campaign(10, 0, 30);
        List<Result> results = concurrentOrders(fixture, buyers(30), 1);
        long successes = results.stream().filter(Result::success).count();

        assertThat(results).allMatch(result -> result.success() || result.code().equals("INVENTORY_SOLD_OUT"));
        assertThat(successes).isEqualTo(10);
        assertThat(count("orders", "campaign_id = " + fixture.campaignId())).isEqualTo(successes);
        assertInventory(fixture.inventory1Id(), 10, 0, 10, 0);
    }

    @Test
    void 예약_만료는_반복_실행돼도_재고와_구매카운터를_한번만_복구한다() {
        CampaignFixture fixture = campaign(1, 0, 10);
        OrderResponse order = orderService.createOrder("buyer1@groupdrop.test", fixture.campaignId(), key(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Item(fixture.sku1Id(), 1))));
        Instant past = Instant.now().minusSeconds(5);
        jdbc.update("UPDATE orders SET expires_at = ? WHERE id = ?", Timestamp.from(past), order.id());
        jdbc.update("UPDATE stock_reservations SET expires_at = ? WHERE order_item_id IN "
                + "(SELECT id FROM order_items WHERE order_id = ?)", Timestamp.from(past), order.id());

        assertThat(expiryService.expireDueReservations()).isEqualTo(1);
        assertThat(expiryService.expireDueReservations()).isZero();

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, order.id()))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM stock_reservations sr JOIN order_items oi "
                + "ON oi.id=sr.order_item_id WHERE oi.order_id=?", String.class, order.id()))
                .isEqualTo("EXPIRED");
        assertInventory(fixture.inventory1Id(), 1, 1, 0, 0);
        assertThat(counter(fixture.campaignId(), buyerId("buyer1@groupdrop.test"))).isZero();
    }

    @Test
    void 같은_주문_멱등키는_예약을_중복하지_않고_다른_본문_재사용은_거부한다() {
        CampaignFixture fixture = campaign(5, 0, 10);
        String key = key();
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new CreateOrderRequest.Item(fixture.sku1Id(), 2)));

        OrderResponse first = orderService.createOrder("buyer1@groupdrop.test", fixture.campaignId(), key, request);
        OrderResponse replay = orderService.createOrder("buyer1@groupdrop.test", fixture.campaignId(), key, request);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(count("orders", "campaign_id = " + fixture.campaignId())).isEqualTo(1);
        assertInventory(fixture.inventory1Id(), 5, 3, 2, 0);
        assertThatThrownBy(() -> orderService.createOrder("buyer1@groupdrop.test", fixture.campaignId(), key,
                new CreateOrderRequest(List.of(new CreateOrderRequest.Item(fixture.sku1Id(), 1)))))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 같은_멱등키와_동일_SKU_수량도_다른_캠페인에는_재사용할_수_없다() {
        CampaignFixture firstCampaign = campaign(5, 0, 10);
        CampaignFixture secondCampaign = campaignForExistingSku(firstCampaign.sku1Id(), 5, 10);
        String key = key();
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new CreateOrderRequest.Item(firstCampaign.sku1Id(), 1)));

        OrderResponse first = orderService.createOrder(
                "buyer1@groupdrop.test", firstCampaign.campaignId(), key, request);

        assertThatThrownBy(() -> orderService.createOrder(
                "buyer1@groupdrop.test", secondCampaign.campaignId(), key, request))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(first.campaignId()).isEqualTo(firstCampaign.campaignId());
        assertThat(count("orders", "campaign_id = " + secondCampaign.campaignId())).isZero();
        assertInventory(secondCampaign.inventory1Id(), 5, 5, 0, 0);
    }

    private List<Result> concurrentOrders(CampaignFixture fixture, List<String> buyers, int quantity) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(buyers.size());
        try (ExecutorService executor = Executors.newFixedThreadPool(buyers.size())) {
            List<Future<Result>> futures = new ArrayList<>();
            for (String buyer : buyers) {
                futures.add(executor.submit(() -> concurrentOrder(fixture, buyer, quantity, barrier)));
            }
            List<Result> results = new ArrayList<>();
            for (Future<Result> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private Result concurrentOrder(CampaignFixture fixture, String buyer, int quantity, CyclicBarrier barrier) {
        try {
            barrier.await();
            orderService.createOrder(buyer, fixture.campaignId(), key(),
                    new CreateOrderRequest(List.of(new CreateOrderRequest.Item(fixture.sku1Id(), quantity))));
            return new Result("SUCCESS");
        } catch (ApiException exception) {
            return new Result(exception.getCode());
        } catch (InterruptedException | BrokenBarrierException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private List<String> buyers(int count) {
        Instant now = Instant.now();
        return IntStream.range(0, count).mapToObj(index -> {
            String email = "stock-buyer-" + UUID.randomUUID() + "@groupdrop.test";
            jdbc.update("""
                    INSERT INTO users(email,password_hash,display_name,role,created_at)
                    SELECT ?, password_hash, ?, 'BUYER', ? FROM users WHERE email='buyer1@groupdrop.test'
                    """, email, "stock buyer " + index, Timestamp.from(now));
            return email;
        }).toList();
    }

    private CampaignFixture campaign(int inventory1, int inventory2, int purchaseLimit) {
        Instant now = Instant.now();
        long supplierId = supplierId();
        long influencerId = jdbc.queryForObject("SELECT id FROM influencers LIMIT 1", Long.class);
        long productId = jdbc.queryForObject("INSERT INTO products(supplier_id,name,created_at) VALUES(?,?,?) RETURNING id",
                Long.class, supplierId, "order-test-" + UUID.randomUUID(), Timestamp.from(now));
        long sku1 = sku(productId, "sku-1-" + UUID.randomUUID(), now);
        long sku2 = sku(productId, "sku-2-" + UUID.randomUUID(), now);
        long campaignId = jdbc.queryForObject("""
                INSERT INTO campaigns(name,slug,influencer_id,supplier_id,product_id,status,deal_price,
                    per_user_purchase_limit,starts_at,ends_at,created_at,updated_at)
                VALUES(?,?,?,?,?,'OPEN',19900,?,?,?,?,?) RETURNING id
                """, Long.class, "order-campaign", "order-" + UUID.randomUUID(), influencerId, supplierId,
                productId, purchaseLimit, Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.plusSeconds(3600)),
                Timestamp.from(now), Timestamp.from(now));
        long campaignSku1 = campaignSku(campaignId, sku1);
        long campaignSku2 = campaignSku(campaignId, sku2);
        long inventoryId1 = inventory(campaignSku1, inventory1);
        long inventoryId2 = inventory(campaignSku2, inventory2);
        long policy = jdbc.queryForObject("INSERT INTO campaign_policy_versions"
                + "(campaign_id,version_no,commission_rate_bp,created_at) VALUES(?,1,750,?) RETURNING id",
                Long.class, campaignId, Timestamp.from(now));
        jdbc.update("INSERT INTO campaign_policy_version_items(policy_version_id,campaign_sku_id,supply_unit_price) "
                + "VALUES(?,?,1000),(?,?,1000)", policy, campaignSku1, policy, campaignSku2);
        return new CampaignFixture(campaignId, sku1, sku2, inventoryId1, inventoryId2);
    }

    private CampaignFixture campaignForExistingSku(long skuId, int inventory, int purchaseLimit) {
        Instant now = Instant.now();
        long productId = jdbc.queryForObject("SELECT product_id FROM product_skus WHERE id=?", Long.class, skuId);
        long supplierId = jdbc.queryForObject("SELECT supplier_id FROM products WHERE id=?", Long.class, productId);
        long influencerId = jdbc.queryForObject("SELECT id FROM influencers LIMIT 1", Long.class);
        long campaignId = jdbc.queryForObject("""
                INSERT INTO campaigns(name,slug,influencer_id,supplier_id,product_id,status,deal_price,
                    per_user_purchase_limit,starts_at,ends_at,created_at,updated_at)
                VALUES(?,?,?,?,?,'OPEN',19900,?,?,?,?,?) RETURNING id
                """, Long.class, "same-sku-campaign", "same-sku-" + UUID.randomUUID(), influencerId, supplierId,
                productId, purchaseLimit, Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.plusSeconds(3600)),
                Timestamp.from(now), Timestamp.from(now));
        long campaignSkuId = campaignSku(campaignId, skuId);
        long inventoryId = inventory(campaignSkuId, inventory);
        long policyId = jdbc.queryForObject("INSERT INTO campaign_policy_versions"
                + "(campaign_id,version_no,commission_rate_bp,created_at) VALUES(?,1,750,?) RETURNING id",
                Long.class, campaignId, Timestamp.from(now));
        jdbc.update("INSERT INTO campaign_policy_version_items(policy_version_id,campaign_sku_id,supply_unit_price) "
                + "VALUES(?,?,1000)", policyId, campaignSkuId);
        return new CampaignFixture(campaignId, skuId, skuId, inventoryId, inventoryId);
    }

    private long sku(long productId, String option, Instant now) {
        return jdbc.queryForObject("INSERT INTO product_skus(product_id,option_name,created_at) VALUES(?,?,?) RETURNING id",
                Long.class, productId, option, Timestamp.from(now));
    }

    private long campaignSku(long campaignId, long skuId) {
        return jdbc.queryForObject("INSERT INTO campaign_skus(campaign_id,product_sku_id) VALUES(?,?) RETURNING id",
                Long.class, campaignId, skuId);
    }

    private long inventory(long campaignSkuId, int quantity) {
        return jdbc.queryForObject("INSERT INTO campaign_inventories(campaign_sku_id,initial_quantity,available_quantity) "
                + "VALUES(?,?,?) RETURNING id", Long.class, campaignSkuId, quantity, quantity);
    }

    private long supplierId() {
        return jdbc.queryForObject("SELECT id FROM suppliers LIMIT 1", Long.class);
    }

    private long buyerId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }

    private int counter(long campaignId, long buyerId) {
        return jdbc.queryForObject("SELECT quantity FROM campaign_user_purchase_counters WHERE campaign_id=? AND user_id=?",
                Integer.class, campaignId, buyerId);
    }

    private long count(String table, String where) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Long.class);
    }

    private void assertInventory(long id, int initial, int available, int reserved, int sold) {
        List<Integer> values = jdbc.queryForObject("SELECT ARRAY[initial_quantity,available_quantity,reserved_quantity,sold_quantity] "
                + "FROM campaign_inventories WHERE id=?", (rs, rowNum) -> {
            Integer[] array = (Integer[]) rs.getArray(1).getArray();
            return List.of(array);
        }, id);
        assertThat(values).containsExactly(initial, available, reserved, sold);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private record CampaignFixture(long campaignId, long sku1Id, long sku2Id,
                                   long inventory1Id, long inventory2Id) { }
    private record Result(String code) {
        boolean success() {
            return code.equals("SUCCESS");
        }
    }
}
