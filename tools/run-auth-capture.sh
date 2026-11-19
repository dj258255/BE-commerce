#!/usr/bin/env bash
# 승인 · 매입을 나누면 무엇을 얻나(#476). 같은 한정 상품 부하(#374)를 세 앱에 동시에 건다.
#
#   bash tools/run-auth-capture.sh
#   OBSERVE=240 bash tools/run-auth-capture.sh     # 짧게 시험
#
#   R1: 취소 장애 없음,  망취소 5회 규칙(지금)
#   R2: 취소 장애 180초, 망취소 5회 규칙
#   R3: 취소 장애 180초, 망취소 시간 창 30분(app.compensation.retry-window-seconds=1800)
#
# 세 앱 모두 재고 전략 CHECK, 매입 마감은 첫 승인 뒤 60초(판매 끝). 일회용 MySQL(tmpfs) · Redis 를 띄우고 끝나면 볼륨까지 지운다.
set -euo pipefail

JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-auth-capture}
PORT_BASE=${PORT_BASE:-18191}; DB_PORT=${DB_PORT:-13476}; REDIS_PORT=${REDIS_PORT:-16476}
OBSERVE=${OBSERVE:-780}; STOCK=${STOCK:-100}; PRODUCT_ID=${PRODUCT_ID:-90476}
RATE=${RATE:-5}; DURATION=${DURATION:-60s}; ABANDON=${ABANDON:-0.2}; UNKNOWN=${UNKNOWN:-0.1}
OUTAGE_MS=${OUTAGE_MS:-180000}; WINDOW_S=${WINDOW_S:-1800}; CUTOFF_MS=${CUTOFF_MS:-60000}
STRATEGY=${STRATEGY:-CHECK}   # 보조 실행은 NONE(매진 뒤 결제가 모두 망취소가 되어 표본이 많다)
mkdir -p "$OUT"
# 집계는 부하가 끝난 뒤에 돈다. 그때 패키지를 못 받으면 정리 단계가 DB 를 지워 결과가 사라진다(2회차에서 실제로 그랬다).
# 시작 전에 확인하고 캐시를 먼저 쓴다
PY_RUN="uv run --offline --quiet --with pymysql python3"
$PY_RUN -c 'import pymysql' 2>/dev/null || { PY_RUN="uv run --quiet --with pymysql python3"; $PY_RUN -c 'import pymysql'; }
echo "strategy=$STRATEGY outage_ms=$OUTAGE_MS window_s=$WINDOW_S cutoff_ms=$CUTOFF_MS rate=$RATE duration=$DURATION" > "$OUT/params.txt"

df -h / | tail -1 > "$OUT/df-before.txt"
uptime > "$OUT/uptime-before.txt"
ps -axo pcpu,etime,command | grep -E 'k6 |run-.*\.sh|gradle' | grep -v grep > "$OUT/ps-before.txt" || true
docker run -d --name ac-mysql --tmpfs /var/lib/mysql:rw,size=2g -e MYSQL_ROOT_PASSWORD=root -p "$DB_PORT":3306 \
  mysql:8.4 --skip-log-bin --innodb-buffer-pool-size=256M --max-connections=400 >/dev/null
docker run -d --name ac-redis -p "$REDIS_PORT":6379 redis:7.4-alpine >/dev/null

APPS=()
cleanup() {
  for p in ${APPS[@]+"${APPS[@]}"}; do kill "$p" 2>/dev/null || true; done
  wait 2>/dev/null || true
  docker rm -f -v ac-mysql ac-redis >/dev/null 2>&1 || true
}
trap cleanup EXIT

MYSQL="docker exec -i ac-mysql mysql -uroot -proot"
for _ in $(seq 1 90); do
  [ "$(docker logs ac-mysql 2>&1 | grep -c 'ready for connections')" -ge 2 ] && $MYSQL -e "SELECT 1" >/dev/null 2>&1 && break
  sleep 2
done

NAMES=(R1 R2 R3)
FLAGS=("" "--payment.fake-pg.cancel-fail-for-ms=$OUTAGE_MS" \
       "--payment.fake-pg.cancel-fail-for-ms=$OUTAGE_MS --app.compensation.retry-window-seconds=$WINDOW_S")
PORTS=()
for i in 0 1 2; do
  n=${NAMES[$i]}; port=$((PORT_BASE + i)); db="ac_$(echo "$n" | tr 'A-Z' 'a-z')"
  if lsof -tiTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then echo "포트 $port 를 이미 쓰고 있다. PORT_BASE 를 바꾼다" >&2; exit 1; fi
  $MYSQL -e "CREATE DATABASE $db" 2>/dev/null
  # shellcheck disable=SC2086
  SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$DB_PORT/$db?serverTimezone=UTC&characterEncoding=UTF-8" \
  SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=root \
  SPRING_DATA_REDIS_PORT=$REDIS_PORT SPRING_DATA_REDIS_DATABASE=$i \
    "$JAVA" -Xmx512m -jar "$JAR" --server.port="$port" --app.ratelimit.enabled=false \
    --app.stock.reservation="$STRATEGY" \
    --app.recovery.enabled=true --app.checkout.recovery.enabled=true \
    --app.compensation.enabled=true --app.order.expiry.enabled=true \
    --payment.fake-pg.timeout-approved-prefix=unk-ok- --payment.fake-pg.timeout-lost-prefix=unk-lost- \
    --payment.fake-pg.capture-cutoff-after-ms="$CUTOFF_MS" ${FLAGS[$i]} \
    > "$OUT/app-$n.log" 2>&1 &
  APPS+=($!); PORTS+=("$port")
done
for i in 0 1 2; do
  port=${PORTS[$i]}
  for _ in $(seq 1 180); do curl -sf "http://localhost:$port/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
  db="ac_$(echo "${NAMES[$i]}" | tr 'A-Z' 'a-z')"
  $MYSQL "$db" -e "INSERT INTO products (product_id, name, price) VALUES ($PRODUCT_ID, '한정 상품', 10000);
                   INSERT INTO stock (product_id, quantity, version) VALUES ($PRODUCT_ID, $STOCK, 0);"
done

PIDS=()
for i in 0 1 2; do
  k6 run --quiet --summary-export "$OUT/k6-${NAMES[$i]}.json" -e BASE_URL="http://localhost:${PORTS[$i]}" \
    -e PRODUCT_ID=$PRODUCT_ID -e RATE=$RATE -e DURATION=$DURATION -e ABANDON=$ABANDON -e UNKNOWN=$UNKNOWN \
    k6/flash-sale-stock.js > "$OUT/k6-${NAMES[$i]}.txt" 2>&1 &
  PIDS+=($!)
done
for p in "${PIDS[@]}"; do wait "$p" || true; done
sleep "$OBSERVE"

for i in 0 1 2; do
  n=${NAMES[$i]}; port=${PORTS[$i]}
  # /actuator/metrics 는 인증이 걸려 401 이라 prometheus 텍스트에서 읽는다(run-pg-brownout.sh 와 같음)
  curl -sf "http://localhost:$port/actuator/prometheus" | grep -E '^fake_pg_(cancel|approve_first)' > "$OUT/prom-$n.txt" || true
  db="ac_$(echo "$n" | tr 'A-Z' 'a-z')"
  # 집계 스크립트가 실패해도 원본은 남긴다
  $MYSQL -e "SELECT id, order_no, status, retry_count, created_at, updated_at, last_outcome FROM compensation_tasks; SELECT status, COUNT(*) FROM orders GROUP BY status" "$db" > "$OUT/raw-$n.tsv" 2>/dev/null
  DB_PORT=$DB_PORT DB_NAME="$db" $PY_RUN tools/auth_capture_eval.py snapshot "$OUT" "$n"
done
uptime > "$OUT/uptime-after.txt"
OUTAGE_MS=$OUTAGE_MS python3 tools/auth_capture_eval.py report "$OUT"
