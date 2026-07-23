-- 루틴 반복 모델 이원화: 요일 지정(강제) / 빈도(유연) + 선호 요일 개인화 (ADR-0039, #149)

-- 1. routine_templates: repeat_type/repeat_value → schedule_type/days_of_week/target_count
ALTER TABLE routine_templates ADD COLUMN schedule_type VARCHAR(20);
ALTER TABLE routine_templates ADD COLUMN days_of_week  SMALLINT NULL;
ALTER TABLE routine_templates ADD COLUMN target_count  INT      NULL;

-- 기존 데이터 매핑
UPDATE routine_templates
SET schedule_type = CASE repeat_type
        WHEN 'DAILY'     THEN 'DAILY'
        WHEN 'WEEKLY'    THEN 'WEEKLY_COUNT'
        WHEN 'WEEKLY_N'  THEN 'WEEKLY_COUNT'
        WHEN 'MONTHLY_N' THEN 'MONTHLY_COUNT'
        WHEN 'DAILY_N'   THEN 'DAILY'  -- 하루 N회는 신규 모델에서 미지원 → DAILY로 수렴
        ELSE 'DAILY'
    END,
    target_count = CASE
        WHEN repeat_type IN ('WEEKLY_N', 'MONTHLY_N') THEN repeat_value
        WHEN repeat_type = 'WEEKLY' THEN 1
        ELSE NULL
    END;

ALTER TABLE routine_templates ALTER COLUMN schedule_type SET NOT NULL;

ALTER TABLE routine_templates DROP CONSTRAINT IF EXISTS ck_rt_repeat_value;
ALTER TABLE routine_templates DROP COLUMN repeat_type;
ALTER TABLE routine_templates DROP COLUMN repeat_value;

-- days_of_week는 월~일 7비트만 유효하므로 1~127 범위로 강제한다(0=빈 집합, 128 이상/음수=잘못된 마스크 차단).
ALTER TABLE routine_templates ADD CONSTRAINT ck_rt_schedule CHECK (
    (schedule_type = 'DAILY'
        AND days_of_week IS NULL AND target_count IS NULL)
    OR (schedule_type = 'SPECIFIC_DAYS'
        AND days_of_week IS NOT NULL AND days_of_week BETWEEN 1 AND 127 AND target_count IS NULL)
    OR (schedule_type IN ('WEEKLY_COUNT', 'MONTHLY_COUNT')
        AND target_count IS NOT NULL AND target_count >= 1 AND days_of_week IS NULL)
);

COMMENT ON COLUMN routine_templates.schedule_type IS '반복 유형 — DAILY(매일)/SPECIFIC_DAYS(요일 지정·강제)/WEEKLY_COUNT(주 N회)/MONTHLY_COUNT(월 N회). (ADR-0039)';
COMMENT ON COLUMN routine_templates.days_of_week  IS '지정 요일 비트마스크(bit0=월 … bit6=일) — SPECIFIC_DAYS 전용, 그 외 NULL. 예: 월수금 = 21';
COMMENT ON COLUMN routine_templates.target_count  IS '기간당 목표 횟수 — WEEKLY_COUNT/MONTHLY_COUNT 전용, 그 외 NULL';

-- 2. routines: 선호 요일(soft) 추가 — 빈도 유형 루틴의 알림/표시용 선호 (ADR-0039)
ALTER TABLE routines ADD COLUMN preferred_days SMALLINT NULL;

-- preferred_days도 월~일 7비트(1~127)만 유효. NULL은 미설정.
ALTER TABLE routines ADD CONSTRAINT ck_routines_preferred_days CHECK (
    preferred_days IS NULL OR preferred_days BETWEEN 1 AND 127
);

COMMENT ON COLUMN routines.preferred_days IS '선호 요일 비트마스크(bit0=월 … bit6=일) — 알림/표시용 개인 선호(soft). 완료를 제약하지 않음. NULL 허용';
