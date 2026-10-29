-- #167 (ADR-0044): 챌린지 루틴 정의를 challenge-service가 직접 소유한다.
-- 백필하지 않고 처음부터 NOT NULL로 만든다 — 배포된 운영 데이터가 없다.
-- 기존 challenges 행이 있으면 NOT NULL 추가가 실패한다 → ./scripts/local-down.sh 후 볼륨을 비우고 다시 기동한다.
ALTER TABLE challenges ADD COLUMN routine_title VARCHAR(100) NOT NULL;
ALTER TABLE challenges ADD COLUMN schedule_type VARCHAR(20)  NOT NULL;
ALTER TABLE challenges ADD COLUMN days_of_week  SMALLINT     NULL;
ALTER TABLE challenges ADD COLUMN target_count  INT          NULL;

-- ⚠️ routine-service의 ck_rt_schedule · ck_routines_schedule과 짝이다(같은 규칙, 글자 그대로 복사).
--    한쪽을 고치면 세 제약과 ChallengeRoutineDefinition · RoutineDefinition을 함께 고친다.
ALTER TABLE challenges ADD CONSTRAINT ck_challenges_schedule CHECK (
    (schedule_type = 'DAILY'
        AND days_of_week IS NULL AND target_count IS NULL)
    OR (schedule_type = 'SPECIFIC_DAYS'
        AND days_of_week IS NOT NULL AND days_of_week BETWEEN 1 AND 127 AND target_count IS NULL)
    OR (schedule_type = 'WEEKLY_COUNT'
        AND target_count IS NOT NULL AND target_count BETWEEN 1 AND 6 AND days_of_week IS NULL)
    OR (schedule_type = 'MONTHLY_COUNT'
        AND target_count IS NOT NULL AND target_count BETWEEN 1 AND 28 AND days_of_week IS NULL)
);

COMMENT ON COLUMN challenges.routine_title IS '챌린지 루틴 제목 — 방장 혼자인 WAITING 상태에서만 수정 가능';
COMMENT ON COLUMN challenges.schedule_type IS '반복 유형 — DAILY / SPECIFIC_DAYS / WEEKLY_COUNT / MONTHLY_COUNT (생성 후 변경 불가)';
COMMENT ON COLUMN challenges.days_of_week  IS '지정 요일 비트마스크(bit0=월 … bit6=일) — SPECIFIC_DAYS 전용, 그 외 NULL (변경 불가)';
COMMENT ON COLUMN challenges.target_count  IS '기간당 목표 횟수 — WEEKLY_COUNT 1~6 · MONTHLY_COUNT 1~28, 그 외 NULL (변경 불가)';
