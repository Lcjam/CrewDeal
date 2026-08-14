#!/usr/bin/env bash
set -euo pipefail

APP_URL="${APP_URL:-http://localhost:58080}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-55432}"
DB_NAME="${DB_NAME:-groupdrop}"
DB_USER="${DB_USER:-groupdrop}"
DB_PASSWORD="${DB_PASSWORD:-groupdrop}"
DB_CONTAINER="${DB_CONTAINER:-}"
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

run_psql < "${LOAD_TEST_DIR}/sql/seed-s1-a-buyers.sql"
supplier_id="$(run_psql -tAc \
  "SELECT s.id FROM suppliers s JOIN users u ON u.id=s.user_id WHERE u.email='supplier@groupdrop.test'")"

login() {
  local email="$1"
  local jar="$2"
  curl --fail --silent --show-error -c "${jar}" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"${email}\",\"password\":\"groupdrop123!\"}" \
    "${APP_URL}/api/auth/login" > /dev/null
}

login 'supplier@groupdrop.test' "${RUN_TMP}/supplier.cookies"
product_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/supplier.cookies" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"S1-a product $(date +%s)\",\"skus\":[{\"optionName\":\"single\"}]}" \
  "${APP_URL}/api/suppliers/me/products")"
product_id="$(jq -r '.id' <<<"${product_json}")"
sku_id="$(jq -r '.skus[0].id' <<<"${product_json}")"

if start_at="$(date -u -v-1M '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null)"; then
  end_at="$(date -u -v+30M '+%Y-%m-%dT%H:%M:%SZ')"
else
  start_at="$(date -u -d '1 minute ago' '+%Y-%m-%dT%H:%M:%SZ')"
  end_at="$(date -u -d '30 minutes' '+%Y-%m-%dT%H:%M:%SZ')"
fi

login 'influencer@groupdrop.test' "${RUN_TMP}/influencer.cookies"
campaign_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/influencer.cookies" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"S1-a campaign\",\"slug\":\"s1-a-$(date +%s)\",\"supplierId\":${supplier_id},\"productId\":${product_id},\"dealPrice\":19900,\"startsAt\":\"${start_at}\",\"endsAt\":\"${end_at}\",\"perUserPurchaseLimit\":10,\"commissionRateBp\":750,\"skus\":[{\"productSkuId\":${sku_id},\"allocatedQuantity\":100,\"supplyUnitPrice\":1000}]}" \
  "${APP_URL}/api/campaigns")"
campaign_id="$(jq -r '.id' <<<"${campaign_json}")"

curl --fail --silent --show-error -b "${RUN_TMP}/influencer.cookies" -X POST \
  "${APP_URL}/api/campaigns/${campaign_id}/submit" > /dev/null
login 'admin@groupdrop.test' "${RUN_TMP}/admin.cookies"
curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" -X POST \
  "${APP_URL}/api/admin/campaigns/${campaign_id}/approve" > /dev/null

for _ in $(seq 1 30); do
  status="$(curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" \
    "${APP_URL}/api/campaigns/${campaign_id}" | jq -r '.status')"
  if [[ "${status}" == "OPEN" ]]; then
    break
  fi
  sleep 1
done
if [[ "${status}" != "OPEN" ]]; then
  echo "campaign did not open: ${status}" >&2
  exit 1
fi

if command -v k6 > /dev/null 2>&1; then
  APP_URL="${APP_URL}" CAMPAIGN_ID="${campaign_id}" PRODUCT_SKU_ID="${sku_id}" \
    k6 run "${LOAD_TEST_DIR}/k6/s1-a.js"
else
  k6_app_url="${K6_APP_URL:-${APP_URL/localhost/host.docker.internal}}"
  docker run --rm -i \
    -e APP_URL="${k6_app_url}" -e CAMPAIGN_ID="${campaign_id}" -e PRODUCT_SKU_ID="${sku_id}" \
    -v "${LOAD_TEST_DIR}:/scripts:ro" "${K6_IMAGE}" run /scripts/k6/s1-a.js
fi

run_psql -v campaign_id="${campaign_id}" < "${LOAD_TEST_DIR}/sql/inventory-invariants.sql"

echo "S1-a PASS campaign_id=${campaign_id} product_sku_id=${sku_id}"
