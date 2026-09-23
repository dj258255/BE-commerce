#!/usr/bin/env bash
# 정산 판매자 공정성 실험 (ADR-027)
#
# 대형 판매자 하나가 틱 용량을 독식할 때 소형 판매자의 정산이 며칠 밀리는지(id 순)와,
# 라운드로빈으로 바꾸면 0 이 되는지를 실제 배치(어드민 /run)로 잰다. 대신 대형 판매자의
# 하루 지급액이 어떻게 바뀌는지(=대가)도 함께 잰다.
#
# 틱 용량 = max-pages(2) × read-chunk-size(500) = 1000건.
#
# 실행: tools/settlement-fairness-experiment.sh
set -uo pipefail

JAVA=/opt/homebrew/Cellar/openjdk@21/21.0.9/libexec/openjdk.jdk/Contents/Home/bin/java
cd "$(dirname "$0")/.."
BASE=http://localhost:8080
DATE=2001-01-01
OFFSET=910000000
SMALL_OFFSET=910100000
BIG_N=2500
SMALL_N=10

q() { docker exec -i pay-mysql-1 mysql -ubecommerce -pbecommerce becommerce -N -B 2>/dev/null -e "$1"; }

seed() {
  q "DELETE FROM settlement_items WHERE payment_id >= $OFFSET;
     DELETE FROM settlements WHERE settlement_date BETWEEN '2001-01-01' AND '2001-01-15';" >/dev/null
  # 지급 게이트가 판매자 행을 본다 — 실험용 소형 판매자를 등록한다(없으면 게이트가 보류로 본다).
  q "INSERT INTO sellers (id, business_number, legal_name, representative_name, country_code, status, created_at, updated_at)
     VALUES (2, 'FAIR-SMALL', '실험 소형 판매자', '실험', 'KR', 'ACTIVE', NOW(6), NOW(6))
     ON DUPLICATE KEY UPDATE legal_name = VALUES(legal_name);" >/dev/null
  q "SET SESSION cte_max_recursion_depth=10000;
     INSERT INTO settlement_items (payment_id, order_no, amount, confirmed_date, status, seller_id)
     WITH RECURSIVE seq(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM seq WHERE n < $((BIG_N-1)))
     SELECT $OFFSET + n, CONCAT('FAIR-B-', n), 10000, '$DATE', 'CONFIRMED', 1 FROM seq;
     INSERT INTO settlement_items (payment_id, order_no, amount, confirmed_date, status, seller_id)
     WITH RECURSIVE seq(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM seq WHERE n < $((SMALL_N-1)))
     SELECT $SMALL_OFFSET + n, CONCAT('FAIR-S-', n), 10000, '$DATE', 'CONFIRMED', 2 FROM seq;" >/dev/null
}

start_app() {  # $1 = policy
  $JAVA -Xmx1g -Xms256m -jar commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar --server.port=8080 \
    --spring.docker.compose.enabled=false --app.ratelimit.enabled=false \
    --app.batch.settlement-max-pages=2 --app.batch.read-chunk-size=500 \
    --app.settlement.fairness-policy="$1" > "/tmp/fair-$1.log" 2>&1 &
  echo $!
}
wait_health() {
  for _ in $(seq 1 60); do curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && return 0; sleep 1; done
  return 1
}

run_policy() {  # $1 = policy label, $2 = app pid
  local policy=$1
  seed
  local tok
  tok=$(curl -s -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
        -d '{"username":"admin","password":"admin-local-only"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
  local small_day="" big_day="" first_day_big=0
  for tick in 0 1 2 3 4 5 6 7 8 9; do
    local d
    d=$(date -j -v+"${tick}"d -f "%Y-%m-%d" "$DATE" +%Y-%m-%d)
    curl -s -o /dev/null -X POST "$BASE/api/v1/admin/settlements/run?date=$d" \
      -H "Authorization: Bearer $tok"
    local settled_small settled_big
    settled_small=$(q "SELECT COUNT(*) FROM settlement_items WHERE payment_id >= $SMALL_OFFSET AND status='SETTLED'")
    settled_big=$(q "SELECT COUNT(*) FROM settlement_items WHERE payment_id >= $OFFSET AND payment_id < $SMALL_OFFSET AND status='SETTLED'")
    # 첫 틱에서 대형이 얼마나 가져갔는지(하루 지급액) = 대가 지표
    if [ "$tick" -eq 0 ]; then
      first_day_big=$(q "SELECT COUNT(*) FROM settlement_items WHERE payment_id >= $OFFSET AND payment_id < $SMALL_OFFSET AND status='SETTLED'")
    fi
    if [ -z "$small_day" ] && [ "$settled_small" -ge "$SMALL_N" ]; then
      small_day="$tick"
    fi
    if [ "$settled_big" -ge "$BIG_N" ] && [ -z "$big_day" ]; then
      big_day="$tick"
    fi
  done
  echo "[$policy] 소형 판매자 전건 정산까지 = ${small_day:-9+}일 지연 | 대형 판매자 전건 완료 = ${big_day:-9+}일 | 첫날 대형 정산 건수 = $first_day_big"
}

pkill -9 -f 'be-commerce-0.0.1-SNAPSHOT.jar' 2>/dev/null; sleep 1
echo "=== policy=id ==="
PID=$(start_app id); wait_health || { echo "app 기동 실패"; exit 1; }
run_policy id "$PID"
kill -9 "$PID" 2>/dev/null; sleep 1

echo "=== policy=round-robin ==="
PID=$(start_app round-robin); wait_health || { echo "app 기동 실패"; exit 1; }
run_policy round-robin "$PID"
kill -9 "$PID" 2>/dev/null

echo "=== 정리 ==="
q "DELETE FROM settlement_items WHERE payment_id >= $OFFSET; DELETE FROM settlements WHERE settlement_date BETWEEN '2001-01-01' AND '2001-01-10';" >/dev/null
echo done
