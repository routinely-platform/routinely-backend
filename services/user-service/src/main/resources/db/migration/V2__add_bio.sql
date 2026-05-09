-- 한 줄 소개(bio) 컬럼 추가
ALTER TABLE users ADD COLUMN bio VARCHAR(100) NULL;

COMMENT ON COLUMN users.bio IS '한 줄 소개 (최대 100자, nullable)';
