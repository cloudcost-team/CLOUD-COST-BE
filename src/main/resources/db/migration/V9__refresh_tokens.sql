-- 리프레시 토큰 (ADR-017). 원문은 HttpOnly 쿠키로만 내려주고, DB에는 SHA-256 해시만 둔다.
-- 쓸 때마다 새 토큰으로 바꾸고(rotation), 이미 쓴 토큰이 다시 오면 같은 family 전체를 무효화한다.

CREATE TABLE refresh_tokens
(
    id         UUID PRIMARY KEY,
    owner_id   VARCHAR(64)              NOT NULL,
    family_id  UUID                     NOT NULL,
    token_hash VARCHAR(64)              NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at    TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_owner_expires ON refresh_tokens (owner_id, expires_at);
