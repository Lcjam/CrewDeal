package com.groupdrop.common;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 캠페인별 결제 생성·확정과 정산 확정의 짧은 DB 직렬화 경계.
 *
 * <p>외부 PG 호출을 감싸지 않고 결과를 기록하는 짧은 트랜잭션에서만 캠페인 행을 잠근다.
 * 따라서 ADR-003의 외부 호출 트랜잭션 분리를 유지하면서, 정산 확정이 결제 생성·확정 사이의
 * 서로 다른 스냅숏을 보고 유효 주문을 누락하는 경쟁을 막는다 (SET-02).
 */
@Component
public class CampaignTransactionBarrier {

    private final JdbcTemplate jdbc;

    public CampaignTransactionBarrier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CampaignLock> lockByOrderId(Long orderId) {
        return jdbc.query("""
                SELECT c.id, c.status, c.closed_at, c.ends_at
                  FROM campaigns c
                  JOIN orders o ON o.campaign_id = c.id
                 WHERE o.id = ?
                 FOR UPDATE OF c
                """, (rs, rowNum) -> new CampaignLock(rs.getLong("id"), rs.getString("status"),
                instant(rs.getTimestamp("closed_at")), rs.getTimestamp("ends_at").toInstant()), orderId)
                .stream().findFirst();
    }

    public Optional<CampaignLock> lockByCampaignId(Long campaignId) {
        return jdbc.query("""
                SELECT id, status, closed_at, ends_at FROM campaigns WHERE id = ? FOR UPDATE
                """, (rs, rowNum) -> new CampaignLock(rs.getLong("id"), rs.getString("status"),
                instant(rs.getTimestamp("closed_at")), rs.getTimestamp("ends_at").toInstant()), campaignId)
                .stream().findFirst();
    }

    public CampaignLock requireByOrderId(Long orderId) {
        return lockByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("주문의 캠페인을 찾을 수 없습니다: " + orderId));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record CampaignLock(Long campaignId, String status, Instant closedAt, Instant endsAt) {

        /** CAM-05: 자연 종료 CLOSED는 기존 주문 결제를 허용하고 조기 강제 종료만 거부한다. */
        public boolean allowsExistingOrderPayment() {
            if ("OPEN".equals(status) || "SOLD_OUT".equals(status)) {
                return true;
            }
            return "CLOSED".equals(status) && closedAt != null && !closedAt.isBefore(endsAt);
        }
    }
}
