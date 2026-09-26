#!/usr/bin/env bash
# 조회 재시도가 일시 장애를 흡수하는지 PG 호출을 부풀리는지(#334). 설정마다 앱을 하나씩 띄워 동시에 잰다.
#
#   RATES="0.01 0.05" WINDOW=600 bash tools/run-retry-budget.sh
#   RATES="0.1 0.3" WINDOW=1200 bash tools/run-retry-budget.sh
#
# 실패율 × 조회 시도 횟수(ATTEMPTS)마다 인스턴스 하나. 인스턴스마다 DB 와 Redis 번호를 따로 쓴다. 기본은 compose 의 mysql(3306, root/root).
set -euo pipefail

JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-retry-budget}
DB_PORT=${DB_PORT:-3306}; DB_PREFIX=${DB_PREFIX:-rt}; REDIS_PORT=${REDIS_PORT:-6379}; PORT_BASE=${PORT_BASE:-18091}
MYSQL_ROOT=${MYSQL_ROOT:-"docker exec -i pay-mysql-1 mysql -uroot -proot"}
RATES=${RATES:-"0.01 0.05"}; ATTEMPTS=${ATTEMPTS:-"1 2 3"}; N=${N:-1000}; WINDOW=${WINDOW:-600}; READ_RATE=${READ_RATE:-20}
mkdir -p "$OUT"

NAMES=(); PORTS=(); APPS=()
stop_all() { for p in "${APPS[@]}"; do kill "$p" 2>/dev/null || true; done; wait 2>/dev/null || true; }
trap stop_all EXIT

i=0
for f in $RATES; do
  for m in $ATTEMPTS; do
    name="f${f}-m${m}"; port=$((PORT_BASE + i)); db="${DB_PREFIX}$i"
    if lsof -tiTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then echo "포트 $port 를 이미 쓰고 있다. PORT_BASE 를 바꾼다" >&2; exit 1; fi
    $MYSQL_ROOT -e "DROP DATABASE IF EXISTS $db; CREATE DATABASE $db;" 2>/dev/null
    SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$DB_PORT/$db?serverTimezone=UTC&characterEncoding=UTF-8" \
    SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=root \
    SPRING_DATA_REDIS_PORT=$REDIS_PORT SPRING_DATA_REDIS_DATABASE=$i \
      "$JAVA" -Xmx512m -jar "$JAR" --server.port="$port" --app.ratelimit.enabled=false \
      --app.recovery.enabled=true --app.recovery.interval-ms=5000 --app.batch.read-chunk-size=50 --app.recovery.policy=backoff \
      --payment.fake-pg.query-fail-prefix=rq-fail- --payment.fake-pg.query-fail-rate="$f" --payment.pg.query-max-attempts="$m" \
      > "$OUT/app-$name.log" 2>&1 &
    APPS+=($!); NAMES+=("$name"); PORTS+=("$port"); i=$((i + 1))
  done
done
for port in "${PORTS[@]}"; do
  for _ in $(seq 1 180); do curl -sf "http://localhost:$port/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
done

PIDS=()
for k in "${!NAMES[@]}"; do
  name=${NAMES[$k]}; port=${PORTS[$k]}
  DB_PORT=$DB_PORT DB_NAME="${DB_PREFIX}$k" uv run --quiet --with pymysql python3 tools/retry_budget_eval.py \
    run "$OUT" "$name" "$port" "$N" "$WINDOW" &
  PIDS+=($!)
  k6 run --quiet --summary-export "$OUT/k6-$name.json" -e BASE_URL="http://localhost:$port" -e RATE="$READ_RATE" \
    -e DURATION="${WINDOW}s" k6/read-steady.js > "$OUT/k6-$name.txt" 2>&1 &
  PIDS+=($!)
done
for p in "${PIDS[@]}"; do wait "$p" || true; done
python3 tools/retry_budget_eval.py report "$OUT" | tee "$OUT/report.md"
