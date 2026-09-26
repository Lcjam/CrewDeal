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
                SELECT request_hash, status, response_status, response_body, resource_type, resource_id, expires_at
                  FROM idempotency_requests
                 WHERE scope = ? AND idempotency_key = ?
                """, this::map, scope, key).stream().findFirst();
    }

    public void complete(String scope, String key, String resourceType, Long resourceId,
                         int responseStatus, String responseBody, Instant now) {
        if (!completeIfInProgress(scope, key, resourceType, resourceId, responseStatus, responseBody, now)) {
            throw new IllegalStateException("멱등 요청 완료 기록이 유실되었습니다: " + scope + "/" + key);
        }
    }

    /**
     * 조건부 완료. 0행은 다른 경로(결제의 고착 선점 회수)가 먼저 완료했다는 뜻이며, 해석은 호출자가 한다.
     * 이미 완료된 레코드는 덮어쓰지 않는다 — 같은 키에는 한 가지 응답만 나가야 한다 (PAY-02).
     */
    public boolean completeIfInProgress(String scope, String key, String resourceType, Long resourceId,
                                        int responseStatus, String responseBody, Instant now) {
        return jdbc.update("""
                UPDATE idempotency_requests
                   SET status = 'COMPLETED', resource_type = ?, resource_id = ?,
                       response_status = ?, response_body = ?, updated_at = ?
                 WHERE scope = ? AND idempotency_key = ? AND status = 'IN_PROGRESS'
                """, resourceType, resourceId, responseStatus, responseBody, ts(now), scope, key) == 1;
    }

    /**
     * 선점과 같은 트랜잭션에서 만든 리소스를 IN_PROGRESS 레코드에 연결한다. 요청 스레드가 완료 기록 전에 죽어도
     * "이 키가 어느 결제의 것인가"가 남아야 고착 선점을 결제 상태로 회수할 수 있다.
     *
     * <p>주문 scope({@code ORDER:})는 {@code resource_id}로 완료를 판정하므로 이 메서드를 쓰지 않는다.
     * 결제 scope는 {@code status}로 완료를 판정한다({@link Record#completed()}).
     */
    public void attachResource(String scope, String key, String resourceType, Long resourceId, Instant now) {
        int updated = jdbc.update("""
                UPDATE idempotency_requests
                   SET resource_type = ?, resource_id = ?, updated_at = ?
                 WHERE scope = ? AND idempotency_key = ? AND status = 'IN_PROGRESS' AND resource_id IS NULL
                """, resourceType, resourceId, ts(now), scope, key);
        if (updated != 1) {
            throw new IllegalStateException("멱등 요청에 리소스를 연결하지 못했습니다: " + scope + "/" + key);
        }
    }

    private Record map(ResultSet rs, int rowNum) throws SQLException {
        Integer responseStatus = rs.getObject("response_status", Integer.class);
        Long resourceId = rs.getObject("resource_id", Long.class);
        return new Record(rs.getString("request_hash"), rs.getString("status"), responseStatus,
                rs.getString("response_body"), rs.getString("resource_type"), resourceId, rs.getTimestamp("expires_at").toInstant());
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    public record Record(String requestHash, String status, Integer responseStatus, String responseBody,
                         String resourceType, Long resourceId, Instant expiresAt) {

        public boolean completed() {
            return "COMPLETED".equals(status);
        }
    }
}
