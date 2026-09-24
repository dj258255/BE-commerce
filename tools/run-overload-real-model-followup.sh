#!/usr/bin/env bash
# #271 후속 측정. run-overload-real-model.sh 뒤에 돈다(같은 OUT 의 activity-items.json · capacity.json 을 쓴다).
# 무엇을 왜 재는지는 이슈 #271 두 번째 댓글에 측정 전에 적었다.
#
#   Y     /page 가 추천 행과 같은 자리(4)를 쓴다(page-capacity=shared). 추천 행 300/s + 2쪽 1Cp · 2Cp
#   대조  모델 없이(model.kind=stub) 추천 행 300/s + 2쪽 1Cp — 무너짐이 2쪽의 앱 경로 때문인지 가른다
#   A′    OBSERVED 150/s(무효 실행의 절반) · 300/s(재현 확인)
set -euo pipefail
cd "$(dirname "$0")/.."

PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-personalization/data}
OUT=${OUT:-personalization/docs/runs/$(date +%Y%m%d)-overload-real-model}
ITEMS="$(cd "$OUT" && pwd)/activity-items.json"
export PORT=${PORT:-18091}
read -r P1 P2 < <(python3 -c "
import json; d = json.load(open('$OUT/capacity.json'))
print(round(d['Cp']), round(2 * d['Cp']))")

MODEL=""
trap '[ -n "$MODEL" ] && kill "$MODEL" 2>/dev/null; wait 2>/dev/null || true' EXIT
"$PY" personalization/serving/genpage_server.py 8765 > "$OUT/model-server-followup.log" 2>&1 &
MODEL=$!
for _ in $(seq 1 120); do curl -sf http://localhost:8765/health >/dev/null 2>&1 && break; sleep 1; done

for PR in $P1 $P2; do
  APP_RECOMMENDATION_MODEL_PAGE_CAPACITY=shared POLICIES=ADMISSION ADMISSION_ESTIMATE=OBSERVED MODEL_KIND=genpage \
    ACTIVITY_ITEMS="$ITEMS" RATES=300 PAGE_RATE="$PR" OUT_DIR="$OUT/Y-shared-page-$PR" bash tools/run-inference-overload.sh
done
POLICIES=ADMISSION ADMISSION_ESTIMATE=OBSERVED MODEL_KIND=stub ACTIVITY_ITEMS="$ITEMS" RATES=300 PAGE_RATE="$P1" \
  OUT_DIR="$OUT/control-stub-page-$P1" bash tools/run-inference-overload.sh
POLICIES=ADMISSION ADMISSION_ESTIMATE=OBSERVED MODEL_KIND=genpage ACTIVITY_ITEMS="$ITEMS" RATES="150 300" \
  OUT_DIR="$OUT/A-OBSERVED-rerun" bash tools/run-inference-overload.sh

python3 tools/overload_real_model_report.py "$OUT" | tee "$OUT/report-table.md"
