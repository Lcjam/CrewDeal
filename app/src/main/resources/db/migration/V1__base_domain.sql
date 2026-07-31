-- V1: 1주차 도메인 — 사용자·상품·캠페인 (기획서 13장)
-- 마이그레이션은 주차별 증분으로 작성한다 (D-004). 주문·결제·원장·정산 테이블은 해당 주차에 추가.
-- 커밋된 마이그레이션은 불변 — 수정 대신 새 버전 추가.

CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('BUYER', 'INFLUENCER', 'SUPPLIER', 'ADMIN')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE influencers (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL UNIQUE REFERENCES users (id),
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE suppliers (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL UNIQUE REFERENCES users (id),
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE products (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    supplier_id BIGINT       NOT NULL REFERENCES suppliers (id),
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_products_supplier ON products (supplier_id);

CREATE TABLE product_skus (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id  BIGINT       NOT NULL REFERENCES products (id),
    option_name VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (product_id, option_name)
);

CREATE TABLE campaigns (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                    VARCHAR(200) NOT NULL,
    slug                    VARCHAR(100) NOT NULL UNIQUE,
    influencer_id           BIGINT       NOT NULL REFERENCES influencers (id),
    supplier_id             BIGINT       NOT NULL REFERENCES suppliers (id),
    product_id              BIGINT       NOT NULL REFERENCES products (id),
    status                  VARCHAR(20)  NOT NULL CHECK (status IN
                                ('DRAFT', 'REVIEWING', 'SCHEDULED', 'OPEN', 'SOLD_OUT',
                                 'CLOSED', 'CANCELLED', 'SETTLING', 'SETTLED')),
    deal_price              BIGINT       NOT NULL CHECK (deal_price > 0),
    per_user_purchase_limit INT          NOT NULL CHECK (per_user_purchase_limit > 0),
    starts_at               TIMESTAMPTZ  NOT NULL,
    ends_at                 TIMESTAMPTZ  NOT NULL,
    closed_at               TIMESTAMPTZ,
    rejection_reason        VARCHAR(500),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at)
);

CREATE INDEX idx_campaigns_status ON campaigns (status);
CREATE INDEX idx_campaigns_influencer ON campaigns (influencer_id);
CREATE INDEX idx_campaigns_supplier ON campaigns (supplier_id);

CREATE TABLE campaign_skus (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id    BIGINT NOT NULL REFERENCES campaigns (id),
    product_sku_id BIGINT NOT NULL REFERENCES product_skus (id),
    UNIQUE (campaign_id, product_sku_id)
);

-- 재고 카운터를 campaign_skus와 1:1 분리 — 조건부 UPDATE 경합 범위를 정책 컬럼과 분리 (13.1, ADR-001 예정)
CREATE TABLE campaign_inventories (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_sku_id    BIGINT NOT NULL UNIQUE REFERENCES campaign_skus (id),
    initial_quantity   INT    NOT NULL CHECK (initial_quantity >= 0),
    available_quantity INT    NOT NULL CHECK (available_quantity >= 0),
    reserved_quantity  INT    NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    sold_quantity      INT    NOT NULL DEFAULT 0 CHECK (sold_quantity >= 0)
);

-- 정산 정책 스냅숏: 주문이 생성 시점 버전을 FK 참조 (CAM-04). MVP는 캠페인당 버전 1개.
CREATE TABLE campaign_policy_versions (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id        BIGINT      NOT NULL REFERENCES campaigns (id),
    version_no         INT         NOT NULL,
    commission_rate_bp INT         NOT NULL CHECK (commission_rate_bp >= 0 AND commission_rate_bp <= 10000),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (campaign_id, version_no)
);

-- 공급 단가는 SKU 단위이므로 버전의 상세 행으로 저장 (D-006)
CREATE TABLE campaign_policy_version_items (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_version_id BIGINT NOT NULL REFERENCES campaign_policy_versions (id),
    campaign_sku_id   BIGINT NOT NULL REFERENCES campaign_skus (id),
    supply_unit_price BIGINT NOT NULL CHECK (supply_unit_price >= 0),
    UNIQUE (policy_version_id, campaign_sku_id)
);
