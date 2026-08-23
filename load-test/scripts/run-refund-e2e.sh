#!/usr/bin/env bash
# 4주차 환불·원장 실증: 앱과 가상 PG를 실제 HTTP로 붙여 REF-02 전체 환불과
# 환불 성공 응답 유실(17.4) 복구를 확인한다. 스텁이 아니라 진짜 계약을 검증하는 것이 목적이다.
#
# 전제: APP_PORT=58084 MOCK_PG_PORT=58085 POSTGRES_PORT=55433 \
#         docker compose -p groupdrop-week4 up -d --build app mock-pg postgres
set -euo pipefail

APP_URL="${APP_URL:-http://localhost:58084}"
MOCK_PG_URL="${MOCK_PG_URL:-http://localhost:58085}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-55433}"
DB_NAME="${DB_NAME:-groupdrop}"
DB_USER="${DB_USER:-groupdrop}"
DB_PASSWORD="${DB_PASSWORD:-groupdrop}"
# 호스트에 psql이 없는 환경을 위한 우회 경로.
DB_CONTAINER="${DB_CONTAINER:-groupdrop-week4-postgres-1}"

RUN_TMP="$(mktemp -d)"
trap 'rm -rf "${RUN_TMP}"' EXIT

run_psql() {
  if command -v psql > /dev/null 2>&1; then
    PGPASSWORD="${DB_PASSWORD}" psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" "$@"
  elif [[ -n "${DB_CONTAINER}" ]]; then
    docker exec -i -e PGPASSWORD="${DB_PASSWORD}" "${DB_CONTAINER}" psql -U "${DB_USER}" -d "${DB_NAME}" "$@"
  else
    echo 'psql is unavailable; set DB_CONTAINER to the PostgreSQL container name' >&2
    exit 127
  fi
}

login() {
  curl --fail --silent --show-error -c "$3" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"groupdrop123!\"}" "$2/api/auth/login" > /dev/null
}

fail() { echo "FAIL: $*" >&2; exit 1; }

wait_for() { # wait_for <설명> <sql> <기대값>
  local description="$1" sql="$2" expected="$3" actual=''
  for _ in $(seq 1 30); do
    actual="$(run_psql -tAc "${sql}" | tr -d '[:space:]')"
    [[ "${actual}" == "${expected}" ]] && { echo "  OK  ${description}: ${actual}"; return 0; }
    sleep 1
  done
  fail "${description}: expected ${expected}, got ${actual}"
}

echo '== 0. 가상 PG 정상 모드 =='
curl --fail --silent --show-error -H 'Content-Type: application/json' \
  -d '{"mode":"NORMAL","refundMode":"NORMAL"}' \
  "${MOCK_PG_URL}/mock-pg/test/failure-mode" > /dev/null

# 가상 PG 카운터는 프로세스 수명 동안 누적되므로 실행 전 값을 기준선으로 잡고 증분만 본다
# (같은 스택에서 스크립트를 두 번 돌려도 어서션이 흔들리지 않게).
stats_before="$(curl --fail --silent --show-error "${MOCK_PG_URL}/mock-pg/test/stats")"
refund_executed_before="$(jq -r '.refundExecutedCount' <<<"${stats_before}")"

# mock-pg는 인메모리이고 PostgreSQL은 볼륨을 사용한다. PG만 재기동하면 providerPaymentId 시퀀스가
# pg_1로 돌아가 기존 DB 행과 충돌하므로, 모호한 500 대신 격리 문제를 시작 시점에 명확히 알린다.
existing_provider_payments="$(run_psql -tAc \
  "SELECT count(*) FROM payments WHERE provider_payment_id IS NOT NULL" | tr -d '[:space:]')"
confirm_requests_before="$(jq -r '.confirmRequestCount' <<<"${stats_before}")"
if [[ "${existing_provider_payments}" != '0' && "${confirm_requests_before}" == '0' ]]; then
  fail "PostgreSQL에는 기존 결제가 있지만 mock-pg 상태는 비어 있습니다. 전용 compose 프로젝트를 down -v 후 재기동하세요"
fi

echo '== 1. 캠페인 준비 (공구가 19,900 × 수수료율 7.5% — 나누어떨어지지 않는 금액, 3.3) =='
supplier_id="$(run_psql -tAc \
  "SELECT s.id FROM suppliers s JOIN users u ON u.id=s.user_id WHERE u.email='supplier@groupdrop.test'" \
  | tr -d '[:space:]')"

login 'supplier@groupdrop.test' "${APP_URL}" "${RUN_TMP}/supplier.cookies"
product_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/supplier.cookies" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"refund e2e product $(date +%s)\",\"skus\":[{\"optionName\":\"single\"}]}" \
  "${APP_URL}/api/suppliers/me/products")"
product_id="$(jq -r '.id' <<<"${product_json}")"
sku_id="$(jq -r '.skus[0].id' <<<"${product_json}")"

if start_at="$(date -u -v-1M '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null)"; then
  end_at="$(date -u -v+30M '+%Y-%m-%dT%H:%M:%SZ')"
else
  start_at="$(date -u -d '1 minute ago' '+%Y-%m-%dT%H:%M:%SZ')"
  end_at="$(date -u -d '30 minutes' '+%Y-%m-%dT%H:%M:%SZ')"
fi

login 'influencer@groupdrop.test' "${APP_URL}" "${RUN_TMP}/influencer.cookies"
campaign_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/influencer.cookies" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"refund e2e campaign\",\"slug\":\"refund-e2e-$(date +%s)\",\"supplierId\":${supplier_id},\"productId\":${product_id},\"dealPrice\":19900,\"startsAt\":\"${start_at}\",\"endsAt\":\"${end_at}\",\"perUserPurchaseLimit\":50,\"commissionRateBp\":750,\"skus\":[{\"productSkuId\":${sku_id},\"allocatedQuantity\":10,\"supplyUnitPrice\":1000}]}" \
  "${APP_URL}/api/campaigns")"
campaign_id="$(jq -r '.id' <<<"${campaign_json}")"

curl --fail --silent --show-error -b "${RUN_TMP}/influencer.cookies" -X POST \
  "${APP_URL}/api/campaigns/${campaign_id}/submit" > /dev/null
login 'admin@groupdrop.test' "${APP_URL}" "${RUN_TMP}/admin.cookies"
curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" -X POST \
  "${APP_URL}/api/admin/campaigns/${campaign_id}/approve" > /dev/null

status=''
for _ in $(seq 1 30); do
  status="$(curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" \
    "${APP_URL}/api/campaigns/${campaign_id}" | jq -r '.status')"
  [[ "${status}" == 'OPEN' ]] && break
  sleep 1
done
[[ "${status}" == 'OPEN' ]] || fail "campaign did not open: ${status}"

PAY_ORDER_ID=''
PAY_PAYMENT_ID=''

pay_once() { # pay_once <tag> → PAY_ORDER_ID, PAY_PAYMENT_ID
  local tag="$1"
  local order_json amount payment_json
  order_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/buyer.cookies" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: refund-e2e-order-${tag}-$(date +%s)" \
    -d "{\"items\":[{\"productSkuId\":${sku_id},\"quantity\":1}]}" \
    "${APP_URL}/api/campaigns/${campaign_id}/orders")" || fail "주문 ${tag} 생성 실패"
  PAY_ORDER_ID="$(jq -er '.id' <<<"${order_json}")" || fail "주문 ${tag} 응답에 id가 없습니다"
  amount="$(jq -r '.totalAmount' <<<"${order_json}")"
  payment_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/buyer.cookies" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: refund-e2e-pay-${tag}-$(date +%s)" \
    -d "{\"amount\":${amount}}" "${APP_URL}/api/orders/${PAY_ORDER_ID}/payments")" \
    || fail "결제 ${tag} 요청 실패"
  PAY_PAYMENT_ID="$(jq -er '.id' <<<"${payment_json}")" || fail "결제 ${tag} 응답에 id가 없습니다"
  [[ "$(jq -r '.status' <<<"${payment_json}")" == 'SUCCEEDED' ]] || fail "결제 ${tag}가 성공하지 않았습니다"
}

login 'buyer1@groupdrop.test' "${APP_URL}" "${RUN_TMP}/buyer.cookies"

echo '== 2. 정상 결제 → LED-02 분개 확인 =='
pay_once normal
order_id="${PAY_ORDER_ID}"
payment_id="${PAY_PAYMENT_ID}"
wait_for "주문 ${order_id} PAID" "SELECT status FROM orders WHERE id=${order_id}" 'PAID'
wait_for "결제 원장 거래 1건" \
  "SELECT count(*) FROM ledger_transactions WHERE transaction_type='PAYMENT' AND reference_id=${payment_id}" '1'

echo '  결제 분개:'
run_psql -c "SELECT a.code, e.side, e.amount FROM ledger_entries e
               JOIN ledger_accounts a ON a.id=e.account_id
               JOIN ledger_transactions t ON t.id=e.transaction_id
              WHERE t.transaction_type='PAYMENT' AND t.reference_id=${payment_id} ORDER BY e.id"

echo '== 3. 운영자 환불 (REF-02) — 202 접수 후 워커가 PG를 호출한다 =='
refund_http="$(curl --silent --show-error -o "${RUN_TMP}/refund.json" -w '%{http_code}' \
  -b "${RUN_TMP}/admin.cookies" -X POST -H 'Content-Type: application/json' \
  -H "Idempotency-Key: refund-e2e-refund-$(date +%s)" \
  -d '{"reason":"e2e 실증"}' "${APP_URL}/api/payments/${payment_id}/refunds")"
[[ "${refund_http}" == '202' ]] || { cat "${RUN_TMP}/refund.json" >&2; fail "환불 접수가 202가 아닙니다: ${refund_http}"; }
refund_id="$(jq -r '.id' < "${RUN_TMP}/refund.json")"
[[ "$(jq -r '.status' < "${RUN_TMP}/refund.json")" == 'REQUESTED' ]] || fail '접수 응답이 REQUESTED가 아닙니다'

wait_for "환불 ${refund_id} COMPLETED" "SELECT status FROM refunds WHERE id=${refund_id}" 'COMPLETED'
wait_for "결제 ${payment_id} REFUNDED" "SELECT status FROM payments WHERE id=${payment_id}" 'REFUNDED'
wait_for "주문 ${order_id} REFUNDED" "SELECT status FROM orders WHERE id=${order_id}" 'REFUNDED'
wait_for "환불 역분개 1건 (LED-03)" \
  "SELECT count(*) FROM ledger_transactions WHERE transaction_type='REFUND' AND reference_id=${refund_id}" '1'

echo '== 4. 환불 성공 후 응답 유실 (17.4) — REQUESTED 유지 후 재시도로 해소 =='
curl --fail --silent --show-error -H 'Content-Type: application/json' \
  -d '{"mode":"NORMAL","refundMode":"SUCCEED_BUT_TIMEOUT"}' \
  "${MOCK_PG_URL}/mock-pg/test/failure-mode" > /dev/null

pay_once lossy
lossy_order_id="${PAY_ORDER_ID}"
lossy_payment_id="${PAY_PAYMENT_ID}"
wait_for "주문 ${lossy_order_id} PAID" "SELECT status FROM orders WHERE id=${lossy_order_id}" 'PAID'

lossy_refund_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" -X POST \
  -H 'Content-Type: application/json' -H "Idempotency-Key: refund-e2e-lossy-$(date +%s)" \
  -d '{"reason":"응답 유실 실증"}' "${APP_URL}/api/payments/${lossy_payment_id}/refunds")"
lossy_refund_id="$(jq -r '.id' <<<"${lossy_refund_json}")"

# 첫 실행은 504를 받아 결과 불명으로 남는다. 재시도 백오프(기본 5초) 뒤 같은 호출이 재생되어 확정된다.
wait_for "환불 ${lossy_refund_id} COMPLETED (재시도 해소)" \
  "SELECT status FROM refunds WHERE id=${lossy_refund_id}" 'COMPLETED'
wait_for "결제 ${lossy_payment_id} REFUNDED" \
  "SELECT status FROM payments WHERE id=${lossy_payment_id}" 'REFUNDED'

wait_for "환불 ${lossy_refund_id} 역분개 (LED-03)" \
  "SELECT count(*) FROM ledger_transactions WHERE transaction_type='REFUND' AND reference_id=${lossy_refund_id}" '1'

stats_json="$(curl --fail --silent --show-error "${MOCK_PG_URL}/mock-pg/test/stats")"
refund_executed="$(( $(jq -r '.refundExecutedCount' <<<"${stats_json}") - refund_executed_before ))"
echo "  이번 실행의 PG 환불 실제 실행 ${refund_executed}건 (수신 누적 $(jq -r '.refundRequestCount' <<<"${stats_json}")회)"
# 환불 2건(정상 1 + 유실 1). 유실 건은 두 번 호출되지만 PG 쪽 멱등으로 실행은 1회다 (14.5).
[[ "${refund_executed}" == '2' ]] || fail "PG 환불 실행 증분이 2건이어야 합니다 (실제 ${refund_executed})"

echo '== 5. S5 원장 균형 검증 =='
unbalanced="$(run_psql -tAc "
  SELECT count(*) FROM (
    SELECT t.id
      FROM ledger_transactions t LEFT JOIN ledger_entries e ON e.transaction_id=t.id
     GROUP BY t.id
    HAVING COALESCE(SUM(CASE WHEN e.side='DEBIT' THEN e.amount ELSE 0 END),0)
        <> COALESCE(SUM(CASE WHEN e.side='CREDIT' THEN e.amount ELSE 0 END),0)
        OR count(e.id)=0
  ) x" | tr -d '[:space:]')"
[[ "${unbalanced}" == '0' ]] || fail "차대가 맞지 않는 원장 거래 ${unbalanced}건"
echo "  OK  불균형 거래 0건"

echo '  캠페인 계정 잔액 (환불 2건이 결제 2건을 소거해 전부 0이어야 한다):'
run_psql -c "SELECT a.code, SUM(CASE WHEN e.side='CREDIT' THEN e.amount ELSE -e.amount END) AS balance
               FROM ledger_entries e
               JOIN ledger_accounts a ON a.id=e.account_id
               JOIN ledger_transactions t ON t.id=e.transaction_id
              WHERE t.campaign_id=${campaign_id} GROUP BY a.code ORDER BY a.code"

nonzero="$(run_psql -tAc "
  SELECT count(*) FROM (
    SELECT a.code
      FROM ledger_entries e
      JOIN ledger_accounts a ON a.id=e.account_id
      JOIN ledger_transactions t ON t.id=e.transaction_id
     WHERE t.campaign_id=${campaign_id}
     GROUP BY a.code
    HAVING SUM(CASE WHEN e.side='CREDIT' THEN e.amount ELSE -e.amount END) <> 0
  ) x" | tr -d '[:space:]')"
[[ "${nonzero}" == '0' ]] || fail "소거되지 않은 계정 ${nonzero}개"
echo '  OK  전 계정 잔액 0'

echo '== 6. 환불 지표 노출 =='
curl --fail --silent --show-error "${APP_URL}/actuator/prometheus" \
  | grep -E '^refund_(requested|completed|pg_duration_seconds_count)' || fail '환불 지표가 노출되지 않았습니다'

curl --fail --silent --show-error -H 'Content-Type: application/json' \
  -d '{"mode":"NORMAL","refundMode":"NORMAL"}' \
  "${MOCK_PG_URL}/mock-pg/test/failure-mode" > /dev/null
echo
echo 'PASS: 4주차 환불·원장 실증 완료'
