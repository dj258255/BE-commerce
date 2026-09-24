#!/usr/bin/env bash
# 홈 2쪽 앱 경로의 용량을 잰다(#284). 모델 없이(stub, 규칙 행), 추천 행 1/s 를 깔고 2쪽 부하를 올린다.
#
#   ITEMS=/path/activity-items.json OUT=... bash tools/run-page2-capacity.sh
#
# ITEMS 는 활동에 심을 상품 id 목록(JSON)이다. tools/run-overload-real-model.sh 가 만든 것을 쓴다(모델 어휘 안 · 재고 있음).
# 판정은 k6 dropped 다 — 0 이 아니면 그 부하를 앱이 받지 못한 것이다.
set -euo pipefail
cd "$(dirname "$0")/.."
ITEMS=${ITEMS:?활동 상품 목록 JSON 경로를 ITEMS 로 준다}
OUT=${OUT:-personalization/docs/runs/$(date +%Y%m%d)-page2-capacity}
export PORT=${PORT:-18091}
for R in ${RATES:-30 60 90 120 150}; do
  POLICIES=ADMISSION ADMISSION_ESTIMATE=OBSERVED MODEL_KIND=stub ACTIVITY_ITEMS="$ITEMS" RATES=1 PAGE_RATE="$R" \
    OUT_DIR="$OUT/page-$R" bash tools/run-inference-overload.sh > "$OUT.page-$R.log" 2>&1 || true
  grep -h '^\[PAGE\]' "$OUT/page-$R/raw/summary.txt" 2>/dev/null | sed "s/^/page=$R /" || echo "page=$R 결과 없음"
done
