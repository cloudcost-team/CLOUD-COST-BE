-- Google 로그인 (ADR-004). 비밀번호는 저장하지 않는다. Google이 확인한 이메일·이름과 Google 고유 ID(sub)만 둔다.
-- 로컬 DevAuth(X-Owner-Id) 사용자는 auth_provider가 NULL이다.

ALTER TABLE users ADD COLUMN email VARCHAR(320);
ALTER TABLE users ADD COLUMN display_name VARCHAR(200);
ALTER TABLE users ADD COLUMN auth_provider VARCHAR(16);
ALTER TABLE users ADD COLUMN provider_subject VARCHAR(255);
ALTER TABLE users ADD COLUMN last_login_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD CONSTRAINT uq_users_provider_subject UNIQUE (auth_provider, provider_subject);
