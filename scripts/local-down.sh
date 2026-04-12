#!/usr/bin/env bash
# =============================================================
# Routinely 로컬 인프라 종료 스크립트
#
# 사용법:
#   ./scripts/local-down.sh         — 컨테이너만 종료 (볼륨 유지)
#   ./scripts/local-down.sh -v      — 컨테이너 + 볼륨 삭제 (데이터 초기화)
# =============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$SCRIPT_DIR/../infra"

cd "$INFRA_DIR"

if [ "${1:-}" = "-v" ]; then
  echo "▶ 컨테이너 및 볼륨 삭제 중... (데이터가 초기화됩니다)"
  docker compose -f docker-compose.yml -f docker-compose.observability.yml down --volumes 2>/dev/null || \
  docker compose down --volumes
else
  echo "▶ 컨테이너 종료 중... (볼륨은 유지됩니다)"
  docker compose -f docker-compose.yml -f docker-compose.observability.yml down 2>/dev/null || \
  docker compose down
fi

echo "✅ 완료"