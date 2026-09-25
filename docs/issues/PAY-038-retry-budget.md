# PAY-038 — retry 예산과 조회 복구 SLO

## 목적

조회 재시도가 일시 장애를 흡수하는지, 장애 중 호출량을 증폭하는지 구분한다.

## 완료 조건

- 오류율·도착률·동시성·PG 지연을 고정한 비교 실행이 있다.
- retry 횟수·소진 횟수·UNKNOWN oldest age·정상 API p95를 같은 결과표에 기록한다.
- `payment.pg.query.retry`와 `payment.pg.query.retry.exhausted`가 결과와 일치한다.

## 예상

- 예상 시간: 4시간
- 결과물: k6/실험 명령, p50/p95/p99 표, retry budget ADR 후보
- 위험: 로컬 fake PG의 분포는 실제 PG 장애를 대표하지 않는다.

## 결과(2026-09-26, [#334](https://github.com/dj258255/BE-commerce/issues/334))

재시도는 일시 장애를 흡수했고 PG 호출을 부풀리지 않았다. 현행 3회를 유지한다. 표와 해석은
[docs/34 의 2026-09-26 절](../34-결제-재시도와-retry-storm-실험.md#2026-09-26-일시-장애에서-재시도-횟수를-바꿔-봤다-334), 원자료는
[`performance/raw/20260926-p038-334/`](../performance/raw/20260926-p038-334/).

## 검증

- `./gradlew -p commerce test --tests '*ResilientPgClientTest'`
- `k6 run -e RATE=... -e DURATION=... k6/pg-brownout.js`

