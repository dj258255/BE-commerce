# be-commerce-data

[BE-commerce](https://github.com/dj258255/BE-commerce)는 주문, 결제, 취소, 정산을 다루는 결제 백엔드입니다. 이 저장소는 그 결제 시스템이 남긴 데이터를 분석과 정산에 쓸 수 있는 곳까지 옮기면서 생기는 문제를 하나씩 재현하고 측정하는 실험 프로젝트입니다.

처음에는 운영 DB에 SQL 한 줄을 던지는 데서 시작합니다. 그 SQL이 틀린 숫자를 내고, 매일 돌리면 재실행이 숫자를 바꾸고, 분석 쿼리가 결제 쓰기를 느리게 만듭니다. 그래서 CDC를 들이고, Kafka를 운영하고, 실시간 집계와 레이크하우스를 붙입니다. 장마다 앞 장이 남긴 문제가 다음 장의 출발점입니다.

## 지금 상태

| 항목 | 상태 |
|---|---|
| 환경(compose 프로필 6개) | 동작 확인 완료(2026-11-10). 확인 기록은 [docs/breakpoints.md](docs/breakpoints.md) |
| 원본 스키마 | BE-commerce 커밋 `ed3757d`의 마이그레이션 V1~V72에서 표 9개 |
| 부하 생성기 | 동작 확인 완료. 초당 30건 30초에서 원본 주문 1,051행과 CDC 토픽 고유 주문 1,051개 일치 |
| 실험 | 계획 완료, 결과 0건 |

## 장 구성

| 장 | 출발점이 되는 문제 | 공부하는 것 | 실험 수 |
|---|---|---|---|
| [1. 하루 매출 숫자](chapters/01-daily-revenue/) | "어제 판매자별 매출"을 운영 DB에서 뽑는다 | grain, 조인 행 증식, 취소 반영, 시간대, 실행 계획 | 4 |
| [2. 매일 돌리는 집계](chapters/02-daily-batch/) | 그 SQL을 매일 아침 돌린다 | 오케스트레이터, 논리 날짜, 멱등 쓰기, backfill, 잠정치와 확정치 | 4 |
| [3. CDC](chapters/03-cdc/) | 분석 쿼리가 결제 쓰기를 느리게 만든다 | binlog, Debezium 스냅샷과 스트리밍, 오프셋, 로그 보존 | 4 |
| [4. Kafka 운영](chapters/04-kafka/) | CDC 토픽이 플랫폼의 중심이 된다 | 복제와 acks, 오프셋 커밋, 리밸런스, lag, 컴팩션, hot key, 롤링 재시작, 따라잡기 | 9 |
| [5. 실시간 집계](chapters/05-streaming/) | 정산 담당자가 "지금" 매출을 원한다 | 이벤트 시간, watermark, 체크포인트, savepoint와 uid, 백프레셔 | 6 |
| [6. 레이크하우스](chapters/06-lakehouse/) | 원본과 토픽은 이력을 오래 들고 있지 못한다 | Iceberg MERGE, 삭제 파일, 작은 파일, 스냅샷 만료, 여러 테이블의 공개 시점 | 5 |
| [7. 운영과 대조](chapters/07-operations/) | 숫자가 틀렸을 때 어느 구간에서 틀렸는지 모른다 | 3층 대조, 종단 지연과 신선도 SLO, DLQ 재투입, 스키마 의미 변경, 삭제 되살아남, 펜싱 | 7 |
| [8. 고난도](chapters/08-advanced/) | 규모가 커지면 다시 계산하는 비용이 문제가 된다 | 영향 범위만 재계산, 실시간과 재생 결과 비교, 작업 대량 재시작 | 3 |

## 공부와 기록

무엇을 어떤 순서로 공부하고 무엇을 읽는지는 [STUDY.md](STUDY.md) 한 곳에 있습니다. 기록은 세 곳에 나눠 둡니다.

| 단계 | 위치 | 공개 |
|---|---|---|
| 공부 노트 | `notes/` (git에서 제외) | 아니요 |
| 실험 기록 | `chapters/<장>/<실험>/` | 예 |
| 블로그 | 장이 끝나고 정리된 뒤에만 | 예 |

## 실행

Docker VM 메모리 8GiB를 기준으로 잡았습니다. 장마다 필요한 프로필만 올립니다.

```bash
cd compose
docker compose up -d --wait                                 # 1장: 원본 MySQL
docker compose --profile orchestrate up -d                  # 2장: + Airflow (http://127.0.0.1:8080)
docker compose --profile cdc up -d --wait                   # 3장: + Kafka 3대 + Debezium
../scripts/register-connector.sh                            #       CDC 커넥터 등록
docker compose --profile kafka up -d --wait                 # 4장: Kafka 3대
docker compose --profile cdc --profile stream up -d --wait  # 5장: + Flink (http://127.0.0.1:8081)
docker compose --profile cdc --profile lake up -d --wait    # 6장: + Iceberg 카탈로그, S3, Trino
docker compose --profile registry up -d                     # 스키마 레지스트리
```

원본에 데이터를 흘립니다.

```bash
python3 -m venv .venv && .venv/bin/pip install -r loadgen/requirements.txt
.venv/bin/python loadgen/loadgen.py --rate 30 --duration 60
```

처음 상태로 되돌릴 때는 `scripts/reset.sh`를 씁니다. 볼륨까지 지웁니다.
실험 전에는 `scripts/doctor.sh`로 디스크와 다른 부하를 확인하고 그 출력을 결과에 함께 남깁니다.

## 저장소 구조

```
compose/        compose.yaml, 원본 스키마, CDC 커넥터 설정, 카탈로그 초기화
loadgen/        원본에 주문·결제·취소·구매 확정을 쓰는 부하 생성기
flink/          Flink 이미지(Kafka·Iceberg 커넥터), SQL 작업
airflow/dags/   2장의 DAG
chapters/       장별 README. 실험 코드와 결과는 장 폴더 아래 실험 폴더에 둔다
docs/           측정 규칙, 원본 스키마 출처, 실험 기록 틀, 막힌 지점 기록
notes/          공부 노트(git에서 제외, 이 저장소에는 올라가지 않음)
scripts/        점검, 커넥터 등록, 초기화, 토픽 건수
```

## 근거 등급

실험마다 근거 등급을 붙입니다.

| 등급 | 뜻 |
|---|---|
| `E1` | 기업 기술 블로그나 포스트모템 원문이 있는 사례를 재현 |
| `E1·축소` | 사례는 실재하지만 규모나 인프라가 달라 같은 메커니즘만 축소 재현 |
| `E2` | 특정 사례는 없지만 공식 문서가 경고하는 동작을 재현 |

사례 원문을 직접 확인하지 못한 경우에는 수치를 인용하지 않고 그 사실을 실험 README에 적습니다.

## 측정 규칙

[docs/measurement-rules.md](docs/measurement-rules.md)에 있습니다. 요약하면 한 번에 조건 하나만 바꾸고, 0이 나오면 조건이 성립했다는 증거를 함께 남기고, 결과를 본 뒤 판정 기준을 바꾸면 원래 기준의 판정부터 적습니다.

## 관련 글

개념 설명은 블로그 [데이터 엔지니어링 공부 시리즈](https://dj258255.github.io/IT-Oasis/blog/data/foundations/de-00-top-down-map)에 있고, 이 저장소의 장 README가 해당 편을 가리킵니다. 실험 결과 글은 장 하나가 끝나 정리된 뒤에 블로그에 올리고 여기 표에 링크합니다.
