-- #57 루틴 실행 기록(sparse 저장) — 완료 취소 시 S3 원본 오브젝트 삭제를 위해 오브젝트 키를 저장한다.
-- photo_url(외부 접근 URL)만으로는 버킷/키를 안전하게 역산할 수 없어 삭제용 키를 별도 컬럼으로 둔다.
ALTER TABLE routine_executions
    ADD COLUMN photo_object_key VARCHAR(500) NULL;

COMMENT ON COLUMN routine_executions.photo_object_key IS '인증 사진 S3 오브젝트 키 — 완료 취소 시 원본 삭제용 (선택)';
