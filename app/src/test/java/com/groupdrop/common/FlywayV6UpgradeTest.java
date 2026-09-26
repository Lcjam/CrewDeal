package com.groupdrop.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupdrop.payment.AbstractPaymentIntegrationTest;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/** V5 운영 데이터에 기존 경쟁의 중복 행이 있어도 V6·V7이 안전하게 적용되는지 검증한다. */
class FlywayV6UpgradeTest extends AbstractPaymentIntegrationTest {

    @Test
    void V5의_중복_PENDING과_복수_RUNNING을_종결하고_V6_유니크와_V7_시각_유형을_적용한다() {
        String schema = "v6_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway toV5 = Flyway.configure()
                .dataSource(jdbc.getDataSource())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("5"))
                .load();
        try {
            toV5.migrate();
            jdbc.update("""
                    INSERT INTO %s.outbox_events
                        (event_type,aggregate_type,aggregate_id,payload,status,attempts,available_at,created_at)
                    VALUES('refund.requested','REFUND',42,'{}','PENDING',0,now(),now()),
                          ('refund.requested','REFUND',42,'{}','PENDING',0,now(),now())
                    """.formatted(schema));
            jdbc.update("""
                    INSERT INTO %s.reconciliation_runs(status,min_age_minutes,started_at)
                    VALUES('RUNNING',0,now()-interval '2 minutes'),('RUNNING',30,now()-interval '1 minute')
                    """.formatted(schema));

            Flyway.configure()
                    .dataSource(jdbc.getDataSource())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    // 이 테스트의 대상은 V6·V7 업그레이드다. 이후 버전이 추가돼도 검증 범위가 흔들리지 않게 고정한다.
                    .target(MigrationVersion.fromVersion("7"))
                    .load()
                    .migrate();

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM " + schema + ".outbox_events WHERE status='PENDING'", Long.class))
                    .isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM " + schema + ".outbox_events WHERE status='FAILED'", Long.class))
                    .isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM " + schema + ".reconciliation_runs WHERE status='RUNNING'", Long.class))
                    .isEqualTo(1L);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM " + schema + ".reconciliation_runs WHERE status='FAILED'", Long.class))
                    .isEqualTo(1L);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                     WHERE table_schema=? AND table_name='reconciliation_discrepancies'
                       AND column_name='subject_occurred_at'
                    """, Long.class, schema)).isEqualTo(1L);
            Long runId = jdbc.queryForObject(
                    "SELECT min(id) FROM " + schema + ".reconciliation_runs", Long.class);
            jdbc.update("""
                    INSERT INTO %s.reconciliation_discrepancies
                        (run_id,last_seen_run_id,discrepancy_type,provider_payment_id,status,detail,
                         detected_at,updated_at)
                    VALUES(?,?,'OCCURRED_AT_MISMATCH',?,'OPEN','V7 검증',now(),now())
                    """.formatted(schema), runId, runId, "pg_v7_" + UUID.randomUUID());
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM %s.reconciliation_discrepancies
                     WHERE discrepancy_type='OCCURRED_AT_MISMATCH'
                    """.formatted(schema), Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("""
                    SELECT version FROM %s.flyway_schema_history
                     WHERE success ORDER BY installed_rank DESC LIMIT 1
                    """.formatted(schema), String.class)).isEqualTo("7");
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
