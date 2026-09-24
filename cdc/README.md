# CDC — `user_activities` → Kafka `user.activity`

MySQL `becommerce.user_activities` 테이블의 **새 행**을 Debezium(binlog)으로 읽어 Kafka
`user.activity` 토픽으로 흘린다. 소비자는 앱 안의 `KafkaContextTransport`(`@KafkaListener`)이고,
토픽명·페이로드 모양은 그 컨슈머가 기대하는 것에 맞췄다.

- 커넥터 설정: [`register-user-activity-connector.json`](./register-user-activity-connector.json)
- 인프라: `compose.yaml` 의 `mysql`(binlog 옵션, B1) · `kafka` · `debezium`(`--profile cdc`)

## 왜

- 앱은 영속 아웃박스(Modulith)로 `user.activity` 를 발행한다. 이 커넥터는 **같은 최종 토픽**을
  DB 변경에서 직접 만든다 — 두 경로를 같은 컨슈머로 비교하기 위해서다.
- 그래서 커넥터의 출력 토픽과 페이로드는 **컨슈머가 이미 아는 모양**이어야 한다. 아래 매핑이 그 이유다.

## 전제: 컨테이너

```bash
# mysql(ROW binlog) · kafka · debezium 을 띄운다. debezium 은 mysql/kafka 가 healthy 일 때 뜬다.
docker compose -p pay --profile cdc up -d
```

- `debezium` 은 기본 `up` 에 딸려 오지 않는다(`--profile cdc`). 로컬에서 늘 떠 있으면 느려져서다.
- Connect REST 는 호스트 `http://localhost:8083`.

## 등록

설정 파일은 **`{"name":"user-activity-cdc","config":{...}}` 봉투**다 — `POST /connectors` 가 요구하는
형태다. (config 를 평평하게 담은 파일은 `PUT /connectors/<이름>/config` 용이고, 봉투를 `PUT` 에 주면
그 안의 `config` 키가 config 항목으로 취급돼 어긋난다. 둘을 섞지 않는다.)

```bash
curl -sS -X POST -H 'Content-Type: application/json' \
  --data @cdc/register-user-activity-connector.json \
  http://localhost:8083/connectors
```

- 같은 이름이 이미 있으면 `POST` 는 `409 Conflict` 다. 먼저 해제(아래)하고 다시 등록하거나,
  config 만 바꿀 때는 봉투의 `config` 를 `PUT /connectors/user-activity-cdc/config` 로 보낸다.

## 확인

```bash
# 1) 커넥터가 붙었는가
curl -sS http://localhost:8083/connectors
# → ["user-activity-cdc"]

# 2) 태스크가 RUNNING 인가 (여기가 FAILED 면 대개 DB 접속·binlog 권한·schema history 문제다)
curl -sS http://localhost:8083/connectors/user-activity-cdc/status

# 3) 실제로 적용된 config 를 되읽는다 (파일과 어긋나면 이 값이 진실이다)
curl -sS http://localhost:8083/connectors/user-activity-cdc/config

# 4) 새 활동이 토픽에 닿는지 본다
docker compose -p pay exec kafka \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic user.activity --from-beginning --max-messages 5
```

`user_activities` 에 새 행이 들어가면 그 행이 `user.activity` 에 나타나야 한다. 안 나오면
`docker compose -p pay logs -f debezium` 로 커넥터 로그를 본다.

## 해제

```bash
# 커넥터만 지운다(내부 토픽·오프셋은 남는다). 다시 등록하면 이어서 읽는다.
curl -sS -X DELETE http://localhost:8083/connectors/user-activity-cdc
```

## 설정에 담은 이유

### `snapshot.mode = schema_only`

`user_activities` 에는 **이미 49,010 행**이 있다. `initial` 로 두면 커넥터가 붙는 순간 그 전량이
`user.activity` 로 흘러나간다. 그 백필은 B5 의 **신선도 측정을 오염시킨다** — 재려는 것은 "새 활동이
몇 ms 만에 닿는가"이지 과거 재생이 아니다. `schema_only` 는 **스키마만** 읽고 데이터는 스냅샷하지
않으며, 지금부터의 binlog 변경만 스트리밍한다.

### `database.include.list` / `table.include.list`

`becommerce.user_activities` **한 표만** 본다. 다른 표가 섞이면 컨슈머가 모르는 메시지를 받는다.
`include.schema.changes=false` 로 DDL(schema change) 이벤트도 내보내지 않는다 — 이 역시 컨슈머가
파싱할 수 없는 메시지이고, `RegexRouter` 가 `.*` 로 잡으므로 두면 토픽을 오염시킨다.

### `database.server.id`

Debezium 이 MySQL 에 **복제 클라이언트**로 붙을 때 쓰는 고유 식별자다. mysql 의 `server-id`(compose 에서 `1`)와 **같으면 안 된다** — 같으면 복제 프로토콜에서 서버 ID 가 충돌해 커넥터가 뜨지 않는다. 그래서 `1` 이 아닌 큰 값 `184054` 를 쓴다. (이 항목이 빠지면 등록이 설정 검증에서 `The database.server.id value is invalid: A value is required.` 로 떨어진다.)

### `kafka:29092`

브로커 광고 주소가 둘이다(`compose.yaml` 의 `KAFKA_ADVERTISED_LISTENERS`). 호스트용은
`localhost:9092` 이고, **컨테이너 안에서는 그 localhost 가 자기 자신이라 못 붙는다.** 그래서 컨테이너용
광고 주소 `kafka:29092` 를 쓴다 — `schema.history.internal.kafka.bootstrap.servers` 도 같은 이유다.

### `key/value.converter = JSON, schemas.enable=false`

소비자 `KafkaContextTransport` 는 `ConsumerRecord<String,String>` 을 받아
`objectMapper.readValue(record.value(), UserActivityEvent.class)` 로 직접 바인딩한다. Connect 의
스키마 봉투(`{"schema":...,"payload":...}`)가 붙으면 **그 역직렬화가 깨진다.** 그래서 스키마 없이
JSON 만 보낸다.

### SMT 다섯 개

순서가 곧 적용 순서다(`transforms=unwrap,rename,ts,keyFromValue,keyField,route`). 값이 먼저 평평해지고
이름이 바뀐 뒤에야 키·시각 변환이 그 필드를 찾을 수 있다.

1. **`io.debezium.transforms.ExtractNewRecordState`(`unwrap`)** — Debezium 기본 값은 `{before, after, source, op}` 봉투다.
   컨슈머는 **활동 그 자체**를 기대하므로 값에서 `after` 를 꺼내 평평하게 만든다.
   (`tombstones.on.delete=false` + `delete.handling.mode=drop` — 이 표는 append-only라 삭제가 없다.)
   구체 클래스라 접미사가 없다.
2. **`org.apache.kafka.connect.transforms.ReplaceField$Value`(`rename`)** — `after` 의 컬럼명은 snake_case 지만
   이벤트 필드는 camelCase 다. 아래 매핑으로 **값(value)** 의 이름을 맞춘다.
3. **`org.apache.kafka.connect.transforms.TimestampConverter$Value`(`ts`)** — `occurredAt` 의 **값 표현**을
   epoch 마이크로초 정수에서 **ISO-8601 문자열**로 바꾼다. 아래 "`occurredAt` 은 ISO-8601 문자열" 참고.
4. **`org.apache.kafka.connect.transforms.ValueToKey`(`keyFromValue`)** — 값의 `userId` 로 **키**를 다시 만든다.
   아래 "파티션 키 = `userId`" 참고.
5. **`org.apache.kafka.connect.transforms.ExtractField$Key`(`keyField`)** — 위에서 만든 `{userId: ...}` 키를
   **스칼라 `userId`** 로 접는다.
6. **`org.apache.kafka.connect.transforms.RegexRouter`(`route`)** — Debezium 이 만든 토픽(`pay-cdc.becommerce.user_activities`)을
   최종 토픽 **`user.activity`** 로 바꾼다. `topic.prefix` 값과 무관하게 잡도록 `.*` 를 쓴다.
   토픽 이름만 바꾸는 구체 클래스라 접미사가 없다.

**접미사 주의**: `ReplaceField`·`TimestampConverter`·`ExtractField` 는 모두 **추상 클래스**라
내부 클래스 `Key`/`Value`(`$Key`/`$Value`)를 줘야 인스턴스화된다. 값을 바꾸는 셋(`rename`,`ts`)은
`$Value`, 키를 자르는 `ExtractField` 는 `$Key` 다. `ExtractNewRecordState`·`ValueToKey`·`RegexRouter` 는
구체 클래스라 접미사가 없다.

### 파티션 키 = `userId`

`@Externalized("user.activity::#{userId}")` 가 라우팅 키를 `userId` 로 잡는 이유는 **한 사용자의 활동이
같은 파티션에 들어가 순서가 보존**되고, 그래서 컨슈머가 사용자별 read-modify-write 에 분산 락을 쓰지
않아도 되기 때문이다(`UserActivityEvent` 주석). Debezium 기본 키는 행 PK(`id`)라 그 보장이 깨진다 —
파티션이 1개일 때만 안 드러난다. 그래서 `ValueToKey(fields=userId)` 로 값을 키로 올리고,
`ExtractField$Key(field=userId)` 로 `{userId:...}` 를 스칼라 `userId` 로 접는다 — 앱이 키로 쓰는 것과
같은 `userId` 라 한 사용자가 같은 파티션으로 간다.

### `occurredAt` 은 ISO-8601 문자열

Debezium 은 `datetime(6)` 을 epoch **마이크로초 정수**로 낸다(기본 `time.precision.mode=adaptive` →
`io.debezium.time.MicroTimestamp`, INT64). 이벤트 필드는 `java.time.Instant` 이고 컨슈머는 앱의
ObjectMapper(Spring 기본)로 읽는데, Jackson 은 `Instant` 의 **숫자를 초로 해석**하므로 마이크로초 정수를
그대로 두면 **조용히 틀린 시각**이 된다(던지지 않는다). 그래서 `TimestampConverter$Value` 로 맞춘다.

- **왜 밀리초 숫자가 아니라 문자열인가**: 앱(Modulith)이 발행하는 `occurredAt` 도 ISO-8601 문자열이고, 그
  경로가 이미 이 컨슈머에서 정상 동작한다(같은 컨슈머로 두 경로를 비교하는 게 이 실험의 목적이다). 숫자로
  맞추려면 초/밀리초 단위를 컨슈머가 알아야 하는데 그건 컨슈머 수정(`src/` 금지) 없이는 못 박는다. 문자열은
  단위 해석이 필요 없어 두 경로가 **같은 표현**을 쓴다.
- `unix.precision=microseconds` 로 **입력** 해석을 지정하고, `format=yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`(UTC)로
  **출력**한다 → `2026-09-23T07:00:00.123Z` 형태라 Jackson 이 그대로 `Instant` 로 읽는다.
- 대가는 밀리초 아래 정밀도 손실이다(TimestampConverter 는 밀리초까지만 다룬다). ms 신선도 측정에는
  충분하다.

## 컬럼 ↔ 이벤트 필드 매핑

소비자가 읽는 레코드: `UserActivityEvent(long userId, long itemId, String activityType, long seq, Instant occurredAt, String source)`

| `user_activities` 컬럼 | 이벤트 필드 | 처리 |
|---|---|---|
| `user_id` | `userId` | `ReplaceField` rename |
| `item_id` | `itemId` | `ReplaceField` rename |
| `activity_type` | `activityType` | `ReplaceField` rename |
| `occurred_at` | `occurredAt` | `ReplaceField` rename + `TimestampConverter$Value` 로 ISO-8601 문자열화 |
| `seq` | `seq` | 그대로 |
| `source` | `source` | 그대로 |
| `id`, `created_at` | (없음) | 이벤트에 없는 컬럼. 그대로 흘러가지만 소비자 ObjectMapper 가 무시한다 |
| (PK) `id` | **키** | `ValueToKey` + `ExtractField$Key` 로 키를 `userId` 로 교체 |

## 알아 둘 것

- **해결됨(더 이상 한계 아님)**: `occurredAt` 은 ISO-8601 문자열로, 파티션 키는 `userId` 로 맞췄다
  (위 두 절). "값 표현은 config 로 못 맞춘다"던 이전 판단은 틀렸다 — `TimestampConverter$Value` 로 됐다.
- **`id`·`created_at`**: 이벤트에 없는 여분 컬럼이라 값에 그대로 남는다(`created_at` 은 epoch 정수).
  소비자 ObjectMapper 가 미지 필드를 무시하므로 바인딩은 안 깨진다. 정확히 6필드로 만들고 싶으면
  `ReplaceField$Value` 에 `exclude=id,created_at` 를 더하면 된다.
- **`occurredAt` 정밀도**: ISO 문자열은 밀리초까지만 담는다(마이크로초 → 밀리초). ms 단위 신선도
  측정에는 영향이 없다.
- **키 표현**: 스칼라 정수 `userId` 라 JsonConverter 로 `999002` 처럼(따옴표 없이) 직렬화된다. 앱도 같은
  `userId` 를 키로 쓰므로 한 사용자가 두 경로에서 같은 파티션으로 간다.

---

# 카탈로그 커넥터 — `products`·`stock` → `catalog.change` (#246)

검색 색인의 반영 지연을 줄이려고 둔다([ADR-056](../docs/adr/ADR-056-search-index-freshness-by-cdc.md)).
소비자는 앱 안의 `LuceneChangeListener`(Lucene, 인스턴스마다)와 `EngineIndexer`(ES·OpenSearch, 하나)다.

- 설정: [`register-catalog-connector.json`](./register-catalog-connector.json)
- 앱: `app.catalog.search.cdc.enabled=true`(기본) + kafka 프로파일

```bash
# 토픽을 먼저 만든다. 없는 토픽을 구독하면 앱이 파티션을 늦게(메타데이터 갱신 주기만큼) 받는다
docker compose -p pay exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic catalog.change --partitions 3 --replication-factor 1
curl -sS -X POST -H 'Content-Type: application/json' \
  --data @cdc/register-catalog-connector.json http://localhost:8083/connectors
```

## 사용자 활동 커넥터와 다른 점

| | `user-activity-cdc` | `catalog-cdc` |
|---|---|---|
| 표 | `user_activities` 하나 | `products`, `stock` 둘 |
| 값 | 컨슈머가 이벤트로 바인딩한다(이름·시각 변환) | **쓰지 않는다.** 키(`product_id`)만 쓰고 DB 를 다시 읽는다 |
| 삭제 | 없다(append-only) → `drop` | 상품이 지워질 수 있다 → `rewrite`(키가 남아야 지운 것을 안다) |
| `database.server.id` | 184054 | 184055(커넥터끼리도 겹치면 안 된다) |
| `snapshot.mode` | `schema_only` | `no_data`(3.0 의 새 이름). 기존 10만 행을 흘리지 않는다. 기동 색인이 DB 전체를 읽는다 |

값을 쓰지 않는 이유: stock 이벤트에는 상품 필드가 없고, 두 표의 이벤트가 순서를 바꿔 오거나 두 번 올 수 있다.
id 로 지금 DB 를 읽으면 셋 다 신경 쓸 필요가 없다. 대가는 변경마다 DB 조회 한 번이다(Lucene 은 인스턴스마다).

---

# 하트비트와 감시 (#252)

두 커넥터 모두 `heartbeat.interval.ms=10000` 이다. 하트비트는 `__debezium-heartbeat.<topic.prefix>` 토픽에 찍힌다.
앱의 `CdcHealthMonitor` 가 그 나이와 Connect REST 상태를 지표로 낸다([ADR-059](../docs/adr/ADR-059-cdc-poll-and-health.md)).

```bash
APP_CDC_HEALTH_CONNECT_URL=http://localhost:8083   # 이 값을 줄 때만 감시가 뜬다(kafka 프로파일)
```

**변환에 조건(`predicates.isData`)을 붙였다.** 두 커넥터의 `RegexRouter` 는 `.*` 를 한 토픽으로 보내고 `ValueToKey` 는 값에서
`product_id`·`userId` 를 꺼낸다. 하트비트 레코드에는 그 필드가 없다. 조건 없이 하트비트를 켜면 하트비트가 데이터 토픽에 섞이고
키 변환에서 깨진다. 조건은 토픽 이름이 `<topic.prefix>.becommerce.` 로 시작할 때만 변환을 건다.

**조용할 때 하트비트는 10초가 아니라 약 48초마다 온다**(#252 실측). 10초 간격은 binlog 이벤트를 처리할 때의 값이다.
알림 `CdcHeartbeatStale` 의 문턱(60초)은 이 간격을 보고 정했다.

**이미 등록한 커넥터는 다시 등록해야 설정이 바뀐다**(`DELETE` 후 `POST`, 또는 `config` 를 `PUT`).

