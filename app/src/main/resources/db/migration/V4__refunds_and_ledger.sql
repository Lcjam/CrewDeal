-- V4: 4주차 환불·원장 (REF-02, LED-01~04, 13.1·13.2)
-- V1~V3은 불변이며, 원장의 핵심 규칙(불변성·양수 금액·거래당 멱등)은 DB 제약과 트리거로 이중 방어한다.

-- 성공한 결제당 유효한 환불 최대 1건 (12.2, 13.2). FAILED 환불 행은 여러 개 존재할 수 있다 (10.3).
CREATE TABLE refunds (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id         BIGINT      NOT NULL REFERENCES payments (id),
    order_id           BIGINT      NOT NULL REFERENCES orders (id),
    status             VARCHAR(20) NOT NULL CHECK (status IN ('REQUESTED', 'COMPLETED', 'FAILED')),
    amount             BIGINT      NOT NULL CHECK (amount > 0),
    -- PAY-01 이중 결제 패자의 보상 환불. 수익 분해(LED-02)에 진입한 적이 없으므로 역분개 대상이 아니고,
    -- 주문 상태도 건드리지 않는다 (주문의 유효 결제는 승자 쪽이다).
    compensation       BOOLEAN     NOT NULL DEFAULT FALSE,
    reason             VARCHAR(200),
    provider_refund_id VARCHAR(100),
    failure_code       VARCHAR(50),
    failure_reason     VARCHAR(500),
    requested_at       TIMESTAMPTZ NOT NULL,
    completed_at       TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX ux_refunds_payment_effective ON refunds (payment_id) WHERE status <> 'FAILED';
CREATE INDEX idx_refunds_order ON refunds (order_id);
CREATE INDEX idx_refunds_status ON refunds (status, requested_at);

-- LED-01. 계정 코드는 애플리케이션 상수(LedgerAccount)와 값이 일치해야 한다.
CREATE TABLE ledger_accounts (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       VARCHAR(40) NOT NULL UNIQUE,
    name       VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO ledger_accounts (code, name) VALUES
    ('PG_RECEIVABLE',      'PG 미수금'),
    ('SUPPLIER_PAYABLE',   '공급사 지급 예정금'),
    ('INFLUENCER_PAYABLE', '인플루언서 지급 예정금'),
    ('PG_FEE_PAYABLE',     'PG 수수료 예정금'),
    ('PLATFORM_REVENUE',   '플랫폼 수익'),
    ('PAYOUT_CASH',        '지급 완료 (가상 현금)');

-- 참조 단위당 거래 1건. at-least-once 전달(13.4)에서 같은 이벤트가 두 번 와도
-- 이 유니크가 두 번째 분개를 막는다 — 멱등을 애플리케이션 선조회로 흉내내지 않는다.
CREATE TABLE ledger_transactions (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_type VARCHAR(20) NOT NULL CHECK (transaction_type IN
                         ('PAYMENT', 'REFUND', 'PAYOUT', 'RECOVERY')),
    reference_type   VARCHAR(20) NOT NULL CHECK (reference_type IN
                         ('PAYMENT', 'REFUND', 'SETTLEMENT_BATCH')),
    reference_id     BIGINT      NOT NULL,
    campaign_id      BIGINT      REFERENCES campaigns (id),
    order_id         BIGINT      REFERENCES orders (id),
    occurred_at      TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    UNIQUE (transaction_type, reference_type, reference_id)
);

CREATE INDEX idx_ledger_transactions_campaign ON ledger_transactions (campaign_id, transaction_type);
CREATE INDEX idx_ledger_transactions_order ON ledger_transactions (order_id);

CREATE TABLE ledger_entries (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id BIGINT     NOT NULL REFERENCES ledger_transactions (id),
    account_id     BIGINT     NOT NULL REFERENCES ledger_accounts (id),
    side           VARCHAR(6) NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    -- 13.2: ledger_entry.amount > 0. 0원 분개는 기록하지 않는다 (CAM-01 마진 게이트가 잔여 양수를 보장).
    amount         BIGINT     NOT NULL CHECK (amount > 0)
);

CREATE INDEX idx_ledger_entries_transaction ON ledger_entries (transaction_id);
CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id);

-- LED-01·12.3: 완료된 원장 거래는 수정·삭제하지 않는다. 보정은 반대 거래 추가로만 한다 (ADR-006).
-- 애플리케이션 규율이 아니라 DB가 막게 하는 이유: 원장을 고치는 코드는 사고가 난 뒤에 급하게 작성되며,
-- 그 시점에 규율을 기억하는 사람은 없다.
CREATE FUNCTION ledger_reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $BODY$
BEGIN
    RAISE EXCEPTION '원장은 불변입니다 (LED-01). 보정은 반대 거래를 추가하세요: %', TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$BODY$;

CREATE TRIGGER trg_ledger_transactions_immutable
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();

CREATE TRIGGER trg_ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();
