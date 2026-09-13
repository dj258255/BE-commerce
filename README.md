# pay

결제의 정상 처리보다 **실패 이후의 정합성 회복**에 초점을 둔 Spring Modulith 기반 결제 백엔드입니다.
PG 타임아웃, 중복 요청, 이벤트 재전달, 부분 실패를 실제 운영에서 발생할 수 있는 상태로 보고,
이를 기록·복구·대사하는 흐름을 구현했습니다.

[![CI](https://github.com/dj258255/payment-system/actions/workflows/ci.yml/badge.svg)](https://github.com/dj258255/payment-system/actions/workflows/ci.yml)

## 핵심 결과

| 문제 | 선택 | 검증 |
| --- | --- | --- |
| PG 응답이 불확실한 타임아웃 | 결제를 `UNKNOWN`으로 보존하고 조회·망취소로 확정 | Toxiproxy 네트워크 장애 주입 |
| DB 저장과 이벤트 발행 사이의 유실 | Spring Modulith Event Publication Registry 기반 Outbox | 재발행·멱등 소비·DLQ 테스트 |
| 재고·잔액의 동시 차감 | 조건부 `UPDATE`와 영향 행 수로 성공 여부 판정 | H2와 MySQL 8.4에서 세 전략 비교 |
| 외부 호출을 포함한 긴 트랜잭션 | 예약 → PG 승인 → 확정/보상의 3단계 사가 | 중단 지점별 복구 시나리오 검증 |
| 장부와 PG 기록의 불일치 | 복식부기 원장과 일 단위 대사, 취소 별도 행 | 차변=대변 불변식과 4분류 대사 테스트 |
| 운영자의 분산된 조사 동선 | 10개 도메인의 이력을 주문 단위 타임라인으로 조립 | 조회 7회·목록 탐색 6회를 API 1회로 통합 |
| 트래픽 급증 | 사용자별·전역 rate limit과 대기열 게이트 | 초과 요청 97.5% 차단, 성공 요청 p95 738ms → 52ms |

수치는 로컬 단일 장비에서 측정한 결과이며, 실행 환경과 한계는
[성능 리포트](docs/performance/README.md)에 함께 기록했습니다.

## 아키텍처

![pay 아키텍처: 유입 계층, 결제 코어, Outbox, 후속 도메인과 운영 계층](docs/images/architecture.svg)

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

애플리케이션을 실행하면 별도 프런트엔드 서버 없이 두 화면을 제공합니다.

- 스토어: `http://localhost:8080/` — 주문, 복합결제, 취소, 구매확정, 구독, 월렛, 포인트
- 운영 백오피스: `http://localhost:8080/admin.html` — 복구, 보상, 대사, 정산, 분쟁, FDS, DLQ

두 화면의 개발자 로그 드로어에서 실제 API 요청과 응답을 확인할 수 있습니다.

![결제 플로우 데모](docs/images/demo-checkout.png)

![운영 백오피스의 대사 원인 제안](docs/images/recon-suggestions.jpg)

운영 화면은 원인 후보를 자동 확정하지 않습니다. 후보를 선택하면 입력값만 채워지고 최종 확정은
운영자가 수행합니다.

주요 시연 항목은 다음과 같습니다.

- 결제 승인·취소·구매확정과 PG 타임아웃 복구
- 카드·포인트·월렛 복합결제와 실패 시 보상
- 정산 수수료·부가세·지급 예정액 산출
- 차지백 웹훅, 증빙 제출, 패소 시 원장 역분개
- 강제취소 maker-checker와 본인 승인 차단
- rate limit, 대기열, DLQ 재처리, FDS 사후 심사

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
./gradlew bootRun
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
./gradlew test
./gradlew integrationTest  # Docker의 MySQL 필요
./gradlew chaosTest        # Docker의 Toxiproxy 필요
```

기본 테스트에는 도메인 불변식, 상태 전이, 멱등성, 모듈 경계 검증이 포함됩니다. 통합·카오스 테스트는
외부 인프라가 필요하므로 별도 Gradle 태스크로 분리했습니다.

### 4. 성능 실험

```bash
./gradlew bench -Pprofile=smoke  # 측정 배관 확인
./gradlew bench                  # 처리 용량과 병목 탐색
./gradlew bench -Pprofile=spike  # 과부하 시 유입 제어 확인
```

각 실행은 인프라 초기화, 앱 기동, k6 실행, 리포트 생성을 한 번에 수행하며 결과를
`docs/performance/runs/<실행 시각>-<프로파일>/report.md`에 남깁니다.

## 문서 읽는 순서

1. [결제 도메인 핵심 개념](docs/02-결제도메인-핵심개념.md)
2. [아키텍처 설계](docs/03-아키텍처-설계.md)
3. [장애 시나리오](docs/04-장애-시나리오-설계.md)
4. [성능 리포트](docs/performance/README.md)
5. [ERD](docs/09-ERD-설계.md)와 [API 스펙](docs/10-API-스펙.md)
6. [ADR](docs/README.md#아키텍처-결정-기록)

전체 문서는 목적별로 정리한 [문서 안내](docs/README.md)를 참고하세요.

## 범위와 한계

이 저장소는 결제 실패와 정합성 처리에 집중한 단일 가맹점 데모입니다.

- 카드번호는 서버가 직접 받지 않습니다. PG가 제공한 마스킹 정보의 지문만 사후 탐지에 사용합니다.
- 금액은 KRW 원 단위 정수만 지원합니다. 통화 값 타입은 있으나 다통화 정산은 범위 밖입니다.
- 실제 카드 등록, 가상계좌 발급, 지급 실행은 PG·금융기관 계약이 필요한 외부 경계로 남겼습니다.
- 정산 배치는 서비스 루프로 구현했습니다. 데이터 규모가 커지면 파티셔닝된 Spring Batch 작업으로
  전환해야 합니다.
- 지급 예정일은 주말만 제외하며 법정공휴일 캘린더는 포함하지 않습니다.
- 분쟁 대응기한 자동 패소, 부분 차지백, 재분쟁은 구현하지 않았습니다.
- AI 보조 기능의 결과는 후보와 초안으로만 사용하며 결제·대사 상태를 직접 변경하지 않습니다.

구현하지 않은 기능과 그 이유는 [ADR-010](docs/adr/ADR-010-what-not-to-build.md)에 기록했습니다.
