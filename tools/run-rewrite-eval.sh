#!/usr/bin/env bash
# 검색어 고치기(#260)를 끈 앱과 켠 앱에 같은 쿼리를 흘린다. 엔진은 기본값(Lucene).
set -euo pipefail
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
PORT=${PORT:-18090}; BASE="http://localhost:$PORT"
OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-query-rewrite}
mkdir -p "$OUT"
APP=""
stop() { [ -n "$APP" ] && kill "$APP" 2>/dev/null && wait "$APP" 2>/dev/null || true; APP=""; }
trap stop EXIT
PYRUN=(uv run --quiet --with pymysql python3)
[ -f "$OUT/rewrite-queries.json" ] || "${PYRUN[@]}" tools/search/rewrite_eval.py queries "$OUT"
for NAME in off on; do
  ON=false; [ "$NAME" = on ] && ON=true
  "$JAVA" -jar "$JAR" --server.port="$PORT" --app.ratelimit.enabled=false --app.web.browse-shed.enabled=false \
    --app.catalog.search.rewrite.enabled="$ON" > "$OUT/app-$NAME.log" 2>&1 &
  APP=$!
  for _ in $(seq 1 180); do curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
  "${PYRUN[@]}" tools/search/rewrite_eval.py run "$OUT" "$BASE" "$NAME"
  stop
done
python3 tools/search/rewrite_eval.py report "$OUT" | tee "$OUT/report.md"
