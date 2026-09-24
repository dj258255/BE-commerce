#!/usr/bin/env bash
# 캐시 압축을 동시 요청 아래서 잰다(#266). E6(run-cache-compression.sh)과 같은 벤치를 스레드 1·8·32 로 돌린다.
#
#   bash tools/run-cache-concurrency.sh
# 전제: compose 의 mysql·redis 가 떠 있다.
set -euo pipefail
CODECS=${CODECS:-"NONE LZ4 SNAPPY"}
SIZES=${SIZES:-"1024 51200 512000"}
THREADS=${THREADS:-"1 8 32"}
COUNT=${COUNT:-200}
PORT=${PORT:-18090}; BASE="http://localhost:$PORT"
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
OUT=${OUT:-personalization/docs/runs/$(date +%Y%m%d)-cache-concurrency}
mkdir -p "$OUT"
APP=""
stop() { [ -n "$APP" ] && kill "$APP" 2>/dev/null && wait "$APP" 2>/dev/null || true; APP=""; }
trap stop EXIT
for codec in $CODECS; do
  APP_RATELIMIT_ENABLED=false APP_PERSONALIZATION_CACHE_CODEC="$codec" APP_PERSONALIZATION_CACHE_THRESHOLD=0 \
  APP_PERSONALIZATION_EXPERIMENT_ENABLED=true "$JAVA" -jar "$JAR" --server.port="$PORT" > "$OUT/app-$codec.log" 2>&1 &
  APP=$!
  for _ in $(seq 1 120); do curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
  sleep 3
  TOKEN=$(curl -s -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"1","password":"user-local-only"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
  # 워밍업: JIT 와 연결
  curl -s -X POST "$BASE/api/v1/experiments/cache/bench?sizeBytes=51200&count=300&threads=8" -H "Authorization: Bearer $TOKEN" >/dev/null
  for size in $SIZES; do
    for t in $THREADS; do
      curl -s -X POST "$BASE/api/v1/experiments/cache/bench?sizeBytes=$size&count=$COUNT&threads=$t" \
        -H "Authorization: Bearer $TOKEN" > "$OUT/$codec-$size-$t.json"
      echo "$codec ${size}B x$t $(head -c 160 "$OUT/$codec-$size-$t.json")"
    done
  done
  stop
done
python3 tools/cache_concurrency_report.py "$OUT" | tee "$OUT/report.md"
