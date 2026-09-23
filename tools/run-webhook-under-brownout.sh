#!/usr/bin/env bash
# #230 — PG 가 느릴 때 웹훅이 토스 10초 규약을 지키는가.
#
# 웹훅만 재면 이 문제가 안 보인다. 컨트롤러는 PG 를 안 부르지만 <b>톰캣 워커를 결제와 공유</b>하므로,
# PG 가 느려 워커가 묶이면 웹훅도 같은 큐에 선다. 그래서 결제 부하와 같이 밀어 넣는다.
#
# 사용: tools/run-webhook-under-brownout.sh [결제도착률] [웹훅도착률] [지속]
set -euo pipefail

PAY_RATE=${1:-50}
HOOK_RATE=${2:-10}
DUR=${3:-45s}
LATS=${LATS:-"0 3000 5000"}
LIMITS=${LIMITS:-"0 40"}

JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
PORT=${PORT:-18080}
BASE="http://localhost:${PORT}"
MYSQL="docker exec pay-mysql-1 mysql -N -B -ubecommerce -pbecommerce becommerce"
STAMP=$(date +%Y%m%d-%H%M%S)
OUT="docs/performance/runs/${STAMP}-webhook-brownout-pay${PAY_RATE}-hook${HOOK_RATE}"
mkdir -p "$OUT"

echo "== 웹훅 × 브라운아웃: 지연 [$LATS] × 상한 [$LIMITS] · 결제 ${PAY_RATE}/s · 웹훅 ${HOOK_RATE}/s · ${DUR}"
{ echo "pay_rate=$PAY_RATE hook_rate=$HOOK_RATE dur=$DUR lats=[$LATS] limits=[$LIMITS]"
  echo "host $(uname -sm) $(sysctl -n hw.ncpu) cores"; echo "commit $(git rev-parse --short HEAD)"; } > "$OUT/meta.txt"

hooks() { $MYSQL -e "SELECT COUNT(*) FROM webhook_events" 2>/dev/null | tr -d '[:space:]'; }

for lat in $LATS; do
  for lim in $LIMITS; do
    d="$OUT/lat${lat}-lim${lim}"; mkdir -p "$d"
    echo "---- 지연 ${lat}ms · 상한 ${lim}"
    "$JAVA" -jar "$JAR" --server.port="$PORT" \
      --payment.fake-pg.approve-latency-ms="$lat" \
      --payment.fake-pg.read-timeout-ms=5000 \
      --payment.pg.max-concurrent-calls="$lim" \
      --app.ratelimit.enabled=false \
      --server.tomcat.mbeanregistry.enabled=true \
      > "$d/app.log" 2>&1 &
    APP=$!
    for _ in $(seq 1 120); do curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
    if ! curl -sf "$BASE/actuator/health" >/dev/null 2>&1; then
      echo "   앱이 안 떴다"; kill "$APP" 2>/dev/null || true; continue; fi

    BEFORE=$(hooks)
    # 워밍업 — 기동 직후 첫 요청은 JIT 가 섞여 p95 가 튄다(E1·ADR-022 에서 겪었다).
    k6 run --quiet -e BASE_URL="$BASE" -e PAY_RATE=10 -e HOOK_RATE=5 -e DURATION=15s \
      k6/webhook-under-brownout.js > "$d/k6-warmup.txt" 2>&1 || true
    k6 run --summary-export "$d/summary.json" \
      --summary-trend-stats='avg,min,med,max,p(90),p(95),p(99)' \
      -e BASE_URL="$BASE" -e PAY_RATE="$PAY_RATE" -e HOOK_RATE="$HOOK_RATE" -e DURATION="$DUR" \
      k6/webhook-under-brownout.js > "$d/k6.txt" 2>&1 || true
    AFTER=$(hooks)
    # 200 을 줬다고 저장된 것이 아니다 — DB 로 확인한다.
    { echo "lat=$lat limit=$lim"; echo "webhook_rows_before=$BEFORE"; echo "webhook_rows_after=$AFTER";
      echo "webhook_rows_added=$((AFTER - BEFORE))"; } > "$d/meta.txt"
    curl -s "$BASE/actuator/prometheus" | grep -E "^tomcat_threads_busy|^hikaricp_connections_active" > "$d/prom.txt" 2>/dev/null || true
    kill "$APP" 2>/dev/null || true
    for _ in $(seq 1 60); do lsof -ti tcp:"$PORT" >/dev/null 2>&1 || break; sleep 1; done
    echo "   끝 — 웹훅 저장 $((AFTER - BEFORE))건"
  done
done
echo "== 끝: $OUT"
