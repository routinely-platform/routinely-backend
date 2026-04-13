-- =============================================
-- chat-service DDL
-- PostgreSQL 기준
-- =============================================

-- 1. chat_rooms
CREATE TABLE chat_rooms (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY,
    challenge_id    BIGINT                                 NOT NULL,
    name            VARCHAR(100)                           NOT NULL,
    last_message_id BIGINT                                 NULL,
    last_message_at TIMESTAMPTZ                            NULL,
    created_at      TIMESTAMPTZ   DEFAULT now()            NOT NULL,
    updated_at      TIMESTAMPTZ   DEFAULT now()            NOT NULL,

    CONSTRAINT pk_chat_rooms         PRIMARY KEY (id),
    CONSTRAINT uq_cr_challenge_id    UNIQUE (challenge_id)
);

COMMENT ON TABLE  chat_rooms                  IS '챌린지 채팅방 — 챌린지당 1개 채팅방';
COMMENT ON COLUMN chat_rooms.id               IS '채팅방 고유 식별자 (PK)';
COMMENT ON COLUMN chat_rooms.challenge_id     IS '연결된 챌린지 ID (challenge-service 참조 — FK 불가) — 챌린지당 1개 채팅방 (UNIQUE)';
COMMENT ON COLUMN chat_rooms.name             IS '채팅방 이름 — 챌린지명으로 설정';
COMMENT ON COLUMN chat_rooms.last_message_id  IS '마지막 메시지 ID — 목록 표시용 캐싱 컬럼';
COMMENT ON COLUMN chat_rooms.last_message_at  IS '마지막 메시지 발송 시각 — 채팅방 목록 정렬 기준 캐싱 컬럼';
COMMENT ON COLUMN chat_rooms.created_at       IS '채팅방 생성일시';
COMMENT ON COLUMN chat_rooms.updated_at       IS '채팅방 최종 수정일시 — 애플리케이션 레벨에서 갱신';


-- 2. chat_room_members
CREATE TABLE chat_room_members (
    id                   BIGINT        GENERATED ALWAYS AS IDENTITY,
    chat_room_id         BIGINT                                 NOT NULL,
    user_id              BIGINT                                 NOT NULL,
    role                 VARCHAR(20)                            NOT NULL,
    last_read_message_id BIGINT                                 NULL,
    is_active            BOOLEAN       DEFAULT true             NOT NULL,
    joined_at            TIMESTAMPTZ   DEFAULT now()            NOT NULL,
    left_at              TIMESTAMPTZ                            NULL,

    CONSTRAINT pk_chat_room_members  PRIMARY KEY (id),
    CONSTRAINT uq_crm_room_user      UNIQUE (chat_room_id, user_id),
    CONSTRAINT ck_crm_role           CHECK (role IN ('OWNER', 'MEMBER')),
    CONSTRAINT ck_crm_left_at        CHECK (
        is_active = true OR left_at IS NOT NULL
        ),
    CONSTRAINT fk_crm_chat_room      FOREIGN KEY (chat_room_id)
        REFERENCES chat_rooms (id)
);

COMMENT ON TABLE  chat_room_members                      IS '채팅방 참여 멤버';
COMMENT ON COLUMN chat_room_members.id                   IS '멤버 레코드 고유 식별자 (PK)';
COMMENT ON COLUMN chat_room_members.chat_room_id         IS '참여 채팅방 ID';
COMMENT ON COLUMN chat_room_members.user_id              IS '참여 사용자 ID (user-service 참조 — FK 불가)';
COMMENT ON COLUMN chat_room_members.role                 IS '채팅방 내 역할 — OWNER: 방장 / MEMBER: 일반 멤버';
COMMENT ON COLUMN chat_room_members.last_read_message_id IS '마지막으로 읽은 메시지 ID — 읽음 처리 기준';
COMMENT ON COLUMN chat_room_members.is_active            IS '채팅방 참여 활성 여부 — 나가기 시 false';
COMMENT ON COLUMN chat_room_members.joined_at            IS '채팅방 참여일시';
COMMENT ON COLUMN chat_room_members.left_at              IS '채팅방 퇴장 시각 — is_active=false이면 반드시 NOT NULL';

CREATE INDEX idx_crm_user_id      ON chat_room_members (user_id);
CREATE INDEX idx_crm_chat_room_id ON chat_room_members (chat_room_id);


-- 3. chat_messages
CREATE TABLE chat_messages (
    chat_message_id   BIGINT        GENERATED ALWAYS AS IDENTITY,
    chat_room_id      BIGINT                                 NOT NULL,
    parent_message_id BIGINT                                 NULL,
    sender_id         BIGINT                                 NULL,
    message_type      VARCHAR(20)                            NOT NULL,
    content           TEXT                                   NULL,
    image_url         VARCHAR(500)                           NULL,
    is_deleted        BOOLEAN       DEFAULT false            NOT NULL,
    created_at        TIMESTAMPTZ   DEFAULT now()            NOT NULL,
    updated_at        TIMESTAMPTZ   DEFAULT now()            NOT NULL,

    CONSTRAINT pk_chat_messages      PRIMARY KEY (chat_message_id),
    CONSTRAINT ck_cm_message_type    CHECK (message_type IN ('TEXT', 'IMAGE', 'SYSTEM')),
    CONSTRAINT ck_cm_sender_id       CHECK (
        message_type != 'SYSTEM' OR sender_id IS NULL
    ),
    CONSTRAINT ck_cm_content         CHECK (
        message_type != 'TEXT' OR content IS NOT NULL
    ),
    CONSTRAINT ck_cm_image_url       CHECK (
        message_type != 'IMAGE' OR image_url IS NOT NULL
    ),
    CONSTRAINT fk_cm_chat_room       FOREIGN KEY (chat_room_id)
        REFERENCES chat_rooms (id),
    CONSTRAINT fk_cm_parent_message  FOREIGN KEY (parent_message_id)
        REFERENCES chat_messages (chat_message_id)
);

COMMENT ON TABLE  chat_messages                   IS '채팅 메시지';
COMMENT ON COLUMN chat_messages.chat_message_id   IS '메시지 고유 식별자 (PK)';
COMMENT ON COLUMN chat_messages.chat_room_id      IS '메시지가 속한 채팅방 ID';
COMMENT ON COLUMN chat_messages.parent_message_id IS '답장 대상 메시지 ID — 일반 메시지이면 NULL (자기 참조 FK)';
COMMENT ON COLUMN chat_messages.sender_id         IS '발신자 사용자 ID (user-service 참조 — FK 불가) — SYSTEM 메시지이면 NULL';
COMMENT ON COLUMN chat_messages.message_type      IS '메시지 유형 — TEXT: 텍스트 / IMAGE: 이미지 / SYSTEM: 시스템 메시지';
COMMENT ON COLUMN chat_messages.content           IS '텍스트 메시지 내용 — TEXT 타입이면 반드시 NOT NULL';
COMMENT ON COLUMN chat_messages.image_url         IS '이미지 URL — IMAGE 타입이면 반드시 NOT NULL';
COMMENT ON COLUMN chat_messages.is_deleted        IS '메시지 삭제 여부 — 물리 삭제 없이 true 처리';
COMMENT ON COLUMN chat_messages.created_at        IS '메시지 발송일시';
COMMENT ON COLUMN chat_messages.updated_at        IS '메시지 최종 수정일시 — 애플리케이션 레벨에서 갱신';

CREATE INDEX idx_cm_chat_room_id ON chat_messages (chat_room_id, chat_message_id DESC);


-- 4. chat_outbox
CREATE TABLE chat_outbox (
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

    CONSTRAINT pk_chat_outbox         PRIMARY KEY (id),
    CONSTRAINT uq_cho_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_cho_status          CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_cho_retry_count     CHECK (retry_count >= 0)
);

COMMENT ON TABLE  chat_outbox                 IS 'Outbox 패턴 — Kafka 발행 전 이벤트 임시 저장소';
COMMENT ON COLUMN chat_outbox.id              IS '레코드 고유 식별자 (PK)';
COMMENT ON COLUMN chat_outbox.aggregate_type  IS '이벤트 발생 엔티티 유형 (예: CHAT_ROOM, CHAT_MESSAGE)';
COMMENT ON COLUMN chat_outbox.aggregate_id    IS '이벤트 발생 엔티티의 PK';
COMMENT ON COLUMN chat_outbox.event_type      IS 'Kafka 토픽 이벤트 유형 (예: chat.message.sent, chat.room.created)';
COMMENT ON COLUMN chat_outbox.payload         IS 'Kafka에 발행할 이벤트 JSON 데이터';
COMMENT ON COLUMN chat_outbox.status          IS '발행 상태 — PENDING: 대기 / PUBLISHED: 발행 완료 / FAILED: 실패';
COMMENT ON COLUMN chat_outbox.created_at      IS '이벤트 생성일시';
COMMENT ON COLUMN chat_outbox.published_at    IS 'Kafka 발행 완료 시각';
COMMENT ON COLUMN chat_outbox.retry_count     IS '발행 재시도 횟수';
COMMENT ON COLUMN chat_outbox.idempotency_key IS '중복 이벤트 방지 키 (UNIQUE) — aggregate_type:aggregate_id:event_type:version';

CREATE INDEX idx_cho_status ON chat_outbox (status) WHERE status = 'PENDING';


-- 5. chat_inbox
CREATE TABLE chat_inbox (
    id             BIGINT        GENERATED ALWAYS AS IDENTITY,
    message_id     VARCHAR(100)                          NOT NULL,
    event_type     VARCHAR(100)                          NOT NULL,
    payload        JSONB                                 NOT NULL,
    status         VARCHAR(20)   DEFAULT 'RECEIVED'      NOT NULL,
    received_at    TIMESTAMPTZ   DEFAULT now()           NOT NULL,
    processed_at   TIMESTAMPTZ                           NULL,
    aggregate_type VARCHAR(50)                           NULL,
    aggregate_id   BIGINT                                NULL,

    CONSTRAINT pk_chat_inbox     PRIMARY KEY (id),
    CONSTRAINT uq_chi_message_id UNIQUE (message_id),
    CONSTRAINT ck_chi_status     CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED'))
);

COMMENT ON TABLE  chat_inbox                IS 'Inbox 패턴 — Kafka 수신 이벤트 중복 처리 방지 및 이력 관리';
COMMENT ON COLUMN chat_inbox.id             IS '레코드 고유 식별자 (PK)';
COMMENT ON COLUMN chat_inbox.message_id     IS 'Kafka 메시지 고유 ID (UNIQUE) — 중복 수신 방지';
COMMENT ON COLUMN chat_inbox.event_type     IS '수신된 이벤트 유형';
COMMENT ON COLUMN chat_inbox.payload        IS '수신된 이벤트 JSON 데이터';
COMMENT ON COLUMN chat_inbox.status         IS '처리 상태 — RECEIVED: 수신 / PROCESSED: 처리 완료 / FAILED: 처리 실패';
COMMENT ON COLUMN chat_inbox.received_at    IS 'Kafka 메시지 수신일시';
COMMENT ON COLUMN chat_inbox.processed_at   IS '이벤트 처리 완료 시각';
COMMENT ON COLUMN chat_inbox.aggregate_type IS '이벤트 대상 엔티티 유형';
COMMENT ON COLUMN chat_inbox.aggregate_id   IS '이벤트 대상 엔티티의 PK';

CREATE INDEX idx_chi_status ON chat_inbox (status) WHERE status = 'RECEIVED';
