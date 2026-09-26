#!/usr/bin/env bash
# X3(#317) — 생성하는 동안 품절되면: 재고 확인 방식 네 가지 × 품절 속도 R.
#
#   MODEL_CMD="python3 personalization/serving/genpage_server.py 8765" MODEL_URL=http://localhost:8765 \
#     bash tools/run-v2-stock-race.sh
#
# 왜 방식마다 앱을 다시 띄우나: 방식은 기동 설정이고, 실행 중에 바꾸면 그 사이 요청이 섞인다(E3·E4·M7 과 같은 이유).
# 모델 서버는 환경변수로 받는다. 기본은 v2(personalization/serving/genpage2_server.py, 8766)이고 그 파일이
# 아직 없으면 v1(serving/genpage_server.py, 8765)으로 떨어진다 — #317 은 v2 서버가 준비 중에 만든 하네스다.
# MODEL_CMD="" 로 주면 서버를 띄우지 않는다(이미 떠 있는 것을 쓸 때).
#
# 1차 수정 — 품절이 노출 상품에 닿지 않았다:
#   1. 품절 후보를 DB 가 아니라 **모델이 실제로 보여 주는 상품**에서 고른다. 같은 앱(POST, 품절 없음)으로
#      CANDIDATE_WARMUP 초 부하를 흘려 노출 빈도 상위 PRODUCT_LIMIT 개를 파일로 남기고, 모든 방식 · 속도가
#      그 목록을 함께 쓴다.
#   2. 품절 주입은 워밍업이 끝난 **뒤** 측정과 함께 시작한다(x3_stock_race.py 가 직접 띄운다) — 워밍업 전에
#      시작하면 높은 속도에서 측정이 시작하기도 전에 후보가 다 팔린다.
#   3. 조건이 끝나면 원래 수량으로 되돌리고 **복원을 검증**한다(다음 조건이 이미 품절된 상태로 시작하지 않게).
set -euo pipefail
cd "$(dirname "$0")/.."

STAMP=$(date +%Y%m%d-%H%M%S)
OUT=${OUT_DIR:-"personalization/docs/runs/${STAMP}-x3-품절-경합"}
RAW="$OUT/raw"
mkdir -p "$RAW"

MODES=${MODES:-"NONE POST PRE POST_FINAL"}
# 품절 속도 R (/s). 0 은 대조군. 후보 400개 기준 60초에 R=3 ≈ 45%, R=10 ≈ 100% 가 팔린다.
RATES=${RATES:-"0 3 10"}
USERS=${USERS:-8}
RATE=${RATE:-20}                         # 초당 홈 반복 수(1쪽+2쪽)
DURATION=${DURATION:-60}
WARMUP=${WARMUP:-8}
CANDIDATE_WARMUP=${CANDIDATE_WARMUP:-60} # 품절 후보를 고르는 부하(초) — 품절은 주입하지 않는다
PORT=${PORT:-18080}
BASE="http://localhost:${PORT}"
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
PY=${PY:-python3}
PRODUCT_LIMIT=${PRODUCT_LIMIT:-400}      # 품절 후보 수(노출 빈도 상위)

# 모델 서버 — 파일이 있으면 v2, 없으면 v1.
if [ -z "${MODEL_CMD+x}" ]; then
  if [ -f personalization/serving/genpage2_server.py ]; then
    MODEL_CMD="$PY personalization/serving/genpage2_server.py 8766"
    MODEL_URL=${MODEL_URL:-http://localhost:8766}
  else
    MODEL_CMD="$PY personalization/serving/genpage_server.py 8765"
    MODEL_URL=${MODEL_URL:-http://localhost:8765}
    echo "== 경고: personalization/serving/genpage2_server.py 가 없다 — v1 서버로 돈다(품질이 아니라 방식을 잰다)"
  fi
fi
MODEL_URL=${MODEL_URL:-http://localhost:8765}

[ -f "$JAR" ] || { echo "$JAR 가 없다 — ./gradlew -p commerce bootJar 를 먼저 돌려라"; exit 1; }

APP=""; MODEL=""
stop_app() { [ -n "$APP" ] && kill "$APP" 2>/dev/null || true; APP=""; }
cleanup() {
  stop_app
  [ -n "$MODEL" ] && kill "$MODEL" 2>/dev/null || true
  MODEL=""
}
trap cleanup EXIT

wait_port_free() {
  for _ in $(seq 1 40); do
    lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1 || return 0
    sleep 1
  done
  lsof -nP -tiTCP:"$PORT" -sTCP:LISTEN | xargs -r kill -9 2>/dev/null || true
  sleep 2
}

start_model() {
  [ -z "$MODEL_CMD" ] && { echo "== 모델 서버를 띄우지 않는다(MODEL_CMD 가 비었다) — $MODEL_URL 을 쓴다"; return 0; }
  echo "== 모델 서버: $MODEL_CMD"
  $MODEL_CMD > "$OUT/model.log" 2>&1 &
  MODEL=$!
  for _ in $(seq 1 180); do
    curl -s -o /dev/null -X POST "$MODEL_URL/page" -H 'Content-Type: application/json' \
      -d '{"history":[],"exclude":[],"exclude_categories":[],"rows":1,"items_per_row":1,"prefix":0}' \
      && { sleep 2; return 0; }
    kill -0 "$MODEL" 2>/dev/null || { echo "모델 서버가 죽었다 — $OUT/model.log"; return 1; }
    sleep 1
  done
  echo "모델 서버가 안 떴다 — $OUT/model.log"; return 1
}

start_app() {
  local mode=$1 log=$2
  stop_app
  wait_port_free
  APP_RATELIMIT_ENABLED=false \
  APP_RECOMMENDATION_STOCK_CHECK="$mode" \
  APP_RECOMMENDATION_MODEL_KIND=genpage \
  APP_RECOMMENDATION_MODEL_GENPAGE_URL="$MODEL_URL" \
  APP_RECOMMENDATION_HISTORY_SOURCE=purchases \
  APP_PERSONALIZATION_TRANSPORT=IN_REQUEST \
  "$JAVA" -jar "$JAR" \
    --spring.docker.compose.enabled=false \
    --server.port="$PORT" > "$log" 2>&1 &
  APP=$!
  for _ in $(seq 1 180); do
    curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && { sleep 6; return 0; }
    kill -0 "$APP" 2>/dev/null || { echo "앱이 죽었다 — $log"; return 1; }
    sleep 1
  done
  echo "앱이 안 떴다 — $log"; return 1
}

echo "== 인프라(mysql · redis)"
docker compose up -d mysql redis >/dev/null 2>&1 || echo "  (경고: docker compose up 실패 — 이미 떠 있는 것을 쓴다)"

start_model || exit 1

# ── 품절 후보 — **모델이 실제로 보여 주는 상품**에서 고른다(1번 요구).
#    같은 앱(POST, 품절 없음)으로 부하를 흘려 응답에 담긴 상품의 노출 빈도를 세고 상위 PRODUCT_LIMIT 개를 남긴다.
PRODUCTS_FILE="$OUT/sellout-candidates.txt"
echo "== 품절 후보 수집 — POST · 품절 없음 · ${CANDIDATE_WARMUP}초 · 상위 ${PRODUCT_LIMIT}개"
start_app POST "$OUT/candidates-app.log" || exit 1
$PY tools/x3_stock_race.py --app "$BASE" --name candidates --mode POST \
  --users "$USERS" --rate "$RATE" --duration "$CANDIDATE_WARMUP" --warmup "$WARMUP" \
  --candidates-out "$PRODUCTS_FILE" --candidates-limit "$PRODUCT_LIMIT" \
  --out "$OUT/candidates.json" > "$OUT/candidates.log" 2>&1 \
  || { echo "품절 후보 수집 실패 — $OUT/candidates.log"; exit 1; }
stop_app
[ -s "$PRODUCTS_FILE" ] || { echo "품절 후보가 비었다 — $OUT/candidates.log"; exit 1; }
CANDIDATE_COUNT=$(wc -l < "$PRODUCTS_FILE" | tr -d ' ')
echo "== 품절 후보 ${CANDIDATE_COUNT}개 — 노출 빈도 상위 · $PRODUCTS_FILE"

echo "== X3 실측 시작 — 방식 [$MODES] × 품절 R [$RATES]/s · 홈 ${RATE}/s · ${DURATION}초"
echo "== 모델 $MODEL_URL · 출력 $OUT"

for mode in $MODES; do
  for rate in $RATES; do
    dir="$RAW/${mode}-r${rate}"
    mkdir -p "$dir"
    echo "-- $mode · 품절 ${rate}/s"
    start_app "$mode" "$dir/app.log" || exit 1

    SELLOUT_STATE="$dir/sellout-state.json"
    # 품절 주입은 x3_stock_race.py 가 워밍업 뒤에 띄운다(2번 요구) — 여기서 미리 띄우지 않는다.
    $PY tools/x3_stock_race.py --app "$BASE" --name "${mode}-r${rate}" --mode "$mode" \
      --candidates "$PRODUCTS_FILE" \
      --sellout-rate "$rate" --sellout-products-file "$PRODUCTS_FILE" --sellout-state "$SELLOUT_STATE" \
      --users "$USERS" --rate "$RATE" --duration "$DURATION" --warmup "$WARMUP" \
      --out "$dir/race.json" > "$dir/race.log" 2>&1 || echo "   (race 실패 — $dir/race.log)"

    # 조건이 끝나면 원래 수량으로 되돌리고 **검증**한다(3번 요구).
    RESTORE="없음(대조군 · 품절 안 함)"
    if [ "$rate" != "0" ]; then
      if $PY tools/x3_sellout.py --restore --verify --state "$SELLOUT_STATE" > "$dir/restore.log" 2>&1; then
        RESTORE="검증 통과"
      else
        RESTORE="실패 — $dir/restore.log"
        echo "   !! 복원 실패 — 다음 조건이 이미 품절된 상태로 시작할 수 있다($dir/restore.log)"
      fi
      tail -1 "$dir/restore.log" || true
    fi

    cat > "$dir/meta.txt" <<EOF
mode=$mode
sellout_rate=$rate
users=$USERS
rate=$RATE
duration=$DURATION
warmup=$WARMUP
candidate_warmup=$CANDIDATE_WARMUP
candidates=$CANDIDATE_COUNT
candidates_file=$PRODUCTS_FILE
restore=$RESTORE
model_url=$MODEL_URL
model_cmd=$MODEL_CMD
EOF
    tail -2 "$dir/race.log" || true
  done
  stop_app
done

echo
$PY tools/x3_report.py "$RAW" | tee "$OUT/summary-table.md"
echo "== 원자료: $RAW"
