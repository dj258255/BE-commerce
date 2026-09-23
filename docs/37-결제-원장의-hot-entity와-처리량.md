# 결제 원장의 Hot Entity: 정합성을 지키면서 처리량을 얻는 법

## 문제

원장의 특정 계정이나 판매자에 거래가 몰리면, 강한 일관성을 지키기 위해 같은 행을 직렬화하는
비용이 생긴다. 단순히 인스턴스를 늘려도 한 hot entity에 대한 쓰기 경합은 줄지 않는다.

Uber는 결제 원장에서 hot entity가 직렬화 쓰기 처리량을 제한하는 문제를 설명하고, 강한 일관성을
포기하지 않은 채 serialized batch-write로 처리량을 높였다고 공개했다.

참고: [Uber - Zero-Sum by Design: 10 Years of Uber’s Payments Platform](https://www.uber.com/bl/en/blog/ubers-payments-platform/)

## pay에 적용할 질문

- 원장 엔트리는 append-only인가? 그렇다.
- 한 거래의 차변·대변 합은 0인가? 생성 시점에 강제한다.
- 잔액을 읽기 위한 SUM이 쓰기 경합과 같은 트랜잭션에 묶이는가? 현재는 분리한다.
- 특정 판매자·계정의 동시 지급이 한 행을 갱신하는가? 현재 모델은 엔트리 append가 기준이다.
- 잔액 스냅샷을 추가하면 읽기는 빨라지지만 스냅샷과 원장의 이중 진실이 생기는가? 그렇다.

## 판단

현재 규모에서는 원장 잔액 스냅샷을 추가하지 않는다. `SUM(signed amount)`의 비용을 측정하고,
실제 hot account 경합이 나타날 때만 스냅샷 또는 계정별 직렬화 배치를 검토한다. 스냅샷을 도입해도
원천은 append-only entries이고, 스냅샷은 재생성 가능한 파생값이어야 한다.

## 완료 조건

- 동일 계정에 동시 분개를 몰아넣었을 때 lock wait와 throughput을 측정한다.
- 일반 계정과 hot 계정의 p95/p99를 분리한다.
- 스냅샷 도입 전후로 원장 재생성 잔액과 스냅샷 잔액이 일치하는지 검사한다.
- 처리량 개선이 정합성·감사성·복구성을 훼손하지 않는지 확인한다.

## 2026-09-22 실제 잔액 조회 측정

실제 MySQL 8.4의 `ledger_entries`에 benchmark transaction만 임시로 삽입하고, 측정 후 모두
삭제했다. `RUNS=10 TARGETS="10000 50000 100000"`으로 실행했으며, doubling 방식이라 실제 행 수는
12,474·98,490·196,794행이 됐다.

| 실제 원장 행 수 | 조건 | single account p95 | group by p95 |
|---:|---|---:|---:|
| 12,474 | covering index 있음 | 3.9ms | 11.1ms |
| 98,490 | covering index 있음 | 29.8ms | 90.7ms |
| 196,794 | covering index 있음 | 61.2ms | 183.6ms |
| 196,794 | covering index 없음 | 64.6ms | 121.4ms |

이번 측정은 “인덱스가 항상 빠르다”를 증명하지 않는다. 특정 계정 조회는 covering index의 이점을
얻지만, 모든 계정 `GROUP BY`는 인덱스가 오히려 추가 비용을 만들 수 있다. 따라서 현재는 잔액
스냅샷을 도입하지 않고, 읽기 패턴별 비용을 분리해 관측한다. 동일 hot account에 동시 분개를
몰아넣은 lock wait/commit throughput 측정은 별도 MySQL 컨테이너에서 실행했다.

## 2026-09-22 hot entity 동시 쓰기 측정

MySQL 8.4 InnoDB의 별도 `hotbench` 컨테이너에서 worker 8개가 worker당 200회 갱신했다. 각
트랜잭션은 같은 행을 잠근 뒤 2ms의 임계구간을 유지하도록 했고, cold 조건에서는 worker마다
서로 다른 계정 행을 갱신했다.

| 조건 | 처리 건수 | 전체 시간 | 처리량 | p50 | p95 | p99 | row lock waits | row lock time |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| hot, 같은 계정 | 1,600 | 7,725ms | 207.1/s | 38ms | 44ms | 47ms | 1,599 | 53,012ms |
| cold, 서로 다른 계정 | 1,600 | 1,307ms | 1,224.2/s | 6ms | 8ms | 10ms | 0 | 0ms |

같은 행을 갱신하는 모델은 cold 대비 처리량이 약 83.1% 낮고, p95가 5.5배 높았다. 이는 스냅샷을
당장 도입해야 한다는 뜻은 아니다. append-only 원장의 원천을 버리고 잔액 행을 정답으로 만들면
조회는 빨라져도 재생성·감사·복구 경계가 달라진다. 다만 hot account가 실제로 존재하고 잔액
스냅샷을 도입할 때는 이 직렬화 비용과 파생값 검증 비용을 함께 감당해야 한다는 근거가 생겼다.

재현 명령:

```bash
./gradlew -p commerce integrationTest --no-daemon --console=plain \
  --tests 'com.beomsu.becommerce.ledger.LedgerHotEntityContentionMySqlTest'
```

실행 로그는 `docs/performance/runs/20260922-051930-ledger-hot-entity.txt`에 고정했다.

재현 명령:

```bash
RUNS=10 TARGETS="10000 50000 100000" bash tools/measure-ledger-balance.sh
```

스크립트는 중단 시에도 benchmark transaction을 지우고 covering index를 복구하도록 정리 trap을
둔다. 전체 100만·1000만 행은 저장 공간과 실행 시간이 커서 기본 실험과 분리했고, 필요할 때
`TARGETS`만 늘려 비교한다.

---

## 관련 카드

- PAY-042 ledger hot-entity 실험
