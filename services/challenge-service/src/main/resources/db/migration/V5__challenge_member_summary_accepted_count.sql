ALTER TABLE challenge_member_summary
    ADD COLUMN accepted_count INT NOT NULL DEFAULT 0,
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_cms_accepted_count CHECK (accepted_count >= 0),
    ADD CONSTRAINT ck_cms_revision CHECK (revision >= 0);

DROP INDEX IF EXISTS idx_cms_challenge_rate;
CREATE INDEX idx_cms_challenge_accepted_count
    ON challenge_member_summary (challenge_id, accepted_count DESC);

COMMENT ON COLUMN challenge_member_summary.accepted_count IS '기간별 캡 적용 누적 인정 횟수 — 랭킹 점수';
COMMENT ON COLUMN challenge_member_summary.revision IS '마지막 반영 이벤트 revision — 이하 값은 폐기';
COMMENT ON COLUMN challenge_member_summary.achievement_rate IS '미사용(#61) — 후속 정리에서 삭제';
COMMENT ON COLUMN challenge_member_summary.completed_count IS '미사용(#61) — 후속 정리에서 삭제';
COMMENT ON COLUMN challenge_member_summary.total_scheduled IS '미사용(#61) — 분모는 저장하지 않음';
COMMENT ON COLUMN challenge_member_summary.last_completed_at IS '현재 accepted_count에 도달한 시각 — 동점 나열 기준. 발행 측이 계산한 reachedAt을 그대로 반영';
