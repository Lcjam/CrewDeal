-- PAY-02 고착 선점 회수 (OrphanPaymentSweepService#reclaimStaleIdempotency).
-- 스윕이 1분마다 "임계가 지난 결제 IN_PROGRESS 선점"을 찾는다. idempotency_requests에는 정리 경로가 없어
-- 계속 커지므로, 진행 중인 결제 선점(동시 결제 요청 수만큼)과 고착 선점만 담는 부분 인덱스로 전체 스캔을 피한다.
-- CONCURRENTLY를 쓰지 않는다: Flyway 트랜잭션 밖 실행이 필요하고, 이 규모에서는 생성 중 쓰기 차단이 순간이다.
CREATE INDEX idx_idempotency_stale_payment_claim
    ON idempotency_requests (created_at)
    WHERE status = 'IN_PROGRESS' AND resource_type = 'PAYMENT';
