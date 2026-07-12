-- 프로필 이미지 Object Key 컬럼 추가 (S3 삭제/교체용).
-- url 은 기존 profile_image_url 컬럼을 재사용하고, ProfileImage 임베더블로 함께 묶는다.
ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_image_object_key VARCHAR(500) NULL;

COMMENT ON COLUMN users.profile_image_object_key IS '프로필 이미지 오브젝트 키 (S3 삭제/교체용)';
