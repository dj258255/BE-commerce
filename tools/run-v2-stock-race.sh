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
set -euo pipefail
cd "$(dirname "$0")/.."

STAMP=$(date +%Y%m%d-%H%M%S)
OUT=${OUT_DIR:-"personalization/docs/runs/${STAMP}-x3-품절-경합"}
RAW="$OUT/raw"
mkdir -p "$RAW"

MODES=${MODES:-"NONE POST PRE POST_FINAL"}
RATES=${RATES:-"0 5 20"}                 # 품절 속도 R (/s). 0 은 품절을 주입하지 않는다(대조군)
USERS=${USERS:-8}
RATE=${RATE:-20}                         # 초당 홈 반복 수(1쪽+2쪽)
DURATION=${DURATION:-60}
WARMUP=${WARMUP:-8}
PORT=${PORT:-18080}
BASE="http://localhost:${PORT}"
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
PY=${PY:-python3}
MYSQL_CONTAINER=${MYSQL_CONTAINER:-pay-mysql-1}
PRODUCT_LIMIT=${PRODUCT_LIMIT:-400}

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

APP=""; MODEL=""; SELLOUT=""
stop_app() { [ -n "$APP" ] && kill "$APP" 2>/dev/null || true; APP=""; }
cleanup() {
  [ -n "$SELLOUT" ] && kill "$SELLOUT" 2>/dev/null || true
  stop_app
  [ -n "$MODEL" ] && kill "$MODEL" 2>/dev/null || true
  MODEL=""; SELLOUT=""
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

# 품절 후보 — 실제 카탈로그에서 재고가 있는 상품을 고른다. 홈은 카탈로그에 있는 상품만 그린다.
PRODUCTS_FILE="$OUT/products.txt"
docker exec "$MYSQL_CONTAINER" mysql -N -ubecommerce -pbecommerce becommerce \
  -e "select product_id from stock where quantity > 0 order by product_id limit $PRODUCT_LIMIT" 2>/dev/null \
  > "$PRODUCTS_FILE" || true
[ -s "$PRODUCTS_FILE" ] || { echo "품절 후보 상품을 DB 에서 못 읽었다(컨테이너 $MYSQL_CONTAINER)"; exit 1; }
echo "== 품절 후보 상품 $(wc -l < "$PRODUCTS_FILE" | tr -d ' ')개"

echo "== X3 실측 시작 — 방식 [$MODES] × 품절 R [$RATES]/s · 홈 ${RATE}/s · ${DURATION}초"
echo "== 모델 $MODEL_URL · 출력 $OUT"

start_model || exit 1

for mode in $MODES; do
  for rate in $RATES; do
    dir="$RAW/${mode}-r${rate}"
    mkdir -p "$dir"
    echo "-- $mode · 품절 ${rate}/s"
    start_app "$mode" "$dir/app.log" || exit 1

    # 런마다 재고를 초기화한다 — 앞 런의 품절이 넘어오면 비교가 안 된다.
    if [ "$rate" != "0" ]; then
      SELLOUT_STATE="$dir/sellout-state.json"
      $PY tools/x3_sellout.py --products-file "$PRODUCTS_FILE" --rate "$rate" \
        --state "$SELLOUT_STATE" > "$dir/sellout.log" 2>&1 &
      SELLOUT=$!
      sleep 2
    fi

    $PY tools/x3_stock_race.py --app "$BASE" --name "${mode}-r${rate}" --mode "$mode" \
      --sellout-rate "$rate" --users "$USERS" --rate "$RATE" --duration "$DURATION" --warmup "$WARMUP" \
      --out "$dir/race.json" > "$dir/race.log" 2>&1 || echo "   (race 실패 — $dir/race.log)"

    if [ -n "$SELLOUT" ]; then
      kill "$SELLOUT" 2>/dev/null || true
      SELLOUT=""
      $PY tools/x3_sellout.py --restore --state "$SELLOUT_STATE" > "$dir/restore.log" 2>&1 || true
    fi

    cat > "$dir/meta.txt" <<EOF
mode=$mode
sellout_rate=$rate
users=$USERS
rate=$RATE
duration=$DURATION
warmup=$WARMUP
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
