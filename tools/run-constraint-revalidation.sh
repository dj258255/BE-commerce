#!/usr/bin/env bash
# E4 실측 드라이버 — 제약을 언제 다시 확인하는가.
#
# 정책 4종(확인 없음 / 생성 시작 / 생성 완료 후 / 응답 직전)을 같은 부하에서 돌리고,
# **생성 중에 사실이 바뀌는 상황**을 flip 시나리오로 주입한다.
#
# 사용:
#   bash tools/run-constraint-revalidation.sh
#   POLICIES="NONE AFTER_GENERATION" FLIP_RATES="8" DURATION=30s bash ...
#
# 전제: docker compose up -d mysql redis · ./gradlew bootJar
#
# 설계 요점:
#  - 앱은 정책별로 재기동한다(정책이 프로퍼티라 기동 시 고정된다). 실험 계기도 함께 켠다.
#  - 런 시작 전에 restock 으로 가용성을 초기화한다 — 앞 런의 품절이 넘어오면 비교가 안 된다.
#  - 포트가 비워지길 기다린다(이전 프로세스가 죽는 중에 health 가 통과하는 함정은 E1 에서 겪었다).
set -euo pipefail

cd "$(dirname "$0")/.."

STAMP=$(date +%Y%m%d-%H%M%S)
OUT=${OUT_DIR:-"personalization/docs/runs/${STAMP}-e4-제약-재검증"}
RAW="$OUT/raw"
mkdir -p "$RAW"

POLICIES=${POLICIES:-"NONE AT_GENERATION_START AFTER_GENERATION AT_RESPONSE"}
FLIP_RATES=${FLIP_RATES:-"8"}
DURATION=${DURATION:-60s}
WARMUP_MS=${WARMUP_MS:-15000}
RATE=${RATE:-30}
VUS=${VUS:-20}
# 품절 집합 크기 — 이 값을 **고정**해야 NONE 의 위반율이 정책 효과로 읽힌다.
# 이전에는 소진·해제를 따로 돌려 이 크기가 통제되지 않았고, 그래서 순서가 뒤집혔다(리포트 '틀렸던 것' 1).
SOLD_OUT_TARGET=${SOLD_OUT_TARGET:-6}
PORT=${PORT:-18080}
BASE="http://localhost:${PORT}"
SETTLE_SECONDS=${SETTLE_SECONDS:-6}
JAR=build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"

command -v k6 >/dev/null || { echo "k6 가 없다"; exit 1; }
[ -f "$JAR" ] || { echo "$JAR 가 없다 — ./gradlew bootJar 를 먼저 돌려라"; exit 1; }

APP=""
cleanup() { [ -n "$APP" ] && kill "$APP" 2>/dev/null || true; APP=""; }
trap cleanup EXIT

wait_port_free() {
  for _ in $(seq 1 40); do
    lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1 || return 0
    sleep 1
  done
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN | xargs -r kill -9 2>/dev/null || true
  sleep 2
}

start_app() {
  local policy=$1
  cleanup
  wait_port_free
  APP_RATELIMIT_ENABLED=false \
  APP_RECOMMENDATION_CONSTRAINT_POLICY="$policy" \
  APP_RECOMMENDATION_EXPERIMENT_ENABLED=true \
  "$JAVA" -jar "$JAR" \
    --spring.docker.compose.enabled=false \
    --server.port="$PORT" > "$LOG" 2>&1 &
  APP=$!
  for _ in $(seq 1 120); do
    if curl -sf "$BASE/actuator/health" >/dev/null 2>&1; then
      sleep "$SETTLE_SECONDS"
      curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && return 0
    fi
    kill -0 "$APP" 2>/dev/null || { echo "앱이 죽었다 — $LOG"; return 1; }
    sleep 1
  done
  echo "앱이 안 떴다 — $LOG"; return 1
}

echo "== E4 실측 시작"
echo "== 출력: $OUT"
echo "== 정책: $POLICIES / 변화율: $FLIP_RATES / 품절 고정: ${SOLD_OUT_TARGET} / ${DURATION} @ ${RATE}req/s"

for policy in $POLICIES; do
  for flip in $FLIP_RATES; do
    dir="$RAW/${policy}-flip${flip}"
    mkdir -p "$dir"
    LOG="$dir/app.log"
    echo "-- $policy · 변화 ${flip}/s"
    start_app "$policy" || continue

    # 가용성 초기화 — 앞 런의 품절이 넘어오면 비교가 안 된다.
    TOKEN=$(curl -s -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
      -d '{"username":"1","password":"user-local-only"}' \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])' 2>/dev/null || echo "")
    if [ -n "$TOKEN" ]; then
      curl -s -o /dev/null -X POST "$BASE/api/v1/experiments/constraint/restock" -H "Authorization: Bearer $TOKEN" || true
      # 품절 집합 크기를 K 로 고정한다. 이후 변화는 swap 이라 크기가 유지된다.
      PRIMED=$(curl -s -X POST "$BASE/api/v1/experiments/constraint/prime?count=${SOLD_OUT_TARGET}" \
        -H "Authorization: Bearer $TOKEN" | python3 -c 'import sys,json;print(json.load(sys.stdin)["unavailableCount"])' 2>/dev/null || echo "?")
      echo "   품절 고정: ${PRIMED} (목표 ${SOLD_OUT_TARGET})"
    else
      echo "  (경고: 데모 계정 로그인 실패 — restock/prime 을 못 했다)"
    fi

    CONSTRAINT_POLICY="$policy" BASE_URL="$BASE" DURATION="$DURATION" RATE="$RATE" \
    VUS="$VUS" FLIP_RATE="$flip" WARMUP_MS="$WARMUP_MS" SOLD_OUT_TARGET="$SOLD_OUT_TARGET" \
    k6 run --summary-export "$dir/summary.json" \
      --summary-trend-stats='avg,min,med,max,p(90),p(95),p(99)' \
      k6/constraint-revalidation.js > "$dir/k6.txt" 2>&1 || true

    tail -3 "$dir/k6.txt" | grep '^\[E4\]' || true

    cat > "$dir/meta.txt" <<EOF
constraint_policy=$policy
flip_rate=$flip
sold_out_target=$SOLD_OUT_TARGET
duration=$DURATION
rate=$RATE
vus=$VUS
warmup_ms=$WARMUP_MS
EOF

    cleanup
  done
done

echo
python3 tools/constraint_report.py "$RAW" | tee "$OUT/summary-table.md"
echo
echo "== 원자료: $RAW"
