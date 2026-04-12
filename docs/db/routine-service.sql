-- =============================================
-- routine-service DDL
-- PostgreSQL 기준
-- =============================================

-- 1. routine_templates
CREATE TABLE routine_templates (
                                   id             BIGINT       GENERATED ALWAYS AS IDENTITY,
                                   owner_id       BIGINT                                NOT NULL,
                                   owner_type     VARCHAR(20)                           NOT NULL,
                                   title          VARCHAR(100)                          NOT NULL,
                                   category       VARCHAR(20)                           NOT NULL,
                                   repeat_type    VARCHAR(20)                           NOT NULL,
                                   repeat_value   INT                                   NULL,
                                   preferred_time TIME                                  NULL,
                                   is_deleted     BOOLEAN      DEFAULT false            NOT NULL,
                                   deleted_at     TIMESTAMPTZ                           NULL,
                                   created_at     TIMESTAMPTZ  DEFAULT now()            NOT NULL,
                                   updated_at     TIMESTAMPTZ  DEFAULT now()            NOT NULL,

                                   CONSTRAINT pk_routine_templates PRIMARY KEY (id),
                                   CONSTRAINT ck_rt_repeat_value   CHECK (
                                       (repeat_type IN ('WEEKLY_N', 'MONTHLY_N') AND repeat_value IS NOT NULL)
                                           OR
                                       (repeat_type NOT IN ('WEEKLY_N', 'MONTHLY_N') AND repeat_value IS NULL)
                                       )
);

COMMENT ON TABLE  routine_templates                IS '루틴 설정 정보 템플릿 — 루틴의 틀을 정의';
COMMENT ON COLUMN routine_templates.id             IS '루틴 템플릿 고유 식별자 (PK)';
COMMENT ON COLUMN routine_templates.owner_id       IS '템플릿 소유자 ID — owner_type에 따라 user_id 또는 challenge_id';
COMMENT ON COLUMN routine_templates.owner_type     IS '소유자 유형 — PERSONAL: 개인 루틴 / CHALLENGE: 챌린지 루틴';
COMMENT ON COLUMN routine_templates.title          IS '루틴명 (예: 아침 러닝 30분)';
COMMENT ON COLUMN routine_templates.category       IS '루틴 카테고리 — 서버 Enum으로 검증';
COMMENT ON COLUMN routine_templates.repeat_type    IS '반복 유형 — DAILY/WEEKLY/WEEKLY_N/MONTHLY_N, 서버 Enum으로 검증';
COMMENT ON COLUMN routine_templates.repeat_value   IS '반복 횟수 — WEEKLY_N/MONTHLY_N일 때 N값, 나머지는 NULL';
COMMENT ON COLUMN routine_templates.preferred_time IS '선호 수행 시간 — 알림 발송 기준 시각 (선택)';
COMMENT ON COLUMN routine_templates.is_deleted     IS '소프트 딜리트 여부 — 물리 삭제 없이 false→true 처리';
COMMENT ON COLUMN routine_templates.deleted_at     IS '소프트 딜리트 처리 시각';
COMMENT ON COLUMN routine_templates.created_at     IS '템플릿 생성일시';
COMMENT ON COLUMN routine_templates.updated_at     IS '템플릿 최종 수정일시 — 애플리케이션 레벨에서 갱신';


-- 2. routines
CREATE TABLE routines (
                          id                  BIGINT       GENERATED ALWAYS AS IDENTITY,
                          routine_template_id BIGINT                                NOT NULL,
                          user_id             BIGINT                                NOT NULL,
                          challenge_id        BIGINT                                NULL,
                          started_at          DATE                                  NOT NULL,
                          ended_at            DATE                                  NOT NULL,
                          is_active           BOOLEAN      DEFAULT true             NOT NULL,
                          created_at          TIMESTAMPTZ  DEFAULT now()            NOT NULL,
                          updated_at          TIMESTAMPTZ  DEFAULT now()            NOT NULL,

                          CONSTRAINT pk_routines            PRIMARY KEY (id),
                          CONSTRAINT ck_routines_date_range CHECK (ended_at >= started_at),
                          CONSTRAINT fk_routines_template   FOREIGN KEY (routine_template_id)
                              REFERENCES routine_templates (id)
);

COMMENT ON TABLE  routines                     IS '사용자에게 활성화된 루틴 인스턴스 — 템플릿 기반으로 생성';
COMMENT ON COLUMN routines.id                  IS '루틴 고유 식별자 (PK)';
COMMENT ON COLUMN routines.routine_template_id IS '기반이 된 루틴 템플릿 ID — 템플릿 소프트 딜리트 시에도 유지';
COMMENT ON COLUMN routines.user_id             IS '루틴 소유 사용자 ID (user-service 참조 — FK 불가)';
COMMENT ON COLUMN routines.challenge_id        IS '챌린지 루틴인 경우 챌린지 ID (challenge-service 참조 — FK 불가) — 개인 루틴이면 NULL';
COMMENT ON COLUMN routines.started_at          IS '루틴 시작일';
COMMENT ON COLUMN routines.ended_at            IS '루틴 종료일';
COMMENT ON COLUMN routines.is_active           IS '루틴 활성 여부 — 일시정지/삭제 시 false';
COMMENT ON COLUMN routines.created_at          IS '루틴 생성일시';
COMMENT ON COLUMN routines.updated_at          IS '루틴 최종 수정일시 — 애플리케이션 레벨에서 갱신';

CREATE INDEX idx_routines_user_id       ON routines (user_id);
CREATE INDEX idx_routines_challenge_id  ON routines (challenge_id) WHERE challenge_id IS NOT NULL;


-- 3. routine_executions
CREATE TABLE routine_executions (
                                    id             BIGINT       GENERATED ALWAYS AS IDENTITY,
                                    routine_id     BIGINT                                NOT NULL,
                                    user_id        BIGINT                                NOT NULL,
                                    scheduled_date DATE                                  NOT NULL,
                                    status         VARCHAR(20)                           NOT NULL,
                                    completed_at   TIMESTAMPTZ                           NULL,
                                    photo_url      VARCHAR(500)                          NULL,
                                    memo           TEXT                                  NULL,
                                    created_at     TIMESTAMPTZ  DEFAULT now()            NOT NULL,
                                    updated_at     TIMESTAMPTZ  DEFAULT now()            NOT NULL,

                                    CONSTRAINT pk_routine_executions  PRIMARY KEY (id),
                                    CONSTRAINT uq_re_routine_date     UNIQUE (routine_id, scheduled_date),
                                    CONSTRAINT ck_re_status           CHECK (status IN ('PENDING', 'COMPLETED', 'MISSED')),
                                    CONSTRAINT ck_re_completed_at     CHECK (
                                        status != 'COMPLETED' OR completed_at IS NOT NULL
),
    CONSTRAINT fk_re_routine          FOREIGN KEY (routine_id)
                                          REFERENCES routines (id)
);

COMMENT ON TABLE  routine_executions                IS '루틴 일별 실행 기록 — 날짜별 수행 여부 및 인증 정보';
COMMENT ON COLUMN routine_executions.id             IS '실행 기록 고유 식별자 (PK)';
COMMENT ON COLUMN routine_executions.routine_id     IS '실행 대상 루틴 ID';
COMMENT ON COLUMN routine_executions.user_id        IS '실행한 사용자 ID (user-service 참조 — FK 불가)';
COMMENT ON COLUMN routine_executions.scheduled_date IS '수행 예정일 — routine_id와 복합 UNIQUE';
COMMENT ON COLUMN routine_executions.status         IS '실행 상태 — PENDING: 예정 / COMPLETED: 완료 / MISSED: 미완료';
COMMENT ON COLUMN routine_executions.completed_at   IS '완료 처리 시각 — status=COMPLETED일 때 반드시 NOT NULL';
COMMENT ON COLUMN routine_executions.photo_url      IS '루틴 완료 인증 사진 URL (선택)';
COMMENT ON COLUMN routine_executions.memo           IS '완료 시 작성한 메모 (선택)';
COMMENT ON COLUMN routine_executions.created_at     IS '레코드 생성일시';
COMMENT ON COLUMN routine_executions.updated_at     IS '레코드 최종 수정일시 — 애플리케이션 레벨에서 갱신';

CREATE INDEX idx_re_user_date ON routine_executions (user_id, scheduled_date);


-- 4. routine_daily_summary
CREATE TABLE routine_daily_summary (
                                       id               BIGINT         GENERATED ALWAYS AS IDENTITY,
                                       user_id          BIGINT                                   NOT NULL,
                                       challenge_id     BIGINT                                   NULL,
                                       summary_date     DATE                                     NOT NULL,
                                       total_count      INT            DEFAULT 0                 NOT NULL,
                                       completed_count  INT            DEFAULT 0                 NOT NULL,
                                       achievement_rate NUMERIC(5, 2)  DEFAULT 0                 NOT NULL,
                                       streak           INT            DEFAULT 0                 NOT NULL,
                                       created_at       TIMESTAMPTZ    DEFAULT now()             NOT NULL,
                                       updated_at       TIMESTAMPTZ    DEFAULT now()             NOT NULL,

                                       CONSTRAINT pk_routine_daily_summary   PRIMARY KEY (id),
                                       CONSTRAINT uq_rds_user_challenge_date UNIQUE (user_id, challenge_id, summary_date),
                                       CONSTRAINT ck_rds_counts              CHECK (completed_count <= total_count),
                                       CONSTRAINT ck_rds_achievement_rate    CHECK (achievement_rate BETWEEN 0 AND 100),
                                       CONSTRAINT ck_rds_streak              CHECK (streak >= 0)
);

COMMENT ON TABLE  routine_daily_summary                 IS '루틴 일별 집계 테이블 — 홈/통계 화면 read 최적화용';
COMMENT ON COLUMN routine_daily_summary.id              IS '집계 레코드 고유 식별자 (PK)';
COMMENT ON COLUMN routine_daily_summary.user_id         IS '집계 대상 사용자 ID (user-service 참조 — FK 불가)';
COMMENT ON COLUMN routine_daily_summary.challenge_id    IS '챌린지 집계면 챌린지 ID (challenge-service 참조 — FK 불가) — 개인 전체 집계면 NULL';
COMMENT ON COLUMN routine_daily_summary.summary_date    IS '집계 기준일 — user_id, challenge_id와 복합 UNIQUE';
COMMENT ON COLUMN routine_daily_summary.total_count     IS '해당 날짜 예정된 루틴 수';
COMMENT ON COLUMN routine_daily_summary.completed_count IS '해당 날짜 완료한 루틴 수';
COMMENT ON COLUMN routine_daily_summary.achievement_rate IS '달성률 (%) — completed_count / total_count * 100';
COMMENT ON COLUMN routine_daily_summary.streak          IS '해당 날짜 기준 연속 달성 일수';
COMMENT ON COLUMN routine_daily_summary.created_at      IS '레코드 생성일시';
COMMENT ON COLUMN routine_daily_summary.updated_at      IS '레코드 최종 수정일시 — 애플리케이션 레벨에서 갱신';

CREATE INDEX idx_rds_user_date      ON routine_daily_summary (user_id, summary_date);
CREATE INDEX idx_rds_challenge_date ON routine_daily_summary (challenge_id, summary_date)
    WHERE challenge_id IS NOT NULL;


-- 5. feed_cards
CREATE TABLE feed_cards (
                            id                   BIGINT       GENERATED ALWAYS AS IDENTITY,
                            routine_execution_id BIGINT                                NOT NULL,
                            user_id              BIGINT                                NOT NULL,
                            challenge_id         BIGINT                                NULL,
                            routine_title        VARCHAR(100)                          NOT NULL,
                            photo_url            VARCHAR(500)                          NULL,
                            memo                 TEXT                                  NULL,
                            created_at           TIMESTAMPTZ  DEFAULT now()            NOT NULL,
                            updated_at           TIMESTAMPTZ  DEFAULT now()            NOT NULL,

                            CONSTRAINT pk_feed_cards           PRIMARY KEY (id),
                            CONSTRAINT uq_fc_routine_execution UNIQUE (routine_execution_id),
                            CONSTRAINT fk_fc_routine_execution FOREIGN KEY (routine_execution_id)
                                REFERENCES routine_executions (id)
);

COMMENT ON TABLE  feed_cards                      IS '루틴 완료 인증 피드 — 개인 및 챌린지 피드 통합 관리';
COMMENT ON COLUMN feed_cards.id                   IS '피드 카드 고유 식별자 (PK)';
COMMENT ON COLUMN feed_cards.routine_execution_id IS '완료 처리된 루틴 실행 기록 ID (UNIQUE) — 1 실행 당 1 피드';
COMMENT ON COLUMN feed_cards.user_id              IS '피드 작성 사용자 ID (user-service 참조 — FK 불가)';
COMMENT ON COLUMN feed_cards.challenge_id         IS '챌린지 피드면 챌린지 ID (challenge-service 참조 — FK 불가) — 개인 피드면 NULL';
COMMENT ON COLUMN feed_cards.routine_title        IS '피드 생성 시점의 루틴명 스냅샷 — 이후 템플릿 수정에 영향 없음';
COMMENT ON COLUMN feed_cards.photo_url            IS '루틴 완료 인증 사진 URL (선택)';
COMMENT ON COLUMN feed_cards.memo                 IS '완료 소감 메모 (선택)';
COMMENT ON COLUMN feed_cards.created_at           IS '피드 생성일시';
COMMENT ON COLUMN feed_cards.updated_at           IS '피드 최종 수정일시 — 애플리케이션 레벨에서 갱신';

CREATE INDEX idx_fc_user_id      ON feed_cards (user_id, created_at DESC);
CREATE INDEX idx_fc_challenge_id ON feed_cards (challenge_id, created_at DESC)
    WHERE challenge_id IS NOT NULL;


-- 6. feed_reactions
CREATE TABLE feed_reactions (
                                id           BIGINT       GENERATED ALWAYS AS IDENTITY,
                                feed_card_id BIGINT                                NOT NULL,
                                user_id      BIGINT                                NOT NULL,
                                emoji        VARCHAR(10)                           NOT NULL,
                                created_at   TIMESTAMPTZ  DEFAULT now()            NOT NULL,
                                updated_at   TIMESTAMPTZ  DEFAULT now()            NOT NULL,

                                CONSTRAINT pk_feed_reactions     PRIMARY KEY (id),
                                CONSTRAINT uq_fr_card_user_emoji UNIQUE (feed_card_id, user_id, emoji),
                                CONSTRAINT fk_fr_feed_card       FOREIGN KEY (feed_card_id)
                                    REFERENCES feed_cards (id)
);

COMMENT ON TABLE  feed_reactions              IS '피드 카드 이모지 리액션';
COMMENT ON COLUMN feed_reactions.id           IS '리액션 고유 식별자 (PK)';
COMMENT ON COLUMN feed_reactions.feed_card_id IS '리액션 대상 피드 카드 ID';
COMMENT ON COLUMN feed_reactions.user_id      IS '리액션을 남긴 사용자 ID (user-service 참조 — FK 불가)';
COMMENT ON COLUMN feed_reactions.emoji        IS '이모지 문자 — 동일 사용자가 동일 피드에 동일 이모지 중복 불가';
COMMENT ON COLUMN feed_reactions.created_at   IS '리액션 생성일시';
COMMENT ON COLUMN feed_reactions.updated_at   IS '리액션 최종 수정일시 — 애플리케이션 레벨에서 갱신';

CREATE INDEX idx_fr_feed_card_id ON feed_reactions (feed_card_id);


-- 7. routine_outbox
CREATE TABLE routine_outbox (
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

                                CONSTRAINT pk_routine_outbox     PRIMARY KEY (id),
                                CONSTRAINT uq_ro_idempotency_key UNIQUE (idempotency_key),
                                CONSTRAINT ck_ro_status          CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
                                CONSTRAINT ck_ro_retry_count     CHECK (retry_count >= 0)
);

COMMENT ON TABLE  routine_outbox                 IS 'Outbox 패턴 — Kafka 발행 전 이벤트 임시 저장소';
COMMENT ON COLUMN routine_outbox.id              IS '레코드 고유 식별자 (PK)';
COMMENT ON COLUMN routine_outbox.aggregate_type  IS '이벤트 발생 엔티티 유형 (예: ROUTINE, ROUTINE_EXECUTION)';
COMMENT ON COLUMN routine_outbox.aggregate_id    IS '이벤트 발생 엔티티의 PK';
COMMENT ON COLUMN routine_outbox.event_type      IS 'Kafka 토픽 이벤트 유형 (예: routine.completed, routine.created)';
COMMENT ON COLUMN routine_outbox.payload         IS 'Kafka에 발행할 이벤트 JSON 데이터';
COMMENT ON COLUMN routine_outbox.status          IS '발행 상태 — PENDING: 대기 / PUBLISHED: 발행 완료 / FAILED: 실패';
COMMENT ON COLUMN routine_outbox.created_at      IS '이벤트 생성일시';
COMMENT ON COLUMN routine_outbox.published_at    IS 'Kafka 발행 완료 시각';
COMMENT ON COLUMN routine_outbox.retry_count     IS '발행 재시도 횟수';
COMMENT ON COLUMN routine_outbox.idempotency_key IS '중복 이벤트 방지 키 (UNIQUE) — aggregate_type:aggregate_id:event_type:version';

CREATE INDEX idx_ro_status ON routine_outbox (status) WHERE status = 'PENDING';


-- 8. routine_inbox
CREATE TABLE routine_inbox (
                               id             BIGINT        GENERATED ALWAYS AS IDENTITY,
                               message_id     VARCHAR(100)                          NOT NULL,
                               event_type     VARCHAR(100)                          NOT NULL,
                               payload        JSONB                                 NOT NULL,
                               status         VARCHAR(20)   DEFAULT 'RECEIVED'      NOT NULL,
                               received_at    TIMESTAMPTZ   DEFAULT now()           NOT NULL,
                               processed_at   TIMESTAMPTZ                           NULL,
                               aggregate_type VARCHAR(50)                           NULL,
                               aggregate_id   BIGINT                                NULL,

                               CONSTRAINT pk_routine_inbox  PRIMARY KEY (id),
                               CONSTRAINT uq_ri_message_id  UNIQUE (message_id),
                               CONSTRAINT ck_ri_status      CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED'))
);

COMMENT ON TABLE  routine_inbox                IS 'Inbox 패턴 — Kafka 수신 이벤트 중복 처리 방지 및 이력 관리';
COMMENT ON COLUMN routine_inbox.id             IS '레코드 고유 식별자 (PK)';
COMMENT ON COLUMN routine_inbox.message_id     IS 'Kafka 메시지 고유 ID (UNIQUE) — 중복 수신 방지';
COMMENT ON COLUMN routine_inbox.event_type     IS '수신된 이벤트 유형';
COMMENT ON COLUMN routine_inbox.payload        IS '수신된 이벤트 JSON 데이터';
COMMENT ON COLUMN routine_inbox.status         IS '처리 상태 — RECEIVED: 수신 / PROCESSED: 처리 완료 / FAILED: 처리 실패';
COMMENT ON COLUMN routine_inbox.received_at    IS 'Kafka 메시지 수신일시';
COMMENT ON COLUMN routine_inbox.processed_at   IS '이벤트 처리 완료 시각';
COMMENT ON COLUMN routine_inbox.aggregate_type IS '이벤트 대상 엔티티 유형 (예: CHALLENGE, CHALLENGE_MEMBER)';
COMMENT ON COLUMN routine_inbox.aggregate_id   IS '이벤트 대상 엔티티의 PK';

CREATE INDEX idx_ri_status ON routine_inbox (status) WHERE status = 'RECEIVED';