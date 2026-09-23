#!/usr/bin/env bash
# GenPage 서빙 실측(#238). 모델 서버를 띄워 직접 재고, 앱을 붙여 홈 2쪽을 규칙 행과 비교한다.
#   GENPAGE_PY=<torch 가 있는 python> GENPAGE_DATA=personalization/data bash tools/run-genpage-serving.sh
set -euo pipefail
cd "$(dirname "$0")/.."
PY=${GENPAGE_PY:?torch 가 설치된 python 경로}
USERS=${USERS:-30}
PORT=${PORT:-18080}
MODEL_PORT=${MODEL_PORT:-8765}
BASE="http://localhost:${PORT}"
MODEL="http://localhost:${MODEL_PORT}"
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-genpage-serving}
mkdir -p "$OUT"
APP="" SRV=""
cleanup() { for p in $APP $SRV; do kill "$p" 2>/dev/null || true; done; APP=""; }
trap 'cleanup; kill $SRV 2>/dev/null || true' EXIT

"$PY" personalization/serving/genpage_server.py "$MODEL_PORT" > "$OUT/model-server.log" 2>&1 &
SRV=$!
for _ in $(seq 1 60); do curl -sf "$MODEL/health" >/dev/null && break; sleep 1; done
echo "== 모델 서버 직접"
python3 tools/genpage_serving_eval.py server "$MODEL" "$OUT/server.json"

run_app() {   # $1 이름, $2 모델 종류, $3 prefix
  APP_RATELIMIT_ENABLED=false APP_PERSONALIZATION_TRANSPORT=IN_REQUEST \
  APP_RECOMMENDATION_MODEL_KIND="$2" APP_RECOMMENDATION_MODEL_GENPAGE_PREFIX="$3" \
    "$JAVA" -jar "$JAR" --spring.docker.compose.enabled=false --server.port="$PORT" \
    --app.recommendation.model.kind="$2" --app.recommendation.model.genpage-prefix="$3" > "$OUT/app-$1.log" 2>&1 &
  APP=$!
  for _ in $(seq 1 120); do curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
  sleep 3
  echo "== 홈 2쪽: $1"
  python3 tools/genpage_serving_eval.py home "$BASE" "$USERS" "$OUT/home-$1.json"
  kill "$APP"; wait "$APP" 2>/dev/null || true; APP=""
}
run_app rules stub 0
run_app genpage-prefix0 genpage 0
run_app genpage-prefix2 genpage 2
run_app genpage-prefix8 genpage 8
