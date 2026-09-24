# BE-commerce

이커머스 플랫폼 **BE-commerce**의 백엔드입니다. 상품 탐색·주문·결제·정산·대사까지 다루고,
그 코어는 **실패 이후의 정합성 회복**에 둡니다. PG 타임아웃, 중복 요청, 이벤트 재전달, 부분 실패를
실제 운영에서 발생할 수 있는 상태로 보고, 이를 기록·복구·대사하는 흐름을 구현했습니다.

결제는 이 플랫폼의 코어이지 전부가 아닙니다. 구매자가 상품을 탐색하고 주문해 결제·취소·구매확정까지
가는 화면([스토어프론트](docs/28-스토어프론트-설계.md))이 같은 API 위에 있고, 개인화 시스템을
같은 저장소의 별도 영역에서 진행합니다.

[![CI](https://github.com/dj258255/BE-commerce/actions/workflows/ci.yml/badge.svg)](https://github.com/dj258255/BE-commerce/actions/workflows/ci.yml)

## 핵심 결과

**어려운 순서로 적었다.** 위쪽일수록 정답이 없고, 실험하지 않으면 고를 수 없었던 문제다.
아래로 갈수록 기반 지식을 적용하면 답이 좁혀진다.

| 문제 | 무엇이 충돌했나 | 고른 것과 그 대가 | 근거 |
|---|---|---|---|
| **느린 PG 앞에서 무엇을 먼저 버리나**<br>서킷은 실패를 세므로 **느리지만 성공하는 PG** 는 못 잡는다 | 결제 처리량 ↔ 무관한 요청의 지연 | 동시 호출 상한 40. 지연 3초에서 조회 p95 **7.31초 → 8ms**, 대가는 결제 거절 **73.3%**. 거절률은 `1 − 상한/(도착률×지연)` 으로 예측되고 **실측 오차 0.0%p** | [ADR-022](docs/adr/ADR-022-pg-brownout-resource-limits.md) · [성능 §14](docs/performance/README.md) |
| **언제 서비스를 쪼갤 것인가**<br>흔히 드는 분리 근거 셋을 가설로 세워 각각 재현을 시도했다 | 팔기 쉬운 근거 ↔ 사실 | **셋 중 하나를 기각**(배치 경합은 풀이 포화되지 않아 미재현). 재현된 둘만 근거로 썼고, 배포 단위만 나눠 배포 중단 **270건 → 0건** | [ADR-029](docs/adr/ADR-029-deployment-unit-vs-service-boundary.md) · [성능 §15](docs/performance/README.md) |
| **PG 응답이 불확실한 타임아웃**<br>성공도 실패도 아닌 상태를 무엇으로 적을 것인가 | 즉시 응답 ↔ 잘못 확정한 결제를 되돌리는 비용 | `UNKNOWN` 으로 보존하고 조회·복구로 확정. 복구 지연의 하한은 `MIN_AGE`, 상한은 `청크/주기` — **둘 다 식으로 예측되고 실측이 맞는다**(예측 t+106.5s / 실측 t+106s) | [성능 §14.5](docs/performance/README.md) |
| **정산 분리 시 과거 데이터를 옮기나**<br>에스크로 홀드가 7일이라 전환 직전 7일치가 갈 곳을 잃는다 | 되돌릴 수 있음 ↔ 이중 쓰기 복잡도 | 미결 항목만 이관(C안). **예행에서만 나온 것 둘** — 스키마가 다르고, 이관이 전환보다 먼저여야 한다 | [ADR-024](docs/adr/ADR-024-settlement-extraction-data-cutover.md) |
| **컨슈머가 실패하면 멈출 것인가 넘길 것인가** | 유실 0 ↔ head-of-line 블로킹 | DLT 격리. 대사가 최종 방어선이라 **격리 건은 유실이 아니라 검출 가능한 미처리**가 된다 | [ADR-030](docs/adr/ADR-030-dlt-replay-and-post-recovery-reconciliation.md) |
| **추천 모델을 넣을 것인가**<br>넘어야 할 선을 **측정 전에** 못 박았다 | 모델을 넣었다는 사실 ↔ 실제로 더 나은가 | **안 넣는다.** ALS 최고 MAP@12 **0.0076** 으로 기준선 `repeat_last` 0.0234 의 3분의 1. 두 축을 열어도 폭이 15% 미만 | [ADR-048](docs/adr/ADR-048-als-model-not-adopted.md) |
| **캐시 값이 커질 때 압축할 것인가** | 네트워크·메모리 ↔ CPU | 교차점 실측(**187B 손해 / 279B 이득**), 임계값은 **이득이 확실한 1KB**. 코덱은 실측이 통념을 뒤집어 SNAPPY | [ADR-041](docs/adr/ADR-041-cache-compression-threshold.md) |
| **DB 저장과 이벤트 발행 사이의 유실** | 단순함 ↔ at-least-once 보장 | 커밋과 같은 트랜잭션에 남기는 Outbox. 재기동 **8초** 내 재발행 확인 | [성능 §17](docs/performance/README.md) |
| **재고·잔액의 동시 차감** | 정확한 순서 ↔ 처리량 | 조건부 `UPDATE` + 영향 행 수 판정. 승인 성공률 **39.6% → 100%**. **H2 에서는 순위가 반대였다** | [ADR-004](docs/adr/ADR-004-stock-deduction-locking.md) · [성능 §1](docs/performance/README.md) |
| **트래픽 급증** | 모두가 느려짐 ↔ 일부는 거절 | 사용자별·전역 rate limit. 초과 **97.5% 차단**, p95 738ms → 52ms | [성능 §7](docs/performance/README.md) |

**위 다섯은 실험하지 않으면 고를 수 없었다.** 아래 다섯은 기반 지식으로 답이 좁혀지지만,
그 지식이 **실제 결정에 쓰였는지**를 수치로 남겼다.

수치는 로컬 단일 장비에서 측정한 결과이며, 실행 환경과 한계는
[성능 리포트](docs/performance/README.md)에 함께 기록했습니다.
**지금 무엇이 열려 있는지는 [트러블슈팅 기록](docs/TROUBLESHOOTING-LOG.md)에 있습니다.**

## 저장소 구조

최상위는 **영역**으로 나뉩니다. 결제 코어와 개인화가 나란히 서고, 둘이 같이 쓰는 것만 루트에 둡니다.

| 경로 | 무엇 | 왜 여기에 |
| --- | --- | --- |
| `commerce/` | 주문·결제·정산 Java 앱 (Gradle 루트) | 이 저장소의 본체. 통째로 떼어낼 수 있게 물리적으로 묶었습니다 |
| `commerce/consumer-app/` | 결제 DLT 재처리 소비자 (별도 Gradle 빌드) | commerce 의 DLT 를 읽습니다. commerce 를 떼면 같이 갑니다 |
| `personalization/` | 개인화 파이프라인·실험 문서 | 다른 데이터·다른 언어(Python)·다른 배포 |
| `apps/web/` | Next.js 프론트 | |
| `docs/` | 설계·ADR·API·ERD | **양쪽의 기록이 한 곳에** 있습니다. ADR 은 두 영역을 오갑니다 |
| `k6/` `tools/` `monitoring/` `cdc/` `ci/` `scripts/` | 실험·운영 자산 | 결제와 개인화가 **같이 씁니다**(예: `alert-rules.yml` 에 결제 규칙과 개인화 규칙이 함께 있습니다) |
| `gradlew` `gradle/` `compose.yaml` | 공용 실행 기반 | |

Gradle wrapper 는 루트에 하나입니다. 프로젝트 루트가 `commerce/` 이므로 실행은 이렇게 합니다.

```bash
./gradlew -p commerce test                  # 본체 테스트
./gradlew -p commerce/consumer-app build    # 소비자 앱
```

`ops/` 같은 상위 묶음은 만들지 않았습니다 — `k6`·`tools`·`monitoring` 은 소유자가 한쪽이 아니라
**둘 다**여서, 한 단계 더 감싸도 경계가 선명해지지 않고 링크만 깊어집니다.

## 아키텍처

![BE-commerce 아키텍처: 유입 계층, 결제 코어, Outbox, 후속 도메인과 운영 계층](docs/images/architecture.svg)

모듈형 모놀리스로 시작해 도메인 경계를 코드와 테스트로 강제합니다. 모듈 간 결합은 공개 API와
도메인 이벤트로 제한하며, Spring Modulith의 `ModularityTests`가 잘못된 의존성을 빌드 단계에서 찾습니다.

```text
클라이언트
   │
   ├─ 인증 · 유입 제어 · 대기열
   │
   ▼
주문 ── 체크아웃 사가 ── 결제 ── PG 어댑터
                           │
                           ▼
                 Transactional Outbox
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
       원장·에스크로    정산·대사       알림·FDS·분쟁
```

주요 모듈은 다음과 같습니다.

| 구분 | 모듈 | 책임 |
| --- | --- | --- |
| 결제 코어 | `order`, `payment` | 주문 상태머신, 체크아웃 사가, 승인·취소·웹훅·미확정 복구 |
| 자금 정합성 | `ledger`, `escrow`, `settlement`, `reconciliation` | 복식부기, 자금 보류, 정산, 외부 기록 대사 |
| 결제 수단 | `point`, `wallet`, `subscription` | 포인트·선불 월렛·빌링키 정기결제 |
| 운영 안전장치 | `fraud`, `dispute`, `notification`, `audit` | 이상거래 심사, 차지백, 멱등 소비·DLQ, 감사 로그 |
| 플랫폼 | `member`, `queue`, `receipt`, `shared` | 회원·권한, 선착순 대기열, 현금영수증, 공통 값 타입 |

모듈 루트에는 외부에 공개할 타입만 두고 구현은 `internal` 아래에 둡니다. 이 구조를 택한 이유와
예외는 [ADR-018](docs/adr/ADR-018-module-internal-packages.md)에 정리했습니다.

## 핵심 설계

### 실패를 지우지 않고 확정한다

- 승인 요청의 멱등성은 `Idempotency-Key`와 DB 유니크 제약으로 보장합니다.
- PG 타임아웃은 실패로 단정하지 않고 `UNKNOWN`으로 남긴 뒤 PG 조회로 확정합니다.
- 체크아웃은 외부 호출 중 DB 커넥션을 점유하지 않도록 3단계 사가로 분리했습니다.
- 보상도 실패할 수 있으므로 보상 태스크를 영속화하고 재시도 소진 시 운영자에게 노출합니다.

### 돈의 이동을 불변식으로 검증한다

- 원장은 append-only 복식부기로 기록하고 모든 거래에서 차변과 대변의 합이 같아야 합니다.
- 취소는 기존 대사 행을 덮어쓰지 않고 별도의 거래 행으로 남깁니다.
- 구매확정 전 자금은 에스크로에 보류하고, 확정·취소 이벤트에 따라 해제하거나 환불합니다.
- 정산은 수수료와 부가세를 정수 연산으로 분리하고 지급 확정까지 상태로 관리합니다.

### 운영 기능도 도메인의 일부로 다룬다

- 주문·결제·원장·에스크로·정산·포인트·월렛·분쟁·대사·감사를 주문번호 하나로 조회합니다.
- 강제취소는 요청자와 승인자를 분리하는 maker-checker 규칙을 도메인에서 강제합니다.
- FDS와 운영 보조 모델은 자동 확정하지 않습니다. 규칙 기준선, 섀도 평가, 사람 검토와 전환 조건을
  통과한 기능만 제한적으로 노출합니다.
- Prometheus 알림은 작은 표본의 비율 왜곡과 반복 알림을 고려해 최소 표본과 재알림 간격을 둡니다.

상세한 선택과 트레이드오프는 [ADR 목록](docs/README.md#아키텍처-결정-기록)에서 확인할 수 있습니다.

## 데모

애플리케이션을 실행하면 별도 프런트엔드 서버 없이 스토어프론트와 운영 화면을 함께 제공합니다.
모든 화면은 미리 준비한 응답이 아니라 실제 API를 호출하고, 하단 개발자 로그 드로어에서 전
요청·응답을 확인할 수 있습니다.

**스토어프론트** — 이커머스 쇼핑몰. 상품 탐색부터 결제·주문 확인까지 실제 주문·결제 API로 동작합니다.

| 화면 | 경로 | 내용 |
| --- | --- | --- |
| 홈 | `/` | 히어로, 카테고리, 신상품·추천, 한정판 배너 |
| 카테고리 | `/category.html?code=digital` | 카테고리 필터·정렬·페이지네이션 |
| 검색 | `/search.html?q=이어버드` | 상품명·브랜드 검색 결과 |
| 상품 상세 | `/product.html?id=4` | 가격·수량·장바구니·관련 상품·품절 표시 |
| 장바구니 | `/cart.html` | 수량 편집·금액 요약 |
| 주문/결제 | `/checkout.html` | 카드·포인트·월렛 복합결제, 미확정 승인 폴링 |
| 주문내역 | `/orders.html` | 주문·결제 상태, 취소·구매확정 |
| 로그인 | `/login.html` | 데모 계정, 이메일 회원가입/로그인 |

![스토어프론트 홈](docs/images/storefront-home.jpg)

![주문/결제 — 카드·포인트·월렛 복합결제](docs/images/storefront-checkout.jpg)

**운영·개발 화면**

- 결제 콘솔: `http://localhost:8080/console.html` — 주문, 복합결제, 취소, 구매확정, 구독, 월렛, 포인트
- 운영 백오피스: `http://localhost:8080/admin.html` — 복구, 보상, 대사, 정산, 분쟁, FDS, DLQ
- 웹 앱(Next.js): `apps/web` — 개인화 홈·실험 콘솔. `npm run dev` 로 3000에서 뜬다([README](apps/web/README.md))

![운영 백오피스의 대사 원인 제안](docs/images/recon-suggestions.jpg)

운영 화면은 원인 후보를 자동 확정하지 않습니다. 후보를 선택하면 입력값만 채워지고 최종 확정은
운영자가 수행합니다.

주요 시연 항목은 다음과 같습니다.

- 상품 탐색(카테고리·검색·정렬·페이지네이션)과 품절 표시
- 결제 승인·취소·구매확정과 PG 타임아웃 복구
- 카드·포인트·월렛 복합결제와 실패 시 보상
- 정산 수수료·부가세·지급 예정액 산출
- 차지백 웹훅, 증빙 제출, 패소 시 원장 역분개
- 강제취소 maker-checker와 본인 승인 차단
- rate limit, 대기열, DLQ 재처리, FDS 사후 심사

스토어프론트의 화면 구성과 데이터 흐름은 [스토어프론트 설계](docs/28-스토어프론트-설계.md)에,
카탈로그를 읽기 전용으로 연 이유는 [ADR-031](docs/adr/ADR-031-storefront-catalog-read-api.md)에 정리했습니다.

## 기술 스택

- Java 21, Spring Boot 3.4, Spring Modulith 1.3
- MySQL 8.4, JPA, Flyway
- Redis, Resilience4j, Kafka
- Spring Security, Micrometer, Prometheus, Grafana
- JUnit 5, Mockito, Testcontainers, Toxiproxy, k6

## 실행

### 1. 애플리케이션

```bash
docker compose up -d
./gradlew -p commerce bootRun
```

로컬 데모 계정은 다음과 같습니다.

| 권한 | 아이디 | 비밀번호 |
| --- | --- | --- |
| 사용자 | `1`, `2` | `user-local-only` |
| 운영자 | `admin`, `admin2` | `admin-local-only` |

이 값은 로컬 전용 기본값입니다. 운영 환경에서는 환경변수나 시크릿 매니저로 반드시 교체해야 하며,
약한 키나 누락된 키는 기동 단계에서 거부합니다.

### 2. 관측성

```bash
docker compose --profile monitoring up -d prometheus grafana
```

- Grafana: `http://localhost:3000`
- Prometheus: `http://localhost:9090`
- 알림 상태: `http://localhost:9090/alerts`

대시보드와 알림 기준은 [관측성 문서](monitoring/README.md)에 정리했습니다.

### 3. 테스트

```bash
./gradlew -p commerce test
./gradlew -p commerce integrationTest  # Docker의 MySQL 필요
./gradlew -p commerce chaosTest        # Docker의 Toxiproxy 필요
```

기본 테스트에는 도메인 불변식, 상태 전이, 멱등성, 모듈 경계 검증이 포함됩니다. 통합·카오스 테스트는
외부 인프라가 필요하므로 별도 Gradle 태스크로 분리했습니다.

### 4. 성능 실험

```bash
./gradlew -p commerce bench -Pprofile=smoke  # 측정 배관 확인
./gradlew -p commerce bench                  # 처리 용량과 병목 탐색
./gradlew -p commerce bench -Pprofile=spike  # 과부하 시 유입 제어 확인
```

각 실행은 인프라 초기화, 앱 기동, k6 실행, 리포트 생성을 한 번에 수행하며 결과를
`docs/performance/runs/<실행 시각>-<프로파일>/report.md`에 남깁니다.

## 문서 읽는 순서

1. [결제 도메인 핵심 개념](docs/02-결제도메인-핵심개념.md)
2. [아키텍처 설계](docs/03-아키텍처-설계.md)
3. [장애 시나리오](docs/04-장애-시나리오-설계.md)
4. [성능 리포트](docs/performance/README.md)
5. [ERD](docs/09-ERD-설계.md)와 [API 스펙](docs/10-API-스펙.md)
6. [결제 의사결정 검증](docs/29-결제-의사결정-검증.md)
7. [결제 포트폴리오 초안](docs/30-결제-포트폴리오-초안.md)
8. [결제 플랫폼 확장 계획](docs/32-결제-플랫폼-확장-계획.md)
9. [작업 보드](docs/PROJECT-BOARD.md) — 목표·예상 시간·산출물·위험·검증 상태
10. [ADR](docs/README.md#아키텍처-결정-기록)

전체 문서는 목적별로 정리한 [문서 안내](docs/README.md)를 참고하세요.

### 무엇을 어디서 확인하는가

이 저장소는 **다른 사람이 현재 상태와 과거 판단을 복원할 수 있게** 만드는 것을 목표로 한다.
알고 싶은 것마다 볼 곳을 하나로 정해 뒀다.

| 알고 싶은 것 | 확인하는 곳 |
|---|---|
| **지금 무엇이 열려 있는가** | [`docs/TROUBLESHOOTING-LOG.md`](docs/TROUBLESHOOTING-LOG.md) — 열린 것과 닫은 방식을 한 화면에 |
| 지금 어디까지 왔는가 | [`personalization/ROADMAP.md`](personalization/ROADMAP.md) · [`docs/ROADMAP-TRADEOFFS.md`](docs/ROADMAP-TRADEOFFS.md) |
| 무엇을 만들기로 했는가 · 완료 조건 | GitHub Issue (배경 / 할 일 / 검증 / 하지 말 것) |
| 어느 단위로 나눴는가 | GitHub Milestone (M0~M12) |
| 무엇을 바꿨는가 · 왜 그렇게 골랐는가 | PR 본문과 [ADR 56편](docs/README.md#아키텍처-결정-기록) |
| 실제로 무엇을 확인했는가 | [`docs/performance/`](docs/performance/README.md) · [`personalization/docs/runs/`](personalization/docs/runs) — 원자료와 재현 명령 |
| 사용자에게 무엇이 나갔는가 | [`CHANGELOG.md`](CHANGELOG.md) |
| 무엇을 **안 하기로** 했는가 | ADR 상태가 `기각`·`미결`인 편들, 각 로드맵의 "하지 않은 것" 절 |

마지막 줄이 이 저장소에서 제일 중요하다. **ADR 56편 중 상당수가 "안 한다"로 끝난다** —
검색 엔진([ADR-044](docs/adr/ADR-044-no-search-engine-yet.md)), 추천 모델([ADR-048](docs/adr/ADR-048-als-model-not-adopted.md)),
생성 중 제약 차단([ADR-050](docs/adr/ADR-050-post-filter-over-constrained-generation.md)),
멀티 PG failover([ADR-020](docs/adr/ADR-020-multi-pg-routing-off-by-default.md)) 모두 **재 보고 안 켰고,
그 근거를 숫자로 남겼다.**

## 일정과 작업 방식

첫 커밋은 2025년 10월 1일(`Phase 0: Spring Modulith 뼈대`)입니다. **2026년 9월 24일 기준** 저장소 전체 커밋은 471개이고 가장 최근 커밋도 같은 날입니다. 아래 표의 수치도 같은 시점에 센 값입니다(릴리스마다 다시 셉니다).

| 기간 | 한 일 | 산출물 |
|---|---|---|
| 2025.10 | Phase 0~6: Spring Modulith 모듈 뼈대와 경계 검증, 관측성, DLQ 어드민 | 결제 코어, 모듈 경계 |
| 2025.11~2026.02 | 결제수단 확장(카드·포인트·월렛·구독·가상계좌), FDS, 필드 암호화, 보안·운영 마감 | 결제수단 7종, FDS |
| 2026.03~06 | 월 14~16개 커밋으로 기능을 다듬고 리팩터(체크아웃 사가, 정산 집계, 회원·분쟁 모듈 등) | 안정화 |
| 2026.07 | 문서와 코드의 불일치 정정, 실측 재확인 위주의 감사 | 문서 정합성 |
| 2026.08 | AI 운영 자동화 실험(루브릭 설계, 블라인드 비교, 모델 비교) | rule-first 판단 기준 |
| 2026.09 | GitHub 이슈와 PR로 작업 단위 전환, 이 달만 커밋 269개 | 이슈 73건, PR 155건 |

설계 결정은 커밋 로그에 흩어지지 않게 ADR(Architecture Decision Record) 56편으로 따로 남겼습니다. `docs/adr/`에 있으며 트레이드오프가 있는 결정마다 배경과 대안, 대가를 한 편씩 적었습니다. 9월부터는 GitHub 이슈와 PR로 작업 단위가 뚜렷하게 남습니다. 이슈 73건과 PR 155건이 있고 두 번호는 같은 시퀀스를 공유해 최대 번호가 228까지 갑니다.

프로젝트를 만든 배경과 트레이드오프 판단은 [블로그 소개 글](https://dj258255.github.io/IT-Oasis/blog/project/be-commerce/be-commerce-0-overview/)에 더 자세히 적었습니다.

## 범위와 한계

이 저장소는 단일 가맹점을 가정한 이커머스 플랫폼 데모입니다. 코어는 결제 실패와 정합성 처리이고,
스토어프론트(상품 탐색·주문·결제·주문내역)가 그 위에 얹혀 있습니다.

- 카드번호는 서버가 직접 받지 않습니다. PG가 제공한 마스킹 정보의 지문만 사후 탐지에 사용합니다.
- 금액은 KRW 원 단위 정수만 지원합니다. 통화 값 타입과 통화 불일치 연산 차단, 통화별 정산 분리는 구현했습니다(ADR-016 1~3단계). 원장 계정을 통화별로 나누고 거래에 적용 환율을 저장하는 4~6단계는 보류했습니다.
- 실제 카드 등록, 가상계좌 발급, 지급 실행은 PG·금융기관 계약이 필요한 외부 경계로 남겼습니다.
- 정산 배치는 서비스 루프로 구현했습니다. 데이터 규모가 커지면 파티셔닝된 Spring Batch 작업으로
  전환해야 합니다.
- 지급 예정일은 주말만 제외하며 법정공휴일 캘린더는 포함하지 않습니다.
- 분쟁 대응기한 자동 패소, 부분 차지백, 재분쟁은 구현하지 않았습니다.
- AI 보조 기능의 결과는 후보와 초안으로만 사용하며 결제·대사 상태를 직접 변경하지 않습니다.

구현하지 않은 기능과 그 이유는 [ADR-010](docs/adr/ADR-010-what-not-to-build.md)에 기록했습니다.
