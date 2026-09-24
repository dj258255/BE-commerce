#!/usr/bin/env bash
# 미확정 복구의 읽는 순서 실측(#248). 정책마다 앱을 다시 띄워 같은 행을 넣고 풀리는 과정을 DB 에서 본다.
#
#   bash tools/run-recovery-order.sh [baseline] [unordered] [oldest] [backoff]     # 기본: 넷 다
#
# baseline 은 막히는 건 없이 풀리는 건만 넣는다(정책은 unordered). 전제: compose 의 mysql·redis 가 떠 있다.
set -euo pipefail

RUNS=("$@"); [ $# -eq 0 ] && RUNS=(baseline unordered oldest backoff)
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
PORT=${PORT:-18080}
OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-recovery-order}
STUCK=${STUCK:-100}; OK=${OK:-500}; WINDOW=${WINDOW:-300}
mkdir -p "$OUT"

APP=""
stop_app() { [ -n "$APP" ] && kill "$APP" 2>/dev/null && wait "$APP" 2>/dev/null || true; APP=""; }
trap stop_app EXIT

for R in "${RUNS[@]}"; do
  POLICY=$R; S=$STUCK
  [ "$R" = baseline ] && POLICY=unordered && S=0
  echo "== $R (policy=$POLICY stuck=$S ok=$OK)"
  "$JAVA" -jar "$JAR" --server.port="$PORT" --app.ratelimit.enabled=false \
    --app.recovery.enabled=true --app.recovery.interval-ms=5000 --app.batch.read-chunk-size=50 \
    --app.recovery.policy="$POLICY" --payment.fake-pg.query-in-progress-prefix=rq-stuck- \
    > "$OUT/app-$R.log" 2>&1 &
  APP=$!
  for _ in $(seq 1 180); do curl -sf "http://localhost:$PORT/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
  uv run --quiet --with pymysql python3 tools/recovery_order_eval.py run "$OUT" "$R" "$S" "$OK" "$WINDOW"
  stop_app
done

python3 tools/recovery_order_eval.py report "$OUT" | tee "$OUT/report.md"
