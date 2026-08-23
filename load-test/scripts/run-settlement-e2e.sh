#!/usr/bin/env bash
# 5주차 정산·대사 실증: 앱과 가상 PG를 실제 HTTP로 붙여 캠페인 종료 → 정산 완료 → 정산 후 환불 회수(S8)와
# PG 불일치 주입 → 대사 분류(S7)를 확인한다. 통합 테스트는 PG 대역을 쓰므로, 진짜 계약(대사 목록 조회 API)이
# 맞는지는 이 스크립트만이 증명한다.
#
# 전제 (5주차 전용 프로젝트·포트 — 3·4주차 스택과 겹치지 않게):
#   APP_PORT=58086 MOCK_PG_PORT=58087 POSTGRES_PORT=55434 \
#     docker compose -p groupdrop-week5 down -v
#   APP_PORT=58086 MOCK_PG_PORT=58087 POSTGRES_PORT=55434 \
#     docker compose -p groupdrop-week5 up -d --build app mock-pg postgres
#
# down -v가 필요한 이유: mock-pg는 인메모리인데 PostgreSQL은 볼륨이다. PG만 재기동하면
# providerPaymentId 시퀀스가 pg_1로 되돌아가 기존 DB 행과 충돌한다.
#
# 대안 (레지스트리에서 베이스 이미지를 받을 수 없을 때): PostgreSQL만 컨테이너로 띄우고
# 두 앱은 로컬 JVM으로 실행한다. 스크립트는 URL·DB 접근 경로만 알면 되므로 그대로 동작한다.
#   docker run -d --name groupdrop-week5-postgres-1 #     -e POSTGRES_DB=groupdrop -e POSTGRES_USER=groupdrop -e POSTGRES_PASSWORD=groupdrop #     -p 55434:5432 postgres:16-alpine
#   (mock-pg) ./gradlew bootRun --args='--server.port=58087 #     --mockpg.webhook.target-url=http://localhost:58086/api/webhooks/payments'
#   (app)     ./gradlew bootRun --args='--server.port=58086 #     --spring.datasource.url=jdbc:postgresql://localhost:55434/groupdrop #     --groupdrop.pg.base-url=http://localhost:58087'
#   DB_CONTAINER=groupdrop-week5-postgres-1 bash load-test/scripts/run-settlement-e2e.sh
set -euo pipefail

APP_URL="${APP_URL:-http://localhost:58086}"
MOCK_PG_URL="${MOCK_PG_URL:-http://localhost:58087}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-55434}"
DB_NAME="${DB_NAME:-groupdrop}"
DB_USER="${DB_USER:-groupdrop}"
DB_PASSWORD="${DB_PASSWORD:-groupdrop}"
DB_CONTAINER="${DB_CONTAINER:-groupdrop-week5-postgres-1}"

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

expect() { # expect <설명> <sql> <기대값>
  local actual
  actual="$(run_psql -tAc "$2" | tr -d '[:space:]')"
  [[ "${actual}" == "$3" ]] || fail "$1: expected $3, got ${actual}"
  echo "  OK  $1: ${actual}"
}

echo '== 0. 가상 PG 정상 모드 + 스택 격리 확인 =='
curl --fail --silent --show-error -H 'Content-Type: application/json' \
  -d '{"mode":"NORMAL","refundMode":"NORMAL"}' \
  "${MOCK_PG_URL}/mock-pg/test/failure-mode" > /dev/null

stats_before="$(curl --fail --silent --show-error "${MOCK_PG_URL}/mock-pg/test/stats")"
confirm_requests_before="$(jq -r '.confirmRequestCount' <<<"${stats_before}")"
existing_provider_payments="$(run_psql -tAc \
  "SELECT count(*) FROM payments WHERE provider_payment_id IS NOT NULL" | tr -d '[:space:]')"
if [[ "${existing_provider_payments}" != '0' && "${confirm_requests_before}" == '0' ]]; then
  fail 'PostgreSQL에는 기존 결제가 있지만 mock-pg 상태는 비어 있습니다. compose down -v 후 재기동하세요'
fi

echo '== 1. 캠페인 준비 (공구가 19,900 × 수수료율 7.5% — 나누어떨어지지 않는 금액, 3.3) =='
supplier_id="$(run_psql -tAc \
  "SELECT s.id FROM suppliers s JOIN users u ON u.id=s.user_id WHERE u.email='supplier@groupdrop.test'" \
  | tr -d '[:space:]')"

login 'supplier@groupdrop.test' "${APP_URL}" "${RUN_TMP}/supplier.cookies"
product_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/supplier.cookies" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"settlement e2e product $(date +%s)\",\"skus\":[{\"optionName\":\"single\"}]}" \
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
  -d "{\"name\":\"settlement e2e campaign\",\"slug\":\"settlement-e2e-$(date +%s)\",\"supplierId\":${supplier_id},\"productId\":${product_id},\"dealPrice\":19900,\"startsAt\":\"${start_at}\",\"endsAt\":\"${end_at}\",\"perUserPurchaseLimit\":50,\"commissionRateBp\":750,\"skus\":[{\"productSkuId\":${sku_id},\"allocatedQuantity\":10,\"supplyUnitPrice\":1000}]}" \
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
  local tag="$1" order_json amount payment_json
  order_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/buyer.cookies" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: settle-e2e-order-${tag}-$(date +%s)" \
    -d "{\"items\":[{\"productSkuId\":${sku_id},\"quantity\":1}]}" \
    "${APP_URL}/api/campaigns/${campaign_id}/orders")" || fail "주문 ${tag} 생성 실패"
  PAY_ORDER_ID="$(jq -er '.id' <<<"${order_json}")" || fail "주문 ${tag} 응답에 id가 없습니다"
  amount="$(jq -r '.totalAmount' <<<"${order_json}")"
  payment_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/buyer.cookies" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: settle-e2e-pay-${tag}-$(date +%s)" \
    -d "{\"amount\":${amount}}" "${APP_URL}/api/orders/${PAY_ORDER_ID}/payments")" \
    || fail "결제 ${tag} 요청 실패"
  PAY_PAYMENT_ID="$(jq -er '.id' <<<"${payment_json}")" || fail "결제 ${tag} 응답에 id가 없습니다"
  [[ "$(jq -r '.status' <<<"${payment_json}")" == 'SUCCEEDED' ]] || fail "결제 ${tag}가 성공하지 않았습니다"
}

echo '== 2. 결제 2건 =='
login 'buyer1@groupdrop.test' "${APP_URL}" "${RUN_TMP}/buyer.cookies"
pay_once keep
keep_order_id="${PAY_ORDER_ID}"
pay_once refunded
refunded_order_id="${PAY_ORDER_ID}"
refunded_payment_id="${PAY_PAYMENT_ID}"

wait_for "주문 ${keep_order_id} PAID" "SELECT status FROM orders WHERE id=${keep_order_id}" 'PAID'
wait_for "주문 ${refunded_order_id} PAID" "SELECT status FROM orders WHERE id=${refunded_order_id}" 'PAID'

echo '== 3. 캠페인 강제 종료 + 정산 유예기간 경과 (SET-02) =='
curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" -X POST \
  "${APP_URL}/api/admin/campaigns/${campaign_id}/cancel" > /dev/null
wait_for "캠페인 ${campaign_id} CLOSED" "SELECT status FROM campaigns WHERE id=${campaign_id}" 'CLOSED'

# 유예기간 7일을 실시간으로 기다릴 수 없으므로 종료 시각을 과거로 돌린다. 시간 조건 자체는
# 스케줄러가 판정하므로, 이 조작으로 검증되는 것은 "유예기간이 지난 캠페인이 정산에 진입한다"이다.
run_psql -c "UPDATE campaigns SET closed_at = now() - interval '8 days' WHERE id=${campaign_id}" > /dev/null

echo '== 4. 정산 실행 → SETTLED (SET-01·SET-02, LED-04) =='
settlement_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" \
  -H 'Content-Type: application/json' -d "{\"campaignId\":${campaign_id}}" \
  "${APP_URL}/api/admin/settlements")"
settlement_outcome="$(jq -r '.outcome' <<<"${settlement_json}")"
echo "  outcome=${settlement_outcome}"
# 인프로세스 스케줄러(기본 10초)가 먼저 확정했으면 SKIPPED가 돌아온다. 그것도 정상 경로이므로
# 여기서는 두 값 모두 허용하고, 실제 판정은 아래의 종료 상태 어서션에 맡긴다.
case "${settlement_outcome}" in
  CREATED|SKIPPED) ;;
  *) fail "정산 대상 확정에 실패했습니다: ${settlement_outcome}" ;;
esac

wait_for "캠페인 ${campaign_id} SETTLED" "SELECT status FROM campaigns WHERE id=${campaign_id}" 'SETTLED'
expect '정상 정산 배치 2건 COMPLETED' \
  "SELECT count(*) FROM settlement_batches WHERE campaign_id=${campaign_id} AND batch_type='SETTLEMENT' AND status='COMPLETED'" '2'
# 주문 2건 × 공급 단가 1,000 = 2,000 / 커미션 floor(19,900×7.5%)=1,492 × 2 = 2,984
expect '공급사 배치 금액 2,000' \
  "SELECT total_amount FROM settlement_batches WHERE campaign_id=${campaign_id} AND payee_type='SUPPLIER' AND batch_type='SETTLEMENT'" '2000'
expect '인플루언서 배치 금액 2,984 (원 단위 절사, ADR-008)' \
  "SELECT total_amount FROM settlement_batches WHERE campaign_id=${campaign_id} AND payee_type='INFLUENCER' AND batch_type='SETTLEMENT'" '2984'
expect '지급 분개 2건 (LED-04)' \
  "SELECT count(*) FROM ledger_transactions WHERE transaction_type='PAYOUT' AND campaign_id=${campaign_id}" '2'

echo '  캠페인 계정 잔액 (지급 예정금은 0, 가상 현금으로 이동):'
run_psql -c "SELECT a.code, SUM(CASE WHEN e.side='CREDIT' THEN e.amount ELSE -e.amount END) AS balance
               FROM ledger_entries e
               JOIN ledger_accounts a ON a.id=e.account_id
               JOIN ledger_transactions t ON t.id=e.transaction_id
              WHERE t.campaign_id=${campaign_id} GROUP BY a.code ORDER BY a.code"

echo '== 5. S6 정산 재실행 — 같은 수령 주체의 정산 항목이 2개가 되지 않는다 =='
curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" \
  -H 'Content-Type: application/json' -d "{\"campaignId\":${campaign_id}}" \
  "${APP_URL}/api/admin/settlements" > /dev/null
expect '정산 배치는 여전히 2건' \
  "SELECT count(*) FROM settlement_batches WHERE campaign_id=${campaign_id} AND batch_type='SETTLEMENT'" '2'
expect '수령 주체별 중복 정산 항목 0건' \
  "SELECT count(*) FROM (SELECT si.payee_type, si.order_item_id FROM settlement_items si
                           JOIN settlement_batches b ON b.id=si.batch_id
                          WHERE b.campaign_id=${campaign_id}
                          GROUP BY si.payee_type, si.order_item_id HAVING count(*) > 1) x" '0'

echo '== 6. S8 정산 후 환불 → 회수 배치 자동 실행 (SET-03) =='
refund_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" -X POST \
  -H 'Content-Type: application/json' -H "Idempotency-Key: settle-e2e-refund-$(date +%s)" \
  -d '{"reason":"정산 후 환불 실증"}' "${APP_URL}/api/payments/${refunded_payment_id}/refunds")"
refund_id="$(jq -r '.id' <<<"${refund_json}")"

wait_for "환불 ${refund_id} COMPLETED" "SELECT status FROM refunds WHERE id=${refund_id}" 'COMPLETED'
wait_for '회수 배치 2건 COMPLETED' \
  "SELECT count(*) FROM settlement_batches WHERE campaign_id=${campaign_id} AND batch_type='RECOVERY' AND status='COMPLETED'" '2'
expect '회수 조정 2건, 미회수 0건' \
  "SELECT count(*) FROM settlement_adjustments a JOIN settlement_batches b ON b.id=a.batch_id
    WHERE b.campaign_id=${campaign_id} AND a.recovery_batch_id IS NULL" '0'
# 결제 2건 +2,000 → 지급 -2,000 → 환불 역분개 -1,000 → 회수 +1,000 = 0.
expect '공급사 지급 예정금 잔액 0 (S8)' \
  "SELECT COALESCE(SUM(CASE WHEN e.side='CREDIT' THEN e.amount ELSE -e.amount END),0)
     FROM ledger_entries e JOIN ledger_accounts a ON a.id=e.account_id
     JOIN ledger_transactions t ON t.id=e.transaction_id
    WHERE t.campaign_id=${campaign_id} AND a.code='SUPPLIER_PAYABLE'" '0'
expect '인플루언서 지급 예정금 잔액 0 (S8)' \
  "SELECT COALESCE(SUM(CASE WHEN e.side='CREDIT' THEN e.amount ELSE -e.amount END),0)
     FROM ledger_entries e JOIN ledger_accounts a ON a.id=e.account_id
     JOIN ledger_transactions t ON t.id=e.transaction_id
    WHERE t.campaign_id=${campaign_id} AND a.code='INFLUENCER_PAYABLE'" '0'

unrecovered_api="$(curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" \
  "${APP_URL}/api/admin/settlement-adjustments" | jq "[.[] | select(.campaignId==${campaign_id})] | length")"
[[ "${unrecovered_api}" == '0' ]] || fail "미회수 잔액 조회가 0이 아닙니다: ${unrecovered_api}"
echo '  OK  미회수 잔액 조회 0건'

echo '== 7. S7 PG 불일치 주입 → 대사 분류 (REC-01) =='
ghost_json="$(curl --fail --silent --show-error -H 'Content-Type: application/json' \
  -d "{\"orderId\":999999999,\"amount\":31337,\"status\":\"SUCCEEDED\",\"processedAt\":\"$(date -u -v-1H '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u -d '1 hour ago' '+%Y-%m-%dT%H:%M:%SZ')\"}" \
  "${MOCK_PG_URL}/mock-pg/test/transactions")"
ghost_provider_id="$(jq -r '.providerPaymentId' <<<"${ghost_json}")"

run_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" \
  -H 'Content-Type: application/json' -d '{"minAgeMinutes":0}' \
  "${APP_URL}/api/admin/reconciliations")"
echo "  대사 실행 ${run_json}"
[[ "$(jq -r '.status' <<<"${run_json}")" == 'COMPLETED' ]] || fail '대사 실행이 완료되지 않았습니다'

discrepancy_type="$(curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" \
  "${APP_URL}/api/admin/reconciliation-discrepancies?status=OPEN" \
  | jq -r "[.[] | select(.providerPaymentId==\"${ghost_provider_id}\")][0].type")"
[[ "${discrepancy_type}" == 'MISSING_INTERNAL' ]] \
  || fail "주입 거래가 MISSING_INTERNAL로 분류되지 않았습니다: ${discrepancy_type}"
echo "  OK  주입 거래 ${ghost_provider_id} → MISSING_INTERNAL, 조회 API 노출"

expect '내부 결제에 대한 불일치 0건 (정상 거래는 깨끗하게 매칭)' \
  "SELECT count(*) FROM reconciliation_discrepancies d
     JOIN payments p ON p.id=d.payment_id JOIN orders o ON o.id=p.order_id
    WHERE d.status='OPEN' AND o.campaign_id=${campaign_id}" '0'

echo '== 8. S5 원장 재검산 + 운영 요약 =='
expect '불균형 원장 거래 0건' "
  SELECT count(*) FROM (
    SELECT t.id FROM ledger_transactions t LEFT JOIN ledger_entries e ON e.transaction_id=t.id
     GROUP BY t.id
    HAVING COALESCE(SUM(CASE WHEN e.side='DEBIT' THEN e.amount ELSE 0 END),0)
        <> COALESCE(SUM(CASE WHEN e.side='CREDIT' THEN e.amount ELSE 0 END),0)
        OR count(e.id)=0) x" '0'
expect '대사 실행 기록의 원장 불균형 수치도 0' \
  "SELECT ledger_unbalanced_count FROM reconciliation_runs ORDER BY id DESC LIMIT 1" '0'

summary_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" \
  "${APP_URL}/api/admin/ops/summary")"
echo "  ops/summary ${summary_json}"
[[ "$(jq -r '.unknownPaymentCount' <<<"${summary_json}")" == '0' ]] || fail '미확정 결제가 남아 있습니다'
[[ "$(jq -r '.blockedSettlementCount' <<<"${summary_json}")" == '0' ]] || fail '실패·보류 정산이 남아 있습니다'
[[ "$(jq -r '.unrecoveredAdjustmentCount' <<<"${summary_json}")" == '0' ]] || fail '미회수 잔액이 남아 있습니다'

echo '== 9. 정산·대사 지표 노출 (16.4) =='
curl --fail --silent --show-error "${APP_URL}/actuator/prometheus" \
  | grep -E '^(settlement_completed_total|settlement_recovered_total|ledger_unbalanced|reconciliation_run_total)' \
  || fail '정산·대사 지표가 노출되지 않았습니다'

echo
echo 'PASS: 5주차 정산·대사 실증 완료'
