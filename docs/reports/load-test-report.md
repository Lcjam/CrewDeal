# 부하 테스트 보고서

- 기준: 기획서 v1.10 S1-a/b, 17.3, 17.5
- 작성일: 2026-09-06
- 실행 소스: 기준 커밋 `510434d83686166ce5eb3bc2bcf60af91efbca92`
- 판정: **PASS**

## 고정 부하 정의

| 항목 | 값 |
|---|---|
| 실행기 | k6 `per-vu-iterations` |
| 요청 수 | 200 VU × VU당 5회 = 1,000 주문 시도 |
| 재고 | 100 |
| 구매자 | 시드 구매자 200명, VU와 1:1 매핑 |
| 구매 제한 | 구매자당 10 |
| S1-a | 앱 1개 |
| S1-b | 앱 2개, VU별 URL 고정 배정, 인스턴스별 500회 |

이 실험의 1차 목적은 TPS 수치가 아니라 정합성이다. 부하 발생기·앱·DB가 같은 로컬 머신을 공유하므로 p50/p95/p99는 참고치로만 기록한다.

## 실행 명령

```bash
# S1-a: 준비된 단일 앱 Compose를 대상으로 실행
cd load-test && ./scripts/run-s1-a.sh

# S1-b: 두 앱 Compose를 기동한 뒤 실행
APP_PORT=58102 APP2_PORT=58103 MOCK_PG_PORT=58101 POSTGRES_PORT=55435 \
docker compose -p groupdrop-week6-s1b -f docker-compose.yml -f docker-compose.two-instances.yml \
  up -d --build postgres mock-pg app app2
cd load-test && ./scripts/run-s1-b.sh
```

## 성공 판정과 SQL

| 항목 | 기대값 |
|---|---:|
| 성공 예약 | 정확히 100 |
| 품절 응답 | 정확히 900 |
| 비즈니스 거절 외 5xx | 1% 미만 |
| 재고 불변식 위반 | 0 |
| 예약 교차 불변식 위반 | 0 |
| 구매 카운터 불변식 위반 | 0 |
| 종료 재고 | `available/reserved/sold = 0/100/0` |

`load-test/sql/inventory-invariants.sql`은 `campaign_id`를 받아 재고·예약 교차·구매 카운터 불변식과 성공 주문 수를 직접 검사한다.

```bash
psql -h localhost -p 55435 -U groupdrop -d groupdrop \
  -v campaign_id=<CAMPAIGN_ID> -f load-test/sql/inventory-invariants.sql
```

## 현재 실제 결과

| 실행 | 상태 | 결과 |
|---|---|---|
| 과거 2~3주차 S1-a | 참고 | 당시 체크포인트에 별도 기록됨; 아래 최종 실행 결과와 구분 |
| 현재 최종 S1-a | PASS | campaign 1; attempts 1,000, 성공 100, 품절 900, 5xx 0%, `iteration_duration` p50/p95/p99 `116.27/455.80/588.78ms`; inventory/reservation/purchase-counter 위반 0, `available/reserved/sold=0/100/0` |
| 현재 최종 S1-b | PASS | campaign 1; attempts 1,000, app1/app2 500/500, 성공 100, 품절 900, 5xx 0%, `iteration_duration` p50/p95/p99 `234.72/592.80/887.41ms`; inventory/reservation/purchase-counter 위반 0, `available/reserved/sold=0/100/0` |

두 실행 모두 고정 부하 정의와 SQL 정합성 판정을 만족했다. 지연 수치는 동일 로컬 머신에서 측정한 참고값이며, S1의 주된 성공 근거는 성공 주문 정확히 100건과 불변식 위반 0건이다.

원본 실행 로그: [S1-a](evidence/2026-09-06-s1-a.txt), [S1-b](evidence/2026-09-06-s1-b.txt).
