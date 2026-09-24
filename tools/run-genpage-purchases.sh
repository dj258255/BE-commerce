#!/usr/bin/env bash
# GenPage 를 구매 이력으로 서빙할 때의 품질을 앱 경로로 잰다(#254).
#
#   PY=/path/to/genpage-venv/bin/python bash tools/run-genpage-purchases.sh
#
# 전제: compose 의 mysql·redis 가 떠 있고, personalization/data 에 H&M 데이터와 저장된 GenPage 모델이 있다.
# 심은 주문은 끝나면 지운다(id 9,100,000,000 이상).
set -euo pipefail

PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-personalization/data}
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
PORT=${PORT:-18090}; BASE="http://localhost:$PORT"
OUT=${OUT:-personalization/docs/runs/$(date +%Y%m%d)-genpage-purchases}
mkdir -p "$OUT"

APP=""; MODEL=""
stop() { for p in $APP $MODEL; do kill "$p" 2>/dev/null && wait "$p" 2>/dev/null || true; done; APP=""; MODEL=""; }
trap 'stop; "$PY" tools/genpage_purchase_eval.py cleanup' EXIT

[ -f "$OUT/sample.json" ] || "$PY" tools/genpage_purchase_eval.py prepare "$OUT"
"$PY" tools/genpage_purchase_eval.py offline "$OUT"
"$PY" tools/genpage_purchase_eval.py seed "$OUT"

"$PY" personalization/serving/genpage_server.py 8765 > "$OUT/model-server.log" 2>&1 &
MODEL=$!
for _ in $(seq 1 120); do curl -sf http://localhost:8765/health >/dev/null 2>&1 && break; sleep 1; done

for NAME in model hybrid; do
  REPEAT=false; [ "$NAME" = hybrid ] && REPEAT=true
  "$JAVA" -jar "$JAR" --server.port="$PORT" --app.ratelimit.enabled=false \
    --app.recommendation.model.kind=genpage --app.recommendation.history-source=purchases \
    --app.recommendation.repeat-first="$REPEAT" --app.recommendation.experiment.enabled=true \
    > "$OUT/app-$NAME.log" 2>&1 &
  APP=$!
  for _ in $(seq 1 180); do curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
  "$PY" tools/genpage_purchase_eval.py serve "$OUT" "$NAME" "$BASE"
  kill "$APP"; wait "$APP" 2>/dev/null || true; APP=""
done

"$PY" tools/genpage_purchase_eval.py report "$OUT" | tee "$OUT/report-table.md"
