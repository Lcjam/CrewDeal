package com.groupdrop.refund;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 환불 행의 상태 전이도 예외 없이 조건부 UPDATE다 (10.3의 {@code REQUESTED → COMPLETED|FAILED}).
 * 실행 워커는 at-least-once로 여러 번 돌 수 있으므로, 두 번째 실행은 전이 0건으로 조용히 끝나야 한다.
 */
@Repository
public class RefundRepository {

    private final JdbcTemplate jdbc;

    public RefundRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insertRequested(Long paymentId, Long orderId, long amount, boolean compensation,
                                String reason, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO refunds
                    (payment_id, order_id, status, amount, compensation, reason, requested_at, updated_at)
                VALUES (?, ?, 'REQUESTED', ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, paymentId, orderId, amount, compensation, truncate(reason, 200),
                ts(now), ts(now));
    }

    public Optional<RefundSnapshot> find(Long refundId) {
        return jdbc.query(SELECT + " WHERE r.id = ?", this::map, refundId).stream().findFirst();
    }

    public List<RefundSnapshot> findByPayment(Long paymentId) {
        return jdbc.query(SELECT + " WHERE r.payment_id = ? ORDER BY r.id", this::map, paymentId);
    }

    /** REC-01 해소 대상: 최소 경과 시간이 지난 미완 {@code REQUESTED} 환불. */
    public List<RefundSnapshot> findStaleRequested(Instant threshold, int limit) {
        return jdbc.query(SELECT + " WHERE r.status = 'REQUESTED' AND r.requested_at <= ?"
                + " ORDER BY r.id LIMIT ?", this::map, ts(threshold), limit);
    }

    /** 유효(비FAILED) 환불의 존재 여부. 부분 유니크의 1차 방어이자 API의 409 판정 근거다. */
    public boolean hasEffectiveRefund(Long paymentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM refunds WHERE payment_id = ? AND status <> 'FAILED')
                """, Boolean.class, paymentId));
    }

    public boolean markCompleted(Long refundId, String providerRefundId, Instant completedAt, Instant now) {
        return jdbc.update("""
                UPDATE refunds
                   SET status = 'COMPLETED', provider_refund_id = COALESCE(provider_refund_id, ?),
                       completed_at = COALESCE(completed_at, ?), updated_at = ?
                 WHERE id = ? AND status = 'REQUESTED'
                """, providerRefundId, ts(completedAt), ts(now), refundId) == 1;
    }

    public boolean markFailed(Long refundId, String failureCode, String failureReason, Instant now) {
        return jdbc.update("""
                UPDATE refunds
                   SET status = 'FAILED', failure_code = ?, failure_reason = ?, updated_at = ?
                 WHERE id = ? AND status = 'REQUESTED'
                """, failureCode, truncate(failureReason, 500), ts(now), refundId) == 1;
    }

    /**
     * 10.2: {@code REFUNDING → PAID} 복귀는 <b>PAID 출신 주문에만</b> 허용한다. 출신 판별은 별도 컬럼이
     * 아니라 예약 상태로 한다 — 예약이 {@code CONFIRMED}면 PAID 출신, {@code EXPIRED}면 만료 출신이다.
     * 만료 출신을 PAID로 되돌리면 재고 없는 주문이 정산에 들어간다(실질적 초과 판매).
     */
    public boolean hasConfirmedReservation(Long orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM stock_reservations sr
                      JOIN order_items oi ON oi.id = sr.order_item_id
                     WHERE oi.order_id = ? AND sr.status = 'CONFIRMED'
                )
                """, Boolean.class, orderId));
    }

    /** REF-02 환불 기한: 캠페인 종료 후 30일. 기한이 없으면 회수 배치가 무기한 열려 있는 시스템이 된다. */
    public Optional<Instant> findCampaignEndsAt(Long orderId) {
        return jdbc.query("""
                SELECT c.ends_at FROM orders o JOIN campaigns c ON c.id = o.campaign_id WHERE o.id = ?
                """, (rs, rowNum) -> rs.getTimestamp("ends_at").toInstant(), orderId).stream().findFirst();
    }

    private static final String SELECT = """
            SELECT r.id, r.payment_id, r.order_id, r.status, r.amount, r.compensation, r.reason,
                   r.provider_refund_id, r.failure_code, r.failure_reason, r.requested_at, r.completed_at,
                   p.provider_payment_id
              FROM refunds r JOIN payments p ON p.id = r.payment_id
            """;

    private RefundSnapshot map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new RefundSnapshot(rs.getLong("id"), rs.getLong("payment_id"), rs.getLong("order_id"),
                rs.getString("status"), rs.getLong("amount"), rs.getBoolean("compensation"),
                rs.getString("reason"), rs.getString("provider_refund_id"), rs.getString("provider_payment_id"),
                rs.getString("failure_code"), rs.getString("failure_reason"),
                rs.getTimestamp("requested_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant());
    }

    static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    public record RefundSnapshot(Long id, Long paymentId, Long orderId, String status, long amount,
                                 boolean compensation, String reason, String providerRefundId,
                                 String providerPaymentId, String failureCode, String failureReason,
                                 Instant requestedAt, Instant completedAt) { }
}
