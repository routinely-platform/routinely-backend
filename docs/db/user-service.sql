-- =============================================
-- user-service DDL
-- PostgreSQL 기준
-- =============================================

CREATE TABLE users (
    id                BIGINT       GENERATED ALWAYS AS IDENTITY,
    email             VARCHAR(255)                       NOT NULL,
    password_hash     VARCHAR(255)                       NOT NULL,
    nickname          VARCHAR(20)                        NOT NULL,
    role              VARCHAR(10)                        NOT NULL,
    profile_image_url VARCHAR(500)                       NULL,
    is_active         BOOLEAN      DEFAULT true          NOT NULL,
    created_at        TIMESTAMPTZ  DEFAULT now()         NOT NULL,
    updated_at        TIMESTAMPTZ  DEFAULT now()         NOT NULL,
    CONSTRAINT pk_users                  PRIMARY KEY (id),
    CONSTRAINT uq_users_email            UNIQUE (email),
    CONSTRAINT uq_users_nickname         UNIQUE (nickname),
    CONSTRAINT ck_users_role             CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT ck_users_nickname_length  CHECK (char_length(nickname) >= 2)
);

COMMENT ON TABLE  users                   IS '사용자 계정 테이블';
COMMENT ON COLUMN users.id                IS '사용자 고유 식별자 (PK)';
COMMENT ON COLUMN users.email             IS '로그인에 사용되는 이메일 주소 (UNIQUE)';
COMMENT ON COLUMN users.password_hash     IS 'BCrypt로 암호화된 비밀번호';
COMMENT ON COLUMN users.nickname          IS '서비스 내 표시되는 닉네임 (UNIQUE, 2~20자)';
COMMENT ON COLUMN users.role              IS '계정 권한 — USER: 일반 사용자 / ADMIN: 관리자';
COMMENT ON COLUMN users.profile_image_url IS '프로필 이미지 URL';
COMMENT ON COLUMN users.is_active         IS '계정 활성 여부 — 회원 탈퇴 시 false로 변경 (소프트 딜리트)';
COMMENT ON COLUMN users.created_at        IS '계정 생성일시';
COMMENT ON COLUMN users.updated_at        IS '계정 정보 최종 수정일시 — 애플리케이션 레벨에서 갱신';
