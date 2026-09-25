#!/usr/bin/env bash
# #250 — 워커가 PG 가 아닌 이유(조회 몰림)로 마를 때 웹훅이 10초 규약을 지키는가.
#
# 사용: tools/run-webhook-under-browse.sh            # 조회 0·200·400·800·1600/s
#       BROWSE_RATES="800" EXTRA="--app.web.shedding.enabled=true" NAME=shed tools/run-webhook-under-browse.sh
# 전제: compose 의 mysql·redis 가 떠 있고 k6 가 있다.
set -euo pipefail

PAY_RATE=${PAY_RATE:-50}; HOOK_RATE=${HOOK_RATE:-10}; DUR=${DUR:-45s}; LAT=${LAT:-5000}
BROWSE_RATES=${BROWSE_RATES:-"0 200 400 800 1600"}
EXTRA=${EXTRA:-}; NAME=${NAME:-base}
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
PORT=${PORT:-18080}; BASE="http://localhost:${PORT}"
# 웹훅 저장 건수를 셀 DB. 일회용 스택에서 돌릴 때는 MYSQL 로 바꾼다(#329)
MYSQL=${MYSQL:-"docker exec pay-mysql-1 mysql -N -B -ubecommerce -pbecommerce becommerce"}
OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-webhook-under-browse}
mkdir -p "$OUT"
hooks() { $MYSQL -e "SELECT COUNT(*) FROM webhook_events" 2>/dev/null | tr -d '[:space:]'; }

APP=""; SAMPLER=""
stop_all() { [ -n "$SAMPLER" ] && kill "$SAMPLER" 2>/dev/null || true; [ -n "$APP" ] && kill "$APP" 2>/dev/null && wait "$APP" 2>/dev/null || true; APP=""; SAMPLER=""; }
trap stop_all EXIT

for B in $BROWSE_RATES; do
  d="$OUT/$NAME-browse$B"; mkdir -p "$d"
  echo "---- $NAME · 조회 ${B}/s · 결제 ${PAY_RATE}/s · 웹훅 ${HOOK_RATE}/s · PG ${LAT}ms"
  # shellcheck disable=SC2086
  "$JAVA" -jar "$JAR" --server.port="$PORT" --payment.fake-pg.approve-latency-ms="$LAT" \
    --payment.fake-pg.read-timeout-ms=5000 --app.ratelimit.enabled=false \
    --server.tomcat.mbeanregistry.enabled=true $EXTRA > "$d/app.log" 2>&1 &
  APP=$!
  for _ in $(seq 1 180); do curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
  k6 run --quiet -e BASE_URL="$BASE" -e PAY_RATE=10 -e HOOK_RATE=5 -e BROWSE_RATE=50 -e DURATION=15s \
    k6/webhook-under-browse.js > "$d/k6-warmup.txt" 2>&1 || true
  BEFORE=$(hooks)
  # 2초마다 워커·커넥션을 찍는다(끝의 한 번이 아니라 과정을 본다)
  ( while true; do
      echo "$(date +%s) $(curl -s -m 1 "$BASE/actuator/prometheus" | grep -E '^tomcat_threads_busy_threads|^hikaricp_connections_(active|pending)\{' | awk '{split($1,a,"{"); printf "%s=%s ", a[1], $NF}')"
      sleep 2; done ) > "$d/samples.txt" 2>/dev/null &
  SAMPLER=$!
  k6 run --summary-export "$d/summary.json" --summary-trend-stats='avg,med,max,p(90),p(95),p(99)' \
    -e BASE_URL="$BASE" -e PAY_RATE="$PAY_RATE" -e HOOK_RATE="$HOOK_RATE" -e BROWSE_RATE="$B" -e DURATION="$DUR" \
    k6/webhook-under-browse.js > "$d/k6.txt" 2>&1 || true
  AFTER=$(hooks)
  echo "webhook_rows_added=$((AFTER - BEFORE))" > "$d/meta.txt"
  stop_all
  for _ in $(seq 1 60); do lsof -ti tcp:"$PORT" >/dev/null 2>&1 || break; sleep 1; done
done
python3 tools/webhook_browse_report.py "$OUT" | tee "$OUT/report.md"
