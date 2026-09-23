#!/usr/bin/env bash
# 시연 데이터 시드 (프론트엔드 계획 §2 "시연 데이터", §8 "seed-demo.sh 명세").
#
# 공개 HTTP API만 호출한다 — psql·docker exec 없음. load-test/scripts/run-settlement-e2e.sh의
# 쿠키 자(jar) 로그인·헬퍼 함수 스타일을 그대로 따른다.
#
# 만드는 것:
#   - supplier: 상품 1개, SKU 2개
#   - influencer: 캠페인 A·B (재고 SKU당 100, perUserPurchaseLimit 50, commissionRateBp 750,
#     dealPrice 19900, supplyUnitPrice 1000 — run-s1-a.sh와 같은 마진 게이트 통과 값)
#   - admin: A·B 승인 → OPEN까지 폴링
#   - buyer1: A·B 각각 주문 1건 + 결제 성공(PAID), A에 PENDING_PAYMENT 주문 1건 추가
#
# 재실행하면 매번 새 캠페인(슬러그에 타임스탬프+PID)을 만든다. 캠페인은 닫지 않는다 —
# 시연 스택(docker-compose.demo.yml, 정산 유예 0s)에서는 닫는 순간 정산이 시작되므로
# 7.4 "대사 불일치 → 정산 보류" 시나리오가 시연 직전까지 열린 캠페인 B를 전제로 한다.
#
# 사용법:
#   APP_URL=http://localhost:8080 MOCK_PG_URL=http://localhost:8081 ./ops/demo/seed-demo.sh
set -euo pipefail

APP_URL="${APP_URL:-http://localhost:8080}"
MOCK_PG_URL="${MOCK_PG_URL:-http://localhost:8081}"
PASSWORD='groupdrop123!'

RUN_TMP="$(mktemp -d)"
trap 'rm -rf "${RUN_TMP}"' EXIT

RUN_TAG="$(date +%s)-$$"

fail() { echo "FAIL: $*" >&2; exit 1; }

login() { # login <email> <cookie-jar>
  curl --fail --silent --show-error -c "$2" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"${PASSWORD}\"}" "${APP_URL}/api/auth/login" > /dev/null \
    || fail "로그인 실패: $1"
}

# poll_field <설명> <URL> <cookie-jar> <jq 필터> <기대값> [timeoutSec=30]
poll_field() {
  local description="$1" url="$2" jar="$3" filter="$4" expected="$5" timeout="${6:-30}"
  local waited=0 actual=''
  while (( waited < timeout )); do
    actual="$(curl --fail --silent --show-error -b "${jar}" "${url}" | jq -r "${filter}")"
    if [[ "${actual}" == "${expected}" ]]; then
      echo "  OK  ${description}: ${actual}"
      return 0
    fi
    sleep 1
    waited=$(( waited + 1 ))
  done
  fail "${description}: expected ${expected}, got ${actual} (${timeout}초 초과)"
}

echo '== 0. 가드: 이 스택이 재사용된 것이 아닌지 확인 =='
stats_before="$(curl --fail --silent --show-error "${MOCK_PG_URL}/mock-pg/test/stats")"
confirm_requests_before="$(jq -r '.confirmRequestCount' <<<"${stats_before}")"

login 'admin@groupdrop.test' "${RUN_TMP}/admin.cookies"
# status 파라미터를 생략하면 GET /api/admin/payments는 UNKNOWN만 돌려준다
# (ReconciliationOpsService:98, 기본값 List.of("UNKNOWN")). 이전 실행의 SUCCEEDED 결제를
# 놓치지 않으려면 CHECK 제약의 결제 상태 전부를 명시해야 한다 (format.js §5.7 표).
all_payment_statuses='READY,PROCESSING,SUCCEEDED,FAILED,UNKNOWN,SUPERSEDED,REFUNDING,REFUNDED'
existing_payments_count="$(curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" \
  "${APP_URL}/api/admin/payments?status=${all_payment_statuses}" | jq 'length')"
if [[ "${confirm_requests_before}" == '0' && "${existing_payments_count}" != '0' ]]; then
  fail "PostgreSQL에는 기존 결제(${existing_payments_count}건)가 있지만 mock-pg 상태는 비어 있습니다. \
docker compose -f docker-compose.yml -f docker-compose.demo.yml down -v 후 재기동하세요"
fi
echo "  OK  mock-pg confirmRequestCount=${confirm_requests_before}, admin payments=${existing_payments_count}"

echo '== 1. 공급사: 상품 1개 (SKU 2개) 등록 =='
login 'supplier@groupdrop.test' "${RUN_TMP}/supplier.cookies"
product_name="demo product ${RUN_TAG}"
product_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/supplier.cookies" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"${product_name}\",\"skus\":[{\"optionName\":\"1개입\"},{\"optionName\":\"3개입\"}]}" \
  "${APP_URL}/api/suppliers/me/products")"
echo "  등록됨: $(jq -c '{id,name}' <<<"${product_json}")"

echo '== 2. GET /api/products 로 supplierId·productId·skuIds 조회 =='
login 'influencer@groupdrop.test' "${RUN_TMP}/influencer.cookies"
products_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/influencer.cookies" \
  "${APP_URL}/api/products")"
supplier_id="$(jq -r --arg n "${product_name}" 'map(select(.name==$n)) | .[0].supplierId' <<<"${products_json}")"
product_id="$(jq -r --arg n "${product_name}" 'map(select(.name==$n)) | .[0].id' <<<"${products_json}")"
sku1_id="$(jq -r --arg n "${product_name}" 'map(select(.name==$n)) | .[0].skus[0].id' <<<"${products_json}")"
sku2_id="$(jq -r --arg n "${product_name}" 'map(select(.name==$n)) | .[0].skus[1].id' <<<"${products_json}")"
[[ "${supplier_id}" != 'null' && "${product_id}" != 'null' \
   && "${sku1_id}" != 'null' && "${sku2_id}" != 'null' ]] \
  || fail "GET /api/products 응답에서 방금 등록한 상품을 찾지 못했습니다 (LIMIT 100 밖일 수 있습니다): ${product_name}"
echo "  supplierId=${supplier_id} productId=${product_id} skuIds=${sku1_id},${sku2_id}"

if starts_at="$(date -u -v-1M '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null)"; then
  ends_at="$(date -u -v+2H '+%Y-%m-%dT%H:%M:%SZ')"
else
  starts_at="$(date -u -d '1 minute ago' '+%Y-%m-%dT%H:%M:%SZ')"
  ends_at="$(date -u -d '2 hours' '+%Y-%m-%dT%H:%M:%SZ')"
fi

campaign_payload() { # campaign_payload <name> <slug>
  cat <<JSON
{"name":"$1","slug":"$2","supplierId":${supplier_id},"productId":${product_id},
 "dealPrice":19900,"startsAt":"${starts_at}","endsAt":"${ends_at}",
 "perUserPurchaseLimit":50,"commissionRateBp":750,
 "skus":[
   {"productSkuId":${sku1_id},"allocatedQuantity":100,"supplyUnitPrice":1000},
   {"productSkuId":${sku2_id},"allocatedQuantity":100,"supplyUnitPrice":1000}
 ]}
JSON
}

echo '== 3. 인플루언서: 캠페인 A·B 개설 + 제출 =='
slug_a="demo-a-${RUN_TAG}"
slug_b="demo-b-${RUN_TAG}"

campaign_a_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/influencer.cookies" \
  -H 'Content-Type: application/json' -d "$(campaign_payload "데모 캠페인 A ${RUN_TAG}" "${slug_a}")" \
  "${APP_URL}/api/campaigns")"
campaign_a_id="$(jq -er '.id' <<<"${campaign_a_json}")" || fail "캠페인 A 생성 응답에 id가 없습니다: ${campaign_a_json}"

campaign_b_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/influencer.cookies" \
  -H 'Content-Type: application/json' -d "$(campaign_payload "데모 캠페인 B ${RUN_TAG}" "${slug_b}")" \
  "${APP_URL}/api/campaigns")"
campaign_b_id="$(jq -er '.id' <<<"${campaign_b_json}")" || fail "캠페인 B 생성 응답에 id가 없습니다: ${campaign_b_json}"

curl --fail --silent --show-error -b "${RUN_TMP}/influencer.cookies" -X POST \
  "${APP_URL}/api/campaigns/${campaign_a_id}/submit" > /dev/null
curl --fail --silent --show-error -b "${RUN_TMP}/influencer.cookies" -X POST \
  "${APP_URL}/api/campaigns/${campaign_b_id}/submit" > /dev/null
echo "  캠페인 A id=${campaign_a_id} slug=${slug_a}"
echo "  캠페인 B id=${campaign_b_id} slug=${slug_b}"

echo '== 4. 운영자: 승인 → OPEN까지 폴링 (1초 스케줄러) =='
curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" -X POST \
  "${APP_URL}/api/admin/campaigns/${campaign_a_id}/approve" > /dev/null
curl --fail --silent --show-error -b "${RUN_TMP}/admin.cookies" -X POST \
  "${APP_URL}/api/admin/campaigns/${campaign_b_id}/approve" > /dev/null
poll_field "캠페인 A(${campaign_a_id}) OPEN" "${APP_URL}/api/campaigns/${campaign_a_id}" \
  "${RUN_TMP}/admin.cookies" '.status' 'OPEN' 30
poll_field "캠페인 B(${campaign_b_id}) OPEN" "${APP_URL}/api/campaigns/${campaign_b_id}" \
  "${RUN_TMP}/admin.cookies" '.status' 'OPEN' 30

echo '== 5. buyer1: 주문 + 결제 =='
login 'buyer1@groupdrop.test' "${RUN_TMP}/buyer1.cookies"

# order_and_pay <tag> <campaignId> <skuId> → ORDER_ID, PAYMENT_ID (전역 변수로 결과를 돌려준다)
ORDER_ID=''
PAYMENT_ID=''
order_and_pay() {
  local tag="$1" campaign_id="$2" sku_id="$3" order_json amount payment_json payment_status
  order_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/buyer1.cookies" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: seed-demo-order-${tag}-${RUN_TAG}" \
    -d "{\"items\":[{\"productSkuId\":${sku_id},\"quantity\":1}]}" \
    "${APP_URL}/api/campaigns/${campaign_id}/orders")" || fail "주문 ${tag} 생성 실패"
  ORDER_ID="$(jq -er '.id' <<<"${order_json}")" || fail "주문 ${tag} 응답에 id가 없습니다: ${order_json}"
  amount="$(jq -er '.totalAmount' <<<"${order_json}")"

  payment_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/buyer1.cookies" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: seed-demo-pay-${tag}-${RUN_TAG}" \
    -d "{\"amount\":${amount}}" "${APP_URL}/api/orders/${ORDER_ID}/payments")" \
    || fail "결제 ${tag} 요청 실패"
  PAYMENT_ID="$(jq -er '.id' <<<"${payment_json}")" || fail "결제 ${tag} 응답에 id가 없습니다: ${payment_json}"
  payment_status="$(jq -r '.status' <<<"${payment_json}")"
  # NORMAL 모드에서는 200 SUCCEEDED가 정상이다. 202 UNKNOWN이 와도 웹훅·아웃박스로 뒤이어
  # 확정되므로 실패로 보지 않고 아래 주문 PAID 폴링에 맡긴다. FAILED만 즉시 실패로 다룬다.
  [[ "${payment_status}" != 'FAILED' ]] || fail "결제 ${tag}가 실패했습니다: ${payment_json}"

  # 웹훅 경로는 inbox+outbox가 순차라 합계 최대 약 10초 (프론트엔드 계획 §3). 여유를 두고 30초.
  poll_field "주문 ${tag}(${ORDER_ID}) PAID" "${APP_URL}/api/orders/${ORDER_ID}" \
    "${RUN_TMP}/buyer1.cookies" '.status' 'PAID' 30
}

order_and_pay 'A' "${campaign_a_id}" "${sku1_id}"
order_a_id="${ORDER_ID}"
payment_a_id="${PAYMENT_ID}"

order_and_pay 'B' "${campaign_b_id}" "${sku1_id}"
order_b_id="${ORDER_ID}"
payment_b_id="${PAYMENT_ID}"

echo '== 6. buyer1: 캠페인 A에 PENDING_PAYMENT 주문 1건 추가 (결제 없음) =='
pending_order_json="$(curl --fail --silent --show-error -b "${RUN_TMP}/buyer1.cookies" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: seed-demo-order-A-pending-${RUN_TAG}" \
  -d "{\"items\":[{\"productSkuId\":${sku2_id},\"quantity\":1}]}" \
  "${APP_URL}/api/campaigns/${campaign_a_id}/orders")" || fail 'PENDING_PAYMENT 주문 생성 실패'
order_a_pending_id="$(jq -er '.id' <<<"${pending_order_json}")" \
  || fail "PENDING_PAYMENT 주문 응답에 id가 없습니다: ${pending_order_json}"
pending_status="$(jq -r '.status' <<<"${pending_order_json}")"
[[ "${pending_status}" == 'PENDING_PAYMENT' ]] \
  || fail "예상과 다른 주문 상태입니다: ${pending_status}"
echo "  주문 ${order_a_pending_id} PENDING_PAYMENT (10분 뒤 EXPIRED — 시연 직전에 이 스크립트를 실행하세요)"

echo
echo '=================== 시연 데이터 요약 ==================='
echo "상품        id=${product_id}  supplierId=${supplier_id}  skuIds=${sku1_id},${sku2_id}"
echo "캠페인 A    id=${campaign_a_id}  slug=${slug_a}"
echo "캠페인 B    id=${campaign_b_id}  slug=${slug_b}"
echo "주문(A,PAID)          id=${order_a_id}  결제 id=${payment_a_id}"
echo "주문(B,PAID)          id=${order_b_id}  결제 id=${payment_b_id}"
echo "주문(A,PENDING_PAYMENT) id=${order_a_pending_id}  (결제 없음, 10분 뒤 EXPIRED)"
echo '=========================================================='
echo
echo 'PASS: 시연 데이터 시드 완료'
