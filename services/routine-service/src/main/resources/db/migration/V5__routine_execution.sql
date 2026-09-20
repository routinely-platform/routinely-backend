-- #57: 미배포 브랜치의 스키마 변경은 V5 하나로 관리한다.
-- 기존 목표가 새 상한을 넘으면 자동 축소하지 않고 마이그레이션을 중단한다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM routine_templates
        WHERE (schedule_type = 'WEEKLY_COUNT' AND target_count > 6)
           OR (schedule_type = 'MONTHLY_COUNT' AND target_count > 28)) THEN
        RAISE EXCEPTION '목표 횟수 상한을 넘는 템플릿이 있습니다. 데이터 검토 후 다시 실행하세요.';
    END IF;
END $$;
ALTER TABLE routine_templates DROP CONSTRAINT ck_rt_schedule;
ALTER TABLE routine_templates ADD CONSTRAINT ck_rt_schedule CHECK (
    (schedule_type = 'DAILY'
        AND days_of_week IS NULL AND target_count IS NULL)
    OR (schedule_type = 'SPECIFIC_DAYS'
        AND days_of_week IS NOT NULL AND days_of_week BETWEEN 1 AND 127 AND target_count IS NULL)
    OR (schedule_type = 'WEEKLY_COUNT'
        AND target_count IS NOT NULL AND target_count BETWEEN 1 AND 6 AND days_of_week IS NULL)
    OR (schedule_type = 'MONTHLY_COUNT'
        AND target_count IS NOT NULL AND target_count BETWEEN 1 AND 28 AND days_of_week IS NULL)
);

-- ─────────────────────────────────────────────
-- ① ADR-0040 — 루틴이 정의를 복사해 자기완결적으로 존재한다
--    템플릿을 고치거나 지워도 진행 중 루틴이 흔들리지 않는다.
-- ─────────────────────────────────────────────
ALTER TABLE routines ADD COLUMN title         VARCHAR(100);
ALTER TABLE routines ADD COLUMN category_code VARCHAR(30);
ALTER TABLE routines ADD COLUMN schedule_type VARCHAR(20);
ALTER TABLE routines ADD COLUMN days_of_week  SMALLINT NULL;
ALTER TABLE routines ADD COLUMN target_count  INT      NULL;

-- 기존 행은 참조 중인 템플릿에서 복사해 백필한다.
UPDATE routines r
SET title         = t.title,
    category_code = t.category_code,
    schedule_type = t.schedule_type,
    days_of_week  = t.days_of_week,
    target_count  = t.target_count
FROM routine_templates t
WHERE r.routine_template_id = t.id;

-- 템플릿 없이 만든 루틴을 허용한다 (ADR-0040 §2.4).
-- 출처 표시일 뿐이므로 조회·판정에 쓰지 않는다.
ALTER TABLE routines ALTER COLUMN routine_template_id DROP NOT NULL;

ALTER TABLE routines ALTER COLUMN title         SET NOT NULL;
ALTER TABLE routines ALTER COLUMN category_code SET NOT NULL;
ALTER TABLE routines ALTER COLUMN schedule_type SET NOT NULL;

-- ⚠️ routine_templates.ck_rt_schedule 을 미러링한다. 두 제약은 짝이다 —
--    한쪽을 고치면 다른 쪽도 함께 고친다. 코드에서는 RoutineDefinition 이
--    이 규칙의 유일한 대응물이다.
ALTER TABLE routines ADD CONSTRAINT ck_routines_schedule CHECK (
    (schedule_type = 'DAILY'
        AND days_of_week IS NULL AND target_count IS NULL)
    OR (schedule_type = 'SPECIFIC_DAYS'
        AND days_of_week IS NOT NULL AND days_of_week BETWEEN 1 AND 127 AND target_count IS NULL)
    OR (schedule_type = 'WEEKLY_COUNT'
        AND target_count IS NOT NULL AND target_count BETWEEN 1 AND 6 AND days_of_week IS NULL)
    OR (schedule_type = 'MONTHLY_COUNT'
        AND target_count IS NOT NULL AND target_count BETWEEN 1 AND 28 AND days_of_week IS NULL)
);

COMMENT ON COLUMN routines.routine_template_id IS '출처 템플릿 ID (선택) — 템플릿 없이 직접 만든 루틴이면 NULL. 조회·판정에는 사용하지 않는다';
COMMENT ON COLUMN routines.title               IS '루틴 시작 시점의 제목 사본 — 이후 템플릿 수정에 영향 없음';
COMMENT ON COLUMN routines.category_code       IS '루틴 시작 시점의 카테고리 사본';
COMMENT ON COLUMN routines.schedule_type       IS '루틴 시작 시점의 반복 유형 사본 — 완료 기록 발생 후 변경 금지';
COMMENT ON COLUMN routines.days_of_week        IS '지정 요일 비트마스크(bit0=월 … bit6=일) — SPECIFIC_DAYS 전용. preferred_days(알림용 soft 선호)와 다르다';
COMMENT ON COLUMN routines.target_count        IS '기간당 목표 횟수 — WEEKLY_COUNT/MONTHLY_COUNT 전용';


-- ─────────────────────────────────────────────
-- ② ADR-0041 — 개인 루틴은 종료일이 선택이다(무기한)
--    챌린지 루틴의 필수는 DB CHECK 가 아니라 애플리케이션에서 건다.
--    조건부 제약(challenge_id IS NULL OR ended_at IS NOT NULL)은 스키마를
--    읽기 어렵게 만들고, 생성 경로가 challenge.started 소비자 하나로 고정되어 있다.
-- ─────────────────────────────────────────────
ALTER TABLE routines ALTER COLUMN ended_at DROP NOT NULL;

ALTER TABLE routines DROP CONSTRAINT IF EXISTS ck_routines_date_range;
ALTER TABLE routines ADD CONSTRAINT ck_routines_date_range CHECK (
    ended_at IS NULL OR ended_at >= started_at
);

COMMENT ON COLUMN routines.ended_at IS '루틴 종료일 — NULL이면 무기한(개인 루틴만). 챌린지 루틴은 애플리케이션에서 NOT NULL을 강제한다';


-- ③ 사진·메모의 소유권은 feed_cards에 있다.
ALTER TABLE feed_cards ADD COLUMN photo_object_key VARCHAR(500);
ALTER TABLE feed_cards ADD COLUMN scheduled_date DATE;
UPDATE feed_cards f SET scheduled_date = e.scheduled_date
FROM routine_executions e WHERE f.routine_execution_id = e.id;
-- 기존 실행 중 카드가 없는 행도 보존한다.
INSERT INTO feed_cards (routine_execution_id, user_id, challenge_id, routine_title,
                        photo_url, memo, scheduled_date)
SELECT e.id, e.user_id, r.challenge_id, r.title, e.photo_url, e.memo, e.scheduled_date
FROM routine_executions e JOIN routines r ON r.id = e.routine_id
WHERE e.status = 'COMPLETED'
  AND NOT EXISTS (SELECT 1 FROM feed_cards f WHERE f.routine_execution_id = e.id);
ALTER TABLE feed_cards ALTER COLUMN scheduled_date SET NOT NULL;
ALTER TABLE routine_executions DROP COLUMN photo_url, DROP COLUMN memo;
ALTER TABLE feed_reactions DROP CONSTRAINT fk_fr_feed_card;
ALTER TABLE feed_reactions ADD CONSTRAINT fk_fr_feed_card
    FOREIGN KEY (feed_card_id) REFERENCES feed_cards(id) ON DELETE CASCADE;
-- fk_fc_routine_execution은 NO ACTION 유지: 키를 읽고 카드를 먼저 삭제해야 한다.

CREATE INDEX idx_fc_user_scheduled_date ON feed_cards(user_id, scheduled_date DESC, id DESC);
CREATE INDEX idx_fc_challenge_scheduled_date ON feed_cards(challenge_id, scheduled_date DESC, id DESC)
    WHERE challenge_id IS NOT NULL;
