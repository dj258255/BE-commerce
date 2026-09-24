#!/usr/bin/env bash
# #271 마지막 측정(이슈 세 번째 댓글에 측정 전에 적었다).
#   1) 2쪽 앱 용량 P: 모델 없이(stub), 추천 행 1/s, 2쪽 30 · 60 · 90/s → dropped 0 인 가장 큰 값
#   2) P 에서 page-capacity none 대 shared(genpage, 추천 행 150/s)
set -euo pipefail
cd "$(dirname "$0")/.."

PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-personalization/data}
OUT=${OUT:-personalization/docs/runs/$(date +%Y%m%d)-overload-real-model}
ITEMS="$(cd "$OUT" && pwd)/activity-items.json"
export PORT=${PORT:-18091}

clean() {   # 두 k6 줄 모두 dropped=0 인가
  ! grep -E '^\[(E3|PAGE)\]' "$1/raw/summary.txt" | grep -qv 'dropped=0$'
}

P=""
for PR in 30 60 90; do
  POLICIES=ADMISSION ADMISSION_ESTIMATE=OBSERVED MODEL_KIND=stub ACTIVITY_ITEMS="$ITEMS" RATES=1 PAGE_RATE="$PR" \
    OUT_DIR="$OUT/P-stub-page-$PR" bash tools/run-inference-overload.sh
  if clean "$OUT/P-stub-page-$PR"; then P=$PR; else break; fi
done
[ -n "$P" ] || { echo "2쪽 30/s 에서도 dropped 가 생겼다 — P 를 못 정했다"; exit 1; }
echo "== P = $P/s"

MODEL=""
trap '[ -n "$MODEL" ] && kill "$MODEL" 2>/dev/null; wait 2>/dev/null || true' EXIT
"$PY" personalization/serving/genpage_server.py 8765 > "$OUT/model-server-final.log" 2>&1 &
MODEL=$!
for _ in $(seq 1 120); do curl -sf http://localhost:8765/health >/dev/null 2>&1 && break; sleep 1; done

for CAP in none shared; do
  APP_RECOMMENDATION_MODEL_PAGE_CAPACITY=$CAP POLICIES=ADMISSION ADMISSION_ESTIMATE=OBSERVED MODEL_KIND=genpage \
    ACTIVITY_ITEMS="$ITEMS" RATES=150 PAGE_RATE="$P" OUT_DIR="$OUT/Z-$CAP-page-$P" bash tools/run-inference-overload.sh
done

python3 tools/overload_real_model_report.py "$OUT" | tee "$OUT/report-table.md"
