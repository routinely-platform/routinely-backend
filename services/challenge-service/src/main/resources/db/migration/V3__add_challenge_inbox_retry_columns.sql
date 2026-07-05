-- =============================================
-- challenge_inbox 재시도 메타데이터 추가 (#48)
-- 처리 실패 시 retry_count 증가 후 RECEIVED 유지로 재폴링 재시도하고,
-- MAX_RETRY 초과 시에만 FAILED로 전이한다. (routine_inbox / challenge_outbox 재시도 모델과 대칭)
-- =============================================

ALTER TABLE challenge_inbox
    ADD COLUMN retry_count INT  NOT NULL DEFAULT 0,
    ADD COLUMN last_error  TEXT          NULL;

ALTER TABLE challenge_inbox
    ADD CONSTRAINT ck_ci_retry_count CHECK (retry_count >= 0);

COMMENT ON COLUMN challenge_inbox.retry_count IS '처리 재시도 횟수 — MAX_RETRY 초과 시 status=FAILED로 전이';
COMMENT ON COLUMN challenge_inbox.last_error  IS '마지막 처리 실패 원인 메시지 (운영 디버깅용, 길이 제한 적용)';

-- RECEIVED 폴링 대상 부분 인덱스(idx_ci_status)는 V1__init.sql에서 이미 생성했으므로 여기서 재생성하지 않는다.
-- (재생성 시 Flyway 클린 마이그레이션에서 "relation already exists"로 실패)
