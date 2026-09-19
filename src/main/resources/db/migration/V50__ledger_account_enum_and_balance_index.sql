-- 원장 계정 enum 정합 + 잔액 조회용 인덱스.
--
-- <b>1) account enum 이 코드와 어긋나 있었다.</b>
--   AccountType 은 PG_RECEIVABLE · SALES · CASH · PG_FEE 네 값을 쓰는데
--   (recordSettlementPaidOut 이 CASH · PG_FEE 를 쓴다), V1 이 만든 DB enum 은 앞의 둘뿐이었다.
--   그래서 정산 지급 확정이 원장에 기록되는 순간 MySQL 이 값을 거부한다(데이터 잘림).
--   아직 PAID_OUT 정산이 없어 드러나지 않았을 뿐이고, H2 기반 단위 테스트는 enum 을 강제하지
--   않아 통과한다. enum 을 코드에 맞춘다 — 이게 없으면 "미수금 = 승인합 − 취소합 − 입금합"
--   검증식이 성립할 수 없다(입금 분개가 저장되지 않으므로).
--
-- <b>2) 잔액은 "account 별 SUM(signed amount)" 로 파생된다</b>(ledger/package-info). 그런데
--   account 에 인덱스가 없어 계정별 합계가 매번 풀스캔이었다. 잔액 조회를 실제로 만들면
--   그 비용을 매번 치른다.
--
--   <b>(account, direction, amount) 커버링 인덱스</b>로 좁힌다. SUM 은 direction 과 amount 를
--   읽어야 하므로 둘을 인덱스에 실어야 테이블 힙(PK 랜덤 룩업)을 건너뛴다. account 단일 키나
--   (account, id) 는 WHERE 만 덮고 amount·direction 을 매번 힙에서 가져와 커버링이 되지 않는다
--   (실측: docs/performance/README.md 16절).
--
-- <b>주의: 인덱스는 읽기를 빠르게 하는 대신 쓰기 비용을 늘린다.</b> 원장은 append-only 라
--   결제마다 분개가 쌓인다. 그래서 인덱스 추가는 쓰기 비용도 함께 재야 한다 —
--   IndexWriteCostMySqlTest 와 같은 방식으로 잰다(성능 리포트 16절).

ALTER TABLE ledger_entries
    MODIFY COLUMN account ENUM('PG_RECEIVABLE', 'SALES', 'CASH', 'PG_FEE') NOT NULL;

CREATE INDEX idx_ledger_entries_account_covering
    ON ledger_entries (account, direction, amount);
