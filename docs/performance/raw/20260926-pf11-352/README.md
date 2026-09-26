# #352 원자료

| 폴더 | 조건 |
|---|---|
| `W40` · `S40` · `C40` | 계정 40개(주문 30건씩) · 풀 256MB 데움 · 풀 8MB · 풀 256MB 막 띄운 직후(1000/s 10초 × 6) |
| `W1000r` · `S1000r` | 계정 1,000개를 10번째 사용자마다 흩고 요청마다 무작위로 고름 · 풀 256MB · 8MB |
| `discarded/*-empty-list` | 첫 시도. 조회 계정의 주문이 0건(빈 목록)이라 버렸다 |
| `discarded/W1000` · `S1000` | 두 번째 시드 실패(볼륨이 안 지워져 새 주문이 유니크 제약에 막힘) · k6 setup 60초 초과로 요청 0 — 버렸다. 파일이 남은 것만 |
| `discarded/W1000s` · `S1000s` | 흩었지만 부하기가 VU 번호로 계정을 골라 앞쪽 100여 계정만 쓰였다 — 버렸다 |

`bp-status.txt` 는 측정 전후의 `Innodb_buffer_pool_read_requests` · `Innodb_buffer_pool_reads`(디스크 읽기) · `Innodb_data_reads`.
`mysql-vars.txt` 는 버퍼 풀 크기 · `innodb_flush_method` · 적재 설정. 러너는 `run.sh`(일회용 스택용).
