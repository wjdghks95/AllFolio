-- Task 019: Refresh Token 저장 테이블
-- Refresh Token은 JWT가 아니라 무작위 문자열이며, DB에는 SHA-256 hex(64자) 해시만 저장한다
--   → 로그아웃/토큰 회전 시 서버가 revoked_at을 세팅해 특정 토큰만 무효화할 수 있다.
-- version 컬럼 없음: "token_hash로 단건 조회 후 즉시 revoke"하는 단순 갱신 패턴이라
--   동시 수정 충돌 시나리오가 없다(users 테이블과 동일한 이유).

CREATE TABLE refresh_tokens (
    id         UUID           PRIMARY KEY DEFAULT uuidv7(),
    user_id    UUID           NOT NULL,
    token_hash VARCHAR(64)    NOT NULL,
    expires_at TIMESTAMPTZ    NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_refresh_tokens_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash)
);

-- 로그아웃 시 해당 유저의 토큰 조회용. token_hash는 UNIQUE 제약으로 인덱스가 이미 생성된다
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
