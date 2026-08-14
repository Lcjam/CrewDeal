-- V2: 2주차 주문·주문 항목·재고 예약·구매 제한·멱등 요청 (ORD-01~04)
-- V1은 불변이며, 주문 생성 트랜잭션의 정합성을 DB 제약으로 이중 방어한다.

CREATE TABLE orders (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id       BIGINT      NOT NULL REFERENCES campaigns (id),
    buyer_id          BIGINT      NOT NULL REFERENCES users (id),
    policy_version_id BIGINT      NOT NULL REFERENCES campaign_policy_versions (id),
    status            VARCHAR(30) NOT NULL CHECK (status IN
                          ('PENDING_PAYMENT', 'PAID', 'EXPIRED', 'CANCELLED',
                           'REFUNDING', 'REFUNDED')),
    total_amount      BIGINT      NOT NULL CHECK (total_amount > 0),
    total_quantity    INT         NOT NULL CHECK (total_quantity > 0),
    expires_at        TIMESTAMPTZ NOT NULL,
    ops_hold          BOOLEAN     NOT NULL DEFAULT FALSE,
    ops_hold_reason   VARCHAR(500),
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_orders_buyer_created ON orders (buyer_id, created_at DESC);
CREATE INDEX idx_orders_expiry ON orders (status, expires_at);
CREATE INDEX idx_orders_campaign ON orders (campaign_id);

CREATE TABLE order_items (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id        BIGINT NOT NULL REFERENCES orders (id),
    campaign_sku_id BIGINT NOT NULL REFERENCES campaign_skus (id),
    quantity        INT    NOT NULL CHECK (quantity > 0),
    unit_price      BIGINT NOT NULL CHECK (unit_price > 0),
    line_amount     BIGINT NOT NULL CHECK (line_amount > 0),
    UNIQUE (order_id, campaign_sku_id)
);

CREATE INDEX idx_order_items_order ON order_items (order_id);

CREATE TABLE stock_reservations (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_item_id         BIGINT      NOT NULL UNIQUE REFERENCES order_items (id),
    campaign_inventory_id BIGINT      NOT NULL REFERENCES campaign_inventories (id),
    status                VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    quantity              INT         NOT NULL CHECK (quantity > 0),
    expires_at            TIMESTAMPTZ NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_stock_reservations_inventory_status
    ON stock_reservations (campaign_inventory_id, status);
CREATE INDEX idx_stock_reservations_expiry ON stock_reservations (status, expires_at);

CREATE TABLE campaign_user_purchase_counters (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id BIGINT      NOT NULL REFERENCES campaigns (id),
    user_id     BIGINT      NOT NULL REFERENCES users (id),
    quantity    INT         NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    UNIQUE (campaign_id, user_id)
);

CREATE TABLE idempotency_requests (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scope         VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash  VARCHAR(64)  NOT NULL,
    resource_type VARCHAR(40),
    resource_id   BIGINT,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    UNIQUE (scope, idempotency_key)
);
