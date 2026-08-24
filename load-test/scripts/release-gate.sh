#!/usr/bin/env bash
# 6주차 최종 게이트. S1-a/b~S8을 번호 순서로 실행하고, 외부 실증 뒤 SQL까지 통과해야 PASS다.
# 실행은 Testcontainers·Docker·k6를 사용하므로 CI 상시 작업이 아니라 6주차 릴리스 후보에서 수행한다.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOAD_TEST_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_DIR="$(cd "${LOAD_TEST_DIR}/.." && pwd)"
APP_DIR="${PROJECT_DIR}/app"
MOCK_PG_DIR="${PROJECT_DIR}/mock-pg"
ACTIVE_PROJECT=''

cleanup() {
  local exit_code="$?"
  if [[ -n "${ACTIVE_PROJECT}" ]]; then
    docker compose -p "${ACTIVE_PROJECT}" -f "${PROJECT_DIR}/docker-compose.yml" \
      -f "${PROJECT_DIR}/docker-compose.two-instances.yml" down -v --remove-orphans > /dev/null 2>&1 || true
  fi
  exit "${exit_code}"
}
trap cleanup EXIT INT TERM

fail() { echo "RELEASE GATE FAIL: $*" >&2; exit 1; }

wait_for() {
  local label="$1" url="$2"
  for _ in $(seq 1 90); do
    if curl --fail --silent --show-error "${url}/actuator/health" > /dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  fail "${label} ready timeout: ${url}"
}

ensure_empty_project() {
  local project="$1"
  local existing
  existing="$(docker compose -p "${project}" -f "${PROJECT_DIR}/docker-compose.yml" \
    -f "${PROJECT_DIR}/docker-compose.two-instances.yml" ps -aq)"
  [[ -z "${existing}" ]] || fail "compose project ${project} already exists; inspect/remove it explicitly before the release gate"
}

start_single_stack() {
  local project="$1" app_port="$2" pg_port="$3" db_port="$4"
  ensure_empty_project "${project}"
  ACTIVE_PROJECT="${project}"
  APP_PORT="${app_port}" MOCK_PG_PORT="${pg_port}" POSTGRES_PORT="${db_port}" \
    docker compose -p "${project}" -f "${PROJECT_DIR}/docker-compose.yml" up -d --build postgres mock-pg app
  wait_for app "http://localhost:${app_port}"
  wait_for mock-pg "http://localhost:${pg_port}"
}

start_two_instance_stack() {
  local project="$1" app_port="$2" app2_port="$3" pg_port="$4" db_port="$5"
  ensure_empty_project "${project}"
  ACTIVE_PROJECT="${project}"
  APP_PORT="${app_port}" APP2_PORT="${app2_port}" MOCK_PG_PORT="${pg_port}" POSTGRES_PORT="${db_port}" \
    docker compose -p "${project}" -f "${PROJECT_DIR}/docker-compose.yml" \
      -f "${PROJECT_DIR}/docker-compose.two-instances.yml" up -d --build postgres mock-pg app app2
  wait_for app "http://localhost:${app_port}"
  wait_for app2 "http://localhost:${app2_port}"
  wait_for mock-pg "http://localhost:${pg_port}"
}

stop_active_stack() {
  [[ -n "${ACTIVE_PROJECT}" ]] || return 0
  docker compose -p "${ACTIVE_PROJECT}" -f "${PROJECT_DIR}/docker-compose.yml" \
    -f "${PROJECT_DIR}/docker-compose.two-instances.yml" down -v --remove-orphans
  ACTIVE_PROJECT=''
}

run_app_test() {
  local label="$1"; shift
  echo "== ${label} =="
  (cd "${APP_DIR}" && ./gradlew test "$@")
}

echo '== S1-a: 단일 인스턴스 재고 100 / 주문 1,000 =='
start_single_stack groupdrop-week6-s1a 58100 58101 55440
APP_URL=http://localhost:58100 DB_PORT=55440 DB_CONTAINER=groupdrop-week6-s1a-postgres-1 \
  "${SCRIPT_DIR}/run-s1-a.sh"
stop_active_stack

echo '== S1-b: 두 인스턴스 균등 분산 재고 100 / 주문 1,000 =='
start_two_instance_stack groupdrop-week6-s1b 58102 58103 58104 55441
APP1_URL=http://localhost:58102 APP2_URL=http://localhost:58103 DB_PORT=55441 \
  DB_CONTAINER=groupdrop-week6-s1b-postgres-1 "${SCRIPT_DIR}/run-s1-b.sh"
stop_active_stack

run_app_test 'S2: 동일 멱등 키 동시 결제' --tests com.groupdrop.payment.PaymentConcurrencyTest
run_app_test 'S3: 중복·역순 웹훅 Inbox 멱등' --tests com.groupdrop.payment.PaymentWebhookApiTest
run_app_test 'S4-a: UNKNOWN 웹훅·조회 복구' --tests com.groupdrop.payment.PaymentFailureRecoveryTest
run_app_test 'S4-b: 대사로 UNKNOWN 복구' --tests com.groupdrop.reconciliation.ReconciliationIntegrationTest
run_app_test 'S5: 원장 차변·대변 균형' --tests com.groupdrop.ledger.LedgerIntegrationTest
run_app_test 'S6: 정산 항목 중복 방지' --tests com.groupdrop.settlement.SettlementFlowIntegrationTest
run_app_test 'S7: PG 불일치 대사 분류' --tests com.groupdrop.reconciliation.ReconciliationIntegrationTest
run_app_test 'S8: 정산 후 환불 회수 배치' --tests com.groupdrop.settlement.SettlementRecoveryIntegrationTest

echo '== Mock PG 계약: S4/S7 장애·대사 API =='
(cd "${MOCK_PG_DIR}" && ./gradlew test)

echo '== 실제 HTTP: S3/S4/S5 환불·원장 =='
start_single_stack groupdrop-week6-refund 58105 58106 55442
APP_URL=http://localhost:58105 MOCK_PG_URL=http://localhost:58106 DB_PORT=55442 \
  DB_CONTAINER=groupdrop-week6-refund-postgres-1 "${SCRIPT_DIR}/run-refund-e2e.sh"
stop_active_stack

echo '== 실제 HTTP: S6/S7/S8 정산·대사·회수 =='
start_single_stack groupdrop-week6-settlement 58107 58108 55443
APP_URL=http://localhost:58107 MOCK_PG_URL=http://localhost:58108 DB_PORT=55443 \
  DB_CONTAINER=groupdrop-week6-settlement-postgres-1 "${SCRIPT_DIR}/run-settlement-e2e.sh"
stop_active_stack

echo '== 17.4 실증: docker kill → 재기동 → UNKNOWN/Outbox/Inbox 복구 =='
COMPOSE_PROJECT=groupdrop-week6-recovery APP_PORT=58112 APP2_PORT=58113 MOCK_PG_PORT=58111 POSTGRES_PORT=55436 \
  "${SCRIPT_DIR}/run-compose-kill-recovery.sh"

echo 'RELEASE GATE PASS: S1-a/b~S8 및 docker kill 복구 실증 완료'
