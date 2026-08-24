package com.groupdrop.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 13.4 Inbox. 중복 웹훅 방어의 1차선은 provider_event_id UNIQUE이고,
 * 2차선은 핸들러 효과를 조건부 UPDATE로만 표현하는 것이다 (11.4, S3).
 */
@Repository
public class InboxRepository {

    private final JdbcTemplate jdbc;

    public InboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 이미 받은 이벤트면 false. 수신 스레드는 여기까지만 하고 즉시 200을 반환한다 (PAY-04). */
    public boolean receive(String providerEventId, String eventType, String payload, Instant now) {
        return jdbc.update("""
                INSERT INTO inbox_events
                    (provider_event_id, event_type, payload, status, attempts, available_at, received_at)
                VALUES (?, ?, ?, 'PENDING', 0, ?, ?)
                ON CONFLICT (provider_event_id) DO NOTHING
                """, providerEventId, eventType, payload, ts(now), ts(now)) == 1;
    }

    public List<ClaimedEvent> claim(Instant now, Instant leaseUntil, int limit) {
        return jdbc.query("""
                UPDATE inbox_events i
                   SET attempts = i.attempts + 1, available_at = ?
                  FROM (SELECT id FROM inbox_events
                         WHERE status = 'PENDING' AND available_at <= ?
                         ORDER BY id
                         FOR UPDATE SKIP LOCKED
                         LIMIT ?) c
                 WHERE i.id = c.id
                RETURNING i.id, i.provider_event_id, i.event_type, i.payload, i.attempts
                """, (rs, rowNum) -> new ClaimedEvent(rs.getLong("id"), rs.getString("provider_event_id"),
                rs.getString("event_type"), rs.getString("payload"), rs.getInt("attempts")),
                ts(leaseUntil), ts(now), limit);
    }

    public boolean markProcessed(Long id, Instant now) {
        return jdbc.update("""
                UPDATE inbox_events
                   SET status = 'PROCESSED', processed_at = ?, last_error = NULL
                 WHERE id = ? AND status = 'PENDING'
                """, ts(now), id) == 1;
    }

    /** 허용 전이표 밖의 이벤트(역순 웹훅 등)는 실패가 아니라 무시로 종결한다 (11.5). */
    public boolean markIgnored(Long id, String reason, Instant now) {
        return jdbc.update("""
                UPDATE inbox_events
                   SET status = 'IGNORED', processed_at = ?, last_error = ?
                 WHERE id = ? AND status = 'PENDING'
                """, ts(now), OutboxRepository.truncate(reason), id) == 1;
    }

    public void markRetry(Long id, String error, Instant retryAt) {
        jdbc.update("""
                UPDATE inbox_events
                   SET available_at = ?, last_error = ?
                 WHERE id = ? AND status = 'PENDING'
                """, ts(retryAt), OutboxRepository.truncate(error), id);
    }

    public void markFailed(Long id, String error, Instant now) {
        jdbc.update("""
                UPDATE inbox_events
                   SET status = 'FAILED', last_error = ?, processed_at = ?
                 WHERE id = ? AND status = 'PENDING'
                """, OutboxRepository.truncate(error), ts(now), id);
    }

    /** REC-02 운영자 재처리: FAILED 이벤트만 소비 대기열로 되돌린다. */
    public boolean retryFailed(Long id, Instant now) {
        return jdbc.update("""
                UPDATE inbox_events
                   SET status = 'PENDING', attempts = 0, available_at = ?,
                       last_error = NULL, processed_at = NULL
                 WHERE id = ? AND status = 'FAILED'
                """, ts(now), id) == 1;
    }

    public long countByStatus(String status) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM inbox_events WHERE status = ?", Long.class, status);
        return count == null ? 0L : count;
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    public record ClaimedEvent(Long id, String providerEventId, String eventType, String payload, int attempts) { }
}
