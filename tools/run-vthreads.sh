#!/usr/bin/env bash
# 가상 스레드를 켜면 PG 동시 호출 상한이 필요 없어지는가(#392).
#
#   bash tools/run-vthreads.sh
#   CONDITIONS="VT1-L0:true:0" DUR=20s bash tools/run-vthreads.sh   # 짧게 시험
#
# 조건은 "이름:가상스레드(true|false):상한(0 이면 없음)"이다. 조건마다 기존 하네스(tools/run-pg-brownout.sh)를 그대로
# 부르고 옆에서 1초마다 PG 로 나간 승인 호출 · Hikari · 힙 · 플랫폼 스레드 · RSS 를 적는다. 일회용 MySQL(tmpfs) · Redis 를
# 쓰고 조건마다 DB 를 새로 만든다. 가상 스레드 고정(pinning)은 -Djdk.tracePinnedThreads=short 로 앱 로그에 남긴다.
set -euo pipefail

OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-vthreads}
CONDITIONS=${CONDITIONS:-"VT0-L0:false:0 VT0-L40:false:40 VT1-L0:true:0 VT1-L40:true:40"}
LAT=${LAT:-3000}; RATE=${RATE:-50}; DUR=${DUR:-60s}; RTO=${RTO:-5000}
DB_PORT=${DB_PORT:-13392}; REDIS_PORT=${REDIS_PORT:-16392}; PORT=${PORT:-18392}
mkdir -p "$OUT"

df -h / | tail -1 > "$OUT/df-before.txt"
docker run -d --name vt-mysql --tmpfs /var/lib/mysql:rw,size=2g -e MYSQL_ROOT_PASSWORD=root -p "$DB_PORT":3306 \
  mysql:8.4 --skip-log-bin --innodb-buffer-pool-size=256M --max-connections=400 >/dev/null
docker run -d --name vt-redis -p "$REDIS_PORT":6379 redis:7.4-alpine >/dev/null
SAMPLER=""
cleanup() {
  [ -n "$SAMPLER" ] && kill "$SAMPLER" 2>/dev/null || true
  docker rm -f -v vt-mysql vt-redis >/dev/null 2>&1 || true
}
trap cleanup EXIT

MYSQL="docker exec -i vt-mysql mysql -uroot -proot"
for _ in $(seq 1 90); do
  [ "$(docker logs vt-mysql 2>&1 | grep -c 'ready for connections')" -ge 2 ] && $MYSQL -e "SELECT 1" >/dev/null 2>&1 && break
  sleep 2
done

sample() {   # 파일. 앱이 뜨면 1초마다, 앱이 내려가면 끝
  local f=$1 base="http://localhost:$PORT" pid
  echo "t,pg_inflight,hikari_active,hikari_pending,heap_bytes,platform_threads,rss_kb" > "$f"
  until curl -sf "$base/actuator/health" >/dev/null 2>&1; do sleep 0.5; done
  pid=$(lsof -tiTCP:"$PORT" -sTCP:LISTEN | head -1)
  while kill -0 "$pid" 2>/dev/null; do
    curl -s "$base/actuator/prometheus" 2>/dev/null | python3 -c '
import sys
v = {"pg": "", "ha": "", "hp": "", "heap": 0.0, "th": ""}
for line in sys.stdin:
    if line.startswith("#"):
        continue
    name, _, val = line.rpartition(" ")
    if name.startswith("payment_pg_approval_inflight"): v["pg"] = val.strip()
    elif name.startswith("hikaricp_connections_active"): v["ha"] = val.strip()
    elif name.startswith("hikaricp_connections_pending"): v["hp"] = val.strip()
    elif name.startswith("jvm_memory_used_bytes") and "area=\"heap\"" in name: v["heap"] += float(val)
    elif name.startswith("jvm_threads_live_threads"): v["th"] = val.strip()
print(",".join([v["pg"], v["ha"], v["hp"], "%.0f" % v["heap"], v["th"]]))' > "$f.row" || true
    echo "$(date +%s),$(cat "$f.row" 2>/dev/null),$(ps -o rss= -p "$pid" 2>/dev/null | tr -d ' ')" >> "$f"
    sleep 1
  done
  rm -f "$f.row"
}

for c in $CONDITIONS; do
  IFS=: read -r name vt limit <<< "$c"
  db="vt_$(echo "$name" | tr 'A-Z-' 'a-z_')"
  $MYSQL -e "CREATE DATABASE $db" 2>/dev/null
  if lsof -tiTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then echo "포트 $PORT 를 이미 쓰고 있다" >&2; exit 1; fi
  sample "$OUT/sample-$name.csv" &
  SAMPLER=$!
  SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$DB_PORT/$db?serverTimezone=UTC&characterEncoding=UTF-8" \
  SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=root SPRING_DATA_REDIS_PORT=$REDIS_PORT \
  JAVA_TOOL_OPTIONS="-Djdk.tracePinnedThreads=short" \
  PORT=$PORT OUT="$OUT/$name" EXTRA="--spring.threads.virtual.enabled=$vt" \
  MYSQL="$MYSQL -N -B $db" \
    bash tools/run-pg-brownout.sh "$LAT" "$RATE" "$DUR" "$RTO" "$limit" > "$OUT/run-$name.log" 2>&1 || echo "실패: $name" >&2
  wait "$SAMPLER" 2>/dev/null || true
  SAMPLER=""
  $MYSQL -N -B "$db" -e "SELECT status, COUNT(*) FROM payments GROUP BY status" > "$OUT/payments-$name.tsv" 2>/dev/null || true
done

python3 tools/vthreads_report.py "$OUT" | tee "$OUT/report.md"
