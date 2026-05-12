ALTER TABLE users ADD COLUMN nickname_updated_at TIMESTAMP NULL;

COMMENT ON COLUMN users.nickname_updated_at IS '닉네임 마지막 변경 시각 (닉네임 변경 쿨다운 계산용)';
