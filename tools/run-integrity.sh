#!/usr/bin/env bash
# 정합성 불변식 점검을 부하 · 장애 뒤와 일부러 어긋낸 데이터로 잰다(#389).
#
#   bash tools/run-integrity.sh
#   STRATEGIES=CHECK OBSERVE=120 bash tools/run-integrity.sh   # 짧게 시험
#
# 1) 재고 전략마다 앱을 띄워 한정 상품 부하(#374 와 같음: 결과 모름 10% · 이탈 20%)를 건다
# 2) 부하 중과 부하 뒤 OBSERVE 초 동안 5초마다 유예 GRACES 초로 점검을 부른다. 끝에 진짜 위반이 0 이면
#    그 사이에 센 건은 모두 진행 중인 건을 잘못 센 것이다
# 3) 배치를 끈 앱으로 다시 띄워 불변식마다 오염을 하나씩 넣고 그 불변식만 1 이 되는지 본다
set -euo pipefail

JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-integrity}
STRATEGIES=${STRATEGIES:-"CHECK AT_PAYMENT"}
PORT_BASE=${PORT_BASE:-18191}; DB_PORT=${DB_PORT:-13389}; REDIS_PORT=${REDIS_PORT:-16389}
OBSERVE=${OBSERVE:-300}; STOCK=${STOCK:-100}; PRODUCT_ID=${PRODUCT_ID:-90374}
RATE=${RATE:-5}; DURATION=${DURATION:-60s}; ABANDON=${ABANDON:-0.2}; UNKNOWN=${UNKNOWN:-0.1}
GRACES=${GRACES:-"0 5 15 30 60 120"}
mkdir -p "$OUT"

df -h / | tail -1 > "$OUT/df-before.txt"
docker run -d --name ig-mysql --tmpfs /var/lib/mysql:rw,size=2g -e MYSQL_ROOT_PASSWORD=root -p "$DB_PORT":3306 \
  mysql:8.4 --skip-log-bin --innodb-buffer-pool-size=256M --max-connections=400 >/dev/null
docker run -d --name ig-redis -p "$REDIS_PORT":6379 redis:7.4-alpine >/dev/null

APPS=()
cleanup() {
  for p in ${APPS[@]+"${APPS[@]}"}; do kill "$p" 2>/dev/null || true; done
  wait 2>/dev/null || true
  docker rm -f -v ig-mysql ig-redis >/dev/null 2>&1 || true
}
trap cleanup EXIT

MYSQL="docker exec -i ig-mysql mysql -uroot -proot"
for _ in $(seq 1 90); do
  [ "$(docker logs ig-mysql 2>&1 | grep -c 'ready for connections')" -ge 2 ] && $MYSQL -e "SELECT 1" >/dev/null 2>&1 && break
  sleep 2
done

start_app() {   # 이름 포트 DB번호 배치켜기(true|false) 로그
  local s=$1 port=$2 idx=$3 batches=$4 log=$5 db
  db="ig_$(echo "$s" | tr 'A-Z' 'a-z')"
  SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$DB_PORT/$db?serverTimezone=UTC&characterEncoding=UTF-8" \
  SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=root \
  SPRING_DATA_REDIS_PORT=$REDIS_PORT SPRING_DATA_REDIS_DATABASE=$idx \
    "$JAVA" -Xmx512m -jar "$JAR" --server.port="$port" --app.ratelimit.enabled=false \
    --app.stock.reservation="$s" \
    --app.recovery.enabled="$batches" --app.checkout.recovery.enabled="$batches" \
    --app.compensation.enabled="$batches" --app.order.expiry.enabled="$batches" \
    --payment.fake-pg.timeout-approved-prefix=unk-ok- --payment.fake-pg.timeout-lost-prefix=unk-lost- \
    > "$log" 2>&1 &
  APPS+=($!)
  for _ in $(seq 1 180); do curl -sf "http://localhost:$port/actuator/health" >/dev/null 2>&1 && return 0; sleep 1; done
  echo "앱이 안 떴다: $s" >&2; tail -20 "$log" >&2; exit 1
}

token() {   # 포트
  curl -sf -X POST "http://localhost:$1/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin-local-only"}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])'
}

check() {   # 포트 토큰 유예 단계 이름
  local body
  body=$(curl -sf "http://localhost:$1/api/v1/admin/integrity?graceSeconds=$3&samples=3" -H "Authorization: Bearer $2") || body='{"error":"call failed"}'
  python3 -c 'import sys,json,time; d=json.loads(sys.argv[1]); d.update(t=round(time.time(),1), phase=sys.argv[2], name=sys.argv[3], grace=int(sys.argv[4])); print(json.dumps(d, ensure_ascii=False))' \
    "$body" "$4" "$5" "$3"
}

NAMES=(); PORTS=(); i=0
for s in $STRATEGIES; do
  port=$((PORT_BASE + i)); db="ig_$(echo "$s" | tr 'A-Z' 'a-z')"
  if lsof -tiTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then echo "포트 $port 를 이미 쓰고 있다" >&2; exit 1; fi
  $MYSQL -e "CREATE DATABASE $db" 2>/dev/null
  start_app "$s" "$port" "$i" true "$OUT/app-$s.log"
  $MYSQL "$db" -e "INSERT INTO products (product_id, name, price) VALUES ($PRODUCT_ID, '한정 상품', 10000);
                   INSERT INTO stock (product_id, quantity, version) VALUES ($PRODUCT_ID, $STOCK, 0);"
  NAMES+=("$s"); PORTS+=("$port"); i=$((i + 1))
done
TOKENS=()
for k in "${!NAMES[@]}"; do TOKENS+=("$(token "${PORTS[$k]}")"); done
ps -axo pcpu,etime,command | grep -E 'genpage|k6 |uvicorn' | grep -v grep > "$OUT/ps-before.txt" || true

PIDS=()
for k in "${!NAMES[@]}"; do
  k6 run --quiet --summary-export "$OUT/k6-${NAMES[$k]}.json" -e BASE_URL="http://localhost:${PORTS[$k]}" \
    -e PRODUCT_ID=$PRODUCT_ID -e RATE=$RATE -e DURATION=$DURATION -e ABANDON=$ABANDON -e UNKNOWN=$UNKNOWN \
    k6/flash-sale-stock.js > "$OUT/k6-${NAMES[$k]}.txt" 2>&1 &
  PIDS+=($!)
done

# 부하 중 · 부하 뒤 OBSERVE 초 동안 5초마다
poll_until=$(( $(date +%s) + ${DURATION%s} + 30 + OBSERVE ))
load_end=$(( $(date +%s) + ${DURATION%s} + 30 ))
while [ "$(date +%s)" -lt "$poll_until" ]; do
  phase=drain; [ "$(date +%s)" -lt "$load_end" ] && phase=load
  for k in "${!NAMES[@]}"; do
    for g in $GRACES; do check "${PORTS[$k]}" "${TOKENS[$k]}" "$g" "$phase" "${NAMES[$k]}" >> "$OUT/integrity-${NAMES[$k]}.jsonl"; done
  done
  sleep 5
done
for p in "${PIDS[@]}"; do wait "$p" || true; done
for k in "${!NAMES[@]}"; do
  check "${PORTS[$k]}" "${TOKENS[$k]}" 0 final "${NAMES[$k]}" >> "$OUT/integrity-${NAMES[$k]}.jsonl"
done

# 결제 승인 시각부터 뒤따르는 기록이 생긴 시각까지(마이크로초). 셋 다 쓴 시각(Instant.now())이다
for k in "${!NAMES[@]}"; do
  db="ig_$(echo "${NAMES[$k]}" | tr 'A-Z' 'a-z')"
  {
    $MYSQL -N -B "$db" -e "SELECT 'ledger', TIMESTAMPDIFF(MICROSECOND, p.approved_at, lt.created_at) FROM payments p
      JOIN ledger_transactions lt ON lt.tx_type = 'PAYMENT_APPROVED' AND lt.source_type = 'PAYMENT' AND lt.source_id = p.id
      WHERE p.approved_at IS NOT NULL" 2>/dev/null
    $MYSQL -N -B "$db" -e "SELECT 'recon', TIMESTAMPDIFF(MICROSECOND, p.approved_at, ir.recorded_at) FROM payments p
      JOIN internal_records ir ON ir.order_no = p.order_no AND ir.seq = 0 WHERE p.approved_at IS NOT NULL" 2>/dev/null
  } > "$OUT/lag-${NAMES[$k]}.tsv"
done

# 오염: 배치를 끄고 다시 띄운 첫 전략의 DB 에 불변식마다 하나씩 넣는다
for p in ${APPS[@]+"${APPS[@]}"}; do kill "$p" 2>/dev/null || true; done
wait 2>/dev/null || true
APPS=()
s=${NAMES[0]}; port=${PORTS[0]}; db="ig_$(echo "$s" | tr 'A-Z' 'a-z')"
start_app "$s" "$port" 0 false "$OUT/app-$s-inject.log"
tok=$(token "$port")
check "$port" "$tok" 0 before-inject "$s" >> "$OUT/integrity-inject.jsonl"
paid() {   # n 번째 PAID 주문(승인 결제 하나)
  $MYSQL -N -B "$db" -e "SELECT o.order_no FROM orders o JOIN payments p ON p.order_no = o.order_no AND p.status = 'DONE'
                          WHERE o.status = 'PAID' ORDER BY o.id LIMIT 1 OFFSET $1" 2>/dev/null
}
pid_of() { $MYSQL -N -B "$db" -e "SELECT id FROM payments WHERE order_no = '$1' AND status = 'DONE'" 2>/dev/null; }
inject() {   # 불변식 SQL
  # 오염이 들어가지 않으면 "못 잡았다"와 같은 모양이 된다. SQL 오류를 남긴다
  $MYSQL "$db" -e "$2" 2>&1 | grep -v "Using a password" | sed "s/^/$1: /" >> "$OUT/inject-errors.txt" || true
  check "$port" "$tok" 0 "inject:$1" "$s" >> "$OUT/integrity-inject.jsonl"
}
o=$(paid 0); inject PAYMENT_DONE_ORDER_NOT_PAID "UPDATE orders SET status = 'PAYMENT_IN_PROGRESS' WHERE order_no = '$o'"
o=$(paid 0); inject ORDER_PAID_PAYMENT_NOT_DONE "UPDATE payments SET status = 'ABORTED' WHERE order_no = '$o'"
o=$(paid 0); p=$(pid_of "$o")
inject APPROVED_WITHOUT_LEDGER "DELETE le FROM ledger_entries le JOIN ledger_transactions lt ON lt.id = le.transaction_id
  WHERE lt.source_type = 'PAYMENT' AND lt.source_id = $p AND lt.tx_type = 'PAYMENT_APPROVED';
  DELETE FROM ledger_transactions WHERE source_type = 'PAYMENT' AND source_id = $p AND tx_type = 'PAYMENT_APPROVED'"
o=$(paid 1); p=$(pid_of "$o")
inject LEDGER_AMOUNT_MISMATCH "UPDATE ledger_entries le JOIN ledger_transactions lt ON lt.id = le.transaction_id
  SET le.amount = le.amount + 1 WHERE lt.source_type = 'PAYMENT' AND lt.source_id = $p AND lt.tx_type = 'PAYMENT_APPROVED'"
o=$(paid 2); p=$(pid_of "$o")
inject LEDGER_UNBALANCED "UPDATE ledger_entries le JOIN ledger_transactions lt ON lt.id = le.transaction_id
  SET le.amount = le.amount + 1 WHERE lt.source_type = 'PAYMENT' AND lt.source_id = $p AND lt.tx_type = 'PAYMENT_APPROVED'
  AND le.direction = 'CREDIT'"
o=$(paid 3); inject APPROVED_WITHOUT_RECON_RECORD "DELETE FROM internal_records WHERE order_no = '$o' AND seq = 0"
o=$(paid 4); inject FAILED_ORDER_CARD_NOT_REFUNDED "UPDATE orders SET status = 'FAILED' WHERE order_no = '$o'"
o=$(paid 4)
inject RESERVATION_LEFT_OPEN "INSERT INTO stock_reservations (order_no, product_id, quantity, status, created_at, updated_at)
  VALUES ('$o', 999999, 1, 'RESERVED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))"
inject STOCK_NEGATIVE "UPDATE stock SET quantity = -1 WHERE product_id = $PRODUCT_ID"
o=$(paid 5)
inject UNKNOWN_OVER_10_MINUTES "INSERT INTO payments (order_no, payment_key, amount, balance_amount, status, requested_at, version, cancel_count, recovery_attempts, installment_months, pg_provider)
  VALUES ('$o', 'inject-unknown', 10000, 10000, 'UNKNOWN', DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 11 MINUTE), 0, 0, 0, 0, 'FAKE')"

python3 tools/integrity_eval.py "$OUT" | tee "$OUT/report.md"
