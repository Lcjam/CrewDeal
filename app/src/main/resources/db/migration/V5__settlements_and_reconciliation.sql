-- V5: 5주차 정산·대사 (SET-01~03, LED-04, REC-01~02, 10.5, 13.1·13.2)
-- V1~V4는 불변이며, 여기서는 컬럼 추가와 신규 테이블만 만든다.

-- SET-02 동결 스냅숏의 확정 시각. 환불 귀속 컷오프의 기준이자 "재산정하지 않는다"의 근거다.
-- 캠페인에 두는 이유: 배치가 0개인 캠페인(전액 환불)도 확정 시각을 가져야 이후 유입 환불을
-- "확정 이후"로 판정할 수 있기 때문이다.
ALTER TABLE campaigns ADD COLUMN settlement_determined_at TIMESTAMPTZ;

-- 10.5 정산 배치. 상태는 배치 단위이며, 수령 주체(공급사·인플루언서)마다 별개 배치다 (13.3).
CREATE TABLE settlement_batches (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id    BIGINT      NOT NULL REFERENCES campaigns (id),
    payee_type     VARCHAR(12) NOT NULL CHECK (payee_type IN ('SUPPLIER', 'INFLUENCER')),
    payee_id       BIGINT      NOT NULL,
    batch_type     VARCHAR(12) NOT NULL CHECK (batch_type IN ('SETTLEMENT', 'RECOVERY')),
    status         VARCHAR(12) NOT NULL CHECK (status IN
                       ('PENDING', 'READY', 'PROCESSING', 'COMPLETED', 'FAILED', 'HELD')),
    -- 정상 배치는 양수, 회수 배치는 음수 (SET-03). 0원 배치는 만들지 않는다 —
    -- ledger_entry.amount > 0과 충돌하고, 지급할 것이 없는 배치는 상태 전이의 의미가 없다.
    total_amount   BIGINT      NOT NULL,
    -- SET-02 동결 스냅숏 확정 시각. HELD 해소 후 재개해도 이 값과 소속 항목은 재산정하지 않는다.
    determined_at  TIMESTAMPTZ NOT NULL,
    attempts       INT         NOT NULL DEFAULT 0,
    failure_code   VARCHAR(50),
    failure_reason VARCHAR(500),
    hold_reason    VARCHAR(500),
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    completed_at   TIMESTAMPTZ,
    CHECK ((batch_type = 'SETTLEMENT' AND total_amount > 0)
        OR (batch_type = 'RECOVERY'   AND total_amount < 0)),
    -- settlement_items의 복합 FK 대상. payee_type 복제본이 부모와 어긋나지 않게 DB가 강제한다 (ADR-009).
    UNIQUE (id, payee_type)
);

-- S6의 1차 방어. 캠페인·수령 주체당 정상 배치는 1개뿐이므로 정산 재실행이 두 번째 배치를 만들 수 없다.
-- 회수 배치는 같은 수령 주체에 여러 번 생길 수 있으므로 부분 인덱스로 제외한다 (SET-03).
CREATE UNIQUE INDEX ux_settlement_batches_campaign_payee
    ON settlement_batches (campaign_id, payee_type, payee_id)
 WHERE batch_type = 'SETTLEMENT';

CREATE INDEX idx_settlement_batches_status ON settlement_batches (status, batch_type);
CREATE INDEX idx_settlement_batches_campaign ON settlement_batches (campaign_id);

-- 12.4·S6: 하나의 주문 항목은 수령 주체마다 하나의 정상 정산 항목에만 포함된다 (ADR-009).
-- 회수 배치는 이 테이블에 행을 갖지 않는다 (SET-03).
CREATE TABLE settlement_items (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id      BIGINT      NOT NULL REFERENCES settlement_batches (id),
    payee_type    VARCHAR(12) NOT NULL,
    order_id      BIGINT      NOT NULL REFERENCES orders (id),
    order_item_id BIGINT      NOT NULL REFERENCES order_items (id),
    amount        BIGINT      NOT NULL CHECK (amount > 0),
    created_at    TIMESTAMPTZ NOT NULL,
    UNIQUE (payee_type, order_item_id),
    FOREIGN KEY (batch_id, payee_type) REFERENCES settlement_batches (id, payee_type)
);

CREATE INDEX idx_settlement_items_batch ON settlement_items (batch_id);
CREATE INDEX idx_settlement_items_order ON settlement_items (order_id);

-- SET-03. 정산 확정 이후의 환불이 지급된 항목을 건드린 경우의 회수 채권.
-- recovery_batch_id IS NULL이 미회수 잔액의 판정 기준이다 (LED-04, 13.2).
CREATE TABLE settlement_adjustments (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    settlement_item_id BIGINT      NOT NULL REFERENCES settlement_items (id),
    batch_id           BIGINT      NOT NULL REFERENCES settlement_batches (id),
    refund_id          BIGINT      NOT NULL REFERENCES refunds (id),
    -- 회수 금액은 음수다. 원 정산 항목 금액의 반대 부호.
    amount             BIGINT      NOT NULL CHECK (amount < 0),
    recovery_batch_id  BIGINT      REFERENCES settlement_batches (id),
    created_at         TIMESTAMPTZ NOT NULL,
    recovered_at       TIMESTAMPTZ,
    -- refund.completed 재전달에서 같은 조정이 두 번 생기지 않게 한다 (13.4 at-least-once).
    UNIQUE (settlement_item_id, refund_id)
);

CREATE INDEX idx_settlement_adjustments_unrecovered
    ON settlement_adjustments (batch_id) WHERE recovery_batch_id IS NULL;
CREATE INDEX idx_settlement_adjustments_recovery ON settlement_adjustments (recovery_batch_id);

-- REC-01 대사 실행 이력.
CREATE TABLE reconciliation_runs (
    id                         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    status                     VARCHAR(12) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    -- 대사 대상의 최소 경과 시간(분). 기본 30, S4-b는 0으로 실행한다.
    min_age_minutes            INT         NOT NULL CHECK (min_age_minutes >= 0),
    provider_transaction_count INT         NOT NULL DEFAULT 0,
    internal_payment_count     INT         NOT NULL DEFAULT 0,
    mismatch_count             INT         NOT NULL DEFAULT 0,
    resolved_count             INT         NOT NULL DEFAULT 0,
    -- 원장 재검산 결과 (S5, 16.4의 ledger_unbalanced_total).
    ledger_unbalanced_count    INT         NOT NULL DEFAULT 0,
    started_at                 TIMESTAMPTZ NOT NULL,
    finished_at                TIMESTAMPTZ,
    error                      VARCHAR(1000)
);

CREATE INDEX idx_reconciliation_runs_started ON reconciliation_runs (started_at DESC);

CREATE TABLE reconciliation_discrepancies (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id              BIGINT      NOT NULL REFERENCES reconciliation_runs (id),
    last_seen_run_id    BIGINT      NOT NULL REFERENCES reconciliation_runs (id),
    discrepancy_type    VARCHAR(24) NOT NULL CHECK (discrepancy_type IN
                            ('MISSING_INTERNAL', 'MISSING_PROVIDER', 'AMOUNT_MISMATCH',
                             'STATUS_MISMATCH', 'REFUND_MISMATCH', 'DUPLICATE_PAYMENT',
                             'UNRESOLVED_INTERNAL')),
    -- payment_id는 내부 결제가 실제로 있을 때만 채우므로 FK를 건다.
    payment_id          BIGINT      REFERENCES payments (id),
    -- order_id에는 FK를 걸지 않는다. MISSING_INTERNAL은 정의상 "PG가 말하는 주문이 내부에 없음"이므로,
    -- FK를 걸면 그 유형을 기록하는 것 자체가 불가능해진다 — 대사가 발견해야 할 상태를 DB가 막는 셈이다.
    order_id            BIGINT,
    provider_payment_id VARCHAR(100),
    internal_status     VARCHAR(20),
    provider_status     VARCHAR(20),
    internal_amount     BIGINT,
    provider_amount     BIGINT,
    detail              VARCHAR(1000),
    status              VARCHAR(12) NOT NULL CHECK (status IN ('OPEN', 'RESOLVED', 'IGNORED')),
    resolution_note     VARCHAR(1000),
    detected_at         TIMESTAMPTZ NOT NULL,
    resolved_at         TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL
);

-- 대사는 30분마다 돌므로 같은 불일치가 매 실행마다 새 행으로 쌓이면 운영자 목록이 즉시 무의미해진다.
-- 미해결(OPEN) 건은 유형·대상당 1행으로 접고, 재검출은 last_seen_run_id 갱신으로 표현한다.
CREATE UNIQUE INDEX ux_reconciliation_discrepancies_open
    ON reconciliation_discrepancies
       (discrepancy_type, COALESCE(provider_payment_id, ''), COALESCE(payment_id, -1))
 WHERE status = 'OPEN';

CREATE INDEX idx_reconciliation_discrepancies_status
    ON reconciliation_discrepancies (status, detected_at DESC);
CREATE INDEX idx_reconciliation_discrepancies_run ON reconciliation_discrepancies (run_id);
