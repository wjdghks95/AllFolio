-- 거래 이력 커서 페이지네이션(GET /v1/assets/{id}/transactions) 복합 인덱스 보강
-- 기존 idx_transactions_asset_traded (asset_id, traded_at DESC)에는 타이브레이크 컬럼 id가 없어
-- 커서 조건이 인덱스 레인지가 아닌 Filter로 처리됐다(ROADMAP Task 024 「남은 갭」).
-- id DESC를 정렬 컬럼으로 추가하면 row-value 비교 (traded_at, id) < (:t, :id)가 통째로
-- Index Cond로 내려간다. PG18 실측(타깃 자산 2만 건, 커서 offset 19,000 지점, LIMIT 21):
--   Filter 방식  : Rows Removed by Filter 19001 / Buffers hit 566 / 1.24ms
--   Index Cond   : Rows Removed by Filter 0     / Buffers hit 4   / 0.03ms
CREATE INDEX idx_transactions_asset_traded_id ON transactions (asset_id, traded_at DESC, id DESC);

-- 기존 인덱스는 신규 인덱스의 선행 컬럼 부분집합이라 완전히 중복된다(첫 페이지 조회도 신규
-- 인덱스가 그대로 커버함을 EXPLAIN으로 확인). 쓰기 시 인덱스 갱신 비용만 이중으로 들므로 제거한다.
DROP INDEX idx_transactions_asset_traded;
