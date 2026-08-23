package com.groupdrop.settlement;

import com.groupdrop.ledger.LedgerAccount;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 정산 저장소 (SET-01~03, 10.5). 모든 상태 전이는 조건부 UPDATE이며, 허용 전이표(10.5)는
 * 각 UPDATE의 WHERE 절에 그대로 적혀 있다 — 애플리케이션에서 현재 상태를 읽고 판단한 뒤 쓰는
 * read-modify-write를 쓰지 않는 이유는 그 사이가 곧 경쟁 구간이기 때문이다 (ADR-001).
 */
@Repository
public class SettlementRepository {

    private final JdbcTemplate jdbc;

    public SettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── 캠페인 상태와 SETTLING 진입 전제 ───────────────────────────────────────────────

    /** 종료 후 정산 유예기간이 지난 캠페인 (13.4: 이벤트가 아니라 스케줄러 스캔). */
    public List<Long> findCampaignsDueForSettlement(Instant graceCutoff, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM campaigns
                 WHERE status = 'CLOSED' AND closed_at IS NOT NULL AND closed_at <= ?
                 ORDER BY closed_at
                 LIMIT ?
                """, Long.class, ts(graceCutoff), limit);
    }

    /**
     * 진입 전제 ①: 미처리 {@code payment.finalized}·{@code refund.completed} 이벤트 (SET-02).
     * 처리되지 않은 확정 이벤트가 남아 있으면 "결제는 SUCCEEDED인데 주문 미전이"인 주문이
     * 소리 없이 정산에서 빠진다.
     */
    public long countUnprocessedFinalizationEvents(Long campaignId) {
        return jdbc.queryForObject("""
                SELECT count(*)
                  FROM outbox_events oe
                 WHERE oe.status = 'PENDING'
                   AND ((oe.event_type = 'payment.finalized' AND EXISTS (
                            SELECT 1 FROM payments p JOIN orders o ON o.id = p.order_id
                             WHERE p.id = oe.aggregate_id AND o.campaign_id = ?))
                     OR (oe.event_type = 'refund.completed' AND EXISTS (
                            SELECT 1 FROM refunds r JOIN orders o ON o.id = r.order_id
                             WHERE r.id = oe.aggregate_id AND o.campaign_id = ?)))
                """, Long.class, campaignId, campaignId);
    }

    /**
     * 진입 전제 ②: 미확정 결제 (SET-02). 드레인은 이벤트 발행 <b>전</b>의 미확정 결제를 잡지 못하므로
     * 두 전제는 별개다. 늦게 확정된 대금이 동결 스냅숏 밖에서 고립되는 것을 막는다.
     */
    public long countUnfinalizedPayments(Long campaignId) {
        return jdbc.queryForObject("""
                SELECT count(*)
                  FROM payments p JOIN orders o ON o.id = p.order_id
                 WHERE o.campaign_id = ? AND p.status IN ('PROCESSING', 'UNKNOWN')
                """, Long.class, campaignId);
    }

    /** {@code CLOSED → SETTLING}. 확정 시각을 함께 심어 동결 스냅숏의 기준을 남긴다 (SET-02). */
    public boolean enterSettling(Long campaignId, Instant now) {
        return jdbc.update("""
                UPDATE campaigns
                   SET status = 'SETTLING', settlement_determined_at = ?, updated_at = ?
                 WHERE id = ? AND status = 'CLOSED'
                """, ts(now), ts(now), campaignId) == 1;
    }

    /**
     * 소속 정상 배치가 전부 {@code COMPLETED}면 {@code SETTLING → SETTLED}.
     * 배치가 0개인 캠페인도 이 조건을 만족하므로 즉시 SETTLED가 된다 (SET-02).
     */
    public boolean settleCampaignIfAllBatchesCompleted(Long campaignId, Instant now) {
        return jdbc.update("""
                UPDATE campaigns c
                   SET status = 'SETTLED', updated_at = ?
                 WHERE c.id = ? AND c.status = 'SETTLING'
                   AND NOT EXISTS (
                       SELECT 1 FROM settlement_batches b
                        WHERE b.campaign_id = c.id AND b.batch_type = 'SETTLEMENT'
                          AND b.status <> 'COMPLETED')
                """, ts(now), campaignId) == 1;
    }

    public Optional<CampaignSettlementContext> findCampaignContext(Long campaignId) {
        return jdbc.query("""
                SELECT c.id, c.status, c.settlement_determined_at, c.supplier_id, c.influencer_id
                  FROM campaigns c WHERE c.id = ?
                """, (rs, rowNum) -> new CampaignSettlementContext(rs.getLong("id"), rs.getString("status"),
                instant(rs.getTimestamp("settlement_determined_at")), rs.getLong("supplier_id"),
                rs.getLong("influencer_id")), campaignId).stream().findFirst();
    }

    // ── SET-01 정산 대상과 금액 (원천은 원장 하나) ────────────────────────────────────

    /**
     * 수령 주체의 지급 예정금 계정 잔액을 <b>주문 단위</b>로 집계한다 (ADR-008: 정산액은 주문별 원장
     * 금액의 합). 환불된 주문은 역분개로 잔액이 0이 되어 {@code HAVING > 0}에서 자연히 빠진다 —
     * "환불 제외" 조건을 따로 쓰지 않는 이유이며, 그렇게 써야 원천이 둘로 갈라지지 않는다.
     *
     * <p>{@code occurred_at <= cutoff}는 SET-02의 환불 귀속 컷오프다. 확정 이후 유입된 환불은
     * 배치 금액을 바꾸지 않고 SET-03 회수 배치로 처리한다.
     *
     * <p>{@code ops_hold} 주문은 제외한다 (13.1) — 운영자 확인 대상을 자동 지급에 태우지 않는다.
     */
    public List<OrderPayable> findPayableOrders(Long campaignId, LedgerAccount account, Instant cutoff) {
        return jdbc.query("""
                SELECT t.order_id AS order_id,
                       SUM(CASE WHEN e.side = 'CREDIT' THEN e.amount ELSE -e.amount END) AS amount
                  FROM ledger_entries e
                  JOIN ledger_transactions t ON t.id = e.transaction_id
                  JOIN ledger_accounts a ON a.id = e.account_id
                  JOIN orders o ON o.id = t.order_id
                 WHERE t.campaign_id = ?
                   AND a.code = ?
                   AND t.transaction_type IN ('PAYMENT', 'REFUND')
                   AND t.occurred_at <= ?
                   AND o.ops_hold = FALSE
                 GROUP BY t.order_id
                HAVING SUM(CASE WHEN e.side = 'CREDIT' THEN e.amount ELSE -e.amount END) > 0
                 ORDER BY t.order_id
                """, (rs, rowNum) -> new OrderPayable(rs.getLong("order_id"), rs.getLong("amount")),
                campaignId, account.name(), ts(cutoff));
    }

    /**
     * 주문 항목별 배분 기준값. 공급사는 항목별 공급 단가 × 수량이 정확한 분해이고, 인플루언서 커미션은
     * 주문 단위 절사값이라 항목별 정확 분해가 존재하지 않으므로 상품 금액 비율로 배분한다
     * (잔여는 마지막 항목이 흡수 — 합계는 항상 원장의 주문 금액과 정확히 일치한다).
     */
    public List<OrderItemBasis> findOrderItemBases(Long orderId) {
        return jdbc.query("""
                SELECT oi.id AS order_item_id, oi.line_amount,
                       COALESCE(pvi.supply_unit_price, 0) * oi.quantity AS supply_amount
                  FROM order_items oi
                  JOIN orders o ON o.id = oi.order_id
                  LEFT JOIN campaign_policy_version_items pvi
                         ON pvi.policy_version_id = o.policy_version_id
                        AND pvi.campaign_sku_id = oi.campaign_sku_id
                 WHERE oi.order_id = ?
                 ORDER BY oi.id
                """, (rs, rowNum) -> new OrderItemBasis(rs.getLong("order_item_id"), rs.getLong("line_amount"),
                rs.getLong("supply_amount")), orderId);
    }

    // ── 배치 쓰기 ────────────────────────────────────────────────────────────────────

    public Long insertBatch(Long campaignId, PayeeType payeeType, Long payeeId, BatchType batchType,
                            SettlementBatchStatus status, long totalAmount, Instant determinedAt, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO settlement_batches
                    (campaign_id, payee_type, payee_id, batch_type, status, total_amount,
                     determined_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, campaignId, payeeType.name(), payeeId, batchType.name(), status.name(),
                totalAmount, ts(determinedAt), ts(now), ts(now));
    }

    public void insertItem(Long batchId, PayeeType payeeType, Long orderId, Long orderItemId,
                           long amount, Instant now) {
        jdbc.update("""
                INSERT INTO settlement_items
                    (batch_id, payee_type, order_id, order_item_id, amount, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, batchId, payeeType.name(), orderId, orderItemId, amount, ts(now));
    }

    /** 검증 통과: {@code PENDING → READY} (10.5). */
    public boolean markReady(Long batchId, Instant now) {
        return jdbc.update("""
                UPDATE settlement_batches
                   SET status = 'READY', hold_reason = NULL, updated_at = ?
                 WHERE id = ? AND status = 'PENDING'
                """, ts(now), batchId) == 1;
    }

    /**
     * 불일치 해소: {@code HELD → PENDING} (10.5). READY로 곧장 보내지 않는 이유는, 해소 후에도
     * 지급 전 대조(SET-02)를 다시 통과해야 하기 때문이다. 동결 스냅숏은 재산정하지 않는다.
     */
    public boolean releaseHold(Long batchId, Instant now) {
        return jdbc.update("""
                UPDATE settlement_batches
                   SET status = 'PENDING', hold_reason = NULL, updated_at = ?
                 WHERE id = ? AND status = 'HELD'
                """, ts(now), batchId) == 1;
    }

    /** 대사 불일치·원장 이상 또는 운영자 보류: {@code PENDING·READY → HELD} (10.5). */
    public boolean markHeld(Long batchId, String reason, Instant now) {
        return jdbc.update("""
                UPDATE settlement_batches
                   SET status = 'HELD', hold_reason = ?, updated_at = ?
                 WHERE id = ? AND status IN ('PENDING', 'READY')
                """, reason, ts(now), batchId) == 1;
    }

    /**
     * 지급 실행 선점: {@code READY·FAILED → PROCESSING} (10.5의 READY→PROCESSING, FAILED→PROCESSING).
     * 조건부 UPDATE이므로 다중 인스턴스에서도 한 배치를 두 워커가 동시에 지급하지 않는다.
     */
    public boolean claimForProcessing(Long batchId, Instant now) {
        return jdbc.update("""
                UPDATE settlement_batches
                   SET status = 'PROCESSING', attempts = attempts + 1,
                       failure_code = NULL, failure_reason = NULL, updated_at = ?
                 WHERE id = ? AND status IN ('READY', 'FAILED')
                """, ts(now), batchId) == 1;
    }

    public boolean markCompleted(Long batchId, Instant now) {
        return jdbc.update("""
                UPDATE settlement_batches
                   SET status = 'COMPLETED', completed_at = ?, updated_at = ?
                 WHERE id = ? AND status = 'PROCESSING'
                """, ts(now), ts(now), batchId) == 1;
    }

    public boolean markFailed(Long batchId, String failureCode, String failureReason, Instant now) {
        return jdbc.update("""
                UPDATE settlement_batches
                   SET status = 'FAILED', failure_code = ?, failure_reason = ?, updated_at = ?
                 WHERE id = ? AND status = 'PROCESSING'
                """, failureCode, failureReason, ts(now), batchId) == 1;
    }

    // ── 배치 조회 ────────────────────────────────────────────────────────────────────

    public Optional<Batch> findBatch(Long batchId) {
        return jdbc.query(BATCH_SELECT + " WHERE b.id = ?", this::mapBatch, batchId).stream().findFirst();
    }

    public List<Batch> findBatchesOfCampaign(Long campaignId) {
        return jdbc.query(BATCH_SELECT + " WHERE b.campaign_id = ? ORDER BY b.id", this::mapBatch, campaignId);
    }

    /** 스케줄러가 지급을 실행할 대상. PENDING(검증 대기)과 READY(지급 대기)를 함께 집는다. */
    public List<Batch> findBatchesInStatus(List<SettlementBatchStatus> statuses, int limit) {
        Object[] args = new Object[statuses.size() + 1];
        for (int i = 0; i < statuses.size(); i++) {
            args[i] = statuses.get(i).name();
        }
        args[statuses.size()] = limit;
        return jdbc.query(BATCH_SELECT + " WHERE b.status IN (" + placeholders(statuses.size())
                        + ") ORDER BY b.id LIMIT ?",
                this::mapBatch, args);
    }

    public List<Long> findBatchOrderIds(Long batchId) {
        return jdbc.queryForList(
                "SELECT DISTINCT order_id FROM settlement_items WHERE batch_id = ? ORDER BY order_id",
                Long.class, batchId);
    }

    public List<BatchItem> findBatchItems(Long batchId) {
        return jdbc.query("""
                SELECT id, order_id, order_item_id, amount FROM settlement_items
                 WHERE batch_id = ? ORDER BY id
                """, (rs, rowNum) -> new BatchItem(rs.getLong("id"), rs.getLong("order_id"),
                rs.getLong("order_item_id"), rs.getLong("amount")), batchId);
    }

    /** 14.3 수령 주체별 확정 정산 내역. 컷 4가 발동해도 이 최소 형태는 남는다. */
    public List<Batch> findBatchesOfPayee(PayeeType payeeType, Long payeeId) {
        return jdbc.query(BATCH_SELECT + " WHERE b.payee_type = ? AND b.payee_id = ? ORDER BY b.id DESC",
                this::mapBatch, payeeType.name(), payeeId);
    }

    // ── 지급 전 원장 대조 (SET-02) ────────────────────────────────────────────────────

    /**
     * 구성 검증: 배치 소속 주문의 지급 예정금 분개 합. 계정 총잔액과 비교하지 않는 이유는
     * {@code ops_hold} 등으로 정당하게 제외된 분개가 가짜 HELD를 유발하기 때문이다 (SET-02).
     */
    public long sumLedgerPayable(List<Long> orderIds, LedgerAccount account, Instant cutoff) {
        if (orderIds.isEmpty()) {
            return 0L;
        }
        Object[] args = new Object[orderIds.size() + 2];
        for (int i = 0; i < orderIds.size(); i++) {
            args[i] = orderIds.get(i);
        }
        args[orderIds.size()] = account.name();
        args[orderIds.size() + 1] = ts(cutoff);
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN e.side = 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
                  FROM ledger_entries e
                  JOIN ledger_transactions t ON t.id = e.transaction_id
                  JOIN ledger_accounts a ON a.id = e.account_id
                 WHERE t.order_id IN (%s)
                   AND a.code = ?
                   AND t.transaction_type IN ('PAYMENT', 'REFUND')
                   AND t.occurred_at <= ?
                """.formatted(placeholders(orderIds.size())), Long.class, args);
    }

    /** 균형 검증: 배치 소속 주문의 거래 중 차변 합 ≠ 대변 합인 건수 (LED-01, S5). */
    public long countUnbalancedTransactions(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return 0L;
        }
        return jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT t.id
                      FROM ledger_transactions t
                      LEFT JOIN ledger_entries e ON e.transaction_id = t.id
                     WHERE t.order_id IN (%s)
                     GROUP BY t.id
                    HAVING COALESCE(SUM(CASE WHEN e.side = 'DEBIT'  THEN e.amount ELSE 0 END), 0)
                        <> COALESCE(SUM(CASE WHEN e.side = 'CREDIT' THEN e.amount ELSE 0 END), 0)
                        OR count(e.id) = 0
                ) unbalanced
                """.formatted(placeholders(orderIds.size())), Long.class, orderIds.toArray());
    }

    /** 미해결 대사 불일치 (SET-02). 캠페인 소속 결제·주문에 걸린 OPEN 건만 센다. */
    public long countOpenDiscrepancies(Long campaignId) {
        return jdbc.queryForObject("""
                SELECT count(*)
                  FROM reconciliation_discrepancies d
                 WHERE d.status = 'OPEN'
                   AND (EXISTS (SELECT 1 FROM payments p JOIN orders o ON o.id = p.order_id
                                 WHERE p.id = d.payment_id AND o.campaign_id = ?)
                     OR EXISTS (SELECT 1 FROM orders o
                                 WHERE o.id = d.order_id AND o.campaign_id = ?))
                """, Long.class, campaignId, campaignId);
    }

    // ── SET-03 회수 ──────────────────────────────────────────────────────────────────

    /**
     * 환불된 주문의 정산 항목. 여기서 행이 나오는 것이 곧 "지급 배치에 실제 포함되었다"이며,
     * 회수 여부의 판정 기준이다 (SET-03). 지급된 적 없는 주문은 행이 없어 회수 대상이 아니다.
     */
    public List<SettledItem> findSettledItemsOfOrder(Long orderId) {
        return jdbc.query("""
                SELECT si.id, si.batch_id, si.order_item_id, si.amount, b.payee_type, b.payee_id,
                       b.campaign_id, b.status AS batch_status
                  FROM settlement_items si
                  JOIN settlement_batches b ON b.id = si.batch_id
                 WHERE si.order_id = ?
                 ORDER BY si.id
                """, (rs, rowNum) -> new SettledItem(rs.getLong("id"), rs.getLong("batch_id"),
                rs.getLong("order_item_id"), rs.getLong("amount"),
                PayeeType.valueOf(rs.getString("payee_type")), rs.getLong("payee_id"),
                rs.getLong("campaign_id"), SettlementBatchStatus.valueOf(rs.getString("batch_status"))),
                orderId);
    }

    /**
     * @return 새 조정 ID. 같은 환불의 재전달이면 비어 있다 — 멱등은 유니크 제약에 맡긴다 (13.4).
     */
    public Optional<Long> insertAdjustmentIfAbsent(Long settlementItemId, Long batchId, Long refundId,
                                                   long amount, Instant now) {
        return jdbc.query("""
                INSERT INTO settlement_adjustments
                    (settlement_item_id, batch_id, refund_id, amount, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (settlement_item_id, refund_id) DO NOTHING
                RETURNING id
                """, (rs, rowNum) -> rs.getLong("id"), settlementItemId, batchId, refundId, amount, ts(now))
                .stream().findFirst();
    }

    public int attachRecoveryBatch(List<Long> adjustmentIds, Long recoveryBatchId, Instant now) {
        if (adjustmentIds.isEmpty()) {
            return 0;
        }
        Object[] args = new Object[adjustmentIds.size() + 2];
        args[0] = recoveryBatchId;
        args[1] = ts(now);
        for (int i = 0; i < adjustmentIds.size(); i++) {
            args[i + 2] = adjustmentIds.get(i);
        }
        return jdbc.update("""
                UPDATE settlement_adjustments
                   SET recovery_batch_id = ?, recovered_at = ?
                 WHERE id IN (%s) AND recovery_batch_id IS NULL
                """.formatted(placeholders(adjustmentIds.size())), args);
    }

    /** 미회수 잔액 (LED-04·13.2: {@code recovery_batch_id IS NULL}이 판정 기준). */
    public List<UnrecoveredAdjustment> findUnrecoveredAdjustments() {
        return jdbc.query("""
                SELECT a.id, a.settlement_item_id, a.batch_id, a.refund_id, a.amount, a.created_at,
                       b.campaign_id, b.payee_type, b.payee_id
                  FROM settlement_adjustments a
                  JOIN settlement_batches b ON b.id = a.batch_id
                 WHERE a.recovery_batch_id IS NULL
                 ORDER BY a.id
                """, (rs, rowNum) -> new UnrecoveredAdjustment(rs.getLong("id"),
                rs.getLong("settlement_item_id"), rs.getLong("batch_id"), rs.getLong("refund_id"),
                rs.getLong("amount"), rs.getLong("campaign_id"),
                PayeeType.valueOf(rs.getString("payee_type")), rs.getLong("payee_id"),
                instant(rs.getTimestamp("created_at"))));
    }

    public List<Long> findAdjustmentIdsOfRecoveryBatch(Long recoveryBatchId) {
        return jdbc.queryForList("SELECT id FROM settlement_adjustments WHERE recovery_batch_id = ? ORDER BY id",
                Long.class, recoveryBatchId);
    }

    // ── 16.4 운영 요약 ───────────────────────────────────────────────────────────────

    /** 실패 또는 보류된 정산 건수 (`/api/admin/ops/summary`). */
    public long countFailedOrHeldBatches() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM settlement_batches WHERE status IN ('FAILED', 'HELD')", Long.class);
    }

    public long countUnrecoveredAdjustments() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM settlement_adjustments WHERE recovery_batch_id IS NULL", Long.class);
    }

    /** IN 절 플레이스홀더. 배열 파라미터 대신 쓰는 이유는 드라이버별 배열 바인딩 차이를 피하기 위해서다. */
    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static final String BATCH_SELECT = """
            SELECT b.id, b.campaign_id, b.payee_type, b.payee_id, b.batch_type, b.status, b.total_amount,
                   b.determined_at, b.attempts, b.failure_code, b.failure_reason, b.hold_reason,
                   b.created_at, b.completed_at
              FROM settlement_batches b
            """;

    private Batch mapBatch(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Batch(rs.getLong("id"), rs.getLong("campaign_id"),
                PayeeType.valueOf(rs.getString("payee_type")), rs.getLong("payee_id"),
                BatchType.valueOf(rs.getString("batch_type")),
                SettlementBatchStatus.valueOf(rs.getString("status")), rs.getLong("total_amount"),
                instant(rs.getTimestamp("determined_at")), rs.getInt("attempts"), rs.getString("failure_code"),
                rs.getString("failure_reason"), rs.getString("hold_reason"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("completed_at")));
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record CampaignSettlementContext(Long campaignId, String status, Instant determinedAt,
                                            Long supplierId, Long influencerId) { }

    public record OrderPayable(Long orderId, long amount) { }

    public record OrderItemBasis(Long orderItemId, long lineAmount, long supplyAmount) { }

    public record Batch(Long id, Long campaignId, PayeeType payeeType, Long payeeId, BatchType batchType,
                        SettlementBatchStatus status, long totalAmount, Instant determinedAt, int attempts,
                        String failureCode, String failureReason, String holdReason, Instant createdAt,
                        Instant completedAt) { }

    public record BatchItem(Long id, Long orderId, Long orderItemId, long amount) { }

    public record SettledItem(Long id, Long batchId, Long orderItemId, long amount, PayeeType payeeType,
                              Long payeeId, Long campaignId, SettlementBatchStatus batchStatus) { }

    public record UnrecoveredAdjustment(Long id, Long settlementItemId, Long batchId, Long refundId,
                                        long amount, Long campaignId, PayeeType payeeType, Long payeeId,
                                        Instant createdAt) { }
}
