#!/usr/bin/env bash
# E3 — 과부하 degradation. **정책 × 부하 단계**를 돌려 "SLO 를 지키려면 개인화를 얼마나 포기하는가"를
# 표로 남긴다.
#
# 왜 부하 단계인가: 정책 하나의 성능을 재는 것이 아니라 **교환비**를 내는 실험이기 때문이다.
# 유입을 올리면서 coverage 와 지연이 어디서 갈라지는지 봐야 "몇 개 큐가 정답"이 아니라
# "이 정책은 어느 부하까지 개인화를 지키는가"를 말할 수 있다.
#
# 왜 정책마다 앱을 다시 띄우나: 정책은 기동 설정이다(실험 중에 바꾸면 그 사이 요청이 섞인다).
# 부하 단계는 재기동 없이 바꾼다.
#
# 사용:
#   ./gradlew -p commerce bootJar
#   bash tools/run-inference-overload.sh
set -euo pipefail
cd "$(dirname "$0")/.."

STAMP=$(date +%Y%m%d-%H%M%S)
OUT=${OUT_DIR:-personalization/docs/runs/${STAMP}-e3-과부하-degradation}
RAW="$OUT/raw"
mkdir -p "$RAW"

POLICIES=${POLICIES:-"UNBOUNDED BOUNDED ADMISSION"}
RATES=${RATES:-"40 80 160 320"}      # 모델 용량 80/s(4동시/50ms) 기준 0.5× · 1× · 2× · 4×
DURATION=${DURATION:-40s}
WARMUP_MS=${WARMUP_MS:-10000}
ACCOUNTS=${ACCOUNTS:-8}       # 계정은 공유한다(순수 GET 이라 사용자별 상태가 없다)
MAX_VUS=${MAX_VUS:-200}       # 최악 지연에서 도착률을 채울 만큼 — 부족하면 dropped 가 0이 아니게 된다
PORT=${PORT:-18080}
BASE="http://localhost:${PORT}"
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"

# 모델 용량과 정책 파라미터 — 리포트에 함께 적어야 수치를 읽을 수 있다.
MODEL_CONCURRENCY=${MODEL_CONCURRENCY:-4}
MODEL_LATENCY_MS=${MODEL_LATENCY_MS:-50}
MAX_IN_FLIGHT=${MAX_IN_FLIGHT:-24}
ADMISSION_BUDGET_MS=${ADMISSION_BUDGET_MS:-100}
BUSY_TIMEOUT_MS=${BUSY_TIMEOUT_MS:-400}

[ -f "$JAR" ] || { echo "$JAR 가 없다 — ./gradlew -p commerce bootJar 를 먼저 돌려라"; exit 1; }
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
  local policy=$1 log=$2
  cleanup
  wait_port_free
  # 레이트리밋을 끈다 — 부하 실험의 변수는 정책이지 IP 제한이 아니다(한 장비에서 전부 같은 IP 다).
  APP_RATELIMIT_ENABLED=false \
  APP_RECOMMENDATION_POLICY="$policy" \
  APP_RECOMMENDATION_MAX_IN_FLIGHT="$MAX_IN_FLIGHT" \
  APP_RECOMMENDATION_ADMISSION_BUDGET_MS="$ADMISSION_BUDGET_MS" \
  APP_RECOMMENDATION_MODEL_CONCURRENCY="$MODEL_CONCURRENCY" \
  APP_RECOMMENDATION_MODEL_LATENCY_MS="$MODEL_LATENCY_MS" \
  APP_RECOMMENDATION_MODEL_BUSY_TIMEOUT_MS="$BUSY_TIMEOUT_MS" \
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

echo "== E3 실측 시작 (모델 ${MODEL_CONCURRENCY}동시/${MODEL_LATENCY_MS}ms = $(( MODEL_CONCURRENCY * 1000 / MODEL_LATENCY_MS ))/s)"
echo "== 정책: $POLICIES"
echo "== 부하: $RATES req/s (${DURATION}씩, 워밍업 ${WARMUP_MS}ms)"
echo "== 출력: $OUT"

for policy in $POLICIES; do
  mkdir -p "$RAW/$policy"
  echo "-- $policy"
  start_app "$policy" "$RAW/$policy/app.log" || exit 1

  for rate in $RATES; do
    echo "   $policy @ ${rate} req/s"
    # shellcheck disable=SC2086
    POLICY="$policy" BASE_URL="$BASE" RATE="$rate" DURATION="$DURATION" WARMUP_MS="$WARMUP_MS" \
      ACCOUNTS="$ACCOUNTS" MAX_VUS="$MAX_VUS" \
      k6 run k6/inference-overload.js \
        --summary-export "$RAW/$policy/summary-${rate}.json" \
        > "$RAW/$policy/k6-${rate}.txt" 2>&1 || true
    grep '^\[E3\]' "$RAW/$policy/k6-${rate}.txt" | tee -a "$RAW/summary.txt" || true
  done
  cleanup
done

echo
echo "== 조건별 요약"
python3 tools/degradation_report.py "$RAW" | tee "$OUT/summary-table.md"
echo "== 원자료: $RAW"
