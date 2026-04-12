#!/usr/bin/env bash
# =============================================================
# Routinely 로컬 인프라 기동 스크립트
#
# 사용법:
#   ./scripts/local-up.sh           — 앱 인프라만 (postgres, redis, kafka)
#   ./scripts/local-up.sh --obs     — 관찰 가능성 스택 포함
#
# 전제 조건: infra/.env 파일 존재 (cp infra/.env.example infra/.env)
# =============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$SCRIPT_DIR/../infra"

if [ ! -f "$INFRA_DIR/.env" ]; then
  echo "⚠️  infra/.env 파일이 없습니다."
  echo "   cp infra/.env.example infra/.env 후 값을 채워주세요."
  exit 1
fi

cd "$INFRA_DIR"

if [ "${1:-}" = "--obs" ]; then
  echo "▶ 앱 인프라 + Observability 스택 기동 중..."
  docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d
else
  echo "▶ 앱 인프라 기동 중... (postgres, redis, kafka)"
  docker compose up -d
fi

echo ""
echo "✅ 기동 완료"
echo "   PostgreSQL : localhost:5432"
echo "   Redis      : localhost:6379"
echo "   Kafka      : localhost:29092"

if [ "${1:-}" = "--obs" ]; then
  echo "   Zipkin     : http://localhost:9411"
  echo "   Prometheus : http://localhost:9090"
  GRAFANA_PWD="$(grep -E '^GRAFANA_PASSWORD=' .env | cut -d= -f2)"
  GRAFANA_PWD="${GRAFANA_PWD:-admin}"
  echo "   Grafana    : http://localhost:3000  (admin / ${GRAFANA_PWD})"
fi