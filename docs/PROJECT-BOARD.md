# pay 작업 보드

이 문서는 개인 프로젝트에서도 다른 사람이 현재 상태와 다음 의사결정을 복원할 수 있게 하는
작업 보드다. GitHub Issue·PR·Milestone을 사용할 때도 이 표의 목적·완료 조건·예상 시간·위험을
먼저 채운다.

새 검증 카드의 실행 명세는 [`docs/issues/`](issues/)에 둔다. 원격 GitHub Issue가 아니라도 저장소만
읽으면 작업 범위와 완료 조건을 복원할 수 있어야 한다.

## 현재 목표

> 결제 상태·원장·정산·대사의 불확실성을 숫자와 재처리 가능한 기록으로 드러내고,
> 구현된 것과 외부 계약이 필요한 것을 분리한다.

## 현재 단계

| Milestone | 목표 | 상태 | 예상 범위 | 결과물 |
|---|---|---|---:|---|
| M0 | 핵심 계약·불변식·멱등 기준선 | Done | 완료 | API 스펙·ADR·단위/통합 테스트 |
| M1 | UNKNOWN·웹훅·보상 수렴 | Done | 완료 | 복구 배치·웹훅 통합 테스트 |
| M2 | PG 브라운아웃과 retry storm 기준선 | Done | 완료 | p95·connection wait·거절률·retry 횟수 리포트 |
| M3 | 원장·대사·지급 정합성 지표 | Done (synthetic) | 완료 | pending 금액·oldest age·지급 대사 엔진·원장 검증 기록 |
| M4 | replay·DLQ·복구 후 재대사 | Planned | 1~2일 | replay 정책·통합 시나리오·운영 기록 |
| M5 | 다통화·두 PG·실 지급 계약 | Blocked by external state | 별도 협의 | 계약 테스트·수수료/승인율 비교 |
| M6 | pending/posted 대사와 hot entity | Done (local) | 완료 | 잠정 대사 엔진·잔액 조회 실측·lock wait 실험 |

## 작업 카드

### PAY-033 — 미해결 대사 금액 지표

- 목적: 건수만으로는 운영 위험을 알 수 없으므로 PENDING 금액 노출
- 완료 조건: 내부 전용·외부 전용·금액 불일치를 절대 차액 합으로 계산하고 Gauge로 노출
- 예상: 2시간
- 실제: 완료
- 산출물: `ReconciliationResultRepository`, `ReconciliationMetrics`, 회귀 테스트
- 검증: `ReconciliationMetricsTest`, 전체 `./gradlew test`
- 위험: 절대 차액은 회계상 손실액과 같은 의미가 아니므로 metric 이름에 `unexplained`를 포함

### PAY-034 — webhook replay와 DLQ 수렴

- 목적: 실패 이벤트를 사람이 다시 넣을 때 중복 분개·중복 알림이 생기지 않게 함
- 완료 조건: poison event 격리 → replay → 재대사 → 불일치 0건을 한 시나리오로 확인
- 예상: 1~2일
- 산출물: replay 정책, 감사 로그, 통합 테스트, 운영 명령
- 위험: 이미 처리된 이벤트의 replay와 아직 처리되지 않은 이벤트의 재시도 의미가 다름
- 다음 갱신 조건: 현재 DLT/웹훅 보류 저장 구조를 하나의 replay 계약으로 합칠지 결정

### PAY-035 — 지급 reconciliation

- 목적: 정산 금액과 실제 지급 금액의 차이를 수수료·환불·차지백·reserve로 설명
- 완료 조건: 지급 reference가 없는 지급은 `UNMATCHED_PAYOUT`으로 남고 `PAID_OUT`으로 확정되지 않음
- 예상: 1일(합성 데이터), 외부 PG report 계약은 별도
- 산출물: 지급 수식·샘플 파일·대사 테스트·ADR
- 위험: 실제 PG report와 은행 reference는 계정·계약 없이는 검증 불가

#### 현재 결과

- 합성 입력 기준 완료: `PayoutReconciliationEngine`이 `MATCHED`, `PENDING`, `AMOUNT_MISMATCH`,
  `CURRENCY_MISMATCH`, `UNMATCHED_SETTLEMENT`, `UNMATCHED_PAYOUT`, 중복 reference를 결정적으로 분류한다.
- 내부 지급 확정 게이트까지 완료: `POST /api/v1/admin/settlements/{id}/payout`은 report 입력을
  받아 `MATCHED`일 때만 `PAID_OUT`과 `SettlementPaidOutEvent`를 만든다. pending·불일치 결과는
  정산에 기록하고 CREATED에 남긴다.
- 실제 외부 report 연동은 미완료: PG·은행 계약과 reference 생명주기가 필요하다.

### PAY-036 — retry storm 실험

- 목적: 재시도가 PG 장애를 회복시키는지, 정상 API를 더 악화시키는지 구분
- 완료 조건: 도착률·PG 지연·동시성 고정 조건에서 retry 수·간격·UNKNOWN age·정상 API p95 기록
- 예상: 4시간
- 산출물: 재현 명령, p50/p95/p99 표, retry budget 결정
- 위험: 로컬 PG 지연 분포는 실 PG p99를 대표하지 않음

#### 현재 결과

- `./gradlew experimentTest`에서 200 요청·32 worker·항상 실패하는 조회 더블을 실행했다.
- delegate 호출은 32회, retry event 32회, exhausted 0회, 총 45ms였다. 공유 서킷이 OPEN되어
  재시도 폭증을 32개 delegate 호출에서 잘랐다.
- 별도 k6 brownout에서는 limit=1일 때 read p95 16.20ms, limit 없음일 때 read p95 3.00초와
  Hikari connection timeout 누적 133건을 관측했다.

### PAY-037 — 다통화·환율·반올림

- 목적: 통화가 다를 때 환율 비용과 반올림 잔액의 책임 주체 결정
- 완료 조건: 승인·정산·환불 각각의 환율 기준시각과 rounding owner를 ADR로 확정
- 예상: 1일(정책·합성 데이터), 실 카드 계약은 미검증
- 산출물: 통화별 원장 규칙·합성 테스트·ADR
- 위험: 매입일 환율과 승인일 환율은 PG·카드사 계약에 따라 달라짐

### PAY-038 — retry 예산과 조회 복구 SLO

- 목적: 조회 재시도가 장애를 회복하는지 retry storm을 만드는지 구분
- 완료 조건: retry 횟수·소진 수·UNKNOWN oldest age·정상 API p95를 같은 실험에서 비교
- 예상: 4시간
- 실제: 계측 구현 완료, 부하 실험 예정
- 산출물: `payment.pg.query.retry`, `payment.pg.query.retry.exhausted`, 실험 기록
- 위험: 로컬 fake PG의 오류 분포는 실제 PG p99를 대표하지 않음

### PAY-039 — tentative/posted 대사 모델 검토

- 목적: 사람 예외 큐의 PENDING과 외부 금융 거래의 pending을 혼동하지 않음
- 완료 조건: 외부 reference와 posted 상태가 있는 합성 파일로 잠정→최종 재대사를 검증
- 예상: 1일
- 산출물: 상태 전이 ADR, 합성 파일, 지급 금지 검증
- 위험: 실제 PG·은행이 provisional/posted와 동일 reference를 제공해야 함

#### 현재 결과

- 합성 pending→posted 경계와 지급 확정 금지 규칙을 `PayoutReconciliationEngineTest`로 검증했다.
- 현재 대사의 `PENDING`(사람 예외 큐)과 외부 지급 `PENDING`(미게시)을 별도 enum으로 유지한다.

### PAY-040 — 두 PG failover 계약 테스트

- 목적: 복원력을 얻는 대신 중복 승인·환불·정산 복잡성을 감당할 가치가 있는지 판단
- 완료 조건: UNKNOWN failover 금지, provider 보존, 환불 원 provider 고정, 대사 reference 연결
- 예상: 1~2일(합성), 실 PG 계약은 별도
- 산출물: provider별 계약표·상태 머신·실패 전환 시나리오
- 위험: 두 실 PG 계정과 토큰·환불·정산 계약이 없으면 통합 검증 불가

### PAY-041 — provider별 정산·환불 reference 비교

- 목적: 라우팅 이후 거래·환불·정산을 하나의 내부 payment로 복원할 수 있는지 확인
- 완료 조건: provider transaction id, refund id, payout reference를 내부 사건과 연결
- 예상: 1일(합성 파일)
- 산출물: reference 매핑표·누락/중복 예외 테스트
- 위험: provider별 reference 생명주기가 다름

### PAY-042 — ledger hot-entity 실험

- 목적: 원장 잔액 읽기·동시 쓰기에서 실제 병목이 계정 경합인지 확인
- 완료 조건: 일반/ hot 계정의 lock wait, p95/p99, throughput, 재생성 잔액 비교
- 예상: 1일
- 산출물: 부하 스크립트·결과표·스냅샷 도입 기준 ADR
- 위험: 현재 규모에서 병목이 재현되지 않을 수 있음. 그 결과도 미도입 근거로 기록

#### 현재 결과

- 실제 MySQL 원장 12,474·98,490·196,794행에서 잔액 `SUM` 비용을 측정했다.
- 196,794행 single account p95는 covering index 있음 61.2ms, 없음 64.6ms였고, 전체
  `GROUP BY` p95는 있음 183.6ms, 없음 121.4ms였다.
- 별도 MySQL 8.4 컨테이너에서 hot account 1,600건은 207.1/s·p95 44ms·row lock waits 1,599,
  cold account 1,600건은 1,224.2/s·p95 8ms·row lock waits 0이었다.
- hot 쓰기 경합은 확인했지만 실제 운영 트래픽의 hotness 분포는 아직 없다. 스냅샷 도입은 원장
  재생성·파생값 검증 설계와 함께 보류한다.

## 상태 갱신 규칙

- 작업 시작 시 `Planned → In progress`와 예상 시간을 기록한다.
- 예상과 실제가 달라지면 완료일과 원인을 같은 카드에 갱신한다.
- 구현 완료와 검증 완료를 분리한다.
- 외부 계약·계정·실트래픽이 없으면 `Done`이 아니라 `Blocked by external state`다.
- 실패한 실험도 가설·조건·관측·배제한 원인·다음 행동을 남긴다.

## 이번 사이클의 완료 기록

2026-09-22 기준으로 대사 미해결 금액 Gauge, PG 조회 retry 계측, 지급 대사 엔진, pending/posted
경계 테스트, brownout/retry storm 실측, 원장 잔액 조회 실측을 추가했다. 결제 영속성·웹훅 순서 역전
통합 테스트 6개는 별도 실행에서 통과했고, 기본 단위 테스트와 블로그 빌드도 통과했다. 전체 통합
스위트는 여러 Testcontainers 컨텍스트가 동시에 결과 XML을 기록하는 단계에서 실패했으며, 애플리케이션
assertion 실패로 분류하지 않았다. 실 지급 report·두 실 PG 계약·hot entity lock wait는 외부 계약
또는 추가 부하 환경이 필요하므로 합성 구현/부분 실측과 실제 검증을 분리해 기록한다.
