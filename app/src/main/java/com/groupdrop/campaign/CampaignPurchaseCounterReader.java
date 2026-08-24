package com.groupdrop.campaign;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ORD-04 카운터의 읽기 전용 조회 어댑터. 주문 목록 집계로 대체하면 활성 예약을 빠뜨릴 수 있다.
 */
@Repository
public class CampaignPurchaseCounterReader {

    private final JdbcTemplate jdbc;

    public CampaignPurchaseCounterReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int findQuantity(Long campaignId, Long userId) {
        Integer quantity = jdbc.queryForObject("""
                SELECT COALESCE((
                    SELECT quantity
                      FROM campaign_user_purchase_counters
                     WHERE campaign_id = ? AND user_id = ?
                ), 0)
                """, Integer.class, campaignId, userId);
        return quantity == null ? 0 : quantity;
    }
}
