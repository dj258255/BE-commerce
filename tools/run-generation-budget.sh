#!/usr/bin/env bash
# E5 — 생성 범위·계산 예산. **범위 × 부하**를 돌려 "전체 예산에서 모델이 차지하는 몫"을 낸다.
#
# 왜 범위마다 앱을 다시 띄우나: 범위는 기동 설정이다(모델 지연이 범위로 정해지고, 과부하 정책의
# 대기 예상도 그 지연을 본다). 실험 중에 바꾸면 그 사이 요청이 섞인다.
#
# 왜 부하 단계도 두나: 범위가 느려지면 **처리량**(동시성/지연)이 줄어든다. 낮은 부하에서는 셋 다
# 개인화를 지키지만, 용량을 넘기는 순간 어느 범위가 먼저 무엇을 포기하는지가 갈린다 —
# 그 갈림이 "범위를 넓히면 무엇을 내주는가"의 답이다.
#
# 사용:
#   ./gradlew bootJar
#   bash tools/run-generation-budget.sh
set -euo pipefail
cd "$(dirname "$0")/.."

STAMP=$(date +%Y%m%d-%H%M%S)
OUT=${OUT_DIR:-personalization/docs/runs/${STAMP}-e5-생성-범위-예산}
RAW="$OUT/raw"
mkdir -p "$RAW"

# 범위 3종 = E5 의 독립변수. 부하 2단계 = 랭킹 용량(80/s)의 아래·위.
SCOPES=${SCOPES:-"RANKING PREFIX_AR_TOP_K FULL_AR"}
RATES=${RATES:-"30 80"}
DURATION=${DURATION:-40s}
WARMUP_MS=${WARMUP_MS:-10000}
ACCOUNTS=${ACCOUNTS:-8}
MAX_VUS=${MAX_VUS:-200}
PORT=${PORT:-18080}
BASE="http://localhost:${PORT}"
JAR=build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"

# 범위와 무관하게 고정하는 것들 — 리포트에 함께 적어야 수치를 읽을 수 있다.
MODEL_CONCURRENCY=${MODEL_CONCURRENCY:-4}
MODEL_LATENCY_MS=${MODEL_LATENCY_MS:-50}
AR_PREFIX=${AR_PREFIX:-4}
PER_ITEM_MS=${PER_ITEM_MS:-15}
RESULT_SIZE=${RESULT_SIZE:-12}
POLICY=${POLICY:-ADMISSION}                 # E3 의 기본값을 고정한다(변수를 하나만 움직인다)
ADMISSION_BUDGET_MS=${ADMISSION_BUDGET_MS:-100}
BUSY_TIMEOUT_MS=${BUSY_TIMEOUT_MS:-400}
CONSTRAINT_POLICY=${CONSTRAINT_POLICY:-AFTER_GENERATION}   # E4 의 기본값을 고정한다

[ -f "$JAR" ] || { echo "$JAR 가 없다 — ./gradlew bootJar 를 먼저 돌려라"; exit 1; }
command -v k6 >/dev/null || { echo "k6 가 없다"; exit 1; }

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
  local scope=$1 log=$2
  cleanup
  wait_port_free
  APP_RATELIMIT_ENABLED=false \
  APP_RECOMMENDATION_ITEM_POOL=EXPERIMENT \
  APP_RECOMMENDATION_POLICY="$POLICY" \
  APP_RECOMMENDATION_ADMISSION_BUDGET_MS="$ADMISSION_BUDGET_MS" \
  APP_RECOMMENDATION_CONSTRAINT_POLICY="$CONSTRAINT_POLICY" \
  APP_RECOMMENDATION_MODEL_CONCURRENCY="$MODEL_CONCURRENCY" \
  APP_RECOMMENDATION_MODEL_LATENCY_MS="$MODEL_LATENCY_MS" \
  APP_RECOMMENDATION_MODEL_BUSY_TIMEOUT_MS="$BUSY_TIMEOUT_MS" \
  APP_RECOMMENDATION_RESULT_SIZE="$RESULT_SIZE" \
  APP_RECOMMENDATION_GENERATION_SCOPE="$scope" \
  APP_RECOMMENDATION_GENERATION_AR_PREFIX="$AR_PREFIX" \
  APP_RECOMMENDATION_GENERATION_PER_ITEM_MS="$PER_ITEM_MS" \
  "$JAVA" -jar "$JAR" \
    --spring.docker.compose.enabled=false \
    --server.port="$PORT" > "$log" 2>&1 &
  APP=$!
  for _ in $(seq 1 120); do
    curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && { sleep 5; return 0; }
    kill -0 "$APP" 2>/dev/null || { echo "앱이 죽었다 — $log"; return 1; }
    sleep 1
  done
  echo "앱이 안 떴다 — $log"; return 1
}

echo "== E5 실측 시작"
echo "== 모델: ${MODEL_CONCURRENCY}동시 · 기준 ${MODEL_LATENCY_MS}ms · AR 항목당 ${PER_ITEM_MS}ms · 결과 ${RESULT_SIZE}개"
echo "== 후보 집합: EXPERIMENT(합성 풀) — 하네스가 명시한다"
echo "== 범위: $SCOPES / 부하: $RATES req/s / 과부하 정책: $POLICY (고정) / 제약: $CONSTRAINT_POLICY (고정)"
echo "== 출력: $OUT"

for scope in $SCOPES; do
  mkdir -p "$RAW/$scope"
  echo "-- $scope"
  start_app "$scope" "$RAW/$scope/app.log" || exit 1

  for rate in $RATES; do
    echo "   $scope @ ${rate} req/s"
    GENERATION_SCOPE="$scope" BASE_URL="$BASE" RATE="$rate" DURATION="$DURATION" WARMUP_MS="$WARMUP_MS" \
      ACCOUNTS="$ACCOUNTS" MAX_VUS="$MAX_VUS" \
      k6 run k6/generation-budget.js \
        --summary-export "$RAW/$scope/summary-${rate}.json" \
        > "$RAW/$scope/k6-${rate}.txt" 2>&1 || true
    grep '^\[E5\]' "$RAW/$scope/k6-${rate}.txt" | tee -a "$RAW/summary.txt" || true
  done

  cat > "$RAW/$scope/meta.txt" <<EOF
generation_scope=$scope
model_concurrency=$MODEL_CONCURRENCY
model_latency_ms=$MODEL_LATENCY_MS
ar_prefix=$AR_PREFIX
per_item_ms=$PER_ITEM_MS
result_size=$RESULT_SIZE
policy=$POLICY
admission_budget_ms=$ADMISSION_BUDGET_MS
constraint_policy=$CONSTRAINT_POLICY
EOF
  cleanup
done

echo
python3 tools/generation_report.py "$RAW" | tee "$OUT/summary-table.md"
echo "== 원자료: $RAW"
