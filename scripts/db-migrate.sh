#!/usr/bin/env bash
# =============================================================
# Routinely DB 마이그레이션 스크립트
# docs/db/ 아래 SQL 파일을 각 서비스 DB에 적용한다.
#
# 사용법:
#   ./scripts/db-migrate.sh                    — 전체 서비스 마이그레이션
#   ./scripts/db-migrate.sh user-service       — 특정 서비스만
#
# 전제 조건:
#   - infra/.env 에 DB_PASSWORD 설정
#   - PostgreSQL 컨테이너 실행 중 (./scripts/local-up.sh)
#   - psql CLI 설치 (brew install postgresql)
# =============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."
SQL_DIR="$PROJECT_ROOT/docs/db"
ENV_FILE="$PROJECT_ROOT/infra/.env"

# .env 에서 환경변수 로드
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

DB_USER="${DB_USER:-routinely}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
: "${DB_PASSWORD:?infra/.env 에 DB_PASSWORD 가 설정되지 않았습니다}"

declare -A SERVICE_DB_MAP=(
  ["user-service"]="user_db"
  ["routine-service"]="routine_db"
  ["challenge-service"]="challenge_db"
  ["chat-service"]="chat_db"
  ["notification-service"]="notification_db"
)

run_migration() {
  local service="$1"
  local db="${SERVICE_DB_MAP[$service]}"
  local sql_file="$SQL_DIR/${service}.sql"

  if [ ! -f "$sql_file" ]; then
    echo "  ⚠️  SQL 파일 없음: docs/db/${service}.sql (건너뜀)"
    return
  fi

  echo "  ▶ $service → $db"
  PGPASSWORD="$DB_PASSWORD" psql \
    -h "$DB_HOST" -p "$DB_PORT" \
    -U "$DB_USER" -d "$db" \
    -f "$sql_file" \
    --quiet
  echo "  ✅ 완료"
}

TARGET="${1:-}"

if [ -n "$TARGET" ]; then
  if [[ -z "${SERVICE_DB_MAP[$TARGET]:-}" ]]; then
    echo "❌ 알 수 없는 서비스: $TARGET"
    echo "   사용 가능: ${!SERVICE_DB_MAP[*]}"
    exit 1
  fi
  echo "▶ $TARGET 마이그레이션 시작..."
  run_migration "$TARGET"
else
  echo "▶ 전체 서비스 마이그레이션 시작..."
  for service in user-service routine-service challenge-service chat-service notification-service; do
    run_migration "$service"
  done
fi

echo ""
echo "✅ 마이그레이션 완료"
