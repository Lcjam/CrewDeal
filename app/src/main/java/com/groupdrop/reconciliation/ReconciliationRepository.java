package com.groupdrop.reconciliation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** REC-01·REC-02 저장소. */
@Repository
public class ReconciliationRepository {

    private final JdbcTemplate jdbc;

    public ReconciliationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── 실행 이력 ────────────────────────────────────────────────────────────────────

    public Long startRun(int minAgeMinutes, Instant now) {
        try {
            return jdbc.queryForObject("""
                    INSERT INTO reconciliation_runs (status, min_age_minutes, started_at)
                    VALUES ('RUNNING', ?, ?)
                    RETURNING id
                    """, Long.class, minAgeMinutes, ts(now));
        } catch (DuplicateKeyException exception) {
            throw new ReconciliationAlreadyRunningException(exception);
        }
    }

    /** 프로세스 강제 종료 등으로 남은 RUNNING 세대를 명시적으로 FAILED 처리한다. */
    public int failStaleRuns(Instant staleBefore, Instant now) {
        return jdbc.update("""
                UPDATE reconciliation_runs
                   SET status = 'FAILED', finished_at = ?,
                       error = '실행 주기의 2배를 넘겨 중단된 RUNNING 세대를 자동 회수했습니다.'
                 WHERE status = 'RUNNING' AND started_at <= ?
                """, ts(now), ts(staleBefore));
    }

    public boolean completeRun(Long runId, int providerCount, int internalCount, int mismatchCount,
                               int resolvedCount, int ledgerUnbalancedCount, Instant now) {
        return jdbc.update("""
                UPDATE reconciliation_runs
                   SET status = 'COMPLETED', provider_transaction_count = ?, internal_payment_count = ?,
                       mismatch_count = ?, resolved_count = ?, ledger_unbalanced_count = ?, finished_at = ?
                 WHERE id = ? AND status = 'RUNNING'
                """, providerCount, internalCount, mismatchCount, resolvedCount, ledgerUnbalancedCount,
                ts(now), runId) == 1;
    }

    public void failRun(Long runId, String error, Instant now) {
        jdbc.update("""
                UPDATE reconciliation_runs SET status = 'FAILED', error = ?, finished_at = ?
                 WHERE id = ? AND status = 'RUNNING'
                """, truncate(error, 1000), ts(now), runId);
    }

    public boolean isRunning(Long runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT status = 'RUNNING' FROM reconciliation_runs WHERE id = ?", Boolean.class, runId));
    }

    public Optional<Run> findRun(Long runId) {
        return jdbc.query("""
                SELECT id, status, min_age_minutes, provider_transaction_count, internal_payment_count,
                       mismatch_count, resolved_count, ledger_unbalanced_count, started_at, finished_at, error
                  FROM reconciliation_runs WHERE id = ?
                """, (rs, rowNum) -> new Run(rs.getLong("id"), rs.getString("status"),
                rs.getInt("min_age_minutes"), rs.getInt("provider_transaction_count"),
                rs.getInt("internal_payment_count"), rs.getInt("mismatch_count"), rs.getInt("resolved_count"),
                rs.getInt("ledger_unbalanced_count"), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")), rs.getString("error")), runId).stream().findFirst();
    }

    public List<Run> findRecentRuns(int limit) {
        return jdbc.query("""
                SELECT id, status, min_age_minutes, provider_transaction_count, internal_payment_count,
                       mismatch_count, resolved_count, ledger_unbalanced_count, started_at, finished_at, error
                  FROM reconciliation_runs ORDER BY id DESC LIMIT ?
                """, (rs, rowNum) -> new Run(rs.getLong("id"), rs.getString("status"),
                rs.getInt("min_age_minutes"), rs.getInt("provider_transaction_count"), rs.getInt("internal_payment_count"),
                rs.getInt("mismatch_count"), rs.getInt("resolved_count"), rs.getInt("ledger_unbalanced_count"),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")), rs.getString("error")), limit);
    }

    // ── 대사 입력 ────────────────────────────────────────────────────────────────────

    /**
     * 대사 대상 내부 결제. {@code FAILED}이면서 PG 식별자가 없는 결제는 제외한다 — PG에 도달한 적이
     * 없는 결제이므로 "PG에 매칭이 없다"가 정상이고, 이것을 {@code MISSING_PROVIDER}로 올리면
     * 거절 결제가 하나만 있어도 그 캠페인의 정산이 영구히 HELD된다 (SET-02).
     */
    public List<InternalPayment> findReconcilablePayments(Instant cutoff) {
        return jdbc.query("""
                SELECT p.id, p.order_id, p.status, p.amount, p.provider_payment_id,
                       p.approved_at, p.created_at,
                       COALESCE((SELECT SUM(r.amount) FROM refunds r
                                  WHERE r.payment_id = p.id AND r.status = 'COMPLETED'), 0) AS refunded_amount,
                       (SELECT MAX(r.completed_at) FROM refunds r
                         WHERE r.payment_id = p.id AND r.status = 'COMPLETED') AS refund_completed_at
                  FROM payments p
                 WHERE p.created_at <= ?
                   AND NOT (p.status = 'FAILED' AND p.provider_payment_id IS NULL)
                 ORDER BY p.id
                """, this::mapInternalPayment, ts(cutoff));
    }

    /** 해소 대상: 최소 경과 시간이 지난 비최종 결제 (REC-01의 해소 단계). */
    public List<InternalPayment> findNonFinalPayments(Instant cutoff) {
        return jdbc.query("""
                SELECT p.id, p.order_id, p.status, p.amount, p.provider_payment_id,
                       NULL::TIMESTAMPTZ AS approved_at, p.created_at, 0 AS refunded_amount,
                       NULL::TIMESTAMPTZ AS refund_completed_at
                  FROM payments p
                 WHERE p.created_at <= ? AND p.status IN ('PROCESSING', 'UNKNOWN')
                 ORDER BY p.id
                """, this::mapInternalPayment, ts(cutoff));
    }

    // ── 불일치 ───────────────────────────────────────────────────────────────────────

    /**
     * 미해결 불일치는 유형·대상당 1행으로 접는다. 대사는 주기적으로 반복 실행되므로 매 실행마다 새 행을
     * 쌓으면 운영자 목록이 곧바로 쓸모없어진다. 재검출은 {@code last_seen_run_id} 갱신으로 표현한다.
     */
    public void upsertOpen(Long runId, DiscrepancyType type, Long paymentId, Long orderId,
                           String providerPaymentId, String internalStatus, String providerStatus,
                           Long internalAmount, Long providerAmount, String detail,
                           Instant subjectOccurredAt, Instant now) {
        jdbc.update("""
                WITH run_fence AS MATERIALIZED (
                    SELECT id FROM reconciliation_runs
                     WHERE id = ? AND status = 'RUNNING'
                     FOR SHARE
                )
                INSERT INTO reconciliation_discrepancies
                    (run_id, last_seen_run_id, discrepancy_type, payment_id, order_id, provider_payment_id,
                     internal_status, provider_status, internal_amount, provider_amount, detail, status,
                     subject_occurred_at, detected_at, updated_at)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ? FROM run_fence
                ON CONFLICT (discrepancy_type, COALESCE(provider_payment_id, ''), COALESCE(payment_id, -1))
                    WHERE status = 'OPEN'
                DO UPDATE SET last_seen_run_id = EXCLUDED.last_seen_run_id,
                              detail = EXCLUDED.detail,
                              subject_occurred_at = EXCLUDED.subject_occurred_at,
                              updated_at = EXCLUDED.updated_at
                WHERE reconciliation_discrepancies.last_seen_run_id < EXCLUDED.last_seen_run_id
                """, runId, runId, runId, type.name(), paymentId, orderId, providerPaymentId, internalStatus,
                providerStatus, internalAmount, providerAmount, truncate(detail, 1000), ts(subjectOccurredAt),
                ts(now), ts(now));
    }

    /**
     * 이번 실행에서 재검출되지 않은 미해결 건을 자동 해소한다. 조건이 사라진 불일치를 운영자가 손으로
     * 지우게 하면 목록이 과거의 잔해로 채워진다.
     */
    public List<Long> resolveDisappeared(Long runId, Instant cutoff, Instant now) {
        return jdbc.query("""
                WITH run_fence AS MATERIALIZED (
                    SELECT id FROM reconciliation_runs
                     WHERE id = ? AND status = 'RUNNING'
                     FOR SHARE
                )
                UPDATE reconciliation_discrepancies d
                   SET status = 'RESOLVED', resolved_at = ?, updated_at = ?,
                       resolution_note = COALESCE(resolution_note, '다음 대사에서 재검출되지 않아 자동 해소')
                  FROM run_fence
                 WHERE d.status = 'OPEN' AND d.last_seen_run_id < ?
                   AND d.subject_occurred_at IS NOT NULL AND d.subject_occurred_at <= ?
                RETURNING d.id
                """, (rs, rowNum) -> rs.getLong("id"), runId, ts(now), ts(now), runId, ts(cutoff));
    }

    public boolean resolve(Long discrepancyId, String note, Instant now) {
        return jdbc.update("""
                UPDATE reconciliation_discrepancies
                   SET status = 'RESOLVED', resolution_note = ?, resolved_at = ?, updated_at = ?
                 WHERE id = ? AND status = 'OPEN'
                """, truncate(note, 1000), ts(now), ts(now), discrepancyId) == 1;
    }

    public Optional<Discrepancy> findDiscrepancy(Long id) {
        return jdbc.query(DISCREPANCY_SELECT + " WHERE d.id = ?", this::mapDiscrepancy, id)
                .stream().findFirst();
    }

    public List<Discrepancy> findDiscrepancies(String status, int limit) {
        if (status == null || status.isBlank()) {
            return jdbc.query(DISCREPANCY_SELECT + " ORDER BY d.id DESC LIMIT ?", this::mapDiscrepancy, limit);
        }
        return jdbc.query(DISCREPANCY_SELECT + " WHERE d.status = ? ORDER BY d.id DESC LIMIT ?",
                this::mapDiscrepancy, status, limit);
    }

    /** 16.4 운영 요약: 미해결 대사 불일치 건수. */
    public long countOpen() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM reconciliation_discrepancies WHERE status = 'OPEN'", Long.class);
    }

    /** 16.4 운영 요약: 미확정 UNKNOWN 결제 수. */
    public long countUnknownPayments() {
        return jdbc.queryForObject("SELECT count(*) FROM payments WHERE status = 'UNKNOWN'", Long.class);
    }

    private static final String DISCREPANCY_SELECT = """
            SELECT d.id, d.run_id, d.last_seen_run_id, d.discrepancy_type, d.payment_id, d.order_id,
                   d.provider_payment_id, d.internal_status, d.provider_status, d.internal_amount,
                   d.provider_amount, d.detail, d.status, d.resolution_note, d.subject_occurred_at,
                   d.detected_at, d.resolved_at
              FROM reconciliation_discrepancies d
            """;

    private InternalPayment mapInternalPayment(ResultSet rs, int rowNum) throws SQLException {
        return new InternalPayment(rs.getLong("id"), rs.getLong("order_id"), rs.getString("status"),
                rs.getLong("amount"), rs.getString("provider_payment_id"), rs.getLong("refunded_amount"),
                instant(rs.getTimestamp("approved_at")), instant(rs.getTimestamp("refund_completed_at")),
                instant(rs.getTimestamp("created_at")));
    }

    private Discrepancy mapDiscrepancy(ResultSet rs, int rowNum) throws SQLException {
        return new Discrepancy(rs.getLong("id"), rs.getLong("run_id"), rs.getLong("last_seen_run_id"),
                DiscrepancyType.valueOf(rs.getString("discrepancy_type")),
                rs.getObject("payment_id", Long.class), rs.getObject("order_id", Long.class),
                rs.getString("provider_payment_id"), rs.getString("internal_status"),
                rs.getString("provider_status"), rs.getObject("internal_amount", Long.class),
                rs.getObject("provider_amount", Long.class), rs.getString("detail"), rs.getString("status"),
                rs.getString("resolution_note"), instant(rs.getTimestamp("subject_occurred_at")),
                instant(rs.getTimestamp("detected_at")),
                instant(rs.getTimestamp("resolved_at")));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record Run(Long id, String status, int minAgeMinutes, int providerTransactionCount,
                      int internalPaymentCount, int mismatchCount, int resolvedCount,
                      int ledgerUnbalancedCount, Instant startedAt, Instant finishedAt, String error) { }

    public record InternalPayment(Long id, Long orderId, String status, long amount, String providerPaymentId,
                                  long refundedAmount, Instant approvedAt, Instant refundCompletedAt,
                                  Instant createdAt) {

        /** 10.3의 최종 상태. 비최종은 해소 단계의 대상이지 분류 대상이 아니다. */
        public boolean isFinal() {
            return switch (status) {
                case "SUCCEEDED", "FAILED", "REFUNDED", "SUPERSEDED" -> true;
                default -> false;
            };
        }

        /** 내부 기록이 "PG에 성공한 결제가 있다"고 주장하는가. MISSING_PROVIDER 판정의 전제다. */
        public boolean claimsProviderSuccess() {
            return switch (status) {
                case "SUCCEEDED", "REFUNDING", "REFUNDED", "SUPERSEDED" -> true;
                default -> providerPaymentId != null;
            };
        }
    }

    public record Discrepancy(Long id, Long runId, Long lastSeenRunId, DiscrepancyType type, Long paymentId,
                              Long orderId, String providerPaymentId, String internalStatus,
                              String providerStatus, Long internalAmount, Long providerAmount, String detail,
                              String status, String resolutionNote, Instant subjectOccurredAt,
                              Instant detectedAt, Instant resolvedAt) { }

    public static class ReconciliationAlreadyRunningException extends RuntimeException {

        public ReconciliationAlreadyRunningException(Throwable cause) {
            super("이미 실행 중인 대사가 있습니다.", cause);
        }
    }

    public static class ReconciliationLeaseLostException extends RuntimeException {

        public ReconciliationLeaseLostException(Long runId) {
            super("대사 실행 소유권을 잃었습니다: " + runId);
        }
    }
}
