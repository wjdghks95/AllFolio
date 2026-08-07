-- 유저별 자산 목록 조회
CREATE INDEX idx_assets_user_id ON assets (user_id);

-- 자산별 거래 이력 시간순
CREATE INDEX idx_transactions_asset_traded ON transactions (asset_id, traded_at DESC);

-- holdings.asset_id는 UNIQUE 제약(uk_holdings_asset_id)으로 이미 인덱스가 생성되므로 별도 추가 불필요
