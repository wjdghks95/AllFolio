-- Task 029: 푸시 알림(FCM/APNs) 기기 토큰 저장 테이블
-- refresh_tokens(V3)와 구조는 비슷하지만 token을 해시하지 않고 평문으로 저장한다
--   → FCM 토큰은 위협 모델이 다르다. 탈취돼도 "그 기기로 알림을 보낼 수 있다" 이상의 권한이 없고,
--     FCM 발송 시 원문 토큰 자체를 Google에 전달해야 하므로 해시로는 기능이 성립하지 않는다.
-- version 컬럼 없음: "등록 또는 revoke"뿐인 단순 갱신 패턴이라 동시 수정 충돌 시나리오가 없다
--   (refresh_tokens와 동일한 이유).

CREATE TABLE device_tokens (
    id         UUID           PRIMARY KEY DEFAULT uuidv7(),
    user_id    UUID           NOT NULL,
    token      VARCHAR(4096)  NOT NULL,
    platform   VARCHAR(10)    NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_device_tokens_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uk_device_tokens_token UNIQUE (token)
);

-- 유저 단위 전체 조회(해지 이력 포함)용
CREATE INDEX idx_device_tokens_user_id ON device_tokens (user_id);

-- 발송 대상 조회는 항상 "해당 유저의 활성 토큰"이라 부분 인덱스로 죽은 토큰을 인덱스에서 제외한다
CREATE INDEX idx_device_tokens_active ON device_tokens (user_id) WHERE revoked_at IS NULL;
