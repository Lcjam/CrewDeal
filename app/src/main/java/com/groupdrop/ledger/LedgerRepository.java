package com.groupdrop.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 원장 저장소 (LED-01). 쓰기는 거래 INSERT + 분개 INSERT 두 종류뿐이고 UPDATE·DELETE는 없다 —
 * 없는 것이 실수로 생기지 않도록 V4의 트리거가 DB에서도 막는다 (ADR-006).
 *
 * <p>거래의 멱등은 {@code (transaction_type, reference_type, reference_id)} 유니크에 맡긴다.
 * at-least-once 이벤트 전달(13.4)에서 같은 결제·환불이 두 번 도착해도 두 번째 INSERT가 0행이 되고,
 * 호출자는 그것을 "이미 기록됨"으로 읽는다.
 */
@Repository
public class LedgerRepository {

    private final JdbcTemplate jdbc;
    private final ConcurrentMap<LedgerAccount, Long> accountIds = new ConcurrentHashMap<>();

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return 새로 만든 거래 ID. 이미 같은 참조의 거래가 있으면 비어 있다 (재전달이므로 분개하지 않는다).
     */
    public Optional<Long> insertTransactionIfAbsent(String transactionType, String referenceType,
                                                    Long referenceId, Long campaignId, Long orderId,
                                                    Instant occurredAt, Instant now) {
        return jdbc.query("""
                INSERT INTO ledger_transactions
                    (transaction_type, reference_type, reference_id, campaign_id, order_id, occurred_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (transaction_type, reference_type, reference_id) DO NOTHING
                RETURNING id
                """, (rs, rowNum) -> rs.getLong("id"), transactionType, referenceType, referenceId,
                campaignId, orderId, ts(occurredAt), ts(now)).stream().findFirst();
    }

    public void insertEntry(Long transactionId, LedgerAccount account, LedgerSide side, long amount) {
        jdbc.update("""
                INSERT INTO ledger_entries (transaction_id, account_id, side, amount)
                VALUES (?, ?, ?, ?)
                """, transactionId, accountId(account), side.name(), amount);
    }

    public Optional<Long> findTransactionId(String transactionType, String referenceType, Long referenceId) {
        return jdbc.query("""
                SELECT id FROM ledger_transactions
                 WHERE transaction_type = ? AND reference_type = ? AND reference_id = ?
                """, (rs, rowNum) -> rs.getLong("id"), transactionType, referenceType, referenceId)
                .stream().findFirst();
    }

    /** 역분개 거래를 원본과 같은 캠페인·주문에 귀속시키기 위한 조회. */
    public Scope findTransactionScope(Long transactionId) {
        return jdbc.query("SELECT campaign_id, order_id FROM ledger_transactions WHERE id = ?",
                (rs, rowNum) -> new Scope(rs.getObject("campaign_id", Long.class),
                        rs.getObject("order_id", Long.class)), transactionId).getFirst();
    }

    /** 역분개(LED-03)의 원본. 재계산이 아니라 원본 분개를 읽어 뒤집기 위해 사용한다. */
    public List<Posting> findPostings(Long transactionId) {
        return jdbc.query("""
                SELECT a.code, e.side, e.amount
                  FROM ledger_entries e
                  JOIN ledger_accounts a ON a.id = e.account_id
                 WHERE e.transaction_id = ?
                 ORDER BY e.id
                """, this::mapPosting, transactionId);
    }

    /**
     * S5 원장 균형 검증 (LED-01, 12.3). 분개가 하나도 없는 거래도 불균형으로 본다 —
     * 거래만 만들고 분개에 실패한 상태가 정상일 수 없기 때문이다.
     */
    public List<Unbalanced> findUnbalancedTransactions() {
        return jdbc.query("""
                SELECT t.id,
                       COALESCE(SUM(CASE WHEN e.side = 'DEBIT'  THEN e.amount ELSE 0 END), 0) AS debit_total,
                       COALESCE(SUM(CASE WHEN e.side = 'CREDIT' THEN e.amount ELSE 0 END), 0) AS credit_total
                  FROM ledger_transactions t
                  LEFT JOIN ledger_entries e ON e.transaction_id = t.id
                 GROUP BY t.id
                HAVING COALESCE(SUM(CASE WHEN e.side = 'DEBIT'  THEN e.amount ELSE 0 END), 0)
                    <> COALESCE(SUM(CASE WHEN e.side = 'CREDIT' THEN e.amount ELSE 0 END), 0)
                    OR count(e.id) = 0
                 ORDER BY t.id
                """, (rs, rowNum) -> new Unbalanced(rs.getLong("id"), rs.getLong("debit_total"),
                rs.getLong("credit_total")));
    }

    /**
     * 캠페인의 계정별 잔액 (대변 − 차변). 예상 정산액 조회의 단일 원천이며, 집계식으로 다시 계산하지
     * 않는다 — 원천을 둘로 두면 라운딩 차이만으로 정상 캠페인이 불일치로 보인다 (LED-01, ADR-008).
     */
    public Map<String, Long> campaignBalances(Long campaignId) {
        Map<String, Long> balances = new java.util.LinkedHashMap<>();
        jdbc.query("""
                SELECT a.code,
                       SUM(CASE WHEN e.side = 'CREDIT' THEN e.amount ELSE -e.amount END) AS balance
                  FROM ledger_entries e
                  JOIN ledger_accounts a ON a.id = e.account_id
                  JOIN ledger_transactions t ON t.id = e.transaction_id
                 WHERE t.campaign_id = ?
                 GROUP BY a.code
                """, rs -> {
            balances.put(rs.getString("code"), rs.getLong("balance"));
        }, campaignId);
        return balances;
    }

    /** 캠페인의 결제·환불 거래 건수. 예상 정산액 응답의 근거 수치로 노출한다. */
    public Counts campaignCounts(Long campaignId) {
        return jdbc.query("""
                SELECT count(*) FILTER (WHERE transaction_type = 'PAYMENT') AS payment_count,
                       count(*) FILTER (WHERE transaction_type = 'REFUND')  AS refund_count
                  FROM ledger_transactions WHERE campaign_id = ?
                """, (rs, rowNum) -> new Counts(rs.getLong("payment_count"), rs.getLong("refund_count")),
                campaignId).getFirst();
    }

    /**
     * LED-02 분해의 입력. 수수료율과 공급 단가는 주문이 참조하는 <b>정책 버전</b>에서 읽는다 —
     * 캠페인의 현재 값이 아니다 (CAM-04 정책 스냅숏, ADR-007). 공급사 지급 예정금은 SKU 단위
     * 공급 단가 × 수량의 합이므로 애플리케이션이 아니라 SQL에서 합산한다.
     */
    public Optional<OrderPricing> findOrderPricing(Long orderId) {
        return jdbc.query("""
                SELECT o.campaign_id, o.total_amount, pv.commission_rate_bp,
                       COALESCE(SUM(oi.quantity * pvi.supply_unit_price), 0) AS supply_total
                  FROM orders o
                  JOIN campaign_policy_versions pv ON pv.id = o.policy_version_id
                  JOIN order_items oi ON oi.order_id = o.id
                  LEFT JOIN campaign_policy_version_items pvi
                         ON pvi.policy_version_id = o.policy_version_id
                        AND pvi.campaign_sku_id = oi.campaign_sku_id
                 WHERE o.id = ?
                 GROUP BY o.campaign_id, o.total_amount, pv.commission_rate_bp
                """, (rs, rowNum) -> new OrderPricing(rs.getLong("campaign_id"), rs.getLong("total_amount"),
                rs.getInt("commission_rate_bp"), rs.getLong("supply_total")), orderId).stream().findFirst();
    }

    /** 예상 정산액 조회의 소유자 검증용. MVP는 캠페인당 공급사·인플루언서가 각 1명이다 (5장). */
    public Optional<CampaignParties> findCampaignParties(Long campaignId) {
        return jdbc.query("""
                SELECT c.id, c.name, i.user_id AS influencer_user_id, s.user_id AS supplier_user_id
                  FROM campaigns c
                  JOIN influencers i ON i.id = c.influencer_id
                  JOIN suppliers s ON s.id = c.supplier_id
                 WHERE c.id = ?
                """, (rs, rowNum) -> new CampaignParties(rs.getLong("id"), rs.getString("name"),
                rs.getLong("influencer_user_id"), rs.getLong("supplier_user_id")), campaignId)
                .stream().findFirst();
    }

    /** 계정 코드 → ID. 시드 행은 마이그레이션에서 고정되므로 프로세스 수명 동안 캐시한다. */
    private Long accountId(LedgerAccount account) {
        return accountIds.computeIfAbsent(account, key -> jdbc.queryForObject(
                "SELECT id FROM ledger_accounts WHERE code = ?", Long.class, key.name()));
    }

    private Posting mapPosting(ResultSet rs, int rowNum) throws SQLException {
        return new Posting(LedgerAccount.valueOf(rs.getString("code")),
                LedgerSide.valueOf(rs.getString("side")), rs.getLong("amount"));
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    public record Posting(LedgerAccount account, LedgerSide side, long amount) {

        public Posting reversed() {
            return new Posting(account, side.opposite(), amount);
        }
    }

    public record Unbalanced(Long transactionId, long debitTotal, long creditTotal) { }

    public record Scope(Long campaignId, Long orderId) { }

    public record Counts(long paymentCount, long refundCount) { }

    public record OrderPricing(Long campaignId, long totalAmount, int commissionRateBp, long supplyTotal) { }

    public record CampaignParties(Long campaignId, String campaignName, Long influencerUserId,
                                  Long supplierUserId) { }
}
