# 막힌 지점 기록

실제로 겪은 것만 적습니다. 환경은 맥북(Apple Silicon), 콜리마 VM 4코어 8GiB입니다.

## 환경 구축(2026-11-10)

### 1. 이미지 받기가 TLS 핸드셰이크 시간 초과로 끊겼다

`docker compose up`이 이미지를 받다가 `net/http: TLS handshake timeout`으로 멈췄습니다. 같은 명령을 `docker compose pull`로 다시 돌리자 받아졌습니다. 일시적인 네트워크 문제로 보고, 처음 올릴 때는 `pull`을 따로 먼저 돌립니다.

### 2. Flink 이미지 빌드에서 Maven 내려받기가 끊겼다

`curl: (56) OpenSSL SSL_read: unexpected eof while reading`. 다시 빌드하자 통과했습니다. Dockerfile의 `curl`에 `--retry 5 --retry-all-errors`를 넣었습니다.

### 3. MySQL 8.4 접속에 cryptography 패키지가 필요했다

PyMySQL이 `'cryptography' package is required for sha256_password or caching_sha2_password auth methods`로 실패했습니다. MySQL 8.4의 기본 인증 방식이 `caching_sha2_password`이기 때문입니다. 요구 사항을 `PyMySQL[rsa]`로 바꿨습니다.

### 4. 다중 테이블 UPDATE에 LIMIT을 쓸 수 없었다

구매 확정 쿼리(`UPDATE settlement_items JOIN payments ... LIMIT 500`)가 `1221 Incorrect usage of UPDATE and LIMIT`으로 실패했습니다. MySQL은 다중 테이블 UPDATE에서 LIMIT을 받지 않습니다. 대상 id를 먼저 SELECT로 고르고 단일 테이블 UPDATE로 바꿨습니다. 이 오류 전에 들어간 주문 151건이 원본에 남아 아래 확인 수치(1,051)에 포함됐습니다.

## 환경 확인 결과(2026-11-10)

| 프로필 | 확인한 것 | 결과 |
|---|---|---|
| 기본 | MySQL 8.4.11, 표 9개, GTID ON, binlog ROW | 정상 |
| cdc | KRaft 쿼럼 투표자 3, 토픽 복제 3·ISR 3·`min.insync.replicas=2`, 커넥터 RUNNING | 정상 |
| cdc | 부하 생성기 초당 30건 30초 뒤 원본 주문 행 수와 토픽 고유 주문 id 수 | 1,051 = 1,051 (토픽 op: c 1,051, u 1,078) |
| stream | Flink 2.1.0 SQL로 같은 토픽을 읽어 op별 건수 | c 1,051, u 1,078 (콘솔 소비자와 같음) |
| lake | Trino로 Iceberg 테이블 생성, 2행 쓰기, 읽기 | 2행 |
| orchestrate | Airflow 3.3.2 상태 API, 원본 연결로 주문 수 조회 | 상태 200, 1,051 |
| registry | Apicurio Confluent 호환 API | 200 |
