# ADR-069. 결제 한 건이 모듈을 건너며 맞는지 불변식으로 센다

- 상태: 채택
- 날짜: 2026-09-27
- 관련: [ADR-013](ADR-013-cancellation-as-separate-recon-row.md)(대사 내부 기록), [ADR-003](ADR-003-stock-deduction-timing.md)(재고 예약), [ADR-057](ADR-057-recovery-backoff-over-order.md)(결과 모름 복구), 이슈 #389

## 맥락

대사는 내부 기록과 **PG 정산 파일**을 맞춘다. 결제 한 건이 우리 모듈들(결제 · 주문 · 원장 · 대사 내부 기록 · 재고 · 보상)을 건너며 서로 맞는지는 세지 않았다. 결제는 승인됐는데 주문이 PAID 가 아니거나 원장 분개가 없으면 PG 파일과는 맞아도 우리 기록은 갈라진 것이다. 지금까지 이런 어긋남은 실험하다 우연히 찾았다(예약 단계 트랜잭션 누락 #375, 재고 여유 확인 누락 #374).

Airbnb 는 결제 시스템의 정합성을 사건이 난 뒤 찾지 않고 모든 거래를 불변식으로 계속 재서 수치로 본다("Measuring Transactional Integrity in Airbnb's Distributed Payment Ecosystem").

## 결정

불변식 10개의 위반 건수를 1분마다 세 `integrity.violations{invariant}` 게이지로 낸다(`IntegrityCheckScheduler`). 다른 배치와 같이 `app.integrity.enabled` 로 켜고 기본은 꺼짐이다. 꺼져 있으면 게이지도 등록하지 않는다. 0 을 내보내면 "세지 않음"과 "위반 없음"이 같은 모양이 된다. 관리자 API(`GET /api/v1/admin/integrity?graceSeconds=&samples=`)로 바로 세고 위반한 주문 번호를 볼 수 있다. 위반이 10분 넘게 남으면 `PaymentIntegrityViolation` 이 불변식 이름을 달고 울린다.

| 불변식 | 뜻 |
|---|---|
| `PAYMENT_DONE_ORDER_NOT_PAID` | 결제 승인, 주문은 PAID · FAILED 가 아님 |
| `ORDER_PAID_PAYMENT_NOT_DONE` | 주문 PAID, 승인된 결제 없음(전액 포인트 주문은 결제 행이 없어 빠짐) |
| `APPROVED_WITHOUT_LEDGER` | 승인 결제에 원장 승인 분개 없음 |
| `LEDGER_AMOUNT_MISMATCH` | 승인 분개의 차변 금액 ≠ 결제 금액 |
| `LEDGER_UNBALANCED` | 분개의 차변 합 ≠ 대변 합(쓰기 때 막지만 직접 고친 행은 못 막음) |
| `APPROVED_WITHOUT_RECON_RECORD` | 승인 결제에 대사 내부 기록(승인 행)이 없어 PG 파일과 맞출 수 없음 |
| `FAILED_ORDER_CARD_NOT_REFUNDED` | 재고 부족으로 FAILED 가 된 주문의 카드가 승인된 채인데 망취소가 없거나 재시도를 다 씀 |
| `RESERVATION_LEFT_OPEN` | 끝난 주문의 재고 예약이 RESERVED 로 남아 재고를 묾 |
| `STOCK_NEGATIVE` | 재고 음수(초과 판매) |
| `UNKNOWN_OVER_10_MINUTES` | 결과 모름이 10분 넘게 남음(복구는 1~2분, 막힌 건도 최대 10분) |

각 불변식은 SQL 하나로 모듈의 테이블을 직접 잇는다. 모듈 경계(Spring Modulith)는 자바 타입 의존만 보므로 이 점검은 경계를 넘지 않지만 **컬럼 이름이나 ENUM 값이 바뀌면 컴파일은 되고 점검만 조용히 실패한다.** 그래서 `IntegrityCheckMySqlTest` 가 Flyway 로 만든 실제 스키마에서 10개가 모두 도는지 PR 마다 본다. 점검이 한 번 실패하면 게이지는 0 으로 떨어지지 않고 직전 값에 머문다. 0 으로 두면 "점검이 멈춤"과 "위반 없음"이 같은 모양이 된다.

## 정답이 없는 값: 유예

결제 확정 뒤 원장 분개와 대사 내부 기록은 커밋 뒤 이벤트 리스너가 따로 쓴다. 유예가 짧으면 이 사이의 진행 중인 건을 위반으로 세고(잘못된 알림), 길면 진짜 위반을 늦게 잡는다.

### 잰 것

`tools/run-integrity.sh`: 재고 전략 `CHECK` · `AT_PAYMENT` 앱에 #374 와 같은 한정 상품 부하(재고 100 · 주문 300 · 결과 모름 10% · 이탈 20%)를 걸고 부하 중과 부하 뒤 5분 동안 5초마다 유예 0 · 5 · 15 · 30 · 60 · 120초로 셌다. 그 뒤 배치를 끈 앱으로 다시 띄워 불변식마다 오염을 하나씩 넣었다. 판정 기준은 측정 전에 이슈에 적었다.

| 전략 | 뒤따르는 기록 | 건수 | 승인부터 p50 | p99 | 최대 |
|---|---|---:|---:|---:|---:|
| `CHECK` | 원장 분개 | 105 | 21.7ms | 112.1ms | 117.1ms |
| `CHECK` | 대사 내부 기록 | 105 | 22.1ms | 118.1ms | 119.5ms |
| `AT_PAYMENT` | 원장 분개 | 100 | 22.4ms | 79.5ms | 136.5ms |
| `AT_PAYMENT` | 대사 내부 기록 | 100 | 23.1ms | 87.9ms | 135.8ms |

지연은 결제 행의 승인 시각(`approved_at`, 확정 트랜잭션 안에서 찍힘)부터 분개 · 대사 기록이 쓰인 시각까지다. 두 번째 측정에서 같은 부하를 한 번 더 걸고 DB 에서 건마다 쟀다.

- 두 전략 모두 부하 뒤 마지막 점검(유예 0)에서 위반 0. 점검 호출 830번 중 실패 0
- 유예 0 · 5 · 15 · 30 · 60 · 120초 모두 부하 중 · 부하 뒤 69번씩 점검에서 위반 0
- 오염 10개가 모두 의도한 불변식 하나만 1 늘렸다

### 고른 것

**기본 유예 0초.** 측정 전 기준은 "부하 중 · 부하 뒤 모든 점검에서 위반 0 인 가장 짧은 값"이었다. 다만 이 0 은 5초 간격 표본이 몇 ms 짜리 창을 거의 못 본 결과일 수 있어 따로 지연을 쟀다(위 표). 뒤따르는 기록은 승인 뒤 0.14초 안에 모두 생겼다. 진행 중인 창이 이만큼 짧아 이번 부하(초당 승인 약 2건)에서는 5초 간격 점검에 한 번도 걸리지 않았다. **초당 승인이 10건을 넘으면**(창 0.1초 안에 늘 한 건쯤 걸린다) 유예를 1초(최대 지연의 약 7배)로 올린다. 1초 늦게 잡는 것은 점검 주기 1분에 비해 작다.

잠깐 섰다 사라지는 위반은 알림의 `for: 10m` 이 거른다. 처리량이 커서 매 분 서로 다른 진행 중인 건이 걸리면 10분 동안 이어질 수 있다. 그때가 유예를 올릴 때다(아래 다시 볼 조건).

## 대가

- 조인 여섯 개를 1분마다 돈다. 조인 키는 모두 인덱스가 있고(`payments.order_no`, `orders.order_no`, `internal_records(order_no, seq)`, `ledger_transactions(tx_type, source_type, source_id, source_seq)`) `compensation_tasks.order_no` 만 없다. 망취소 대상만 쌓이는 작은 테이블이라 지금은 두었다. 주문 300건 규모라 쿼리 비용은 이 실험으로 말할 수 없다. 테이블이 커지면 "최근 N 일"로 범위를 좁히는 것이 먼저다
- 점검이 다른 모듈의 테이블 모양에 묶인다. 스키마를 바꾸는 PR 이 이 점검을 깨면 `IntegrityCheckMySqlTest` 가 잡는다

## 다시 볼 조건

- 초당 승인이 10건을 넘거나 유예 0 에서 잠깐 서는 위반이 보이면 유예를 1초로 올린다(`app.integrity.grace-seconds`). 이벤트 발행이 밀려(아웃박스 적체) 지연이 초 단위가 되면 그 지연에 맞춘다
- 불변식을 새로 넣으면 `tools/run-integrity.sh` 에 오염 하나를 같이 넣어 잡는지 본다

재현: `bash tools/run-integrity.sh`(약 7분). 원자료: [`performance/raw/20260927-i389-integrity-r1/`](../performance/raw/20260927-i389-integrity-r1/)(1차) · [`performance/raw/20260927-i389-integrity/`](../performance/raw/20260927-i389-integrity/)(지연 보강)

## 만들다 찾은 것

처음에는 `@Scheduled` 를 점검 서비스에 바로 달았다. 이 저장소의 배치는 스케줄러 빈과 같은 프로퍼티의 `@EnableScheduling` 설정이 짝을 이뤄야 실제로 돈다(`SchedulerGatePairingTest`). 짝이 없으면 빈은 뜨고 로그도 깨끗한데 메서드만 불리지 않는다. 실험은 관리자 API 를 직접 불러서 이 결함이 보이지 않았고 가드 테스트가 잡았다. 그대로 나갔으면 게이지가 한 번도 갱신되지 않은 채 0 으로 떠 있었을 것이다.
