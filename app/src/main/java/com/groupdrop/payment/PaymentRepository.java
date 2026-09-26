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

    /**
     * 결제 준비용 주문 조회. {@code FOR UPDATE}로 주문 행을 잠근다 — 10.2가 결제 {@code READY} 동안의
     * 주문 취소를 허용하므로 결제 준비와 취소가 같은 주문에서 만난다. 두 트랜잭션이 서로 다른 행
     * ({@code payments} / {@code orders})만 건드리면 READ COMMITTED에서 write skew가 성립해
     * "CANCELLED 주문 + SUCCEEDED 결제"가 만들어진다. {@code cancelOrder}도 같은 행을 잠그므로
     * (`OrderRepository.lockOrder`) 둘 중 하나는 반드시 상대의 결과를 보고 판단하게 된다.
     *
     * <p>취소 쪽의 조건부 UPDATE만으로는 부족하다: PostgreSQL의 EvalPlanQual은 행 잠금 해제 후
     * WHERE를 재평가할 때 서브쿼리를 원래 스냅숏으로 평가해, 방금 커밋된 결제를 보지 못한다.
     *
     * <p>락 순서는 캠페인 → 주문이다. 호출자가 {@code CampaignTransactionBarrier}로 캠페인 행을 먼저
     * 잠그고, {@code cancelOrder}는 캠페인 행을 잠그지 않으므로 순환은 생기지 않는다.
     */
    public Optional<OrderForPayment> findOrderForPayment(Long orderId) {
        return jdbc.query("""
                SELECT id, buyer_id, status, total_amount, expires_at
                  FROM orders WHERE id = ? FOR UPDATE
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

    /**
     * {@code READY → PROCESSING} (10.3). 주문이 아직 {@code PENDING_PAYMENT}인지 같은 문장에서 확인한다 —
     * 10.2가 취소를 "결제 READY/FAILED일 때만"으로 허용하므로, 결제가 READY로 대기하는 동안 주문이
     * 정당하게 취소될 수 있다. 그때 PG 호출로 넘어가면 "CANCELLED 주문 + SUCCEEDED 결제"가 되는데
     * 10.2에 {@code CANCELLED → REFUNDING}이 없어 자동 환불 경로가 없다.
     *
     * <p>전이에 실패한 결제는 READY로 남아 고아 스윕이 임계 경과 후 FAILED로 정리한다 (PAY-03).
     */
    public boolean markProcessing(Long paymentId, Instant now) {
        return jdbc.update("""
                UPDATE payments SET status = 'PROCESSING', updated_at = ?
                 WHERE id = ? AND status = 'READY'
                   AND EXISTS (
                       SELECT 1 FROM orders o
                        WHERE o.id = payments.order_id AND o.status = 'PENDING_PAYMENT'
                   )
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

    public boolean markFailed(Long paymentId, String providerPaymentId, String failureCode,
                              String failureReason, Instant now) {
        return jdbc.update("""
                UPDATE payments
                   SET status = 'FAILED', provider_payment_id = COALESCE(provider_payment_id, ?),
                       failure_code = ?, failure_reason = ?, updated_at = ?
                 WHERE id = ? AND status IN ('PROCESSING', 'UNKNOWN')
                """, providerPaymentId, failureCode, truncate(failureReason, 500), ts(now), paymentId) == 1;
    }

    public boolean markUnknown(Long paymentId, Instant now) {
        return jdbc.update("""
                UPDATE payments SET status = 'UNKNOWN', updated_at = ?
                 WHERE id = ? AND status = 'PROCESSING'
                """, ts(now), paymentId) == 1;
    }

    /** 이중 결제 패자 (PAY-01 보상). 보상 환불 실행은 4주차. */
    public boolean markSuperseded(Long paymentId, String providerPaymentId, Instant approvedAt,
                                  String reason, Instant now) {
        return jdbc.update("""
                UPDATE payments
                   SET status = 'SUPERSEDED', provider_payment_id = COALESCE(provider_payment_id, ?),
                       approved_at = COALESCE(approved_at, ?), failure_code = 'DUPLICATE_PAYMENT',
                       failure_reason = ?, updated_at = ?
                 WHERE id = ? AND status IN ('PROCESSING', 'UNKNOWN')
                """, providerPaymentId, ts(approvedAt), truncate(reason, 500), ts(now), paymentId) == 1;
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

    /**
     * PAY-02 고착 선점 회수 대상. 요청 스레드가 완료를 기록하지 못한 결제 멱등 레코드 중, 임계가 지났고
     * 연결된 결제가 더 이상 READY·PROCESSING이 아닌 것(스윕·웹훅·조회·대사가 상태를 정한 것)만 고른다.
     * 멱등 행만 {@code SKIP LOCKED}로 잠그고 결제 행은 잠그지 않는다 — 여러 인스턴스의 스윕이 같은 행을
     * 두 번 회수하지 않고, 결제 락을 먼저 잡는 확정 경로(settle·웹훅)와 순환 대기를 만들지 않기 위함이다.
     * 만료된 키는 만료 판정(409 IDEMPOTENCY_KEY_EXPIRED)이 먼저이므로 대상이 아니다.
     */
    public List<StaleIdempotencyClaim> lockReclaimableIdempotencyClaims(Instant threshold, Instant now, int limit) {
        return jdbc.query("""
                SELECT i.scope, i.idempotency_key, i.resource_id
                  FROM idempotency_requests i
                  JOIN payments p ON p.id = i.resource_id
                 WHERE i.resource_type = 'PAYMENT' AND i.status = 'IN_PROGRESS'
                   AND i.created_at <= ? AND i.expires_at > ?
                   AND p.status NOT IN ('READY', 'PROCESSING')
                 ORDER BY i.id
                 LIMIT ?
                   FOR UPDATE OF i SKIP LOCKED
                """, (rs, rowNum) -> new StaleIdempotencyClaim(rs.getString("scope"),
                rs.getString("idempotency_key"), rs.getLong("resource_id")), ts(threshold), ts(now), limit);
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

    public Optional<OrderForPayment> findOrder(Long orderId) {
        return jdbc.query("""
                SELECT id, buyer_id, status, total_amount, expires_at FROM orders WHERE id = ?
                """, (rs, rowNum) -> new OrderForPayment(rs.getLong("id"), rs.getLong("buyer_id"),
                rs.getString("status"), rs.getLong("total_amount"), rs.getTimestamp("expires_at").toInstant()),
                orderId).stream().findFirst();
    }

    public List<PaymentSnapshot> findByOrderId(Long orderId, int limit) {
        return jdbc.query(SELECT_PAYMENT + " WHERE p.order_id = ? ORDER BY p.id DESC LIMIT ?",
                this::mapPayment, orderId, limit);
    }

    public List<AdminPayment> findAdminPayments(List<String> statuses, Long campaignId, boolean oldestFirst,
                                                int limit) {
        String order = oldestFirst ? "ASC" : "DESC";
        String placeholders = String.join(",", java.util.Collections.nCopies(statuses.size(), "?"));
        java.util.ArrayList<Object> args = new java.util.ArrayList<>(statuses);
        args.add(campaignId);
        args.add(campaignId);
        args.add(limit);
        return jdbc.query("""
                SELECT p.id, p.order_id, c.id AS campaign_id, c.name AS campaign_name, p.status, p.amount,
                       p.provider_payment_id, p.created_at, p.approved_at
                  FROM payments p JOIN orders o ON o.id = p.order_id JOIN campaigns c ON c.id = o.campaign_id
                 WHERE p.status IN (%s) AND (CAST(? AS BIGINT) IS NULL OR c.id = ?)
                 ORDER BY p.id %s LIMIT ?
                """.formatted(placeholders, order), (rs, rowNum) -> new AdminPayment(rs.getLong("id"),
                rs.getLong("order_id"), rs.getLong("campaign_id"), rs.getString("campaign_name"),
                rs.getString("status"), rs.getLong("amount"), rs.getString("provider_payment_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toInstant()),
                args.toArray());
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

    public record StaleIdempotencyClaim(String scope, String key, Long paymentId) { }

    public record OrderForPayment(Long id, Long buyerId, String status, long totalAmount, Instant expiresAt) { }

    public record PaymentSnapshot(Long id, Long orderId, Long buyerId, String status, long amount,
                                  String providerPaymentId, String failureCode, String failureReason,
                                  Instant approvedAt, Instant createdAt) { }

    public record OrphanCandidate(Long id, Long orderId) { }

    public record AdminPayment(Long id, Long orderId, Long campaignId, String campaignName, String status,
                               long amount, String providerPaymentId, Instant createdAt, Instant approvedAt) { }
}
