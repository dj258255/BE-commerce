#!/usr/bin/env bash
# 백오프 간격의 대가와 PG 전면 장애의 조회 수(#330). 설정마다 앱을 하나씩 띄워 동시에 잰다(가벼운 부하, 복구 배치만 돈다).
#
#   bash tools/run-recovery-backoff.sh
#
# 인스턴스마다 DB 를 따로 쓴다(DB_PREFIX0 ~ 5). 기본은 compose 의 mysql(3306, root/root). 일회용 DB 는 DB_PORT 로 바꾼다.
set -euo pipefail

JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-recovery-backoff}
DB_PORT=${DB_PORT:-3306}; DB_PREFIX=${DB_PREFIX:-rb}; REDIS_PORT=${REDIS_PORT:-6379}
MYSQL_ROOT=${MYSQL_ROOT:-"docker exec -i pay-mysql-1 mysql -uroot -proot"}
PORT_BASE=${PORT_BASE:-18081}; ONLY=${ONLY:-}
STEP=${STEP:-6}; WINDOW=${WINDOW:-1200}; OUTAGE_S=${OUTAGE_S:-300}; OUTAGE_N=${OUTAGE_N:-1000}; OUTAGE_WINDOW=${OUTAGE_WINDOW:-900}
mkdir -p "$OUT"

# 이름 · 첫 간격 · 상한 · 막힌 건 · 가짜 PG 인자
RUNS=(
  "baseline 1m 10m 0 --payment.fake-pg.query-in-progress-prefix=rq-stuck-"
  "cur-1m-10m 1m 10m 100 --payment.fake-pg.query-in-progress-prefix=rq-stuck- --payment.fake-pg.query-in-progress-release-step-ms=${STEP}000"
  "cap-1m-2m 1m 2m 100 --payment.fake-pg.query-in-progress-prefix=rq-stuck- --payment.fake-pg.query-in-progress-release-step-ms=${STEP}000"
  "base-15s-10m 15s 10m 100 --payment.fake-pg.query-in-progress-prefix=rq-stuck- --payment.fake-pg.query-in-progress-release-step-ms=${STEP}000"
  "base-4m-10m 4m 10m 100 --payment.fake-pg.query-in-progress-prefix=rq-stuck- --payment.fake-pg.query-in-progress-release-step-ms=${STEP}000"
  "outage-1m-10m 1m 10m - --payment.fake-pg.query-fail-prefix=rq-fail- --payment.fake-pg.query-fail-for-ms=${OUTAGE_S}000"
)

APPS=()
stop_all() { for p in "${APPS[@]}"; do kill "$p" 2>/dev/null || true; done; wait 2>/dev/null || true; }
trap stop_all EXIT

i=0
for r in "${RUNS[@]}"; do
  read -r name base cap _ fake <<<"$r"
  db="${DB_PREFIX}$i"; port=$((PORT_BASE + i))
  if [ -n "$ONLY" ] && [ "$ONLY" != "$name" ]; then i=$((i + 1)); continue; fi
  if lsof -tiTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then echo "포트 $port 를 이미 쓰고 있다. PORT_BASE 를 바꾼다" >&2; exit 1; fi
  $MYSQL_ROOT -e "DROP DATABASE IF EXISTS $db; CREATE DATABASE $db;" 2>/dev/null
  # shellcheck disable=SC2086
  SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$DB_PORT/$db?serverTimezone=UTC&characterEncoding=UTF-8" \
  SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=root \
  SPRING_DATA_REDIS_PORT=$REDIS_PORT SPRING_DATA_REDIS_DATABASE=$i \
    "$JAVA" -Xmx512m -jar "$JAR" --server.port="$port" --app.ratelimit.enabled=false \
    --app.recovery.enabled=true --app.recovery.interval-ms=5000 --app.batch.read-chunk-size=50 --app.recovery.policy=backoff \
    --app.recovery.backoff-base="$base" --app.recovery.backoff-cap="$cap" $fake > "$OUT/app-$name.log" 2>&1 &
  APPS+=($!)
  i=$((i + 1))
done
i=0
for r in "${RUNS[@]}"; do
  read -r name _ <<<"$r"; port=$((PORT_BASE + i)); i=$((i + 1))
  [ -n "$ONLY" ] && [ "$ONLY" != "$name" ] && continue
  for _ in $(seq 1 180); do curl -sf "http://localhost:$port/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
done

PIDS=(); i=0
for r in "${RUNS[@]}"; do
  read -r name _ _ stuck _ <<<"$r"
  port=$((PORT_BASE + i))
  if [ -n "$ONLY" ] && [ "$ONLY" != "$name" ]; then i=$((i + 1)); continue; fi
  if [ "$stuck" = "-" ]; then
    DB_PORT=$DB_PORT DB_NAME="${DB_PREFIX}$i" uv run --quiet --with pymysql python3 tools/recovery_backoff_eval.py \
      outage "$OUT" "$name" "$port" "$OUTAGE_N" "$OUTAGE_WINDOW" &
  else
    DB_PORT=$DB_PORT DB_NAME="${DB_PREFIX}$i" uv run --quiet --with pymysql python3 tools/recovery_backoff_eval.py \
      release "$OUT" "$name" "$port" "$stuck" 500 "$STEP" "$WINDOW" &
  fi
  PIDS+=($!); i=$((i + 1))
done
for p in "${PIDS[@]}"; do wait "$p" || true; done
python3 tools/recovery_backoff_eval.py report "$OUT" | tee "$OUT/report.md"
