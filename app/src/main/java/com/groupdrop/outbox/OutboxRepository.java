package com.groupdrop.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 13.4 Outbox. 소비는 2단계다 — 리스 획득(FOR UPDATE SKIP LOCKED)과 처리 완료 표시를
 * 분리해, 외부 호출을 하는 핸들러(4주차 refund.requested)가 생겨도 청구 트랜잭션이
 * 외부 호출을 물고 있지 않게 한다 (ADR-003).
 */
@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 생산 트랜잭션 안에서 호출한다. 이벤트 적재와 원 상태 변경이 같은 커밋에 묶여야 한다. */
    public Long append(String eventType, String aggregateType, Long aggregateId, String payload, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO outbox_events
                    (event_type, aggregate_type, aggregate_id, payload, status, attempts, available_at, created_at)
                VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?)
                RETURNING id
                """, Long.class, eventType, aggregateType, aggregateId, payload, ts(now), ts(now));
    }

    /**
     * 다중 워커 경쟁 소비의 핵심. SKIP LOCKED로 같은 행을 두 워커가 잡지 못하게 하고,
     * available_at을 리스 만료 시각으로 밀어 처리 중 재선점을 막는다.
     */
    public List<ClaimedEvent> claim(Instant now, Instant leaseUntil, int limit) {
        return jdbc.query("""
                UPDATE outbox_events o
                   SET attempts = o.attempts + 1, available_at = ?
                  FROM (SELECT id FROM outbox_events
                         WHERE status = 'PENDING' AND available_at <= ?
                         ORDER BY id
                         FOR UPDATE SKIP LOCKED
                         LIMIT ?) c
                 WHERE o.id = c.id
                RETURNING o.id, o.event_type, o.aggregate_id, o.payload, o.attempts
                """, (rs, rowNum) -> new ClaimedEvent(rs.getLong("id"), rs.getString("event_type"),
                rs.getLong("aggregate_id"), rs.getString("payload"), rs.getInt("attempts")),
                ts(leaseUntil), ts(now), limit);
    }

    public boolean markProcessed(Long id, Instant now) {
        return jdbc.update("""
                UPDATE outbox_events
                   SET status = 'PROCESSED', processed_at = ?, last_error = NULL
                 WHERE id = ? AND status = 'PENDING'
                """, ts(now), id) == 1;
    }

    public void markRetry(Long id, String error, Instant retryAt) {
        jdbc.update("""
                UPDATE outbox_events
                   SET available_at = ?, last_error = ?
                 WHERE id = ? AND status = 'PENDING'
                """, ts(retryAt), truncate(error), id);
    }

    public void markFailed(Long id, String error, Instant now) {
        jdbc.update("""
                UPDATE outbox_events
                   SET status = 'FAILED', last_error = ?, processed_at = ?
                 WHERE id = ? AND status = 'PENDING'
                """, truncate(error), ts(now), id);
    }

    public long countByStatus(String status) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE status = ?", Long.class, status);
        return count == null ? 0L : count;
    }

    static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    public record ClaimedEvent(Long id, String eventType, Long aggregateId, String payload, int attempts) { }
}
