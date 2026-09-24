#!/usr/bin/env bash
# ADR-067 의 다시 볼 조건(#284): 2쪽 앱 용량이 오른 뒤, 실제 GenPage 모델로 page-capacity none 대 shared 를 다시 잰다.
#
#   PY=/path/to/genpage-venv/bin/python ITEMS=/path/activity-items.json bash tools/run-page2-shared-capacity.sh
set -euo pipefail
cd "$(dirname "$0")/.."
PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
ITEMS=${ITEMS:?활동 상품 목록 JSON 경로를 ITEMS 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-personalization/data}
OUT=${OUT:-personalization/docs/runs/$(date +%Y%m%d)-page2-capacity/shared}
export PORT=${PORT:-18091}
MODEL=""
trap '[ -n "$MODEL" ] && kill "$MODEL" 2>/dev/null; wait 2>/dev/null || true' EXIT
for PR in ${PAGE_RATES:-90 120}; do
  for CAP in none shared; do
    # 조건마다 모델 서버를 새로 띄운다. 자리 없이 무너진 실행 뒤에는 서버가 버려진 요청을 계속 계산해
    # 다음 조건의 앱 기동이 200초를 넘겼다(첫 시도)
    [ -n "$MODEL" ] && { kill "$MODEL" 2>/dev/null; wait "$MODEL" 2>/dev/null || true; }
    "$PY" personalization/serving/genpage_server.py 8765 > "$OUT.$CAP-$PR.model-server.log" 2>&1 &
    MODEL=$!
    for _ in $(seq 1 120); do curl -sf http://localhost:8765/health >/dev/null 2>&1 && break; sleep 1; done
    APP_RECOMMENDATION_MODEL_PAGE_CAPACITY=$CAP POLICIES=ADMISSION ADMISSION_ESTIMATE=OBSERVED MODEL_KIND=genpage \
      ACTIVITY_ITEMS="$ITEMS" RATES=150 PAGE_RATE="$PR" OUT_DIR="$OUT/$CAP-page-$PR" bash tools/run-inference-overload.sh \
      > "$OUT.$CAP-$PR.log" 2>&1 || true
    grep -h '^\[\(E3\|PAGE\)\]' "$OUT/$CAP-page-$PR/raw/summary.txt" 2>/dev/null | sed "s/^/$CAP page=$PR /" || echo "$CAP page=$PR 결과 없음"
  done
done
