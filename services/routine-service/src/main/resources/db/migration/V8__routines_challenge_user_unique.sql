-- #157: challenge.started 소비로 멤버별 챌린지 루틴을 만든다.
-- 한 사용자는 한 챌린지에 루틴을 하나만 가진다 — 이 제약은 그 전제의 최후 방어선이다.
--
-- 멱등성은 Inbox(message_id UNIQUE)와 한 트랜잭션 처리로 이미 보장된다. 여기서 막는 것은 그 밖의 경로 —
-- 같은 챌린지에 대해 eventId가 다른 started가 다시 오는 경우 같은 것 — 이다.
--
-- 부분 UNIQUE라 개인 루틴(challenge_id IS NULL)에는 걸리지 않는다. (challenge_id, user_id) 조회
-- (#158 탈퇴 처리)도 이 인덱스를 그대로 쓰므로 별도 인덱스를 두지 않는다.
--
-- ⚠️ MVP는 ACTIVE 재참여가 없어 성립한다. 재참여를 열면(v2 #118) 이 제약을 다시 본다.
CREATE UNIQUE INDEX uq_routines_challenge_user
    ON routines (challenge_id, user_id)
    WHERE challenge_id IS NOT NULL;

COMMENT ON INDEX uq_routines_challenge_user IS '챌린지당 사용자 루틴 1개 — challenge.started 소비(#157)의 최후 방어선. 개인 루틴 제외(부분 UNIQUE)';
COMMENT ON COLUMN routines.challenge_id IS '챌린지 루틴인 경우 챌린지 ID (challenge-service 참조 — FK 불가) — 개인 루틴이면 NULL. challenge.started 소비로만 채워진다 (#157)';
