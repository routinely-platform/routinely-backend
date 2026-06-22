-- =============================================
-- routine_inbox 재시도 메타데이터 추가
-- 처리 실패 시 retry_count 증가 후 RECEIVED 유지로 재폴링 재시도하고,
-- MAX_RETRY 초과 시에만 FAILED로 전이한다. (challenge_outbox 재시도 모델과 대칭)
-- =============================================

ALTER TABLE routine_inbox
    ADD COLUMN retry_count INT  NOT NULL DEFAULT 0,
    ADD COLUMN last_error  TEXT          NULL;

ALTER TABLE routine_inbox
    ADD CONSTRAINT ck_ri_retry_count CHECK (retry_count >= 0);

COMMENT ON COLUMN routine_inbox.retry_count IS '처리 재시도 횟수 — MAX_RETRY 초과 시 status=FAILED로 전이';
COMMENT ON COLUMN routine_inbox.last_error  IS '마지막 처리 실패 원인 메시지 (운영 디버깅용, 길이 제한 적용)';
