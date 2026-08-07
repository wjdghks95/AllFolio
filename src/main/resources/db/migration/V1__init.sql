-- Phase 1 Step 2: users, assets, holdings, transactions 테이블
-- NUMERIC(28,8), uuidv7() DEFAULT (PostgreSQL 18 네이티브 함수, 실측 확인됨), Optimistic Lock(version)
-- CHECK 제약: asset_type, tx_type / holdings.asset_id UNIQUE (종목당 1행)
-- price_snapshots, device_tokens는 Phase 2/3에서 추가

CREATE TABLE users (
    id            UUID           PRIMARY KEY DEFAULT uuidv7(),
    email         VARCHAR(255)   NOT NULL,
    password_hash VARCHAR(255)   NOT NULL,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE assets (
    id         UUID           PRIMARY KEY DEFAULT uuidv7(),
    user_id    UUID           NOT NULL,
    ticker     VARCHAR(20)    NOT NULL,
    name       VARCHAR(100)   NOT NULL,
    asset_type VARCHAR(10)    NOT NULL,
    currency   VARCHAR(3)     NOT NULL DEFAULT 'KRW',
    created_at TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_assets_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_assets_asset_type CHECK (asset_type IN ('STOCK', 'COIN', 'CASH'))
);

CREATE TABLE holdings (
    id         UUID           PRIMARY KEY DEFAULT uuidv7(),
    asset_id   UUID           NOT NULL,
    quantity   NUMERIC(28,8)  NOT NULL,
    avg_price  NUMERIC(28,8)  NOT NULL,
    version    INT            NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_holdings_asset_id FOREIGN KEY (asset_id) REFERENCES assets (id) ON DELETE CASCADE,
    CONSTRAINT uk_holdings_asset_id UNIQUE (asset_id),
    CONSTRAINT ck_holdings_quantity_non_negative CHECK (quantity >= 0),
    CONSTRAINT ck_holdings_avg_price_positive CHECK (avg_price > 0)
);

CREATE TABLE transactions (
    id         UUID           PRIMARY KEY DEFAULT uuidv7(),
    asset_id   UUID           NOT NULL,
    tx_type    VARCHAR(10)    NOT NULL,
    price      NUMERIC(28,8)  NOT NULL,
    quantity   NUMERIC(28,8)  NOT NULL,
    traded_at  TIMESTAMPTZ    NOT NULL,
    CONSTRAINT fk_transactions_asset_id FOREIGN KEY (asset_id) REFERENCES assets (id) ON DELETE CASCADE,
    CONSTRAINT ck_transactions_tx_type CHECK (tx_type IN ('BUY', 'SELL', 'DIVIDEND'))
);
