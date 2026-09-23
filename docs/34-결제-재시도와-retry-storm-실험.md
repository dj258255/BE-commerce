# 결제 재시도는 가용성을 높이는가, 장애를 증폭하는가

## 문제

결제 시스템에서 재시도는 하나의 정책이 아니다. 승인 요청은 네트워크 오류만으로도 PG에서 이미
처리됐을 수 있어 중복 결제가 될 수 있고, 상태 조회는 읽기라 재시도할 여지가 있다. 하지만 조회도
호출 체인이 길어지거나 장애 중에 동시에 몰리면 retry storm으로 장애를 키울 수 있다.

Uber는 장애를 일으킨 서비스가 원인인지, 하위 장애를 전달한 서비스가 증상인지 구분하는
error ownership을 retry 판단에 사용한다. 동일한 재시도 횟수를 모든 호출자에 적용하면 깊이가 깊어질수록
요청 수가 증폭되므로, 재시도 예산과 원인 전파를 함께 다룬다는 선택이다.

참고: [Uber - How Uber Protects Against Retry Storms](https://www.uber.com/us/en/blog/protecting-against-retry-storms/)

## pay의 현재 결정

| 호출 | 결정 | 이유 | 포기한 것 |
|---|---|---|---|
| 승인 | 자동 재시도하지 않음 | 결과가 UNKNOWN일 수 있어 이중 결제를 피해야 함 | 일시 장애에서의 즉시 성공률 |
| 상태 조회 | 최대 3회, exponential backoff+jitter | 읽기이고 복구 배치가 다음 주기에 다시 확인할 수 있음 | 장애 중 조회 지연·호출량 |
| 승인 동시성 초과 | PG에 닿기 전에 확정 실패 | UNKNOWN으로 남기면 조회 대상 없는 유령 복구가 생김 | 순간 처리량 |
| 서킷 OPEN | 승인 UNKNOWN, 조회는 다음 복구로 넘김 | 장애를 더 깊게 전파하지 않음 | 즉시 확정 |

조회 재시도는 `payment.pg.query.retry`, `payment.pg.query.retry.exhausted`로 관측한다. 재시도 횟수만
늘었다고 복구가 잘 된 것으로 해석하지 않고, UNKNOWN age·PG 지연·정상 API p95를 함께 본다.

## 검증할 것

1. PG 오류율을 1%, 5%, 10%로 올린다.
2. 도착률과 동시성을 고정한다. VU만 늘리는 닫힌 부하는 느린 응답 때문에 도착률도 줄어든다.
3. 다음을 같은 실행에서 기록한다.
   - PG에 실제 도달한 승인·조회 횟수
   - retry 횟수와 retry exhaustion
   - retry 간격 분포
   - UNKNOWN oldest age
   - 정상 읽기 API p95/p99
   - DB connection wait와 CPU saturation
4. 재시도 0회, 고정 1회, bounded 3회+jitter를 비교한다.

## 현재 결론

이 프로젝트에서는 Uber의 error ownership을 그대로 복제하지 않는다. 현재 호출 경계는 PG 어댑터와
복구 배치로 짧고, 서비스 간 오류 원인을 전파할 공통 mesh가 없다. 대신 재시도 예산·지터·서킷·동시성
상한과 계측을 먼저 둔다. 실제 다단 호출 체인이 생기면 그때 error ownership을 별도 ADR로 판단한다.

## 2026-09-22 실제 실행 결과

실제 PG 대신 `FakePgClient`에 지연·timeout을 주입하고, 기존 개발 DB와 분리한 임시 MySQL 스키마에서
open-loop k6 부하를 실행했다. 워밍업 15초 뒤 본 측정은 도착률 2 req/s, 읽기 2 req/s, 10초였다.

| 조건 | confirm 결과 | confirm p95 | 일반 읽기 p95 | 관측된 의미 |
|---|---:|---:|---:|---|
| PG 300ms · limit=1 · timeout 500ms | 성공 20건 | 382.81ms | 12.94ms | 정상에 가까운 지연에서는 상한이 순손실로 보이지 않았다 |
| PG 3,000ms · limit=1 · timeout 500ms | PENDING 13건, 사전 거절 8건 | 542.04ms | 16.20ms | PG에 닿은 호출은 UNKNOWN으로 보존하고, 닿지 않은 호출은 거절해 읽기를 보호했다 |
| PG 3,000ms · limit 없음 · timeout 500ms | 성공 9건, HTTP 실패 24/52 | 8.33s | 3.00s | 승인 지연이 일반 읽기로 번지고 Hikari timeout 133건이 관측됐다 |

마지막 대조군의 Prometheus 표본에서는 Hikari active 최대 17, Tomcat busy 최대 18, Hikari acquire
최대 3.008초가 관측됐다. 반면 limit=1 브라운아웃에서는 표본상 Tomcat busy 최대 2였고 Hikari
connection timeout은 0이었다. 이 수치는 k6 본 측정과 워밍업을 합친 앱 누적 지표와 구분해야 한다.

재시도 폭주 더블 실험은 200개 동시 조회 요청과 32개 worker로 실행했다.

```text
requests=200 workers=32 attempts=32 attempts_per_request=0.2
retry_events=32 exhausted=0 elapsed_ms=45
```

이 결과는 모든 요청이 3회씩 재시도했다는 뜻이 아니다. 공유 서킷이 먼저 OPEN되어 실제 PG 더블에
도달한 호출이 32회에서 잘렸고, 서킷에 막힌 호출은 재시도하지 않았다. 단일 요청 기준 bounded
retry는 별도 회귀 테스트에서 `3회 호출 = 2회 retry + 1회 exhausted`를 확인한다. 따라서 retry
budget만 보는 것보다 서킷 상태와 실제 delegate 호출량을 함께 봐야 한다.

재현 산출물:

- `docs/performance/runs/20260922-050740-brownout-lat300-rate2-rto500-lim1/`
- `docs/performance/runs/20260922-050835-brownout-lat3000-rate2-rto500-lim1/`
- `docs/performance/runs/20260922-050922-brownout-lat3000-rate2-rto500-lim0/`
- `./gradlew -p commerce experimentTest --no-daemon --console=plain`

세 실행은 모두 임시 `pay_brownout` 스키마를 사용했고 측정 후 스키마를 삭제했다. fake PG의 지연
분포·오류 분포는 실제 PG p99를 대표하지 않으므로 retry budget의 최종 운영값은 아직 외부 계약
테스트가 필요하다.

---

## 관련 카드

- PAY-036 retry storm 실험
- PAY-038 retry 예산과 조회 복구 SLO
