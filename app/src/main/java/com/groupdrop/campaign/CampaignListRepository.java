package com.groupdrop.campaign;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 목록 전용 단일 집계 조회. 단건 응답 조립의 N+1 조회를 사용하지 않는다. */
@Repository
public class CampaignListRepository {

    private static final int LIST_LIMIT = 100;
    private final JdbcTemplate jdbc;

    public CampaignListRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> find(List<CampaignStatus> statuses, Long influencerId, Long supplierId) {
        List<Object> arguments = new ArrayList<>(statuses.stream().map(Enum::name).toList());
        StringBuilder filter = new StringBuilder("c.status IN (" + "?,".repeat(statuses.size() - 1) + "?)");
        if (influencerId != null) {
            filter.append(" AND c.influencer_id = ?");
            arguments.add(influencerId);
        }
        if (supplierId != null) {
            filter.append(" AND c.supplier_id = ?");
            arguments.add(supplierId);
        }
        arguments.add(LIST_LIMIT);
        return jdbc.query("""
                WITH listed_campaigns AS (
                    SELECT c.id, c.name, c.slug, c.status, c.supplier_id, c.product_id, c.deal_price,
                           c.starts_at, c.ends_at, c.per_user_purchase_limit, c.rejection_reason
                      FROM campaigns c
                     WHERE %s
                     ORDER BY c.id DESC
                     LIMIT ?
                )
                SELECT c.id, c.name, c.slug, c.status, c.supplier_id, s.name AS supplier_name,
                       c.product_id, p.name AS product_name, c.deal_price, c.starts_at, c.ends_at,
                       c.per_user_purchase_limit, c.rejection_reason, cpv.commission_rate_bp,
                       ps.id AS product_sku_id, ps.option_name, ci.available_quantity
                  FROM listed_campaigns c
                  JOIN suppliers s ON s.id = c.supplier_id
                  JOIN products p ON p.id = c.product_id
                  LEFT JOIN campaign_policy_versions cpv ON cpv.campaign_id = c.id AND cpv.version_no = 1
                  LEFT JOIN campaign_skus cs ON cs.campaign_id = c.id
                  LEFT JOIN product_skus ps ON ps.id = cs.product_sku_id
                  LEFT JOIN campaign_inventories ci ON ci.campaign_sku_id = cs.id
                 ORDER BY c.id DESC, ps.id ASC
                """.formatted(filter), (rs, rowNum) -> new Row(
                rs.getLong("id"), rs.getString("name"), rs.getString("slug"),
                CampaignStatus.valueOf(rs.getString("status")), rs.getLong("supplier_id"),
                rs.getString("supplier_name"), rs.getLong("product_id"), rs.getString("product_name"),
                rs.getLong("deal_price"), instant(rs.getTimestamp("starts_at")), instant(rs.getTimestamp("ends_at")),
                rs.getInt("per_user_purchase_limit"), rs.getObject("commission_rate_bp", Integer.class),
                rs.getString("rejection_reason"), rs.getObject("product_sku_id", Long.class),
                rs.getString("option_name"), rs.getObject("available_quantity", Integer.class)), arguments.toArray());
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp.toInstant();
    }

    public record Row(Long id, String name, String slug, CampaignStatus status, Long supplierId, String supplierName,
                      Long productId, String productName, long dealPrice, Instant startsAt, Instant endsAt,
                      int perUserPurchaseLimit, Integer commissionRateBp, String rejectionReason,
                      Long productSkuId, String optionName, Integer availableQuantity) { }
}
