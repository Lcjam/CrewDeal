-- V6: SET-02·REC-01 경쟁 방지와 대사 범위 추적.
-- V1~V5는 불변이며 새 제약과 대사 메타데이터만 추가한다.

-- 같은 집계·유형의 미처리 이벤트는 한 건뿐이다. 특히 refund.requested 재발행의
-- check-then-insert 경쟁에서 두 워커가 같은 PG 환불을 동시에 실행하지 못하게 한다.
-- V5 운영 중 이미 경쟁이 발생한 DB도 업그레이드할 수 있도록 가장 오래된 한 건을 남기고,
-- 나머지는 삭제하지 않고 FAILED 감사 가능 상태로 종결한다. REC-02에서 필요 시 다시 검토할 수 있다.
WITH duplicate_pending AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY event_type, aggregate_type, aggregate_id
               ORDER BY created_at, id
           ) AS occurrence
      FROM outbox_events
     WHERE status = 'PENDING'
)
UPDATE outbox_events o
   SET status = 'FAILED', processed_at = COALESCE(o.processed_at, now()),
       last_error = 'V6 마이그레이션: 동일 집계·유형의 중복 PENDING 이벤트를 종결했습니다.'
  FROM duplicate_pending d
 WHERE o.id = d.id AND d.occurrence > 1;

CREATE UNIQUE INDEX ux_outbox_pending_aggregate_event
    ON outbox_events (event_type, aggregate_type, aggregate_id)
 WHERE status = 'PENDING';

-- 한 시점에 대사 RUNNING 세대는 하나뿐이다. last_seen_run_id 기반 자동 해소가
-- 다른 실행이 방금 재검출한 OPEN 행을 닫는 것을 막는다.
-- 기존 복수 RUNNING은 먼저 시작한 한 세대만 유지하고 나머지를 FAILED로 보존한다.
WITH duplicate_running AS (
    SELECT id, row_number() OVER (ORDER BY started_at, id) AS occurrence
      FROM reconciliation_runs
     WHERE status = 'RUNNING'
)
UPDATE reconciliation_runs r
   SET status = 'FAILED', finished_at = COALESCE(r.finished_at, now()),
       error = 'V6 마이그레이션: 복수 RUNNING 대사 세대 중 후발 실행을 종결했습니다.'
  FROM duplicate_running d
 WHERE r.id = d.id AND d.occurrence > 1;

CREATE UNIQUE INDEX ux_reconciliation_single_running
    ON reconciliation_runs ((1))
 WHERE status = 'RUNNING';

-- minAge가 다른 다음 실행이 자기 cutoff보다 최근인 불일치를 자동 해소하지 않도록
-- 실제 비교 대상의 발생 시각을 보존한다. 기존 OPEN 행은 재검출되어 값이 채워질 때까지 닫지 않는다.
ALTER TABLE reconciliation_discrepancies
    ADD COLUMN subject_occurred_at TIMESTAMPTZ;
