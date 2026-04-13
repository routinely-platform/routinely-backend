-- =============================================
-- challenge-service DDL
-- PostgreSQL 기준
-- =============================================

-- 1. challenges
CREATE TABLE challenges (
                            id              BIGINT        GENERATED ALWAYS AS IDENTITY,
                            creator_user_id BIGINT                                 NOT NULL,
                            title           VARCHAR(100)                           NOT NULL,
                            description     VARCHAR(500)                           NULL,
                            is_public       BOOLEAN       DEFAULT true             NOT NULL,
                            invite_code     VARCHAR(20)                            NULL,
                            max_members     INT                                    NOT NULL,
                            status          VARCHAR(20)                            NOT NULL,
                            started_at      DATE                                   NOT NULL,
                            ended_at        DATE                                   NOT NULL,
                            created_at      TIMESTAMPTZ   DEFAULT now()            NOT NULL,
                            updated_at      TIMESTAMPTZ   DEFAULT now()            NOT NULL,

                            CONSTRAINT pk_challenges             PRIMARY KEY (id),
                            CONSTRAINT uq_challenges_invite_code UNIQUE (invite_code),
                            CONSTRAINT ck_challenges_status      CHECK (status IN ('WAITING', 'ACTIVE', 'ENDED')),
                            CONSTRAINT ck_challenges_invite_code CHECK (is_public = true OR invite_code IS NOT NULL),
                            CONSTRAINT ck_challenges_max_members CHECK (max_members >= 2),
                            CONSTRAINT ck_challenges_date_range  CHECK (ended_at >= started_at)
);

COMMENT ON TABLE  challenges                  IS '챌린지 정보';
COMMENT ON COLUMN challenges.id               IS '챌린지 고유 식별자 (PK)';
COMMENT ON COLUMN challenges.creator_user_id  IS '챌린지 생성자 사용자 ID (user-service 참조 — FK 불가)';
COMMENT ON COLUMN challenges.title            IS '챌린지명';
COMMENT ON COLUMN challenges.description      IS '챌린지 설명 (선택)';
COMMENT ON COLUMN challenges.is_public        IS '공개 여부 — true: 공개 / false: 비공개 (초대코드 필요)';
COMMENT ON COLUMN challenges.invite_code      IS '비공개 챌린지 초대 코드 (UNIQUE) — is_public=false이면 반드시 NOT NULL';
COMMENT ON COLUMN challenges.max_members      IS '최대 참여 인원 수 (최소 2명)';
COMMENT ON COLUMN challenges.status           IS '챌린지 상태 — WAITING: 대기 / ACTIVE: 진행중 / ENDED: 종료';
COMMENT ON COLUMN challenges.started_at       IS '챌린지 시작일';
COMMENT ON COLUMN challenges.ended_at         IS '챌린지 종료일';
COMMENT ON COLUMN challenges.created_at       IS '챌린지 생성일시';
COMMENT ON COLUMN challenges.updated_at       IS '챌린지 최종 수정일시 — 애플리케이션 레벨에서 갱신';

CREATE INDEX idx_challenges_status      ON challenges (status);
CREATE INDEX idx_challenges_invite_code ON challenges (invite_code) WHERE invite_code IS NOT NULL;


-- 2. challenge_members
CREATE TABLE challenge_members (
                                   id           BIGINT        GENERATED ALWAYS AS IDENTITY,
                                   challenge_id BIGINT                                 NOT NULL,
                                   user_id      BIGINT                                 NOT NULL,
                                   role         VARCHAR(20)                            NOT NULL,
                                   status       VARCHAR(20)                            NOT NULL,
                                   joined_at    TIMESTAMPTZ   DEFAULT now()            NOT NULL,
                                   left_at      TIMESTAMPTZ                            NULL,

                                   CONSTRAINT pk_challenge_members PRIMARY KEY (id),
                                   CONSTRAINT uq_cm_challenge_user UNIQUE (challenge_id, user_id),
                                   CONSTRAINT ck_cm_role           CHECK (role IN ('LEADER', 'MEMBER')),
                                   CONSTRAINT ck_cm_status         CHECK (status IN ('ACTIVE', 'LEFT', 'EXPELLED')),
                                   CONSTRAINT ck_cm_left_at        CHECK (
                                       status = 'ACTIVE' OR left_at IS NOT NULL
                                       ),
                                   CONSTRAINT fk_cm_challenge      FOREIGN KEY (challenge_id) REFERENCES challenges (id)
);

COMMENT ON TABLE  challenge_members              IS '챌린지 참여 멤버 — 재참여 시 기존 행 UPDATE 방식으로 처리';
COMMENT ON COLUMN challenge_members.id           IS '멤버 레코드 고유 식별자 (PK)';
COMMENT ON COLUMN challenge_members.challenge_id IS '참여 챌린지 ID — user_id와 복합 UNIQUE';
COMMENT ON COLUMN challenge_members.user_id      IS '참여 사용자 ID (user-service 참조 — FK 불가)';
COMMENT ON COLUMN challenge_members.role         IS '역할 — LEADER: 방장 / MEMBER: 일반 멤버';
COMMENT ON COLUMN challenge_members.status       IS '참여 상태 — ACTIVE: 참여중 / LEFT: 자발적 탈퇴 / EXPELLED: 강퇴';
COMMENT ON COLUMN challenge_members.joined_at    IS '가장 최근 참여일시 — 재참여 시 갱신';
COMMENT ON COLUMN challenge_members.left_at      IS '탈퇴/강퇴 시각 — ACTIVE이면 NULL, 재참여 시 NULL로 초기화';

CREATE INDEX idx_cm_challenge_id ON challenge_members (challenge_id);
CREATE INDEX idx_cm_user_id      ON challenge_members (user_id);


-- 3. challenge_member_summary
CREATE TABLE challenge_member_summary (
                                          id                BIGINT         GENERATED ALWAYS AS IDENTITY,
                                          challenge_id      BIGINT                                   NOT NULL,
                                          user_id           BIGINT                                   NOT NULL,
                                          total_scheduled   INT            DEFAULT 0                 NOT NULL,
                                          completed_count   INT            DEFAULT 0                 NOT NULL,
                                          achievement_rate  NUMERIC(5, 2)  DEFAULT 0                 NOT NULL,
                                          last_completed_at TIMESTAMPTZ                              NULL,
                                          created_at        TIMESTAMPTZ    DEFAULT now()             NOT NULL,
                                          updated_at        TIMESTAMPTZ    DEFAULT now()             NOT NULL,

                                          CONSTRAINT pk_challenge_member_summary PRIMARY KEY (id),
                                          CONSTRAINT uq_cms_challenge_user       UNIQUE (challenge_id, user_id),
                                          CONSTRAINT ck_cms_counts               CHECK (completed_count <= total_scheduled),
                                          CONSTRAINT ck_cms_achievement_rate     CHECK (achievement_rate BETWEEN 0 AND 100),
                                          CONSTRAINT fk_cms_challenge            FOREIGN KEY (challenge_id) REFERENCES challenges (id)
);

COMMENT ON TABLE  challenge_member_summary                   IS '챌린지 멤버별 달성 집계 — 랭킹 조회 read 최적화용';
COMMENT ON COLUMN challenge_member_summary.id                IS '집계 레코드 고유 식별자 (PK)';
COMMENT ON COLUMN challenge_member_summary.challenge_id      IS '챌린지 ID — user_id와 복합 UNIQUE';
COMMENT ON COLUMN challenge_member_summary.user_id           IS '집계 대상 사용자 ID (user-service 참조 — FK 불가)';
COMMENT ON COLUMN challenge_member_summary.total_scheduled   IS '챌린지 기간 내 전체 예정 루틴 수';
COMMENT ON COLUMN challenge_member_summary.completed_count   IS '완료한 루틴 수';
COMMENT ON COLUMN challenge_member_summary.achievement_rate  IS '달성률 (%) — completed_count / total_scheduled * 100';
COMMENT ON COLUMN challenge_member_summary.last_completed_at IS '가장 최근 루틴 완료 시각';
COMMENT ON COLUMN challenge_member_summary.created_at        IS '레코드 생성일시';
COMMENT ON COLUMN challenge_member_summary.updated_at        IS '레코드 최종 수정일시 — 애플리케이션 레벨에서 갱신';

CREATE INDEX idx_cms_challenge_rate ON challenge_member_summary (challenge_id, achievement_rate DESC);


-- 4. challenge_outbox
CREATE TABLE challenge_outbox (
                                  id              BIGINT        GENERATED ALWAYS AS IDENTITY,
                                  aggregate_type  VARCHAR(50)                           NOT NULL,
                                  aggregate_id    BIGINT                                NOT NULL,
                                  event_type      VARCHAR(100)                          NOT NULL,
                                  payload         JSONB                                 NOT NULL,
                                  status          VARCHAR(20)   DEFAULT 'PENDING'       NOT NULL,
                                  created_at      TIMESTAMPTZ   DEFAULT now()           NOT NULL,
                                  published_at    TIMESTAMPTZ                           NULL,
                                  retry_count     INT           DEFAULT 0               NOT NULL,
                                  idempotency_key VARCHAR(200)                          NULL,

                                  CONSTRAINT pk_challenge_outbox   PRIMARY KEY (id),
                                  CONSTRAINT uq_co_idempotency_key UNIQUE (idempotency_key),
                                  CONSTRAINT ck_co_status          CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
                                  CONSTRAINT ck_co_retry_count     CHECK (retry_count >= 0)
);

COMMENT ON TABLE  challenge_outbox                 IS 'Outbox 패턴 — Kafka 발행 전 이벤트 임시 저장소';
COMMENT ON COLUMN challenge_outbox.id              IS '레코드 고유 식별자 (PK)';
COMMENT ON COLUMN challenge_outbox.aggregate_type  IS '이벤트 발생 엔티티 유형 (예: CHALLENGE, CHALLENGE_MEMBER)';
COMMENT ON COLUMN challenge_outbox.aggregate_id    IS '이벤트 발생 엔티티의 PK';
COMMENT ON COLUMN challenge_outbox.event_type      IS 'Kafka 토픽 이벤트 유형 (예: challenge.created, challenge.member.joined)';
COMMENT ON COLUMN challenge_outbox.payload         IS 'Kafka에 발행할 이벤트 JSON 데이터';
COMMENT ON COLUMN challenge_outbox.status          IS '발행 상태 — PENDING: 대기 / PUBLISHED: 발행 완료 / FAILED: 실패';
COMMENT ON COLUMN challenge_outbox.created_at      IS '이벤트 생성일시';
COMMENT ON COLUMN challenge_outbox.published_at    IS 'Kafka 발행 완료 시각';
COMMENT ON COLUMN challenge_outbox.retry_count     IS '발행 재시도 횟수';
COMMENT ON COLUMN challenge_outbox.idempotency_key IS '중복 이벤트 방지 키 (UNIQUE) — aggregate_type:aggregate_id:event_type:version';

CREATE INDEX idx_co_status ON challenge_outbox (status) WHERE status = 'PENDING';


-- 5. challenge_inbox
CREATE TABLE challenge_inbox (
                                 id             BIGINT        GENERATED ALWAYS AS IDENTITY,
                                 message_id     VARCHAR(100)                          NOT NULL,
                                 event_type     VARCHAR(100)                          NOT NULL,
                                 payload        JSONB                                 NOT NULL,
                                 status         VARCHAR(20)   DEFAULT 'RECEIVED'      NOT NULL,
                                 received_at    TIMESTAMPTZ   DEFAULT now()           NOT NULL,
                                 processed_at   TIMESTAMPTZ                           NULL,
                                 aggregate_type VARCHAR(50)                           NULL,
                                 aggregate_id   BIGINT                                NULL,

                                 CONSTRAINT pk_challenge_inbox PRIMARY KEY (id),
                                 CONSTRAINT uq_ci_message_id   UNIQUE (message_id),
                                 CONSTRAINT ck_ci_status       CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED'))
);

COMMENT ON TABLE  challenge_inbox                IS 'Inbox 패턴 — Kafka 수신 이벤트 중복 처리 방지 및 이력 관리';
COMMENT ON COLUMN challenge_inbox.id             IS '레코드 고유 식별자 (PK)';
COMMENT ON COLUMN challenge_inbox.message_id     IS 'Kafka 메시지 고유 ID (UNIQUE) — 중복 수신 방지';
COMMENT ON COLUMN challenge_inbox.event_type     IS '수신된 이벤트 유형';
COMMENT ON COLUMN challenge_inbox.payload        IS '수신된 이벤트 JSON 데이터';
COMMENT ON COLUMN challenge_inbox.status         IS '처리 상태 — RECEIVED: 수신 / PROCESSED: 처리 완료 / FAILED: 처리 실패';
COMMENT ON COLUMN challenge_inbox.received_at    IS 'Kafka 메시지 수신일시';
COMMENT ON COLUMN challenge_inbox.processed_at   IS '이벤트 처리 완료 시각';
COMMENT ON COLUMN challenge_inbox.aggregate_type IS '이벤트 대상 엔티티 유형';
COMMENT ON COLUMN challenge_inbox.aggregate_id   IS '이벤트 대상 엔티티의 PK';

CREATE INDEX idx_ci_status ON challenge_inbox (status) WHERE status = 'RECEIVED';