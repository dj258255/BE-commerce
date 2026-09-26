#!/usr/bin/env bash
# X6(#353) — 2쪽 부하가 v2 /page 용량(Cp)을 넘을 때 자리 공유(#271)가 1쪽 추천 행을 지키나.
# 판정 기준은 personalization/docs/genpage-v2/BACKEND.md X6 절에 측정 전에 적었다.
#
#   ./gradlew -p commerce bootJar
#   PY=/path/to/genpage-venv/bin/python CKPT=<.../genpage2/validate/ckpt/b-base-full> bash tools/run-v2-page-overload.sh
#
# 추천 행은 0.25C 로 고정하고 2쪽을 0.5 · 1.0 · 1.5 Cp 로 올린다. 자리는 shared(기본) 대 none.
# 자리 설정은 앱 기동 설정이라 run-inference-overload.sh 가 띄우는 앱에 환경변수로 넘긴다.
set -euo pipefail
cd "$(dirname "$0")/.."

PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-personalization/data}
MODE=${MODE:-validate}
CKPT=${CKPT:?v2 체크포인트 디렉터리를 CKPT 로 준다}
OUT=${OUT:-personalization/docs/runs/$(date +%Y%m%d)-v2-x6-page-overload}
mkdir -p "$OUT"
ITEMS="$(cd "$OUT" && pwd)/activity-items.json"
export PORT=${PORT:-18091}
MODEL_PORT=${MODEL_PORT:-8766}
export MODEL_URL="http://localhost:${MODEL_PORT}"
REC_RATE=${REC_RATE:-6}                 # 0.25C (C 22.9/s, X2)
PAGE_RATES=${PAGE_RATES:-"8 16 25"}     # 0.5 · 1.0 · 1.5 Cp (Cp 16.5/s, X2)
CAPACITIES=${CAPACITIES:-"shared none"}

MODEL=""
cleanup() { [ -n "$MODEL" ] && kill "$MODEL" 2>/dev/null || true; MODEL=""; }
trap cleanup EXIT

"$PY" tools/genpage_activity_items.py --vocab "$GENPAGE_DATA/hm/model/genpage2/$MODE/vocab.json" --out "$ITEMS"
"$PY" personalization/serving/genpage2_server.py --ckpt "$CKPT" --mode "$MODE" --port "$MODEL_PORT" --device cpu \
  > "$OUT/model-server.log" 2>&1 &
MODEL=$!
for _ in $(seq 1 180); do curl -sf "$MODEL_URL/health" >/dev/null 2>&1 && break; sleep 1; done
curl -sf "$MODEL_URL/health" >/dev/null 2>&1 || { echo "모델 서버가 안 떴다 — $OUT/model-server.log"; exit 1; }

for CAP in $CAPACITIES; do
  for PR in $PAGE_RATES; do
    echo "== 자리 $CAP · 추천 행 ${REC_RATE}/s · 2쪽 ${PR}/s"
    APP_RECOMMENDATION_MODEL_PAGE_CAPACITY="$CAP" \
    POLICIES=ADMISSION ADMISSION_ESTIMATE=OBSERVED MODEL_KIND=genpage MODEL_URL="$MODEL_URL" \
      ACTIVITY_ITEMS="$ITEMS" RATES="$REC_RATE" PAGE_RATE="$PR" \
      OUT_DIR="$OUT/$CAP-page-$PR" bash tools/run-inference-overload.sh
  done
done

# 모델 서버가 앱이 포기한 요청까지 계산했는지(#271 의 BrokenPipeError)
echo "== 모델 서버 끊긴 연결: $(grep -c 'BrokenPipeError' "$OUT/model-server.log" || true)"
grep -h '^\[' "$OUT"/*/raw/summary.txt | tee "$OUT/summary.txt"
