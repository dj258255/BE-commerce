#!/usr/bin/env bash
# 원장 잔액 조회 비용 측정 (ADR-025 · 성능 리포트 16절)
#
# 잔액은 "account 별 SUM(signed amount)" 로 파생된다. SUM 은 본질적으로 O(N) 이므로 원장 행이
# 늘수록 느려진다. 그 비용을 10만 · 100만 · 1000만 행에서 재고, 커버링 인덱스가 얼마를 벌어주는지,
# 어디까지가 인덱스로 버티는 구간인지 찾는다.
#
# 서버 안에서 재기 때문에 클라이언트/도커 exec 오버헤드가 섞이지 않는다(측정 루프가 프로시저 안).
#
# 실행:
#   RUNS=30 tools/measure-ledger-balance.sh
#
# 주의: 원장 테이블에 벤치용 행을 심었다가 지운다(transaction_id 를 마커로 쓴다). 실제 분개는
# 건드리지 않는다. 끝나면 벤치 행과 인덱스를 원상복구한다.
# 기본값은 10만·100만·1000만 행이지만, 로컬 실험은 TARGETS="10000 50000 100000"처럼
# 축소할 수 있다. TARGETS는 공백으로 구분한다.
set -euo pipefail

RUNS=${RUNS:-30}
TARGETS=${TARGETS:-"100000 1000000 10000000"}
MYSQL=(docker exec -i pay-mysql-1 mysql -ubecommerce -pbecommerce becommerce -N -B)

q() { "${MYSQL[@]}" -e "$1"; }

echo "== 1) 벤치 테이블/프로시저 준비 =="
q "DROP TABLE IF EXISTS ledger_balance_bench" || true
q "CREATE TABLE ledger_balance_bench (
     id INT AUTO_INCREMENT PRIMARY KEY,
     rows_total BIGINT NOT NULL,
     indexed TINYINT NOT NULL,
     variant VARCHAR(20) NOT NULL,
     ms DECIMAL(12,3) NOT NULL
   )"

"${MYSQL[@]}" <<'SQL'
DROP PROCEDURE IF EXISTS bench_balance;
DELIMITER //
CREATE PROCEDURE bench_balance(IN runs INT, IN is_indexed TINYINT)
BEGIN
  DECLARE i INT DEFAULT 0;
  DECLARE t0 DATETIME(6);
  DECLARE total BIGINT;
  SET total = (SELECT COUNT(*) FROM ledger_entries);
  WHILE i < runs DO
    -- 한 계정 잔액: 인덱스가 있으면 그 계정 분개만 범위 스캔
    SET t0 = NOW(6);
    SET @x = (SELECT COALESCE(SUM(CASE WHEN direction='DEBIT' THEN amount ELSE -amount END),0)
              FROM ledger_entries WHERE account='PG_RECEIVABLE');
    INSERT INTO ledger_balance_bench(rows_total,indexed,variant,ms)
      VALUES (total, is_indexed, 'single_account', TIMESTAMPDIFF(MICROSECOND,t0,NOW(6))/1000.0);
    -- 계정별 잔액 전체: GROUP BY account (모든 행을 훑는다)
    SET t0 = NOW(6);
    SET @x = (SELECT SUM(bal) FROM (
                SELECT SUM(CASE WHEN direction='DEBIT' THEN amount ELSE -amount END) bal
                FROM ledger_entries GROUP BY account) t);
    INSERT INTO ledger_balance_bench(rows_total,indexed,variant,ms)
      VALUES (total, is_indexed, 'group_by', TIMESTAMPDIFF(MICROSECOND,t0,NOW(6))/1000.0);
    SET i = i + 1;
  END WHILE;
END//
DELIMITER ;
SQL

echo "== 2) 벤치용 트랜잭션/시드 =="
TX=$(q "INSERT INTO ledger_transactions(tx_type,source_type,source_id,source_seq,description,created_at)
        VALUES('BENCH','BENCH',0,0,'ledger balance bench',NOW(6));
        SELECT LAST_INSERT_ID();" | tail -1)
echo "bench transaction_id=$TX"

cleanup() {
  if [ -n "${TX:-}" ]; then
    q "DELETE FROM ledger_entries WHERE transaction_id=$TX" >/dev/null 2>&1 || true
    q "DELETE FROM ledger_transactions WHERE id=$TX" >/dev/null 2>&1 || true
  fi
  q "CREATE INDEX IF NOT EXISTS idx_ledger_entries_account_covering ON ledger_entries(account,direction,amount)" >/dev/null 2>&1 || true
  q "DROP TABLE IF EXISTS ledger_balance_bench" >/dev/null 2>&1 || true
}
trap cleanup EXIT

q "INSERT INTO ledger_entries(transaction_id,account,direction,amount) VALUES
   ($TX,'PG_RECEIVABLE','DEBIT',10000),($TX,'SALES','CREDIT',10000),($TX,'CASH','DEBIT',9703),
   ($TX,'PG_FEE','DEBIT',297),($TX,'PG_RECEIVABLE','CREDIT',10000),($TX,'SALES','DEBIT',10000)"

double_until() {
  local target=$1 cur
  while :; do
    cur=$(q "SELECT COUNT(*) FROM ledger_entries WHERE transaction_id=$TX")
    if [ "$cur" -ge "$target" ]; then break; fi
    q "INSERT INTO ledger_entries(transaction_id,account,direction,amount)
       SELECT transaction_id,account,direction,amount FROM ledger_entries WHERE transaction_id=$TX"
  done
  echo "  rows=$(q "SELECT COUNT(*) FROM ledger_entries WHERE transaction_id=$TX")"
}

echo "== 3) 인덱스 있는 상태로 크기별 측정 =="
for target in $TARGETS; do
  echo "-- target=$target"
  double_until "$target"
  q "CALL bench_balance($RUNS, 1)"
done

echo "== 4) 인덱스를 떼고 마지막 target에서 재측정 =="
q "DROP INDEX idx_ledger_entries_account_covering ON ledger_entries"
q "CALL bench_balance($RUNS, 0)"
q "CREATE INDEX idx_ledger_entries_account_covering ON ledger_entries(account,direction,amount)"

echo "== 5) 결과 (p95 = 상위 5% 지점) =="
q "SELECT rows_total, indexed, variant,
       COUNT(*) n,
       ROUND(AVG(ms),1) avg_ms,
       ROUND(MAX(CASE WHEN rn = CEIL(0.95*cnt) THEN ms END),1) p95_ms,
       ROUND(MAX(ms),1) max_ms
   FROM (
     SELECT rows_total, indexed, variant, ms,
       ROW_NUMBER() OVER (PARTITION BY rows_total,indexed,variant ORDER BY ms) rn,
       COUNT(*)      OVER (PARTITION BY rows_total,indexed,variant) cnt
     FROM ledger_balance_bench) w
   GROUP BY rows_total, indexed, variant
   ORDER BY rows_total, indexed, variant"

echo "== 6) 정리 =="
q "DELETE FROM ledger_entries WHERE transaction_id=$TX"
q "DELETE FROM ledger_transactions WHERE id=$TX"
q "DROP TABLE IF EXISTS ledger_balance_bench"
echo "done"
