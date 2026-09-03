# 최종 회귀 보고서

- 기준: 기획서 v1.10 S1~S8, 17장, 18장 6주차
- 작성일: 2026-09-03
- 실행 소스: 기준 커밋 `cbf48f4ee4c0d4dfc4e2ec2b00d292d706f36d8a`
- 현재 판정: **PASS** (P2 산출물은 별도 추적)

## 자동 회귀

```bash
cd app && ./gradlew cleanTest test
cd ../mock-pg && ./gradlew cleanTest test
```

OpenAPI/Swagger를 포함한 최종 전체 회귀 결과는 다음과 같다.

```text
app      PASS — 157 tests, failures 0, errors 0, skipped 0
mock-pg  PASS — 24 tests
```

release gate 전체도 PASS했다.

## 실행 증거

- 전체 앱 회귀: [full-app-regression](evidence/2026-09-03-full-app-regression.txt)
- S1-a/b: [S1-a](evidence/2026-09-03-s1-a.txt), [S1-b](evidence/2026-09-03-s1-b.txt)
- S2~S8: [S2](evidence/2026-09-03-s2.txt), [S3](evidence/2026-09-03-s3.txt), [S4-a](evidence/2026-09-03-s4-a.txt), [S4-b](evidence/2026-09-03-s4-b.txt), [S5](evidence/2026-09-03-s5.txt), [S6](evidence/2026-09-03-s6.txt), [S7](evidence/2026-09-03-s7.txt), [S8](evidence/2026-09-03-s8.txt)
- 실제 HTTP·복구: [환불](evidence/2026-09-03-refund-e2e.txt), [정산](evidence/2026-09-03-settlement-e2e.txt), [docker-kill 복구](evidence/2026-09-03-compose-kill-recovery.txt)

## 성공 기준 게이트

| 기준 | 자동 테스트 근거 | 외부 실증 | 현 상태 |
|---|---|---|---|
| S1-a | 주문·재고 통합 테스트 | 단일 인스턴스 k6 + SQL | PASS — 1,000 attempts, 성공 100, 품절 900, 5xx 0%, p50/p95/p99 92.00/452.71/559.75ms, 재고·예약·구매 카운터 불변식 위반 0 |
| S1-b | 다중 인스턴스 안전 설계·스크립트 | 2인스턴스 k6 + SQL | PASS — 1,000 attempts, app1/app2 500/500, 성공 100, 품절 900, 5xx 0%, p50/p95/p99 190.28/837.41/1,150ms, 재고·예약·구매 카운터 불변식 위반 0 |
| S2 | 동시 멱등 결제 테스트 | 선택적 HTTP 재현 | PASS |
| S3 | Inbox 중복·역순·원장 테스트 | 2인스턴스 소비 대조 | PASS |
| S4-a | UNKNOWN 웹훅 복구 테스트 | replay | PASS |
| S4-b | UNKNOWN 대사 복구 테스트 | 수동 대사 | PASS |
| S5 | 원장 균형 검증 테스트·메트릭 | 대사 실행 | PASS — `ledger_unbalanced_total=0` |
| S6 | payee별 정산 항목 유니크 테스트 | 재실행 | PASS |
| S7 | 8개 대사 불일치 분류·조회 및 발생 시각 경계 테스트 | mock-pg 주입 | PASS — 1초 허용, 1,001ms·한쪽 누락 OPEN, 재일치 자동 해소, 정산 HELD, 정상 `SUPERSEDED` 보상 쌍 오탐 0 |
| S8 | 회수 배치·미회수 잔액 테스트 | 환불 후 회수 | PASS |

## 별도 추적 항목

1. Grafana 패널·캡처, 데모 영상, 배포는 20장 P2/컷 후보이며 6주차의 S1~S8 최종 게이트와 구분한다.
