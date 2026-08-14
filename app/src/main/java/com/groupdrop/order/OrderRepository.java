package com.groupdrop.order;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 경합 카운터는 모두 SQL 한 문장의 조건부 UPDATE로 바꾼다. 조회한 수량을 Java에서 비교한 뒤
 * 저장하는 경로는 제공하지 않는다 (ORD-02, ORD-04).
 */
@Repository
public class OrderRepository {

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean claimIdempotency(String scope, String key, String hash, Instant now) {
        return jdbc.update("""
                INSERT INTO idempotency_requests
                    (scope, idempotency_key, request_hash, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (scope, idempotency_key) DO NOTHING
                """, scope, key, hash, ts(now), ts(now)) == 1;
    }

    public Optional<IdempotencyRecord> findIdempotency(String scope, String key) {
        return jdbc.query("""
                SELECT request_hash, resource_id
                  FROM idempotency_requests
                 WHERE scope = ? AND idempotency_key = ?
                """, (rs, rowNum) -> new IdempotencyRecord(
                rs.getString("request_hash"), nullableLong(rs, "resource_id")), scope, key).stream().findFirst();
    }

    public void completeIdempotency(String scope, String key, Long orderId, Instant now) {
        int updated = jdbc.update("""
                UPDATE idempotency_requests
                   SET resource_type = 'ORDER', resource_id = ?, updated_at = ?
                 WHERE scope = ? AND idempotency_key = ? AND resource_id IS NULL
                """, orderId, ts(now), scope, key);
        if (updated != 1) {
            throw new IllegalStateException("주문 멱등 요청 완료 기록이 유실되었습니다.");
        }
    }

    public Optional<CampaignGate> lockOrderableCampaign(Long campaignId, Instant now) {
        return jdbc.query("""
                SELECT c.deal_price, c.per_user_purchase_limit, cpv.id AS policy_version_id
                  FROM campaigns c
                  JOIN campaign_policy_versions cpv
                    ON cpv.campaign_id = c.id AND cpv.version_no = 1
                 WHERE c.id = ?
                   AND c.status IN ('OPEN', 'SOLD_OUT')
                   AND c.starts_at <= ? AND c.ends_at > ?
                 FOR SHARE OF c
                """, (rs, rowNum) -> new CampaignGate(
                rs.getLong("deal_price"), rs.getInt("per_user_purchase_limit"),
                rs.getLong("policy_version_id")), campaignId, ts(now), ts(now)).stream().findFirst();
    }

    public Optional<CampaignState> findCampaignState(Long campaignId) {
        return jdbc.query("SELECT status, starts_at, ends_at FROM campaigns WHERE id = ?",
                (rs, rowNum) -> new CampaignState(rs.getString("status"),
                        rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant()),
                campaignId).stream().findFirst();
    }

    public void ensurePurchaseCounter(Long campaignId, Long buyerId, Instant now) {
        jdbc.update("""
                INSERT INTO campaign_user_purchase_counters
                    (campaign_id, user_id, quantity, created_at, updated_at)
                VALUES (?, ?, 0, ?, ?)
                ON CONFLICT (campaign_id, user_id) DO NOTHING
                """, campaignId, buyerId, ts(now), ts(now));
    }

    public boolean incrementPurchaseCounter(Long campaignId, Long buyerId, int quantity, int limit, Instant now) {
        return jdbc.update("""
                UPDATE campaign_user_purchase_counters
                   SET quantity = quantity + ?, updated_at = ?
                 WHERE campaign_id = ? AND user_id = ?
                   AND quantity + ? <= ?
                """, quantity, ts(now), campaignId, buyerId, quantity, limit) == 1;
    }

    public Long insertOrder(Long campaignId, Long buyerId, Long policyVersionId, long totalAmount,
                            int totalQuantity, Instant expiresAt, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO orders
                    (campaign_id, buyer_id, policy_version_id, status, total_amount, total_quantity,
                     expires_at, ops_hold, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING_PAYMENT', ?, ?, ?, FALSE, ?, ?)
                RETURNING id
                """, Long.class, campaignId, buyerId, policyVersionId, totalAmount, totalQuantity,
                ts(expiresAt), ts(now), ts(now));
    }

    public Optional<ReservedInventory> reserveInventory(Long campaignId, Long productSkuId, int quantity) {
        return jdbc.query("""
                UPDATE campaign_inventories ci
                   SET available_quantity = ci.available_quantity - ?,
                       reserved_quantity = ci.reserved_quantity + ?
                  FROM campaign_skus cs
                 WHERE ci.campaign_sku_id = cs.id
                   AND cs.campaign_id = ? AND cs.product_sku_id = ?
                   AND ci.available_quantity >= ?
                RETURNING ci.id AS inventory_id, cs.id AS campaign_sku_id
                """, (rs, rowNum) -> new ReservedInventory(
                rs.getLong("inventory_id"), rs.getLong("campaign_sku_id")),
                quantity, quantity, campaignId, productSkuId, quantity).stream().findFirst();
    }

    public boolean campaignContainsSku(Long campaignId, Long productSkuId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM campaign_skus
                     WHERE campaign_id = ? AND product_sku_id = ?
                )
                """, Boolean.class, campaignId, productSkuId));
    }

    public Long insertOrderItem(Long orderId, Long campaignSkuId, int quantity, long unitPrice, long lineAmount) {
        return jdbc.queryForObject("""
                INSERT INTO order_items (order_id, campaign_sku_id, quantity, unit_price, line_amount)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, orderId, campaignSkuId, quantity, unitPrice, lineAmount);
    }

    public void insertReservation(Long orderItemId, Long inventoryId, int quantity, Instant expiresAt, Instant now) {
        jdbc.update("""
                INSERT INTO stock_reservations
                    (order_item_id, campaign_inventory_id, status, quantity, expires_at, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?)
                """, orderItemId, inventoryId, quantity, ts(expiresAt), ts(now), ts(now));
    }

    public Optional<OrderSnapshot> findOrder(Long orderId) {
        List<OrderHeader> headers = jdbc.query("""
                SELECT id, campaign_id, buyer_id, status, total_amount, total_quantity, expires_at
                  FROM orders WHERE id = ?
                """, this::mapHeader, orderId);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        OrderHeader header = headers.getFirst();
        return Optional.of(new OrderSnapshot(header, findItems(orderId)));
    }

    public List<OrderSnapshot> findOrdersByBuyer(Long buyerId) {
        return jdbc.query("""
                SELECT id, campaign_id, buyer_id, status, total_amount, total_quantity, expires_at
                  FROM orders WHERE buyer_id = ? ORDER BY created_at DESC, id DESC
                """, this::mapHeader, buyerId).stream()
                .map(header -> new OrderSnapshot(header, findItems(header.id())))
                .toList();
    }

    private List<OrderItemSnapshot> findItems(Long orderId) {
        return jdbc.query("""
                SELECT oi.id, cs.product_sku_id, oi.quantity, oi.unit_price, oi.line_amount,
                       sr.status AS reservation_status
                  FROM order_items oi
                  JOIN campaign_skus cs ON cs.id = oi.campaign_sku_id
                  JOIN stock_reservations sr ON sr.order_item_id = oi.id
                 WHERE oi.order_id = ? ORDER BY oi.id
                """, (rs, rowNum) -> new OrderItemSnapshot(
                rs.getLong("id"), rs.getLong("product_sku_id"), rs.getInt("quantity"),
                rs.getLong("unit_price"), rs.getLong("line_amount"), rs.getString("reservation_status")), orderId);
    }

    public List<Long> lockExpirableOrderIds(Instant now, int limit) {
        return jdbc.query("""
                SELECT id FROM orders
                 WHERE status = 'PENDING_PAYMENT' AND expires_at <= ?
                 ORDER BY id
                 FOR UPDATE SKIP LOCKED
                 LIMIT ?
                """, (rs, rowNum) -> rs.getLong("id"), ts(now), limit);
    }

    public Optional<ExpiredOrder> expireOrder(Long orderId, Instant now) {
        return jdbc.query("""
                UPDATE orders
                   SET status = 'EXPIRED', updated_at = ?
                 WHERE id = ? AND status = 'PENDING_PAYMENT' AND expires_at <= ?
                RETURNING campaign_id, buyer_id, total_quantity
                """, (rs, rowNum) -> new ExpiredOrder(
                rs.getLong("campaign_id"), rs.getLong("buyer_id"), rs.getInt("total_quantity")),
                ts(now), orderId, ts(now)).stream().findFirst();
    }

    public List<ExpiredReservation> expireReservations(Long orderId, Instant now) {
        return jdbc.query("""
                UPDATE stock_reservations sr
                   SET status = 'EXPIRED', updated_at = ?
                  FROM order_items oi
                 WHERE sr.order_item_id = oi.id AND oi.order_id = ? AND sr.status = 'ACTIVE'
                RETURNING sr.campaign_inventory_id, sr.quantity
                """, (rs, rowNum) -> new ExpiredReservation(
                rs.getLong("campaign_inventory_id"), rs.getInt("quantity")), ts(now), orderId);
    }

    public boolean restoreInventory(Long inventoryId, int quantity) {
        return jdbc.update("""
                UPDATE campaign_inventories
                   SET available_quantity = available_quantity + ?,
                       reserved_quantity = reserved_quantity - ?
                 WHERE id = ? AND reserved_quantity >= ?
                """, quantity, quantity, inventoryId, quantity) == 1;
    }

    public boolean decrementPurchaseCounter(Long campaignId, Long buyerId, int quantity, Instant now) {
        return jdbc.update("""
                UPDATE campaign_user_purchase_counters
                   SET quantity = quantity - ?, updated_at = ?
                 WHERE campaign_id = ? AND user_id = ? AND quantity >= ?
                """, quantity, ts(now), campaignId, buyerId, quantity) == 1;
    }

    private OrderHeader mapHeader(ResultSet rs, int rowNum) throws SQLException {
        return new OrderHeader(rs.getLong("id"), rs.getLong("campaign_id"), rs.getLong("buyer_id"),
                rs.getString("status"), rs.getLong("total_amount"), rs.getInt("total_quantity"),
                rs.getTimestamp("expires_at").toInstant());
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    public record IdempotencyRecord(String requestHash, Long resourceId) { }
    public record CampaignGate(long dealPrice, int purchaseLimit, Long policyVersionId) { }
    public record CampaignState(String status, Instant startsAt, Instant endsAt) { }
    public record ReservedInventory(Long inventoryId, Long campaignSkuId) { }
    public record OrderHeader(Long id, Long campaignId, Long buyerId, String status,
                              long totalAmount, int totalQuantity, Instant expiresAt) { }
    public record OrderItemSnapshot(Long id, Long productSkuId, int quantity, long unitPrice,
                                    long lineAmount, String reservationStatus) { }
    public record OrderSnapshot(OrderHeader header, List<OrderItemSnapshot> items) { }
    public record ExpiredOrder(Long campaignId, Long buyerId, int totalQuantity) { }
    public record ExpiredReservation(Long inventoryId, int quantity) { }
}
