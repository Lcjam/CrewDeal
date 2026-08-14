-- V3: 3주차 결제·결제 시도·Outbox/Inbox·감사 로그 (PAY-01~04, 13.4)
-- V1·V2는 불변이며, 결제 정합성의 핵심 규칙은 DB 제약으로 이중 방어한다.

CREATE TABLE payments (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id            BIGINT      NOT NULL REFERENCES orders (id),
    status              VARCHAR(20) NOT NULL CHECK (status IN
                            ('READY', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN',
                             'SUPERSEDED', 'REFUNDING', 'REFUNDED')),
    amount              BIGINT      NOT NULL CHECK (amount > 0),
    provider_payment_id VARCHAR(100) UNIQUE,
    failure_code        VARCHAR(50),
    failure_reason      VARCHAR(500),
    approved_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_payments_order ON payments (order_id);
CREATE INDEX idx_payments_status_created ON payments (status, created_at);

-- 13.2: "한 주문에 유효한 성공 결제 최대 1개". SUPERSEDED는 의도적으로 제외해
-- 이중 결제 패자의 보상 경로(PAY-01)가 제약에 막히지 않게 한다.
CREATE UNIQUE INDEX ux_payments_order_effective
    ON payments (order_id)
    WHERE status IN ('SUCCEEDED', 'REFUNDING', 'REFUNDED');

-- 비최종 결제(READY·PROCESSING·UNKNOWN)에는 의도적으로 유니크 제약을 걸지 않는다.
-- PAY-01의 이중 결제 "예방"은 애플리케이션 409로 하고, 그 경쟁 창을 뚫은 이중 승인은
-- 위 부분 유니크가 패자를 만들어 SUPERSEDED 보상 경로로 보낸다. 여기서 DB로 창을 닫으면
-- 기획서가 요구하는 보상 경로 자체가 도달 불가능해진다.

-- PG 호출 단위 기록. PAY-01에 따라 이 행의 생성과 READY → PROCESSING 전이는 같은 트랜잭션이다.
-- 행이 없는 READY는 "PG 호출 전 크래시" 고아이며 스윕이 FAILED로 확정한다 (PAY-03).
CREATE TABLE payment_attempts (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id          BIGINT       NOT NULL REFERENCES payments (id),
    merchant_payment_id VARCHAR(100) NOT NULL,
    amount              BIGINT       NOT NULL CHECK (amount > 0),
    outcome             VARCHAR(20)  NOT NULL DEFAULT 'REQUESTED'
                            CHECK (outcome IN ('REQUESTED', 'SUCCEEDED', 'FAILED', 'TIMEOUT')),
    error_detail        VARCHAR(500),
    requested_at        TIMESTAMPTZ  NOT NULL,
    finished_at         TIMESTAMPTZ
);

CREATE INDEX idx_payment_attempts_payment ON payment_attempts (payment_id);

-- 13.4 Outbox: 원 트랜잭션과 분리되어야 하고 유실되면 안 되는 후속 처리.
-- 다중 워커가 FOR UPDATE SKIP LOCKED로 경쟁 소비한다 (17.3 2계층).
CREATE TABLE outbox_events (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type     VARCHAR(50)  NOT NULL,
    aggregate_type VARCHAR(40)  NOT NULL,
    aggregate_id   BIGINT       NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                       CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED')),
    attempts       INT          NOT NULL DEFAULT 0,
    available_at   TIMESTAMPTZ  NOT NULL,
    last_error     VARCHAR(1000),
    created_at     TIMESTAMPTZ  NOT NULL,
    processed_at   TIMESTAMPTZ
);

CREATE INDEX idx_outbox_pending ON outbox_events (status, available_at, id);

-- 13.2: inbox_event.provider_event_id UNIQUE — 중복 웹훅의 1차 방어선 (11.4, S3)
CREATE TABLE inbox_events (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider_event_id VARCHAR(100) NOT NULL UNIQUE,
    event_type        VARCHAR(50)  NOT NULL,
    payload           TEXT         NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                          CHECK (status IN ('PENDING', 'PROCESSED', 'IGNORED', 'FAILED')),
    attempts          INT          NOT NULL DEFAULT 0,
    available_at      TIMESTAMPTZ  NOT NULL,
    last_error        VARCHAR(1000),
    received_at       TIMESTAMPTZ  NOT NULL,
    processed_at      TIMESTAMPTZ
);

CREATE INDEX idx_inbox_pending ON inbox_events (status, available_at, id);

-- 허용되지 않은 전이를 요구하는 웹훅(역순 등)은 무시하고 감사 로그만 남긴다 (PAY-04, 11.5).
CREATE TABLE audit_logs (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor         VARCHAR(100) NOT NULL,
    action        VARCHAR(60)  NOT NULL,
    resource_type VARCHAR(40)  NOT NULL,
    resource_id   BIGINT,
    detail        VARCHAR(1000),
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_audit_logs_resource ON audit_logs (resource_type, resource_id, created_at DESC);

-- PAY-02 멱등성 정책 전체를 담기 위한 확장. 2주차에는 "선점 + 리소스 ID"까지만 필요했지만
-- 결제는 최초 응답의 재생(응답 코드·본문)과 키 만료(TTL 24시간)까지 요구한다.
ALTER TABLE idempotency_requests
    ADD COLUMN status          VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS'
                                   CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    ADD COLUMN response_status INT,
    ADD COLUMN response_body   TEXT,
    ADD COLUMN expires_at      TIMESTAMPTZ;

UPDATE idempotency_requests
   SET expires_at = created_at + INTERVAL '24 hours'
 WHERE expires_at IS NULL;

ALTER TABLE idempotency_requests ALTER COLUMN expires_at SET NOT NULL;

CREATE INDEX idx_idempotency_expiry ON idempotency_requests (expires_at);
