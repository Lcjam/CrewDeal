package com.groupdrop.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PAY-02 멱등성 저장소. 키의 선점은 INSERT ... ON CONFLICT DO NOTHING 한 문장이며,
 * "먼저 넣은 요청이 주인"이라는 판정을 애플리케이션 조회로 흉내내지 않는다.
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean claim(String scope, String key, String requestHash, Instant now, Instant expiresAt) {
        return jdbc.update("""
                INSERT INTO idempotency_requests
                    (scope, idempotency_key, request_hash, status, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?, ?)
                ON CONFLICT (scope, idempotency_key) DO NOTHING
                """, scope, key, requestHash, ts(expiresAt), ts(now), ts(now)) == 1;
    }

    public Optional<Record> find(String scope, String key) {
        return jdbc.query("""
                SELECT request_hash, status, response_status, response_body, resource_id, expires_at
                  FROM idempotency_requests
                 WHERE scope = ? AND idempotency_key = ?
                """, this::map, scope, key).stream().findFirst();
    }

    public void complete(String scope, String key, String resourceType, Long resourceId,
                         int responseStatus, String responseBody, Instant now) {
        int updated = jdbc.update("""
                UPDATE idempotency_requests
                   SET status = 'COMPLETED', resource_type = ?, resource_id = ?,
                       response_status = ?, response_body = ?, updated_at = ?
                 WHERE scope = ? AND idempotency_key = ? AND status = 'IN_PROGRESS'
                """, resourceType, resourceId, responseStatus, responseBody, ts(now), scope, key);
        if (updated != 1) {
            throw new IllegalStateException("멱등 요청 완료 기록이 유실되었습니다: " + scope + "/" + key);
        }
    }

    /** 요청 스레드가 완료 기록 전에 죽으면 IN_PROGRESS가 남는다. 결제 재시도를 영구 차단하지 않도록 회수한다. */
    public int releaseStale(String scope, String key) {
        return jdbc.update("""
                DELETE FROM idempotency_requests
                 WHERE scope = ? AND idempotency_key = ? AND status = 'IN_PROGRESS'
                """, scope, key);
    }

    private Record map(ResultSet rs, int rowNum) throws SQLException {
        Integer responseStatus = rs.getObject("response_status", Integer.class);
        Long resourceId = rs.getObject("resource_id", Long.class);
        return new Record(rs.getString("request_hash"), rs.getString("status"), responseStatus,
                rs.getString("response_body"), resourceId, rs.getTimestamp("expires_at").toInstant());
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    public record Record(String requestHash, String status, Integer responseStatus, String responseBody,
                         Long resourceId, Instant expiresAt) {

        public boolean completed() {
            return "COMPLETED".equals(status);
        }
    }
}
