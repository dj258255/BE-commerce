#!/usr/bin/env bash
# 한정 상품 판매 시작 순간의 대기열(#383). 조건마다 앱을 하나씩 띄워 같은 부하를 동시에 건다.
#
#   bash tools/run-flash-queue.sh
#   CONDITIONS="Q0:0 Q20L10:20:10" BUYERS=60 OBSERVE=30 bash tools/run-flash-queue.sh   # 짧게 시험
#
# 조건은 "이름:입장 인원[:입장 칸 만료 초]"이고 입장 인원 0 은 대기열 없음, 만료를 비우면 입장권 수명(600초)을 따른다.
# THINK 는 입장 뒤 주문까지 고민하는 최대 시간(초)이다. 재고 전략은 모두 AT_PAYMENT(한정 상품 설정).
# 일회용 MySQL(tmpfs) · Redis 를 띄우고 끝나면 볼륨까지 지운다. 추가 앱 인자는 APP_ARGS 로 넘긴다.
set -euo pipefail

JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-flash-queue}
CONDITIONS=${CONDITIONS:-"Q0:0:0 Q20L10:20:10 Q20L30:20:30 Q50L30:50:30"}
PORT_BASE=${PORT_BASE:-18091}; DB_PORT=${DB_PORT:-13383}; REDIS_PORT=${REDIS_PORT:-16383}
OBSERVE=${OBSERVE:-180}; STOCK=${STOCK:-100}; PRODUCT_ID=${PRODUCT_ID:-90374}
BUYERS=${BUYERS:-300}; RATE=${RATE:-100}; MAX_WAIT=${MAX_WAIT:-180}; APP_ARGS=${APP_ARGS:-}; THINK=${THINK:-20}
mkdir -p "$OUT"

df -h / | tail -1 > "$OUT/df-before.txt"
docker run -d --name fq-mysql --tmpfs /var/lib/mysql:rw,size=2g -e MYSQL_ROOT_PASSWORD=root -p "$DB_PORT":3306 \
  mysql:8.4 --skip-log-bin --innodb-buffer-pool-size=256M --max-connections=400 >/dev/null
docker run -d --name fq-redis -p "$REDIS_PORT":6379 redis:7.4-alpine >/dev/null

APPS=()
cleanup() {
  for p in ${APPS[@]+"${APPS[@]}"}; do kill "$p" 2>/dev/null || true; done
  wait 2>/dev/null || true
  docker rm -f -v fq-mysql fq-redis >/dev/null 2>&1 || true
}
trap cleanup EXIT

MYSQL="docker exec -i fq-mysql mysql -uroot -proot"
for _ in $(seq 1 90); do
  [ "$(docker logs fq-mysql 2>&1 | grep -c 'ready for connections')" -ge 2 ] && $MYSQL -e "SELECT 1" >/dev/null 2>&1 && break
  sleep 2
done

NAMES=(); PORTS=(); LIMITS=(); LEASES=(); i=0
for c in $CONDITIONS; do
  IFS=: read -r name limit lease <<< "$c"; lease=${lease:-0}; port=$((PORT_BASE + i)); db="fq_$(echo "$name" | tr 'A-Z' 'a-z')"
  case " ${NAMES[*]-} " in *" $name "*) echo "조건 이름 $name 이 겹친다(DB 이름으로 쓴다)" >&2; exit 1;; esac
  if lsof -tiTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then echo "포트 $port 를 이미 쓰고 있다" >&2; exit 1; fi
  $MYSQL -e "CREATE DATABASE $db" 2>/dev/null
  gate=""
  if [ "$limit" -gt 0 ]; then gate="--app.queue.gate.product-ids=$PRODUCT_ID --app.queue.admit-limit=$limit"; fi
  if [ "$lease" -gt 0 ]; then gate="$gate --app.queue.admission-lease-seconds=$lease"; fi
  # shellcheck disable=SC2086
  SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$DB_PORT/$db?serverTimezone=UTC&characterEncoding=UTF-8" \
  SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=root \
  SPRING_DATA_REDIS_PORT=$REDIS_PORT SPRING_DATA_REDIS_DATABASE=$i \
    "$JAVA" -Xmx512m -jar "$JAR" --server.port="$port" --app.ratelimit.enabled=false \
    --app.stock.reservation=AT_PAYMENT $gate $APP_ARGS \
    --app.recovery.enabled=true --app.checkout.recovery.enabled=true \
    --app.compensation.enabled=true --app.order.expiry.enabled=true \
    --payment.fake-pg.timeout-approved-prefix=unk-ok- --payment.fake-pg.timeout-lost-prefix=unk-lost- \
    > "$OUT/app-$name.log" 2>&1 &
  APPS+=($!); NAMES+=("$name"); PORTS+=("$port"); LIMITS+=("$limit"); LEASES+=("$lease"); i=$((i + 1))
done
for k in "${!NAMES[@]}"; do
  for _ in $(seq 1 180); do curl -sf "http://localhost:${PORTS[$k]}/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
  db="fq_$(echo "${NAMES[$k]}" | tr 'A-Z' 'a-z')"
  $MYSQL "$db" -e "INSERT INTO products (product_id, name, price) VALUES ($PRODUCT_ID, '한정 상품', 10000);
                   INSERT INTO stock (product_id, quantity, version) VALUES ($PRODUCT_ID, $STOCK, 0);"
done
ps -axo pcpu,etime,command | grep -E 'genpage|k6 |uvicorn' | grep -v grep > "$OUT/ps-before.txt" || true

PIDS=(); RUN=$(date +%s)
for k in "${!NAMES[@]}"; do
  q=0; [ "${LIMITS[$k]}" -gt 0 ] && q=1
  k6 run --quiet --log-format raw -e BASE_URL="http://localhost:${PORTS[$k]}" -e QUEUE=$q -e RUN="$RUN-$k" \
    -e PRODUCT_ID=$PRODUCT_ID -e BUYERS=$BUYERS -e RATE=$RATE -e MAX_WAIT=$MAX_WAIT -e THINK=$THINK \
    k6/flash-sale-queue.js > "$OUT/k6-${NAMES[$k]}.txt" 2>&1 &
  PIDS+=($!)
done
for p in "${PIDS[@]}"; do wait "$p" || true; done
sleep "$OBSERVE"
for k in "${!NAMES[@]}"; do
  DB_PORT=$DB_PORT DB_NAME="fq_$(echo "${NAMES[$k]}" | tr 'A-Z' 'a-z')" INITIAL_STOCK=$STOCK PRODUCT_ID=$PRODUCT_ID \
    uv run --quiet --with pymysql python3 tools/flash_queue_eval.py collect "$OUT" "${NAMES[$k]}" "${LIMITS[$k]}" "${LEASES[$k]}"
done
INITIAL_STOCK=$STOCK python3 tools/flash_queue_eval.py report "$OUT" | tee "$OUT/report.md"
