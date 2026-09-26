#!/usr/bin/env bash
# 재고를 언제 잡을지(#374). 전략마다 앱을 하나씩 띄워 같은 한정 상품 부하를 동시에 건다.
#
#   bash tools/run-stock-reservation.sh
#   STRATEGIES="NONE AT_PAYMENT" OBSERVE=120 bash tools/run-stock-reservation.sh   # 짧게 시험
#
# 일회용 MySQL(tmpfs) · Redis 를 띄우고 끝나면 볼륨까지 지운다. 전략마다 DB 와 Redis 번호를 따로 쓴다.
# 결제 복구 · 주문 복구 · 보상 · 주문 만료 배치는 켜고 주기는 기본값이다(주문 복구는 10분 넘게 멈춘 주문).
set -euo pipefail

JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-stock-reservation}
STRATEGIES=${STRATEGIES:-"NONE CHECK AT_PAYMENT AT_ORDER"}
PORT_BASE=${PORT_BASE:-18091}; DB_PORT=${DB_PORT:-13374}; REDIS_PORT=${REDIS_PORT:-16374}
OBSERVE=${OBSERVE:-780}; STOCK=${STOCK:-100}; PRODUCT_ID=${PRODUCT_ID:-90374}
RATE=${RATE:-5}; DURATION=${DURATION:-60s}; ABANDON=${ABANDON:-0.2}; UNKNOWN=${UNKNOWN:-0.1}
mkdir -p "$OUT"

df -h / | tail -1 > "$OUT/df-before.txt"
docker run -d --name sr-mysql --tmpfs /var/lib/mysql:rw,size=2g -e MYSQL_ROOT_PASSWORD=root -p "$DB_PORT":3306 \
  mysql:8.4 --skip-log-bin --innodb-buffer-pool-size=256M --max-connections=400 >/dev/null
docker run -d --name sr-redis -p "$REDIS_PORT":6379 redis:7.4-alpine >/dev/null

APPS=()
cleanup() {
  for p in ${APPS[@]+"${APPS[@]}"}; do kill "$p" 2>/dev/null || true; done
  wait 2>/dev/null || true
  docker rm -f -v sr-mysql sr-redis >/dev/null 2>&1 || true
}
trap cleanup EXIT

MYSQL="docker exec -i sr-mysql mysql -uroot -proot"
# 공식 이미지는 초기화 중에 임시 서버를 한 번 띄웠다 내린다. 최종 서버의 로그가 나올 때까지 기다린다
for _ in $(seq 1 90); do
  [ "$(docker logs sr-mysql 2>&1 | grep -c 'ready for connections')" -ge 2 ] && $MYSQL -e "SELECT 1" >/dev/null 2>&1 && break
  sleep 2
done

NAMES=(); PORTS=(); i=0
for s in $STRATEGIES; do
  port=$((PORT_BASE + i)); db="sr_$(echo "$s" | tr 'A-Z' 'a-z')"
  if lsof -tiTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then echo "포트 $port 를 이미 쓰고 있다. PORT_BASE 를 바꾼다" >&2; exit 1; fi
  $MYSQL -e "CREATE DATABASE $db" 2>/dev/null
  SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$DB_PORT/$db?serverTimezone=UTC&characterEncoding=UTF-8" \
  SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=root \
  SPRING_DATA_REDIS_PORT=$REDIS_PORT SPRING_DATA_REDIS_DATABASE=$i \
    "$JAVA" -Xmx512m -jar "$JAR" --server.port="$port" --app.ratelimit.enabled=false \
    --app.stock.reservation="$s" \
    --app.recovery.enabled=true --app.checkout.recovery.enabled=true \
    --app.compensation.enabled=true --app.order.expiry.enabled=true \
    --payment.fake-pg.timeout-approved-prefix=unk-ok- --payment.fake-pg.timeout-lost-prefix=unk-lost- \
    > "$OUT/app-$s.log" 2>&1 &
  APPS+=($!); NAMES+=("$s"); PORTS+=("$port"); i=$((i + 1))
done
for k in "${!NAMES[@]}"; do
  port=${PORTS[$k]}
  for _ in $(seq 1 180); do curl -sf "http://localhost:$port/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
  db="sr_$(echo "${NAMES[$k]}" | tr 'A-Z' 'a-z')"
  $MYSQL "$db" -e "INSERT INTO products (product_id, name, price) VALUES ($PRODUCT_ID, '한정 상품', 10000);
                   INSERT INTO stock (product_id, quantity, version) VALUES ($PRODUCT_ID, $STOCK, 0);"
done
ps -axo pcpu,etime,command | grep -E 'genpage|k6 |uvicorn' | grep -v grep > "$OUT/ps-before.txt" || true

snap() {   # label
  for k in "${!NAMES[@]}"; do
    DB_PORT=$DB_PORT DB_NAME="sr_$(echo "${NAMES[$k]}" | tr 'A-Z' 'a-z')" INITIAL_STOCK=$STOCK PRODUCT_ID=$PRODUCT_ID \
      uv run --quiet --with pymysql python3 tools/stock_reservation_eval.py snapshot "$OUT" "${NAMES[$k]}" "$1"
  done
}

PIDS=()
for k in "${!NAMES[@]}"; do
  k6 run --quiet --summary-export "$OUT/k6-${NAMES[$k]}.json" -e BASE_URL="http://localhost:${PORTS[$k]}" \
    -e PRODUCT_ID=$PRODUCT_ID -e RATE=$RATE -e DURATION=$DURATION -e ABANDON=$ABANDON -e UNKNOWN=$UNKNOWN \
    k6/flash-sale-stock.js > "$OUT/k6-${NAMES[$k]}.txt" 2>&1 &
  PIDS+=($!)
done
for p in "${PIDS[@]}"; do wait "$p" || true; done
snap sale-end
for t in $(seq 60 60 "$OBSERVE"); do sleep 60; snap "t$t"; done
snap final
INITIAL_STOCK=$STOCK python3 tools/stock_reservation_eval.py report "$OUT"
