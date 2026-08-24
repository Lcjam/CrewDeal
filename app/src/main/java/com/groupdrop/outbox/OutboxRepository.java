package com.groupdrop.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
     * 같은 유형·집계의 PENDING 이벤트를 DB 유니크 제약으로 한 건만 유지한다.
     * REC-01 재발행의 check-then-insert 경쟁을 닫는 원자 연산이다.
     */
    public Optional<Long> appendIfNoPending(String eventType, String aggregateType, Long aggregateId,
                                            String payload, Instant now) {
        return jdbc.query("""
                INSERT INTO outbox_events
                    (event_type, aggregate_type, aggregate_id, payload, status, attempts, available_at, created_at)
                VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?)
                ON CONFLICT (event_type, aggregate_type, aggregate_id) WHERE status = 'PENDING'
                DO NOTHING
                RETURNING id
                """, (rs, rowNum) -> rs.getLong("id"), eventType, aggregateType, aggregateId, payload,
                ts(now), ts(now)).stream().findFirst();
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

    /** REC-02 운영자 재처리: 실패로 종결된 이벤트만 다시 PENDING으로 되돌린다. */
    public boolean retryFailed(Long id, Instant now) {
        return jdbc.update("""
                UPDATE outbox_events
                   SET status = 'PENDING', attempts = 0, available_at = ?,
                       last_error = NULL, processed_at = NULL
                 WHERE id = ? AND status = 'FAILED'
                """, ts(now), id) == 1;
    }

    /**
     * 특정 집계에 아직 처리되지 않은 이벤트가 남아 있는가. 대사가 미완 환불을 되살릴 때
     * "워커가 이미 들고 있는 건"을 다시 발행하지 않기 위해 쓴다 — 중복 발행은 PG 환불을
     * 두 워커가 동시에 호출하게 만든다 (REC-01).
     */
    public boolean hasPending(String eventType, Long aggregateId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events
                 WHERE event_type = ? AND aggregate_id = ? AND status = 'PENDING'
                """, Long.class, eventType, aggregateId);
        return count != null && count > 0;
    }

    public long countByStatus(String status) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE status = ?", Long.class, status);
        return count == null ? 0L : count;
    }

    /** PENDING 이벤트 수와 가장 오래된 created_at을 한 DB 스냅샷으로 읽는다 (16.4). */
    public PendingStats pendingStats() {
        return jdbc.queryForObject("""
                SELECT count(*) AS pending_count, min(created_at) AS oldest_created_at
                  FROM outbox_events
                 WHERE status = 'PENDING'
                """, (rs, rowNum) -> {
            Timestamp oldest = rs.getTimestamp("oldest_created_at");
            return new PendingStats(rs.getLong("pending_count"), oldest == null ? null : oldest.toInstant());
        });
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

    public record PendingStats(long count, Instant oldestCreatedAt) { }
}
