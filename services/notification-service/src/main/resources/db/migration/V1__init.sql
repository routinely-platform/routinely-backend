-- =============================================
-- notification-service DDL
-- PostgreSQL 기준
-- =============================================

-- 1. notification_history
CREATE TABLE notification_history (
    id             BIGINT        GENERATED ALWAYS AS IDENTITY,
    user_id        BIGINT                                 NOT NULL,
    type           VARCHAR(50)                            NOT NULL,
    title          VARCHAR(200)                           NOT NULL,
    body           TEXT                                   NULL,
    is_read        BOOLEAN       DEFAULT false            NOT NULL,
    sent_at        TIMESTAMPTZ                            NOT NULL,
    reference_type VARCHAR(50)                            NULL,
    reference_id   BIGINT                                 NULL,
    created_at     TIMESTAMPTZ   DEFAULT now()            NOT NULL,
    updated_at     TIMESTAMPTZ   DEFAULT now()            NOT NULL,

    CONSTRAINT pk_notification_history    PRIMARY KEY (id),
    CONSTRAINT ck_nh_type                 CHECK (type IN ('ROUTINE_START', 'DEADLINE', 'CHALLENGE_EVENT')),
    CONSTRAINT ck_nh_reference            CHECK (
        (reference_type IS NULL AND reference_id IS NULL)
        OR
        (reference_type IS NOT NULL AND reference_id IS NOT NULL)
    )
);

COMMENT ON TABLE  notification_history               IS '알림 발송 이력 — 사용자별 알림 목록 조회 및 읽음 처리';
COMMENT ON COLUMN notification_history.id            IS '알림 고유 식별자 (PK)';
COMMENT ON COLUMN notification_history.user_id       IS '알림 수신 사용자 ID (user-service 참조 — FK 불가)';
COMMENT ON COLUMN notification_history.type          IS '알림 유형 — ROUTINE_START: 루틴 시작 / DEADLINE: 마감 리마인드 / CHALLENGE_EVENT: 챌린지 이벤트';
COMMENT ON COLUMN notification_history.title         IS '알림 제목';
COMMENT ON COLUMN notification_history.body          IS '알림 본문 (선택)';
COMMENT ON COLUMN notification_history.is_read       IS '읽음 여부 — 기본값 false, 사용자가 알림 확인 시 true';
COMMENT ON COLUMN notification_history.sent_at       IS '실제 알림 발송 시각';
COMMENT ON COLUMN notification_history.reference_type IS '알림 클릭 시 이동할 대상 유형 — CHALLENGE / ROUTINE / FEED_CARD / CHAT_ROOM';
COMMENT ON COLUMN notification_history.reference_id  IS '알림 클릭 시 이동할 대상 ID — reference_type과 항상 같이 존재하거나 같이 NULL';
COMMENT ON COLUMN notification_history.created_at    IS '레코드 생성일시';
COMMENT ON COLUMN notification_history.updated_at    IS '레코드 최종 수정일시 — 애플리케이션 레벨에서 갱신';

CREATE INDEX idx_nh_user_id      ON notification_history (user_id, created_at DESC);
CREATE INDEX idx_nh_user_is_read ON notification_history (user_id, is_read) WHERE is_read = false;
