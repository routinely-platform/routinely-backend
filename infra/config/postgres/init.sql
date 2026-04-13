-- Routinely — 서비스별 데이터베이스 초기화
-- POSTGRES_DB=user_db 는 컨테이너 기동 시 자동 생성된다.
-- 나머지 4개 DB를 여기서 생성한다.

CREATE DATABASE routine_db;
CREATE DATABASE challenge_db;
CREATE DATABASE chat_db;
CREATE DATABASE notification_db;

