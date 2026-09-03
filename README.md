# GroupDrop

재고 100개에 1,000건의 주문이 짧은 구간에 몰려도 초과 판매 없이 처리하고, 결제 성공 응답이 유실돼도 멱등 처리·웹훅·대사로 최종 상태를 복구하는 인플루언서 공동구매 플랫폼입니다.

GroupDrop은 화면 수보다 거래 정합성을 우선하는 Spring Boot 포트폴리오입니다. 한정 재고 주문, 외부 PG 결제, 전액 환불, 불변 원장, 정산·회수, PG 대사와 운영자 복구를 하나의 모듈러 모놀리스에서 다룹니다.

## 아키텍처와 핵심 규칙

- Spring Boot 4 / Java 21 / PostgreSQL / Flyway, 가상 PG는 별도 애플리케이션으로 실행합니다.
- 재고와 구매 제한은 조건부 원자 `UPDATE`로 처리합니다. 읽은 뒤 계산해 저장하지 않습니다 (ORD-02, ORD-04).
- 외부 PG 호출 중에는 DB 트랜잭션을 유지하지 않습니다. 응답 유실은 실패로 단정하지 않고 `UNKNOWN`으로 기록한 뒤 웹훅·조회·대사로 확정합니다 (PAY-03, ADR-003).
- Outbox/Inbox는 DB에 저장하고 폴링합니다. 웹훅·원장·정산 후속 처리는 at-least-once 전달을 전제로 멱등하게 만듭니다.
- 원장은 수정·삭제하지 않습니다. 환불·정산 보정은 반대 거래를 추가하며, 정산 금액의 원천은 주문별 원장 합계입니다 (LED-01, ADR-008).
- 결제 생성·확정과 정산 동결은 같은 캠페인 행 장벽으로 조정하고, 대사 실행은 DB의 단일 `RUNNING` 세대로 직렬화합니다 (ADR-010, ADR-011).

## 빠른 실행

Docker와 Docker Compose가 필요합니다.

```bash
docker compose up -d --build
docker compose ps
```

기본 접속 주소는 다음과 같습니다.

- 앱: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- 운영 화면: `http://localhost:8080/admin/index.html`
- 가상 PG: `http://localhost:8081`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`

종료는 `docker compose down`으로 합니다. 볼륨까지 지우려면 데이터가 삭제되므로 범위를 확인한 뒤 `docker compose down -v`를 사용합니다.

## 테스트와 실증

```bash
cd app && ./gradlew cleanTest test
cd ../mock-pg && ./gradlew cleanTest test

# 2개 앱 인스턴스 S1-b (Compose 기동 후 실행)
cd ../load-test && ./scripts/run-s1-b.sh

# docker kill 뒤 UNKNOWN·Outbox/Inbox 복구 실증
cd ../load-test && ./scripts/run-compose-kill-recovery.sh
```

S1-b는 k6 `200 VU × 5회 = 1,000` 주문 시도를 두 앱에 500회씩 고정 배정하고, 종료 뒤 재고·예약 SQL 불변식을 검증합니다. 최종 회귀에서는 app 148건과 mock-pg 24건, 총 172건이 통과했습니다.

## 성공 기준과 문서

| 기준 | 검증 대상 | 상태·근거 |
|---|---|---|
| S1-a/b | 재고 100개·1,000 주문, 단일/2인스턴스 | k6 시나리오와 재고·예약 불변식 SQL |
| S2 | 결제 멱등 키 동시 10건 | 결제 동시성 통합 테스트 |
| S3 | 중복 웹훅 10회, Inbox·주문·원장 1회 | 웹훅 Inbox 통합 테스트 |
| S4-a/b | UNKNOWN의 웹훅·대사 복구 | 웹훅·PG 재조회 복구 테스트 |
| S5 | 원장 차변=대변 | 원장 통합 테스트 |
| S6 | 수령 주체별 정산 항목 중복 방지 | 정산 동시성 통합 테스트 |
| S7 | PG-내부 불일치 분류·노출 | 대사 통합 테스트 |
| S8 | 정산 후 환불 회수·미회수 잔액 | 회수 배치 통합 테스트 |

내부 기획·ADR·운영 보고서는 공개 저장소에서 제외하며, 구현과 자동화 테스트가 공개본의 검증 근거입니다.

## 보안 주의

로컬 Compose의 기본 계정과 비밀번호는 데모 전용입니다. 실제 자격 증명, 세션 키, 외부 서비스 토큰은 저장소에 넣지 말고 `.env` 또는 배포 환경의 비밀 관리에만 두세요. `.env`를 커밋하거나 보고서·스크린샷에 출력하지 않습니다.
