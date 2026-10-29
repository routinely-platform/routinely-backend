-- #167 (ADR-0044): 챌린지 루틴 정의는 challenge-service가 소유한다. routine_templates는 사용자가 저장한 틀 전용이 된다.
-- challenge.created → 챌린지 템플릿 생성 흐름(#132·#133)을 제거하면서 챌린지 연결 템플릿과 그 컬럼을 걷어낸다.

-- 챌린지 템플릿을 출처로 가리키는 루틴이 있으면 출처만 끊는다 — 루틴은 정의를 복사해 자기완결적이다(ADR-0040).
UPDATE routines
SET routine_template_id = NULL
WHERE routine_template_id IN (SELECT id FROM routine_templates WHERE challenge_id IS NOT NULL);

DELETE FROM routine_templates WHERE challenge_id IS NOT NULL;

-- 컬럼을 지우면 uq_rt_challenge_id와 부분 인덱스 idx_rt_challenge_id(V1)도 함께 사라진다.
ALTER TABLE routine_templates DROP CONSTRAINT uq_rt_challenge_id;
ALTER TABLE routine_templates DROP COLUMN challenge_id;

COMMENT ON TABLE routine_templates IS '루틴 템플릿 — 사용자가 저장한 루틴의 틀. 챌린지 루틴 정의는 challenge-service가 소유한다 (ADR-0044)';
COMMENT ON COLUMN routine_templates.user_id IS '템플릿 소유자 사용자 ID';
