package com.groupdrop.common;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 무시된 이벤트처럼 "아무 일도 일어나지 않았다"가 결론인 처리의 흔적을 남긴다 (11.5).
 * 로그 파일이 아니라 테이블에 남기는 이유는 대사·운영 조회의 근거가 되어야 하기 때문이다.
 */
@Repository
public class AuditLogRepository {

    private final JdbcTemplate jdbc;

    public AuditLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actor, String action, String resourceType, Long resourceId,
                       String detail, Instant now) {
        jdbc.update("""
                INSERT INTO audit_logs (actor, action, resource_type, resource_id, detail, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, actor, action, resourceType, resourceId, truncate(detail), Timestamp.from(now));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
