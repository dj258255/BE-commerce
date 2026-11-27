# 핫 계정 해법 비교 원자료 (#494, 2026-11-27)

하네스: `commerce/src/test/java/com/beomsu/becommerce/ledger/LedgerHotAccountMitigationMySqlTest.java`
MySQL 8.4 Testcontainers, 워커 8, 워커당 200회, 트랜잭션 안 임계 구간 2ms. 판정은 같은 회차 COLD 대비 비율의 중앙값.

| 파일 | 상태 | 쓰는 곳 |
|---|---|---|
| `final-5rounds.txt` | 결함 셋을 고친 최종 실행. 끝의 두 줄은 시작과 끝의 부하 평균 | ADR-088 · docs/37 판정 |
| `defective-run1-3rounds.txt` | 결함 1(요약이 모양마다 다른 회차를 섞음)과 결함 3(집계기 꼬리 쫓기) | 쓰지 않음. 회차별 줄만 이슈 댓글에서 다시 계산 |
| `defective-run2-5rounds.txt` | 결함 3. 요약은 회차별 비율로 고친 뒤 | HOT · SPLIT 값만 재현성 확인용 |
| `defective-run3-5rounds.txt` | 결함 2(`-Dledger.hot.shapes` 가 테스트 JVM 에 닿지 않아 다섯 모양을 다 잼)와 결함 3 | HOT · SPLIT 값만 재현성 확인용 |
| `defective-run4-noidx.txt` | 결함 3. 보조 인덱스 `(applied, id)` 를 빼도 잠금 대기 1,065~1,295번과 반영 지연 약 1초가 그대로라 "인덱스 범위 잠금" 가설을 버림 | 원인 배제 근거 |
| `trace-before-fix.txt` | 집계기가 1.2초 동안 두 번만 반영함 | 결함 3의 증거 |
| `trace-after-fix.txt` | `FORCE INDEX (PRIMARY)` 뒤 약 100ms마다 반영, 잠금 대기 0 | 수정 확인 |

결함 3: 표가 작을 때 옵티마이저가 집계기의 `UPDATE … WHERE id IN (…)` 에 전체 훑기를 골랐다.
REPEATABLE READ 의 잠금 읽기가 아직 커밋 안 된 새 분개마다 기다리며 표 끝을 쫓아가 쓰기가 끝날 때까지 반영하지 못했다.
잠금 대기 약 1,100번은 쓰는 쪽이 아니라 집계기가 기다린 횟수다. 쓰는 쪽 처리량은 거의 영향을 받지 않았다.

세 번의 유효한 HOT · SPLIT 실행에서 SPLIT-16 의 COLD 대비 처리량은 77.8% · 74.0% · 74.2% 로 모두 기준 80% 아래였다.
