#!/usr/bin/env bash
# 17.3 2계층 검증: 앱 인스턴스 2개가 공유 DB의 Outbox·Inbox를 경쟁 소비할 때
# 이벤트 중복 소비가 없음을 확인한다 (3주차 완료 기준).
#
# 전제: docker compose -p groupdrop-week3 -f docker-compose.yml -f docker-compose.two-instances.yml up -d
set -euo pipefail

APP1_URL="${APP1_URL:-http://localhost:58082}"
APP2_URL="${APP2_URL:-http://localhost:58083}"
MOCK_PG_URL="${MOCK_PG_URL:-http://localhost:58081}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-55432}"
DB_NAME="${DB_NAME:-groupdrop}"
DB_USER="${DB_USER:-groupdrop}"
DB_PASSWORD="${DB_PASSWORD:-groupdrop}"
DB_CONTAINER="${DB_CONTAINER:-}"
ORDER_COUNT="${ORDER_COUNT:-20}"
INVENTORY="${INVENTORY:-100}"

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
  curl --fail --silent --show-error -c "$3" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"groupdrop123!\"}" "$2/api/auth/login" > /dev/null
}

echo '== 1. 캠페인 준비 =='
supplier_id="$(run_psql -tAc \
  "SELECT s.id FROM suppliers s JOIN users u ON u.id=s.user_id WHERE u.email='supplier@groupdrop.test'")"

login 'supplier@groupdrop.test' "${APP1_URL}" "${RUN_TMP}/supplier.cookies"
product_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/supplier.cookies" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"two-instance product $(date +%s)\",\"skus\":[{\"optionName\":\"single\"}]}" \
  "${APP1_URL}/api/suppliers/me/products")"
product_id="$(jq -r '.id' <<<"${product_json}")"
sku_id="$(jq -r '.skus[0].id' <<<"${product_json}")"

if start_at="$(date -u -v-1M '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null)"; then
  end_at="$(date -u -v+30M '+%Y-%m-%dT%H:%M:%SZ')"
else
  start_at="$(date -u -d '1 minute ago' '+%Y-%m-%dT%H:%M:%SZ')"
  end_at="$(date -u -d '30 minutes' '+%Y-%m-%dT%H:%M:%SZ')"
fi

login 'influencer@groupdrop.test' "${APP1_URL}" "${RUN_TMP}/influencer.cookies"
campaign_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/influencer.cookies" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"two-instance campaign\",\"slug\":\"two-inst-$(date +%s)\",\"supplierId\":${supplier_id},\"productId\":${product_id},\"dealPrice\":19900,\"startsAt\":\"${start_at}\",\"endsAt\":\"${end_at}\",\"perUserPurchaseLimit\":50,\"commissionRateBp\":750,\"skus\":[{\"productSkuId\":${sku_id},\"allocatedQuantity\":${INVENTORY},\"supplyUnitPrice\":1000}]}" \
  "${APP1_URL}/api/campaigns")"
campaign_id="$(jq -r '.id' <<<"${campaign_json}")"

curl --fail --silent --show-error -b "${RUN_TMP}/influencer.cookies" -X POST \
  "${APP1_URL}/api/campaigns/${campaign_id}/submit" > /dev/null
login 'admin@groupdrop.test' "${APP1_URL}" "${RUN_TMP}/admin.cookies"
curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" -X POST \
  "${APP1_URL}/api/admin/campaigns/${campaign_id}/approve" > /dev/null

status=''
for _ in $(seq 1 30); do
  status="$(curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" \
    "${APP1_URL}/api/campaigns/${campaign_id}" | jq -r '.status')"
  [[ "${status}" == 'OPEN' ]] && break
  sleep 1
done
[[ "${status}" == 'OPEN' ]] || { echo "campaign did not open: ${status}" >&2; exit 1; }

echo '== 2. 성공 후 응답 유실 모드로 전환 (웹훅은 보류) =='
# 결제는 PG에서 승인되지만 앱은 결과를 모른다 → 전부 UNKNOWN으로 남는다 (11.2).
curl --fail --silent --show-error -H 'Content-Type: application/json' \
  -d '{"mode":"SUCCEED_BUT_TIMEOUT","delayMs":100,"blockWebhook":true}' \
  "${MOCK_PG_URL}/mock-pg/test/failure-mode" > /dev/null

echo "== 3. 두 인스턴스에 번갈아 주문·결제 ${ORDER_COUNT}건 =="
# VU별 인스턴스 고정 배정과 같은 방식 — 세션 인증이므로 로그인한 인스턴스로만 요청한다 (17.3).
login 'buyer1@groupdrop.test' "${APP1_URL}" "${RUN_TMP}/buyer1.cookies"
login 'buyer2@groupdrop.test' "${APP2_URL}" "${RUN_TMP}/buyer2.cookies"

for i in $(seq 1 "${ORDER_COUNT}"); do
  if (( i % 2 == 1 )); then
    target="${APP1_URL}"; jar="${RUN_TMP}/buyer1.cookies"
  else
    target="${APP2_URL}"; jar="${RUN_TMP}/buyer2.cookies"
  fi
  order_json="$(curl --fail --silent --show-error -b "${jar}" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: two-inst-order-${i}-$(date +%s)" \
    -d "{\"items\":[{\"productSkuId\":${sku_id},\"quantity\":1}]}" \
    "${target}/api/campaigns/${campaign_id}/orders")"
  order_id="$(jq -r '.id' <<<"${order_json}")"
  amount="$(jq -r '.totalAmount' <<<"${order_json}")"

  payment_status="$(curl --silent --show-error -o "${RUN_TMP}/pay-${i}.json" -w '%{http_code}' \
    -b "${jar}" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: two-inst-pay-${i}-$(date +%s)" \
    -d "{\"amount\":${amount}}" "${target}/api/orders/${order_id}/payments")"
  if [[ "${payment_status}" != '202' ]]; then
    echo "결제 ${i}가 202(UNKNOWN)가 아닙니다: HTTP ${payment_status}" >&2
    cat "${RUN_TMP}/pay-${i}.json" >&2
    exit 1
  fi
done

unknown_count="$(run_psql -tAc \
  "SELECT count(*) FROM payments p JOIN orders o ON o.id=p.order_id
    WHERE o.campaign_id=${campaign_id} AND p.status='UNKNOWN'")"
echo "UNKNOWN 결제: ${unknown_count}건 (기대: ${ORDER_COUNT})"
[[ "${unknown_count}" == "${ORDER_COUNT}" ]] || { echo 'UNKNOWN 결제 수가 기대와 다릅니다' >&2; exit 1; }

echo '== 4. 보류 웹훅 일괄 재발사 (수신은 app 인스턴스 1개) =='
replayed="$(curl --fail --silent --show-error -X POST -H 'Content-Type: application/json' -d '{}' \
  "${MOCK_PG_URL}/mock-pg/test/webhooks/replay" | jq -r '.replayedCount')"
echo "재발사한 웹훅: ${replayed}건"

echo '== 5. 두 인스턴스의 워커가 이벤트를 소진할 때까지 대기 =='
for _ in $(seq 1 60); do
  pending="$(run_psql -tAc \
    "SELECT (SELECT count(*) FROM outbox_events WHERE status='PENDING')
          + (SELECT count(*) FROM inbox_events WHERE status='PENDING')")"
  [[ "${pending}" == '0' ]] && break
  sleep 1
done
[[ "${pending}" == '0' ]] || { echo "미처리 이벤트가 남았습니다: ${pending}" >&2; exit 1; }

echo '== 6. 인스턴스별 소비량과 이벤트 수 대조 =='
consumed_of() {
  curl --fail --silent --show-error "$1/actuator/prometheus" \
    | awk -v metric="$2" '$0 ~ "^" metric "\\{" { sum += $NF } END { printf "%d", sum + 0 }'
}
app1_outbox="$(consumed_of "${APP1_URL}" outbox_event_processed_total)"
app2_outbox="$(consumed_of "${APP2_URL}" outbox_event_processed_total)"
app1_inbox="$(consumed_of "${APP1_URL}" inbox_event_processed_total)"
app2_inbox="$(consumed_of "${APP2_URL}" inbox_event_processed_total)"
outbox_rows="$(run_psql -tAc "SELECT count(*) FROM outbox_events WHERE status='PROCESSED'")"
inbox_rows="$(run_psql -tAc "SELECT count(*) FROM inbox_events WHERE status='PROCESSED'")"

echo "outbox 소비: app=${app1_outbox} app2=${app2_outbox} 합=$((app1_outbox + app2_outbox)) / PROCESSED 행=${outbox_rows}"
echo "inbox  소비: app=${app1_inbox} app2=${app2_inbox} 합=$((app1_inbox + app2_inbox)) / PROCESSED 행=${inbox_rows}"

# 중복 소비가 있었다면 두 인스턴스의 처리 횟수 합이 이벤트 행 수를 넘는다.
if (( app1_outbox + app2_outbox != outbox_rows )); then
  echo "Outbox 중복(또는 누락) 소비 감지: 합 $((app1_outbox + app2_outbox)) vs 행 ${outbox_rows}" >&2
  exit 1
fi
if (( app1_inbox + app2_inbox != inbox_rows )); then
  echo "Inbox 중복(또는 누락) 소비 감지: 합 $((app1_inbox + app2_inbox)) vs 행 ${inbox_rows}" >&2
  exit 1
fi
# 한쪽이 0이면 "중복이 없다"는 사실은 확인되지만 "경쟁 소비가 안전하다"는 증명은 되지 않는다.
# 이 스크립트의 목적은 후자이므로 실패로 다룬다 (배치 크기를 줄여 일감이 나뉘게 한다).
if (( app1_outbox == 0 || app2_outbox == 0 || app1_inbox == 0 || app2_inbox == 0 )); then
  echo '한쪽 인스턴스만 소비했습니다 — 경쟁 소비가 관찰되지 않았습니다.' >&2
  echo 'GROUPDROP_MESSAGE_BATCH_SIZE를 더 줄이거나 ORDER_COUNT를 늘려 다시 실행하세요.' >&2
  exit 1
fi

echo '== 7. 정합성 검증 SQL =='
run_psql -v campaign_id="${campaign_id}" < "${LOAD_TEST_DIR}/sql/outbox-inbox-invariants.sql" \
  | tee "${RUN_TMP}/invariants.out"
violations="$(awk '/^ *[0-9]+ *\|/ { gsub(/ /, ""); n = split($0, f, "|"); last = f[n] } END { print last + 0 }' \
  "${RUN_TMP}/invariants.out")"
[[ "${violations}" == '0' ]] || { echo "불변식 위반 ${violations}건" >&2; exit 1; }

curl --fail --silent --show-error -H 'Content-Type: application/json' -d '{"mode":"NORMAL"}' \
  "${MOCK_PG_URL}/mock-pg/test/failure-mode" > /dev/null

echo "17.3 2계층 PASS campaign_id=${campaign_id} orders=${ORDER_COUNT} outbox=${outbox_rows} inbox=${inbox_rows}"
