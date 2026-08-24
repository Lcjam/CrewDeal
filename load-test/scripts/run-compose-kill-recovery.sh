#!/usr/bin/env bash
# 17.4 실증 층: 공유 DB의 app/app2 중 app을 docker kill한 뒤 재기동한다.
# UNKNOWN 결제를 보류 웹훅으로 복구하고 Outbox/Inbox 및 결제·주문 정합성 SQL을 통과해야 한다.
set -euo pipefail

COMPOSE_PROJECT="${COMPOSE_PROJECT:-groupdrop-week6-recovery}"
APP_PORT="${APP_PORT:-58112}"
APP2_PORT="${APP2_PORT:-58113}"
MOCK_PG_PORT="${MOCK_PG_PORT:-58111}"
POSTGRES_PORT="${POSTGRES_PORT:-55436}"
DB_NAME="${DB_NAME:-groupdrop}"
DB_USER="${DB_USER:-groupdrop}"
DB_PASSWORD="${DB_PASSWORD:-groupdrop}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOAD_TEST_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_DIR="$(cd "${LOAD_TEST_DIR}/.." && pwd)"
RUN_TMP="$(mktemp -d)"
STACK_STARTED=false

cleanup() {
  local exit_code="$?"
  # 이 스크립트가 만든 고유 compose 프로젝트만 내린다. 다른 개발 스택은 건드리지 않는다.
  if [[ "${STACK_STARTED}" == true ]]; then
    APP_PORT="${APP_PORT}" APP2_PORT="${APP2_PORT}" MOCK_PG_PORT="${MOCK_PG_PORT}" POSTGRES_PORT="${POSTGRES_PORT}" \
      docker compose -p "${COMPOSE_PROJECT}" -f "${PROJECT_DIR}/docker-compose.yml" \
        -f "${PROJECT_DIR}/docker-compose.two-instances.yml" down -v --remove-orphans > /dev/null 2>&1 || true
  fi
  rm -rf "${RUN_TMP}"
  exit "${exit_code}"
}
trap cleanup EXIT INT TERM

run_psql() {
  if command -v psql > /dev/null 2>&1; then
    PGPASSWORD="${DB_PASSWORD}" psql -h localhost -p "${POSTGRES_PORT}" -U "${DB_USER}" -d "${DB_NAME}" "$@"
  else
    docker exec -i -e PGPASSWORD="${DB_PASSWORD}" "${COMPOSE_PROJECT}-postgres-1" \
      psql -U "${DB_USER}" -d "${DB_NAME}" "$@"
  fi
}

wait_for() {
  local label="$1" url="$2"
  for _ in $(seq 1 90); do
    if curl --fail --silent --show-error "${url}/actuator/health" > /dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "${label} 준비 시간 초과: ${url}" >&2
  exit 1
}

echo "== 1. 격리 Compose 기동: ${COMPOSE_PROJECT} (app ${APP_PORT}, app2 ${APP2_PORT}) =="
existing="$(docker compose -p "${COMPOSE_PROJECT}" -f "${PROJECT_DIR}/docker-compose.yml" \
  -f "${PROJECT_DIR}/docker-compose.two-instances.yml" ps -aq)"
[[ -z "${existing}" ]] || { echo "compose project ${COMPOSE_PROJECT} already exists; inspect/remove it explicitly first" >&2; exit 2; }
export APP_PORT APP2_PORT MOCK_PG_PORT POSTGRES_PORT
docker compose -p "${COMPOSE_PROJECT}" -f "${PROJECT_DIR}/docker-compose.yml" \
  -f "${PROJECT_DIR}/docker-compose.two-instances.yml" up -d --build postgres mock-pg app app2
STACK_STARTED=true
wait_for app "http://localhost:${APP_PORT}"
wait_for app2 "http://localhost:${APP2_PORT}"
wait_for mock-pg "http://localhost:${MOCK_PG_PORT}"

echo '== 2. UNKNOWN 생성 → docker kill → app 재기동 → 웹훅/이벤트 복구 =='
RUN_LOG="${RUN_TMP}/two-instance.out"
APP1_URL="http://localhost:${APP_PORT}" APP2_URL="http://localhost:${APP2_PORT}" \
MOCK_PG_URL="http://localhost:${MOCK_PG_PORT}" DB_HOST=localhost DB_PORT="${POSTGRES_PORT}" \
DB_NAME="${DB_NAME}" DB_USER="${DB_USER}" DB_PASSWORD="${DB_PASSWORD}" \
DB_CONTAINER="${COMPOSE_PROJECT}-postgres-1" COMPOSE_PROJECT="${COMPOSE_PROJECT}" \
KILL_APP1_BEFORE_REPLAY=true "${SCRIPT_DIR}/run-two-instance-outbox.sh" | tee "${RUN_LOG}"

campaign_id="$(sed -n 's/.*campaign_id=\([0-9][0-9]*\).*/\1/p' "${RUN_LOG}" | tail -1)"
[[ -n "${campaign_id}" ]] || { echo '복구 스크립트 출력에서 campaign_id를 찾지 못했습니다' >&2; exit 1; }

echo '== 3. UNKNOWN·Outbox·Inbox 종료 후 정합성 SQL =='
run_psql -v campaign_id="${campaign_id}" < "${LOAD_TEST_DIR}/sql/payment-recovery-invariants.sql"
echo "docker-kill recovery PASS campaign_id=${campaign_id} project=${COMPOSE_PROJECT}"
