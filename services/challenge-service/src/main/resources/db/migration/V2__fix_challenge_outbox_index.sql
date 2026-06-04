DROP INDEX IF EXISTS idx_co_status;

CREATE INDEX idx_challenge_outbox_pending_polling
ON challenge_outbox (created_at ASC, id ASC)
WHERE status = 'PENDING';
