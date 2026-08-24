#!/usr/bin/env bash
# S1-b: 17.3 2계층 Docker Compose 앱 2개에 17.5 고정 부하를 나눠 보낸다.
# 전제 예시:
#   APP_PORT=58102 APP2_PORT=58103 MOCK_PG_PORT=58101 POSTGRES_PORT=55435 \
#   docker compose -p groupdrop-week6-s1b -f docker-compose.yml -f docker-compose.two-instances.yml \
#     up -d --build postgres mock-pg app app2
set -euo pipefail

APP1_URL="${APP1_URL:-http://localhost:58102}"
APP2_URL="${APP2_URL:-http://localhost:58103}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-55435}"
DB_NAME="${DB_NAME:-groupdrop}"
DB_USER="${DB_USER:-groupdrop}"
DB_PASSWORD="${DB_PASSWORD:-groupdrop}"
DB_CONTAINER="${DB_CONTAINER:-groupdrop-week6-s1b-postgres-1}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:0.54.0}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOAD_TEST_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
RUN_TMP="$(mktemp -d)"
trap 'rm -rf "${RUN_TMP}"' EXIT

run_psql() {
  if command -v psql > /dev/null 2>&1; then
    PGPASSWORD="${DB_PASSWORD}" psql -h "${DB_HOST}" -p "${DB_PORT}" \
      -U "${DB_USER}" -d "${DB_NAME}" "$@"
  elif [[ -n "${DB_CONTAINER}" ]]; then
    docker exec -i -e PGPASSWORD="${DB_PASSWORD}" "${DB_CONTAINER}" \
      psql -U "${DB_USER}" -d "${DB_NAME}" "$@"
  else
    echo 'psql is unavailable; set DB_CONTAINER to the PostgreSQL container name' >&2
    exit 127
  fi
}

login() {
  local email="$1" base_url="$2" jar="$3"
  curl --fail --silent --show-error -c "${jar}" -H 'Content-Type: application/json' \
    -d "{\"email\":\"${email}\",\"password\":\"groupdrop123!\"}" \
    "${base_url}/api/auth/login" > /dev/null
}

wait_for_open() {
  local status=''
  for _ in $(seq 1 30); do
    status="$(curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" \
      "${APP1_URL}/api/campaigns/${campaign_id}" | jq -r '.status')"
    [[ "${status}" == 'OPEN' ]] && return 0
    sleep 1
  done
  echo "campaign did not open: ${status}" >&2
  exit 1
}

echo '== 1. S1-b fixture: 구매자 200명, 재고 100, 구매 제한 10 =='
run_psql < "${LOAD_TEST_DIR}/sql/seed-s1-a-buyers.sql"
supplier_id="$(run_psql -tAc \
  "SELECT s.id FROM suppliers s JOIN users u ON u.id=s.user_id WHERE u.email='supplier@groupdrop.test'" \
  | tr -d '[:space:]')"

login 'supplier@groupdrop.test' "${APP1_URL}" "${RUN_TMP}/supplier.cookies"
product_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/supplier.cookies" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"S1-b product $(date +%s)\",\"skus\":[{\"optionName\":\"single\"}]}" \
  "${APP1_URL}/api/suppliers/me/products")"
product_id="$(jq -er '.id' <<<"${product_json}")"
sku_id="$(jq -er '.skus[0].id' <<<"${product_json}")"

if start_at="$(date -u -v-1M '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null)"; then
  end_at="$(date -u -v+30M '+%Y-%m-%dT%H:%M:%SZ')"
else
  start_at="$(date -u -d '1 minute ago' '+%Y-%m-%dT%H:%M:%SZ')"
  end_at="$(date -u -d '30 minutes' '+%Y-%m-%dT%H:%M:%SZ')"
fi

login 'influencer@groupdrop.test' "${APP1_URL}" "${RUN_TMP}/influencer.cookies"
campaign_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/influencer.cookies" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"S1-b campaign\",\"slug\":\"s1-b-$(date +%s)\",\"supplierId\":${supplier_id},\"productId\":${product_id},\"dealPrice\":19900,\"startsAt\":\"${start_at}\",\"endsAt\":\"${end_at}\",\"perUserPurchaseLimit\":10,\"commissionRateBp\":750,\"skus\":[{\"productSkuId\":${sku_id},\"allocatedQuantity\":100,\"supplyUnitPrice\":1000}]}" \
  "${APP1_URL}/api/campaigns")"
campaign_id="$(jq -er '.id' <<<"${campaign_json}")"

curl --fail --silent --show-error -b "${RUN_TMP}/influencer.cookies" -X POST \
  "${APP1_URL}/api/campaigns/${campaign_id}/submit" > /dev/null
login 'admin@groupdrop.test' "${APP1_URL}" "${RUN_TMP}/admin.cookies"
curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" -X POST \
  "${APP1_URL}/api/admin/campaigns/${campaign_id}/approve" > /dev/null
wait_for_open

echo '== 2. S1-b k6: app1/app2 각 500회, 합계 1,000회, 30초 이내 =='
if command -v k6 > /dev/null 2>&1; then
  APP1_URL="${APP1_URL}" APP2_URL="${APP2_URL}" CAMPAIGN_ID="${campaign_id}" PRODUCT_SKU_ID="${sku_id}" \
    k6 run "${LOAD_TEST_DIR}/k6/s1-b.js"
else
  k6_app1_url="${K6_APP1_URL:-${APP1_URL/localhost/host.docker.internal}}"
  k6_app2_url="${K6_APP2_URL:-${APP2_URL/localhost/host.docker.internal}}"
  docker run --rm -i \
    -e APP1_URL="${k6_app1_url}" -e APP2_URL="${k6_app2_url}" \
    -e CAMPAIGN_ID="${campaign_id}" -e PRODUCT_SKU_ID="${sku_id}" \
    -v "${LOAD_TEST_DIR}:/scripts:ro" "${K6_IMAGE}" run /scripts/k6/s1-b.js
fi

echo '== 3. 종료 후 SQL: 재고 불변식·성공 주문 정확히 100 =='
run_psql -v campaign_id="${campaign_id}" < "${LOAD_TEST_DIR}/sql/inventory-invariants.sql"
echo "S1-b PASS campaign_id=${campaign_id} product_sku_id=${sku_id} app1=${APP1_URL} app2=${APP2_URL}"
