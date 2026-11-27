# 공부 안내

이 프로젝트로 무엇을, 어떤 순서로, 무엇을 읽으며 공부하고, 어디에 남기는지 한 곳에 모았습니다. 장 README는 실험 설계이고, 이 문서는 공부 순서입니다.

## 어디에 남기나?

| 단계 | 위치 | 남기는 것 | 공개 |
|---|---|---|---|
| 공부 노트 | `notes/` (이 폴더 안, git에서 제외) | 개념 정리, 헷갈린 것, 문서 읽은 메모, 틀렸던 추측 | 아니요 |
| 실험 기록 | `chapters/<장>/<실험>/` | 질문, 가설, 재현 명령, 결과 표, 원자료, 한계([틀](docs/experiment-template.md)) | 예 |
| 블로그 | IT Oasis | 장 하나가 끝난 뒤 정리한 결론과 근거 | 예 |

- 노트에는 회사 자료를 넣지 않습니다. 회사 자료는 원래 있던 곳에 두고 여기와 섞지 않습니다.
- `notes/`는 git에 올라가지 않으므로 백업이 따로 없습니다. 지워지면 복구할 수 없습니다.
- 블로그는 실험마다 쓰지 않습니다. 장의 실험이 다 끝나고, 결과 표와 원인과 고친 뒤 재확인까지 있을 때 그 장을 한두 편으로 정리합니다.

## 한눈에 보는 순서

| 단계 | 장 | 주제 | 끝났다는 기준 |
|---|---|---|---|
| 0 | 1장 | SQL과 데이터 모델링 | 같은 표에서 매출이 왜 여러 개 나오는지 설명하고 맞는 쿼리를 쓸 수 있다 |
| 1 | 2장 | 배치와 오케스트레이션 | 다시 돌려도 같은 결과가 나오는 작업과 마감 규칙을 만들 수 있다 |
| 2 | 3장 | CDC | 커넥터가 죽거나 로그가 지워졌을 때 무슨 일이 생기고 어떻게 복구하는지 안다 |
| 3 | 4장 | Kafka | 메시지가 사라지거나 두 번 오는 조건을 설정 이름으로 말할 수 있다 |
| 4 | 5장 | 스트림 처리(Flink) | 늦은 데이터와 장애 복구와 배포가 결과를 어떻게 바꾸는지 안다 |
| 5 | 6장 | 레이크하우스(Iceberg) | CDC를 테이블에 반영하는 비용과 유지보수를 설명할 수 있다 |
| 6 | 7장 | 운영과 정확성 | 숫자가 틀렸을 때 어느 구간인지 찾는 장치를 만들 수 있다 |
| 7 | 8장 | 고난도 | 다시 계산하는 범위를 줄이는 방법과 그 한계를 안다 |

앞 단계를 건너뛰지 않는 것을 권합니다. 0단계의 grain과 매출 정의가 7단계 대조의 정답이 되고, 3단계의 오프셋 커밋이 4단계 체크포인트를 이해하는 바탕이 됩니다.

블로그 주소의 앞부분 `https://dj258255.github.io/IT-Oasis/blog/data/`는 아래에서 `블로그/`로 줄여 씁니다.

---

## 0단계. SQL과 데이터 모델링 (1장)

개념

- [ ] grain: 표에서 행 하나가 무엇을 뜻하는가
- [ ] 자연키와 대리키
- [ ] 조인 카디널리티와 행 증식, 중복 집계를 막는 방법(사전 집계, `COUNT(DISTINCT)`)
- [ ] 같은 매출의 여러 정의: 승인 금액, 취소 반영 금액, 현재 잔액
- [ ] 시각과 날짜: UTC 시각, KST 영업일, `CONVERT_TZ`
- [ ] 윈도 함수
- [ ] 사실 테이블과 차원 테이블
- [ ] 실행 계획 읽기(`EXPLAIN ANALYZE`)

읽을 것

- 블로그 `블로그/foundations/de-01-pipeline-big-picture` (데이터 파이프라인의 큰 그림)
- MySQL 8.4 `EXPLAIN`: https://dev.mysql.com/doc/refman/8.4/en/explain.html
- MySQL 8.4 날짜·시간 함수: https://dev.mysql.com/doc/refman/8.4/en/date-and-time-functions.html
- 책: The Data Warehouse Toolkit 3판 1~3장(차원 모델링 기초)
- 이 저장소: `docs/source-schema.md` (표마다 행 하나의 뜻)

손으로 할 것: E1-1 → E1-2 → E1-3 → E1-4

끝났다는 기준(노트에 답을 적습니다)

1. `orders`, `order_items`, `payment_history`, `internal_records`의 행 하나는 각각 무엇인가?
2. 어제 승인되고 오늘 취소된 결제는 어제 매출인가 오늘 매출인가? 정의마다 답이 어떻게 다른가?
3. 분석 쿼리가 결제 쓰기를 느리게 만든 이유를 실행 계획과 지연 수치로 설명할 수 있는가?

## 1단계. 배치와 오케스트레이션 (2장)

개념

- [ ] 오케스트레이터가 맡는 일: 스케줄, 의존, 재시도, 상태 기록
- [ ] 멱등성, 재시도, 백오프
- [ ] 논리 날짜(data interval)와 backfill
- [ ] 스케줄 트리거와 이벤트 트리거
- [ ] 동시성 제한(`max_active_runs`, 풀)
- [ ] 멱등 쓰기: 파티션 지우고 다시 쓰기, UPSERT
- [ ] 잠정치와 확정치, 마감
- [ ] 작업 성공과 데이터 정확성의 차이, 건수·신선도 검사

읽을 것

- 블로그 `블로그/orchestration/de-02-what-orchestrator-does`
- 블로그 `블로그/foundations/de-03-idempotency-retry-backoff`
- 블로그 `블로그/foundations/de-04-logical-date-backfill`
- 블로그 `블로그/orchestration/de-05-schedule-vs-event-trigger`
- 블로그 `블로그/orchestration/de-06-concurrency-dedup-approval`
- Airflow 모범 사례: https://airflow.apache.org/docs/apache-airflow/stable/best-practices.html
- Airflow DAG 실행과 data interval: https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/dag-run.html

손으로 할 것: E2-1 → E2-4 → E2-2 → E2-3

끝났다는 기준

1. 쓰기 도중 죽은 작업을 재시도해도 합계가 변하지 않게 하려면 어떻게 써야 하는가?
2. 늦게 확정되는 결제가 있을 때 어제 매출은 언제 닫을 수 있는가?
3. 작업은 성공했는데 결과가 틀린 경우를 무엇으로 잡는가?

## 2단계. CDC (3장)

개념

- [ ] MySQL binlog: ROW 형식, 전후 이미지, GTID, 보존 기간
- [ ] Debezium 초기 스냅샷과 스트리밍 전환
- [ ] 레코드 모양: `op`, `before`, `after`, `source`
- [ ] 커넥터 오프셋 저장과 재시작 뒤 중복
- [ ] 로그 보존이 먼저 끝났을 때와 재스냅샷
- [ ] 스키마 이력 토픽과 DDL
- [ ] 비교: PostgreSQL 복제 슬롯, `max_slot_wal_keep_size`

읽을 것

- 블로그 `블로그/lakehouse/de-20-cdc-to-lakehouse`
- MySQL 8.4 바이너리 로그: https://dev.mysql.com/doc/refman/8.4/en/binary-log.html
- Debezium MySQL 커넥터: https://debezium.io/documentation/reference/stable/connectors/mysql.html
- PostgreSQL 17 복제 설정: https://www.postgresql.org/docs/17/runtime-config-replication.html
- 책: Designing Data-Intensive Applications 1판 5장(복제)

손으로 할 것: E3-1 → E3-2 → E3-3 → E3-4

끝났다는 기준

1. 커넥터를 `kill -9`하면 왜 같은 변경이 다시 오는가? 몇 건이나 오는지는 무엇이 정하는가?
2. binlog가 먼저 지워지면 커넥터는 무엇을 하고, 어떻게 복구하는가? PostgreSQL이라면 무엇이 다른가?
3. 커넥터 상태가 RUNNING인데 데이터가 오지 않는 경우는 언제인가?

## 3단계. Kafka (4장)

개념

- [ ] 로그, 세그먼트, 오프셋, 파티션, 키 해시, 컨슈머 그룹
- [ ] 프로듀서 batch, linger, acks, idempotence
- [ ] 복제: 리더, 팔로워, ISR, high watermark, `min.insync.replicas`
- [ ] unclean leader election
- [ ] 오프셋 커밋 시점과 중복·유실
- [ ] 리밸런스: eager, cooperative-sticky, static membership, 새 컨슈머 프로토콜(KIP-848)
- [ ] lag: 건수 lag과 시간 lag
- [ ] 보존, 컴팩션, tombstone, `delete.retention.ms`
- [ ] 파티션 수와 hot key
- [ ] 롤링 재시작, 운영 지표
- [ ] 장애 뒤 따라잡기 용량 계산
- [ ] KRaft 컨트롤러 쿼럼
- [ ] Kafka Connect 오류 처리와 DLQ

읽을 것

- 블로그 `블로그/streaming/de-11-kafka-log-partition-group`
- 블로그 `블로그/streaming/de-12-kafka-producer-latency`
- Kafka 공식 문서(설계, 복제, 컨슈머 설정, 운영): https://kafka.apache.org/documentation/
- KIP-848: https://cwiki.apache.org/confluence/display/KAFKA/KIP-848%3A+The+Next+Generation+of+the+Consumer+Rebalance+Protocol
- 책: Kafka: The Definitive Guide 2판 3장(프로듀서), 4장(컨슈머), 6장(내부 구조), 7장(신뢰성 있는 전달)

손으로 할 것: E4-1 → E4-2 → E4-3 → E4-5 → E4-4 → E4-6 → E4-7 → E4-8 → E4-9

끝났다는 기준

1. `acks=all`인데 확인받은 메시지를 잃는 조건은 무엇인가?
2. 처리 전 커밋과 처리 후 커밋에서 소비자가 죽으면 각각 무엇이 생기는가?
3. lag 10만 건이 몇 초 지연인지 무엇으로 계산하는가? 한 시간 멈췄다면 언제 따라잡는가?

## 4단계. 스트림 처리 (5장)

개념

- [ ] 이벤트 시간과 처리 시간, watermark, 허용 지연
- [ ] 윈도 종류와 interval join
- [ ] 체크포인트와 장애 복구, 백프레셔, unaligned checkpoint
- [ ] 전달 보장과 exactly-once, Kafka 트랜잭션, `read_committed`
- [ ] savepoint와 연산자 uid
- [ ] Debezium 레코드를 변경 로그로 읽기

읽을 것

- 블로그 `블로그/foundations/de-13-delivery-semantics`
- 블로그 `블로그/streaming/de-15-flink-time-watermark`
- 블로그 `블로그/streaming/de-16-flink-window-join`
- 블로그 `블로그/streaming/de-17-flink-checkpoint-backpressure`
- Flink 시간 개념: https://nightlies.apache.org/flink/flink-docs-release-2.1/docs/concepts/time/
- Flink 체크포인트: https://nightlies.apache.org/flink/flink-docs-release-2.1/docs/ops/state/checkpoints/
- Flink savepoint: https://nightlies.apache.org/flink/flink-docs-release-2.1/docs/ops/state/savepoints/
- Flink Debezium 형식: https://nightlies.apache.org/flink/flink-docs-release-2.1/docs/connectors/table/formats/debezium/
- 책: Streaming Systems 1~3장, Designing Data-Intensive Applications 1판 11장

손으로 할 것: E5-1 → E5-2 → E5-3 → E5-5 → E5-4 → E5-6

끝났다는 기준

1. watermark 지연을 늘리면 무엇을 얻고 무엇을 잃는가? 수치로 말할 수 있는가?
2. 체크포인트 간격은 장애 뒤 재처리량과 결과 지연에 어떤 값을 치르는가?
3. uid 없이 작업을 바꿔 배포하면 왜 상태를 잃는가?

## 5단계. 레이크하우스 (6장)

개념

- [ ] Iceberg 스냅샷, 매니페스트 목록, 매니페스트, 데이터 파일
- [ ] MERGE와 삭제 파일(position, equality)
- [ ] 변경 이력(SCD Type 2)
- [ ] 작은 파일과 컴팩션
- [ ] 스냅샷 만료와 orphan 파일 정리
- [ ] 브랜치와 write-audit-publish
- [ ] 파티션 진화

읽을 것

- 블로그 `블로그/lakehouse/de-18-iceberg-table-format`
- 블로그 `블로그/lakehouse/de-19-iceberg-maintenance`
- 블로그 `블로그/lakehouse/de-20-cdc-to-lakehouse`
- Iceberg 테이블 사양: https://iceberg.apache.org/spec/
- Iceberg 유지보수: https://iceberg.apache.org/docs/latest/maintenance/
- Iceberg 브랜치와 태그: https://iceberg.apache.org/docs/latest/branching/

손으로 할 것: E6-1 → E6-2 → E6-3 → E6-4 → E6-5

끝났다는 기준

1. CDC 갱신을 MERGE로 반영하면 읽기 비용이 왜 늘고, 무엇으로 줄이는가?
2. 커밋 간격과 파일 수와 조회 시간은 어떤 관계인가?
3. 두 테이블을 같은 시점으로 공개하려면 어떻게 하는가?

## 6단계. 운영과 정확성 (7장)

개념

- [ ] 데이터 품질의 세 축: 신선도, 완전성, 정확성
- [ ] 대조: 건수, 합계, 키 범위별 해시
- [ ] 종단 지연 구간 나누기, 백분위, coordinated omission
- [ ] DLQ와 재처리, 버전 비교로 덮어쓰기 막기
- [ ] 스키마 호환성과 의미 변경, 데이터 계약
- [ ] 삭제 기록과 재생·복구
- [ ] 펜싱(세대 번호)
- [ ] 서버 운영 항목을 데이터 운영 항목으로 옮기기

읽을 것

- 블로그 `블로그/foundations/de-08-latency-percentiles`
- 블로그 `블로그/foundations/de-09-measurement-pitfalls`
- 블로그 `블로그/foundations/de-14-schema-registry-compatibility`
- 이 저장소: `docs/measurement-rules.md`
- 사례: Uber 원장 이관 글(2024, 오프라인 전수 비교)

손으로 할 것: E7-1 → E7-2 → E7-3 → E7-4 → E7-5 → E7-6 → E7-7

끝났다는 기준

1. 행이 빠졌을 때 어느 층에서 어느 키까지 짚을 수 있는가? 대조 비용은 얼마인가?
2. 원본 커밋부터 조회까지 가장 긴 구간은 어디이고, 신선도 SLO를 어떻게 정했는가?
3. 호환성 검사를 통과했는데 지표가 틀리는 변경은 무엇으로 잡는가?

## 7단계. 고난도 (8장)

읽을 것

- 사례: Netflix Psyberg(2023, 원문 미확인이라 수치 인용 금지), Uber Kappa 아키텍처 재처리 글(2020)

손으로 할 것: E8-1 → E8-2 → E8-3

끝났다는 기준

1. 늦은 변경이 닿은 날짜만 다시 계산해도 전체 재계산과 같은 결과가 나오는가? 아니라면 언제 다른가?
2. 재생 결과가 실시간 결과와 다른 원인은 무엇이고, 어떻게 없애는가?
3. 작업 수백 개를 동시에 다시 돌리면 어디가 먼저 막히는가?
