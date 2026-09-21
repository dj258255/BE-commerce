#!/usr/bin/env bash
# E6 — 캐시 값 크기와 압축 임계값. **코덱 × 값 크기**를 돌려 "몇 바이트부터 압축이 이득인가"를 낸다.
#
# 왜 코덱마다 앱을 다시 띄우나: 코덱은 **저장 형식을 정하는 값**이라 실행 중에 바꾸면 그 사이 값의
# 판이 섞인다(압축된 값과 원본이 같은 키 공간에 있으면 읽을 때 표식으로만 구분된다 — 되긴 하지만
# 실험이 아니라 복구 절차가 된다). 크기는 재기동 없이 바꾼다.
#
# 왜 값 개수를 크기마다 고정하나: 표본을 크기에 비례해 늘리면 큰 값 구간만 표본이 많아져 백분위가
# 비교되지 않는다. 대신 **개수를 줄여 메모리를 묶는다** — 512KB × 1000개면 500MB 라 로컬 Redis 가
# 버티지 못한다(그 사실도 리포트에 적는다).
#
# 사용:
#   ./gradlew bootJar
#   bash tools/run-cache-compression.sh
set -euo pipefail
cd "$(dirname "$0")/.."

STAMP=$(date +%Y%m%d-%H%M%S)
OUT=${OUT_DIR:-personalization/docs/runs/${STAMP}-e6-캐시-압축-임계값}
RAW="$OUT/raw"
mkdir -p "$RAW"

CODECS=${CODECS:-"NONE LZ4 SNAPPY"}
SIZES=${SIZES:-"1024 10240 51200 102400 512000"}
COUNT=${COUNT:-500}
THRESHOLD=${THRESHOLD:-0}          # 임계값 자체는 별도 실험이다 — 여기선 0(항상 압축)으로 코덱을 비교
PORT=${PORT:-18080}
BASE="http://localhost:${PORT}"
JAR=build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"

[ -f "$JAR" ] || { echo "$JAR 가 없다 — ./gradlew bootJar 를 먼저 돌려라"; exit 1; }

APP=""
cleanup() { [ -n "$APP" ] && kill "$APP" 2>/dev/null || true; APP=""; }
trap cleanup EXIT

wait_port_free() {
  for _ in $(seq 1 40); do
    lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1 || return 0
    sleep 1
  done
  lsof -nP -tiTCP:"$PORT" -sTCP:LISTEN | xargs -r kill -9 2>/dev/null || true
  sleep 2
}

start_app() {
  local codec=$1 log=$2
  cleanup
  wait_port_free
  APP_RATELIMIT_ENABLED=false \
  APP_PERSONALIZATION_CACHE_CODEC="$codec" \
  APP_PERSONALIZATION_CACHE_THRESHOLD="$THRESHOLD" \
  APP_PERSONALIZATION_EXPERIMENT_ENABLED=true \
  "$JAVA" -jar "$JAR" \
    --spring.docker.compose.enabled=false \
    --server.port="$PORT" > "$log" 2>&1 &
  APP=$!
  for _ in $(seq 1 120); do
    curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && { sleep 4; return 0; }
    kill -0 "$APP" 2>/dev/null || { echo "앱이 죽었다 — $log"; return 1; }
    sleep 1
  done
  echo "앱이 안 떴다 — $log"; return 1
}

echo "== E6 실측 시작"
echo "== 코덱: $CODECS / 크기: $SIZES B / 구간당 $COUNT 개 / 임계값: $THRESHOLD B"
echo "== 출력: $OUT"

for codec in $CODECS; do
  mkdir -p "$RAW/$codec"
  echo "-- $codec"
  start_app "$codec" "$RAW/$codec/app.log" || exit 1

  TOKEN=$(curl -s -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"1","password":"user-local-only"}' \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])' 2>/dev/null || echo "")
  [ -n "$TOKEN" ] || { echo "  로그인 실패 — 계기 엔드포인트를 부를 수 없다"; exit 1; }

  for size in $SIZES; do
    echo "   $codec @ ${size}B"
    curl -s -X POST "$BASE/api/v1/experiments/cache/bench?sizeBytes=${size}&count=${COUNT}" \
      -H "Authorization: Bearer $TOKEN" > "$RAW/$codec/bench-${size}.json"
    head -c 200 "$RAW/$codec/bench-${size}.json"; echo
  done

  cat > "$RAW/$codec/meta.txt" <<EOF
codec=$codec
threshold_bytes=$THRESHOLD
count=$COUNT
port=$PORT
EOF
  cleanup
done

echo
python3 tools/cache_compression_report.py "$RAW" | tee "$OUT/summary-table.md"
echo "== 원자료: $RAW"
