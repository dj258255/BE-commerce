#!/usr/bin/env bash
# 홈 다음 쪽의 GenPage 입력 세 변형을 앱 경로로 잰다(#270).
#
#   PY=/path/to/genpage-venv/bin/python bash tools/run-genpage-next-page.sh
#
# 전제: compose 의 mysql·redis 가 떠 있고, personalization/data 에 H&M 데이터와 저장된 GenPage 모델이 있다.
# 심은 주문은 끝나면 지운다(id 9,200,000,000 이상). 가입한 회원(np-*@load.test)은 남는다.
set -euo pipefail

PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-personalization/data}
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
PORT=${PORT:-18090}; BASE="http://localhost:$PORT"
OUT=${OUT:-personalization/docs/runs/$(date +%Y%m%d)-genpage-next-page}
mkdir -p "$OUT"

APP=""; MODEL=""
stop() { for p in $APP $MODEL; do kill "$p" 2>/dev/null && wait "$p" 2>/dev/null || true; done; APP=""; MODEL=""; }
trap 'stop; "$PY" tools/genpage_next_page_eval.py cleanup' EXIT

start_app() {   # $1 이름, 나머지는 앱 인자
  local name=$1; shift
  APP_PERSONALIZATION_TRANSPORT=IN_REQUEST "$JAVA" -jar "$JAR" --spring.docker.compose.enabled=false \
    --server.port="$PORT" --app.ratelimit.enabled=false --app.recommendation.model.kind=genpage "$@" \
    > "$OUT/app-$name.log" 2>&1 &
  APP=$!
  for _ in $(seq 1 180); do curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
}

"$PY" tools/genpage_next_page_eval.py prepare "$OUT"

"$PY" personalization/serving/genpage_server.py 8765 > "$OUT/model-server.log" 2>&1 &
MODEL=$!
for _ in $(seq 1 120); do curl -sf http://localhost:8765/health >/dev/null 2>&1 && break; sleep 1; done

start_app signup
[ -f "$OUT/members.json" ] || "$PY" tools/genpage_next_page_eval.py signup "$OUT" "$BASE"
kill "$APP"; wait "$APP" 2>/dev/null || true; APP=""
"$PY" tools/genpage_next_page_eval.py seed "$OUT"

for NAME in v0 v1 v2; do
  case $NAME in
    v0) ARGS=(--app.recommendation.history-source=activity) ;;
    v1) ARGS=(--app.recommendation.history-source=purchases --app.recommendation.page-history=purchases) ;;
    v2) ARGS=(--app.recommendation.history-source=purchases --app.recommendation.page-history=session-then-purchases) ;;
  esac
  start_app "$NAME" "${ARGS[@]}"
  "$PY" tools/genpage_next_page_eval.py run "$OUT" "$NAME" "$BASE"
  kill "$APP"; wait "$APP" 2>/dev/null || true; APP=""
done

"$PY" tools/genpage_next_page_eval.py offline "$OUT" v1
"$PY" tools/genpage_next_page_eval.py report "$OUT" | tee "$OUT/report-table.md"
