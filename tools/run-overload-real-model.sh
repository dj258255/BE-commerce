#!/usr/bin/env bash
# 과부하 게이트를 실제 GenPage 모델 서버로 다시 잰다(#271). 판정 기준은 이슈에 측정 전에 적었다.
#
#   PY=/path/to/genpage-venv/bin/python bash tools/run-overload-real-model.sh
#
# 모델 서버는 기본값이 v1 이다(#316 에서 매개변수화했다) — MODEL_CMD · MODEL_URL · MODEL_VOCAB 로 바꾼다.
#
# 0) 모델 서버 용량(C · Lm · Cp) → A′) 추천 행 300 · 600/s, CONFIGURED 대 OBSERVED → B′) 추천 행 300/s + 홈 2쪽 1Cp · 2Cp, OBSERVED
#
# 처음 설계(A 2C · 4C)는 앱이 먼저 포화해 무효가 됐다 — C 가 999/s 라 앱이 받을 수 있는 부하보다 컸다. 수정한 설계는 이슈 #271 댓글에 먼저 적었다.
# 전제: compose 의 mysql·redis 가 떠 있고, personalization/data 에 H&M 데이터와 저장된 GenPage 모델이 있다.
set -euo pipefail
cd "$(dirname "$0")/.."

PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-personalization/data}
OUT=${OUT:-personalization/docs/runs/$(date +%Y%m%d)-overload-real-model}
mkdir -p "$OUT"
ITEMS="$(cd "$OUT" && pwd)/activity-items.json"
export PORT=${PORT:-18091}

# 모델 서버를 무엇으로 띄우고 어디로 부르나(#316). 기본값은 v1 이고, v2 는 run-v2-overload.sh 가 바꾼다.
MODEL_CMD=${MODEL_CMD:-"$PY personalization/serving/genpage_server.py 8765"}
MODEL_URL=${MODEL_URL:-http://localhost:8765}
MODEL_VOCAB=${MODEL_VOCAB:-$GENPAGE_DATA/hm/model/genpage/vocab.json}

MODEL=""
trap '[ -n "$MODEL" ] && kill "$MODEL" 2>/dev/null; wait 2>/dev/null || true' EXIT

# 활동에 쓸 상품: 모델 어휘 안 · 재고 있음. 400개를 id 순서로 고르게 뽑는다(무작위가 아니라 재현되게).
# 어휘 형식 차이(v1 `items` · v2 `tokens`)는 tools/genpage_activity_items.py 가 흡수한다(#316).
"$PY" tools/genpage_activity_items.py --vocab "$MODEL_VOCAB" --out "$ITEMS"

eval "$MODEL_CMD" > "$OUT/model-server.log" 2>&1 &
MODEL=$!
for _ in $(seq 1 120); do curl -sf "$MODEL_URL/health" >/dev/null 2>&1 && break; sleep 1; done

[ -f "$OUT/capacity.json" ] || python3 tools/genpage_capacity.py "$ITEMS" "$OUT/capacity.json" 20 --url "$MODEL_URL" | tee "$OUT/capacity.txt"
read -r P1 P2 < <(python3 -c "
import json; d = json.load(open('$OUT/capacity.json'))
print(round(d['Cp']), round(2 * d['Cp']))")
REC_RATES=${REC_RATES:-"300 600"}
echo "== 추천 행 $REC_RATES /s · 2쪽 1Cp=$P1 2Cp=$P2 /s"

for EST in ${ESTIMATES:-CONFIGURED OBSERVED}; do
  POLICIES=ADMISSION ADMISSION_ESTIMATE=$EST MODEL_KIND=genpage MODEL_URL="$MODEL_URL" \
    ACTIVITY_ITEMS="$ITEMS" RATES="$REC_RATES" \
    OUT_DIR="$OUT/A-$EST" bash tools/run-inference-overload.sh
done
for PR in $P1 $P2; do
  POLICIES=ADMISSION ADMISSION_ESTIMATE=OBSERVED MODEL_KIND=genpage MODEL_URL="$MODEL_URL" \
    ACTIVITY_ITEMS="$ITEMS" RATES=300 PAGE_RATE="$PR" \
    OUT_DIR="$OUT/B-page-$PR${B_SUFFIX:-}" bash tools/run-inference-overload.sh
done

python3 tools/overload_real_model_report.py "$OUT" | tee "$OUT/report-table.md"
