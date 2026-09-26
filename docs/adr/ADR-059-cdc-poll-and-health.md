# ADR-059. CDC 는 poll 100ms 를 유지하고, 떠 있는지와 흐르는지를 따로 감시한다

- 상태: **채택**. `poll.interval.ms=100` 유지, 커넥터 하트비트 10초, `CdcHealthMonitor` + 알림 규칙 `cdc` 그룹
- 날짜: 2026-09-24
- 관련: `CdcHealthMonitor`, `cdc/register-*-connector.json`, `monitoring/alert-rules.yml`,
  [실측 리포트](../performance/cdc-cost-and-health.md), [ADR-047](ADR-047-cdc-for-activity-not-outbox.md), [ADR-056](ADR-056-search-index-freshness-by-cdc.md), 이슈 #252

## 맥락

E1b 에서 `poll.interval.ms` 를 500 → 100 으로 줄여 신선도를 얻었고, 대가는 재지 않았다. CDC 는 이제 개인화 컨텍스트(ADR-047)와
검색 색인(ADR-056) 두 곳을 나른다. 그런데 커넥터를 감시하는 것이 없었고, E1b 에서 커넥터가 `RUNNING` 인 채 흐르지 않던 일이 있었다.

## 판정 기준 (이슈 #252 에 측정 전에 적었다)

- poll 100 의 Connect CPU 가 500 대비 한 코어의 5%p 미만 더 쓰면 100 을 유지한다
- 감시: 커넥터를 멈추면 두 알림 조건이 성립하는 시간을 잰다. 조용할 때 하트비트가 오는지 먼저 확인한다

## 결과 (대입)

| | poll 100 | poll 500 | 차이 |
|---|---:|---:|---:|
| Connect CPU 조용(1차 · 2차) | 2.5% · 3.0% | 1.5% · 1.6% | +1.0 · +1.4%p |
| Connect CPU 변경 50/s | 7.7% · 10.0% | 5.7% · 7.5% | +2.0 · +2.5%p |
| MySQL CPU | 차이 없음 | | |
| 커밋 → 토픽 p95 | 97ms | 478ms | |

감시: DB 연결을 5분 막는 동안 Connect REST 는 내내 `RUNNING`, 하트비트 나이는 39초 만에 60초 초과. pause 는 상태 지표가 11초에 잡았다.

## 결정

- **poll 100 을 유지한다.** 기준 안이고, 비용은 Connect 에만 생긴다(DB 는 흘려보낼 뿐이다)
- **상태와 흐름을 따로 본다.** 상태(`cdc_connector_running`)는 pause·FAILED 를, 하트비트 나이(`cdc_heartbeat_age_seconds`)는
  `RUNNING` 인데 흐르지 않는 것을 잡는다. 둘 중 하나만으로는 이번에 넣은 장애 둘을 다 못 잡았다
- 하트비트를 켜면서 **SMT 에 조건을 붙였다**(데이터 토픽에만 변환). 안 붙이면 하트비트가 데이터 토픽에 섞이고 키 변환에서 깨진다

## 버린 것

| 안 | 이유 |
|---|---|
| poll 500 으로 되돌리기 | 아낀 CPU 는 한 코어의 1~2.5%p 이고 지연은 5배다 |
| 상태만 감시 | 5분 동안의 막힘을 한 번도 못 잡았다 |
| Debezium JMX 지표(`MilliSecondsSinceLastEvent` 등) | Connect 에 JMX 익스포터를 붙여야 한다. 하트비트는 이미 있는 Kafka 로 같은 것을 본다 |
| 데이터 토픽의 마지막 레코드 나이 | 변경이 없으면 정상이어도 늙는다. 하트비트는 조용할 때도 온다 |

## 대가 (정직하게)

- 조용할 때 하트비트 간격이 약 48초라 **문턱 60초와의 여유가 9초**다. ~~간격을 무엇이 정하는지 확인하지 못했다~~ → #358 에서 확인했다: **keep-alive 간격(`connect.keep.alive.interval.ms`, 기본 60초) × 0.8** 이다. `heartbeat.interval.ms` 는 조용할 때 간격을 바꾸지 못한다
- 막힘은 toxiproxy 로 만든 것이다. E1b 의 원인과 같은 경로인지는 모른다
- 감시는 앱 인스턴스마다 돈다. Connect REST 를 인스턴스 수만큼 읽는다(30초에 한 번씩)

## 다시 볼 조건

- 조용할 때 `CdcHeartbeatStale` 이 울리면 → 문턱을 올리기 전에 keep-alive 를 본다. 조용할 때 간격은 keep-alive × 0.8 이다. **문턱은 keep-alive × 0.8 보다 충분히 커야 한다**(지금 60초 대 48초). 여유를 넓히려면 keep-alive 를 줄인다(30초면 24초, #358)
- 커넥터가 늘어 Connect CPU 가 의미 있게 오를 때 → poll 을 커넥터별로 다시 고른다

## 조용할 때 하트비트 간격을 정하는 것 (#358, 2026-09-26)

일회용 CDC 스택에서 DB 에 쓰는 것이 없는 상태로 커넥터 넷을 동시에 띄우고 하트비트 토픽 레코드 간격을 200초 동안 쟀다. 가설과 예측은 이슈에 측정 전에 적었다.

| `heartbeat.interval.ms` | `connect.keep.alive.interval.ms` | 조용할 때 간격(실측) | 예측(keep-alive × 0.8) |
|---:|---:|---|---:|
| 10,000 | 60,000(기본) | 48.0 · 48.0 · 48.0초 | 48초 |
| 3,000 | 60,000 | 48.1 · 48.0 · 48.0초 | 48초 |
| 10,000 | 15,000 | 11.9~12.1초(15번) | 12초 |
| 10,000 | 30,000 | 24.0~24.1초(7번) | 24초 |

- **조용할 때 간격 = keep-alive × 0.8.** DB 가 조용하면 커넥터가 처리할 binlog 이벤트는 MySQL 이 보내는 binlog 하트비트뿐이고, 그 주기를 Debezium 이 keep-alive 의 0.8배로 요청한다.
  `heartbeat.interval.ms` 는 이벤트를 처리할 때 하트비트를 낼지 가르는 간격이라 조용할 때는 더 짧게 해도 소용이 없다(3초로 줄여도 48초)
- 알림 문턱(60초)은 이 값에 묶여 있다. keep-alive 를 30초로 줄이면 조용할 때 24초라 여유가 9초에서 36초로 넓어진다. 커넥터 설정은 이번에 바꾸지 않았다
- 원자료 [`performance/raw/20260926-pf3-358/`](../performance/raw/20260926-pf3-358/)
