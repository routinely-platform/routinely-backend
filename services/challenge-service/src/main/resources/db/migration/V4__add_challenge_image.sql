-- 챌린지 대표 이미지 (#142)
-- 접근 URL 과 저장소 삭제/교체에 사용할 objectKey 를 함께 보관한다.
ALTER TABLE challenges ADD COLUMN IF NOT EXISTS image_url VARCHAR(500) NULL;
ALTER TABLE challenges ADD COLUMN IF NOT EXISTS image_object_key VARCHAR(500) NULL;

COMMENT ON COLUMN challenges.image_url IS '대표 이미지 접근 URL';
COMMENT ON COLUMN challenges.image_object_key IS '대표 이미지 저장소 오브젝트 키 (삭제/교체용)';
