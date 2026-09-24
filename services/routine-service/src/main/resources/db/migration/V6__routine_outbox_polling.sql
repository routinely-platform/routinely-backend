DROP INDEX IF EXISTS idx_ro_status;

CREATE INDEX idx_routine_outbox_pending_polling
    ON routine_outbox (created_at ASC, id ASC) WHERE status = 'PENDING';

ALTER TABLE routine_outbox ADD COLUMN partition_key VARCHAR(100);
COMMENT ON COLUMN routine_outbox.partition_key IS 'Kafka 파티션 키 — 루틴 이벤트는 userId';

-- 기존 행이 있으면 payload의 userId로 복원한다. 식별할 수 없는 행은 NULL을 유지한다.
UPDATE routine_outbox SET partition_key = payload ->> 'userId';

CREATE SEQUENCE routine_event_revision_seq;
COMMENT ON SEQUENCE routine_event_revision_seq IS '루틴 랭킹 이벤트 revision — 롤백 시 결번 허용';
