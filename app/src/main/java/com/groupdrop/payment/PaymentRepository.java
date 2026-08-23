package com.groupdrop.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 결제 상태 전이는 예외 없이 조건부 UPDATE다 (10.3 허용 전이표). 전이 메서드가 false를 돌려주면
 * "이미 다른 경로가 확정지었다"는 뜻이며, 호출자는 이를 오류가 아니라 경쟁으로 다뤄야 한다 —
 * 웹훅·조회·스윕이 같은 결제를 동시에 확정하려 드는 것이 정상 동작이기 때문이다 (PAY-03).
 */
@Repository
public class PaymentRepository {

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OrderForPayment> findOrderForPayment(Long orderId) {
        return jdbc.query("""
                SELECT id, buyer_id, status, total_amount, expires_at
                  FROM orders WHERE id = ?
                """, (rs, rowNum) -> new OrderForPayment(rs.getLong("id"), rs.getLong("buyer_id"),
                rs.getString("status"), rs.getLong("total_amount"), rs.getTimestamp("expires_at").toInstant()),
                orderId).stream().findFirst();
    }

    /** PAY-01 이중 결제 예방. 비최종 결제가 있으면 신규 결제 생성을 거부한다. */
    public boolean hasNonFinalPayment(Long orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM payments
                     WHERE order_id = ?
                       AND status IN ('READY', 'PROCESSING', 'UNKNOWN', 'REFUNDING')
                )
                """, Boolean.class, orderId));
    }

    public Long insertReadyPayment(Long orderId, long amount, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO payments (order_id, status, amount, created_at, updated_at)
                VALUES (?, 'READY', ?, ?, ?)
                RETURNING id
                """, Long.class, orderId, amount, ts(now), ts(now));
    }

    public Long insertAttempt(Long paymentId, String merchantPaymentId, long amount, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO payment_attempts
                    (payment_id, merchant_payment_id, amount, outcome, requested_at)
                VALUES (?, ?, ?, 'REQUESTED', ?)
                RETURNING id
                """, Long.class, paymentId, merchantPaymentId, amount, ts(now));
    }

    public void finishAttempt(Long attemptId, String outcome, String errorDetail, Instant now) {
        jdbc.update("""
                UPDATE payment_attempts
                   SET outcome = ?, error_detail = ?, finished_at = ?
                 WHERE id = ?
                """, outcome, truncate(errorDetail, 500), ts(now), attemptId);
    }

    public boolean markProcessing(Long paymentId, Instant now) {
        return jdbc.update("""
                UPDATE payments SET status = 'PROCESSING', updated_at = ?
                 WHERE id = ? AND status = 'READY'
                """, ts(now), paymentId) == 1;
    }

    /**
     * PROCESSING·UNKNOWN → SUCCEEDED. 두 출발 상태를 한 문장에 두는 이유는 요청 스레드·웹훅·조회가
     * 각각 다른 출발 상태에서 같은 확정을 시도하기 때문이다. 둘 다 10.3의 허용 전이다.
     */
    public boolean markSucceeded(Long paymentId, String providerPaymentId, Instant approvedAt, Instant now) {
        return jdbc.update("""
                UPDATE payments
                   SET status = 'SUCCEEDED', provider_payment_id = COALESCE(provider_payment_id, ?),
                       approved_at = COALESCE(approved_at, ?), updated_at = ?
                 WHERE id = ? AND status IN ('PROCESSING', 'UNKNOWN')
                """, providerPaymentId, ts(approvedAt), ts(now), paymentId) == 1;
    }

    public boolean markFailed(Long paymentId, String failureCode, String failureReason, Instant now) {
        return jdbc.update("""
                UPDATE payments
                   SET status = 'FAILED', failure_code = ?, failure_reason = ?, updated_at = ?
                 WHERE id = ? AND status IN ('PROCESSING', 'UNKNOWN')
                """, failureCode, truncate(failureReason, 500), ts(now), paymentId) == 1;
    }

    public boolean markUnknown(Long paymentId, Instant now) {
        return jdbc.update("""
                UPDATE payments SET status = 'UNKNOWN', updated_at = ?
                 WHERE id = ? AND status = 'PROCESSING'
                """, ts(now), paymentId) == 1;
    }

    /** 이중 결제 패자 (PAY-01 보상). 보상 환불 실행은 4주차. */
    public boolean markSuperseded(Long paymentId, String providerPaymentId, String reason, Instant now) {
        return jdbc.update("""
                UPDATE payments
                   SET status = 'SUPERSEDED', provider_payment_id = COALESCE(provider_payment_id, ?),
                       failure_code = 'DUPLICATE_PAYMENT', failure_reason = ?, updated_at = ?
                 WHERE id = ? AND status IN ('PROCESSING', 'UNKNOWN')
                """, providerPaymentId, truncate(reason, 500), ts(now), paymentId) == 1;
    }

    /** REF-02 환불 접수. {@code REFUNDING}은 환불 결과 불명 상태를 겸한다 (10.3, PAY-03). */
    public boolean markRefunding(Long paymentId, Instant now) {
        return jdbc.update("""
                UPDATE payments SET status = 'REFUNDING', updated_at = ?
                 WHERE id = ? AND status = 'SUCCEEDED'
                """, ts(now), paymentId) == 1;
    }

    public boolean markRefunded(Long paymentId, Instant now) {
        return jdbc.update("""
                UPDATE payments SET status = 'REFUNDED', updated_at = ?
                 WHERE id = ? AND status = 'REFUNDING'
                """, ts(now), paymentId) == 1;
    }

    /** 10.3: {@code REFUNDING → SUCCEEDED} 복귀는 PG가 환불 실패를 <b>명시</b>했을 때만 허용한다. */
    public boolean markRefundFailed(Long paymentId, Instant now) {
        return jdbc.update("""
                UPDATE payments SET status = 'SUCCEEDED', updated_at = ?
                 WHERE id = ? AND status = 'REFUNDING'
                """, ts(now), paymentId) == 1;
    }

    /** PG 호출 기록 없이 임계 시간을 넘긴 READY 고아. PG에 기록 자체가 없으므로 FAILED 확정이 안전하다. */
    public boolean markReadyOrphanFailed(Long paymentId, Instant now) {
        return jdbc.update("""
                UPDATE payments
                   SET status = 'FAILED', failure_code = 'ORPHAN_READY',
                       failure_reason = 'PG 호출 기록 없이 임계 시간이 경과했습니다.', updated_at = ?
                 WHERE id = ? AND status = 'READY'
                   AND NOT EXISTS (SELECT 1 FROM payment_attempts WHERE payment_id = ?)
                """, ts(now), paymentId, paymentId) == 1;
    }

    public List<OrphanCandidate> findStaleProcessing(Instant threshold, int limit) {
        return jdbc.query("""
                SELECT id, order_id FROM payments
                 WHERE status = 'PROCESSING' AND created_at <= ?
                 ORDER BY id LIMIT ?
                """, this::mapOrphan, ts(threshold), limit);
    }

    public List<OrphanCandidate> findStaleReadyWithoutAttempt(Instant threshold, int limit) {
        return jdbc.query("""
                SELECT p.id, p.order_id FROM payments p
                 WHERE p.status = 'READY' AND p.created_at <= ?
                   AND NOT EXISTS (SELECT 1 FROM payment_attempts a WHERE a.payment_id = p.id)
                 ORDER BY p.id LIMIT ?
                """, this::mapOrphan, ts(threshold), limit);
    }

    public Optional<PaymentSnapshot> findPayment(Long paymentId) {
        return jdbc.query(SELECT_PAYMENT + " WHERE p.id = ?", this::mapPayment, paymentId).stream().findFirst();
    }

    public Optional<PaymentSnapshot> findByProviderPaymentId(String providerPaymentId) {
        return jdbc.query(SELECT_PAYMENT + " WHERE p.provider_payment_id = ?", this::mapPayment,
                providerPaymentId).stream().findFirst();
    }

    /**
     * providerPaymentId를 아직 모르는 결제(성공 응답 유실)를 웹훅과 잇는 경로.
     * PAY-01의 "주문당 비최종 결제 1건" 규칙이 성립하므로 주문 기준으로 유일하게 지목된다.
     */
    public List<PaymentSnapshot> findNonFinalByOrderId(Long orderId) {
        return jdbc.query(SELECT_PAYMENT + """
                 WHERE p.order_id = ? AND p.status IN ('READY', 'PROCESSING', 'UNKNOWN')
                 ORDER BY p.id DESC
                """, this::mapPayment, orderId);
    }

    public Optional<String> findLatestMerchantPaymentId(Long paymentId) {
        return jdbc.query("""
                SELECT merchant_payment_id FROM payment_attempts
                 WHERE payment_id = ? ORDER BY id DESC LIMIT 1
                """, (rs, rowNum) -> rs.getString("merchant_payment_id"), paymentId).stream().findFirst();
    }

    public boolean existsSucceededForOrder(Long orderId, Long excludingPaymentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM payments
                     WHERE order_id = ? AND id <> ?
                       AND status IN ('SUCCEEDED', 'REFUNDING', 'REFUNDED')
                )
                """, Boolean.class, orderId, excludingPaymentId));
    }

    private static final String SELECT_PAYMENT = """
            SELECT p.id, p.order_id, p.status, p.amount, p.provider_payment_id,
                   p.failure_code, p.failure_reason, p.approved_at, p.created_at, o.buyer_id
              FROM payments p JOIN orders o ON o.id = p.order_id
            """;

    private PaymentSnapshot mapPayment(ResultSet rs, int rowNum) throws SQLException {
        Timestamp approvedAt = rs.getTimestamp("approved_at");
        return new PaymentSnapshot(rs.getLong("id"), rs.getLong("order_id"), rs.getLong("buyer_id"),
                rs.getString("status"), rs.getLong("amount"), rs.getString("provider_payment_id"),
                rs.getString("failure_code"), rs.getString("failure_reason"),
                approvedAt == null ? null : approvedAt.toInstant(), rs.getTimestamp("created_at").toInstant());
    }

    private OrphanCandidate mapOrphan(ResultSet rs, int rowNum) throws SQLException {
        return new OrphanCandidate(rs.getLong("id"), rs.getLong("order_id"));
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

    public record OrderForPayment(Long id, Long buyerId, String status, long totalAmount, Instant expiresAt) { }

    public record PaymentSnapshot(Long id, Long orderId, Long buyerId, String status, long amount,
                                  String providerPaymentId, String failureCode, String failureReason,
                                  Instant approvedAt, Instant createdAt) { }

    public record OrphanCandidate(Long id, Long orderId) { }
}
