#!/usr/bin/env bash
# #316 X2 — 무거워진 v2 모델 앞에서 백엔드가 버티나. 판정 기준은 personalization/docs/genpage-v2/BACKEND.md
# X2 절에 측정 전에 적었다.
#
#   ./gradlew -p commerce bootJar
#   PY=/path/to/genpage-venv/bin/python CKPT=<.../genpage2/<mode>/ckpt/<이름>> bash tools/run-v2-overload.sh
#
# 1) v2 모델 서버 용량: 닫힌 루프 동시성 1 · 2 · 4 (`/recommend`, `/page` prefix 2)
# 2) 그 용량(C = 동시성 4 처리량)의 0.5 · 1.0 · 1.5 · 2.0 배 부하에서
#    입장 제한 OBSERVED(ADMISSION) 켜기 · 끄기(UNBOUNDED)
#
# 앱은 MODEL_URL 을 APP_RECOMMENDATION_MODEL_GENPAGE_URL 로 받는다. 표는 v1(#271) 과 나란히 본다(BASELINE).
# 모델 서버가 MPS 를 쓰면 학습과 섞여 숫자가 흔들린다 — B 학습이 끝나 GPU 가 빈 뒤에 돌린다.
set -euo pipefail
cd "$(dirname "$0")/.."

PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-personalization/data}
MODE=${MODE:-validate}
CKPT=${CKPT:?GenPage v2 체크포인트 디렉터리를 CKPT 로 준다($GENPAGE_DATA/hm/model/genpage2/$MODE/ckpt/<이름>)}
OUT=${OUT:-personalization/docs/runs/20260925-v2-overload}
BASELINE=${BASELINE:-personalization/docs/runs/20260924-overload-real-model}
mkdir -p "$OUT"
ITEMS="$(cd "$OUT" && pwd)/activity-items.json"
export PORT=${PORT:-18091}

# 모델 서버를 무엇으로 띄우고 어디로 부르나(#316). 기본값이 v2 다.
export MODEL_URL=${MODEL_URL:-http://localhost:${MODEL_PORT:-8766}}
export MODEL_VOCAB=${MODEL_VOCAB:-$GENPAGE_DATA/hm/model/genpage2/$MODE/vocab.json}
export MODEL_CMD=${MODEL_CMD:-"$PY personalization/serving/genpage2_server.py --ckpt $CKPT --mode $MODE --port ${MODEL_PORT:-8766} --device ${DEVICE:-cpu}"}

MODEL=""
cleanup() { [ -n "$MODEL" ] && kill "$MODEL" 2>/dev/null || true; MODEL=""; }
trap cleanup EXIT

echo "== v2 모델 서버 $MODEL_URL (어휘 $MODEL_VOCAB)"
"$PY" tools/genpage_activity_items.py --vocab "$MODEL_VOCAB" --out "$ITEMS"

eval "$MODEL_CMD" > "$OUT/model-server.log" 2>&1 &
MODEL=$!
for _ in $(seq 1 180); do curl -sf "$MODEL_URL/health" >/dev/null 2>&1 && break; sleep 1; done
curl -sf "$MODEL_URL/health" >/dev/null 2>&1 || { echo "모델 서버가 안 떴다 — $OUT/model-server.log"; exit 1; }

python3 tools/genpage_capacity.py "$ITEMS" "$OUT/capacity.json" 20 --url "$MODEL_URL" --threads "1 2 4" \
  | tee "$OUT/capacity.txt"

read -r R1 R2 R3 R4 C < <(python3 -c "
import json
C = json.load(open('$OUT/capacity.json'))['C']
print(*[max(1, round(C * m)) for m in (0.5, 1.0, 1.5, 2.0)], round(C))")
RATES="$R1 $R2 $R3 $R4"
echo "== C = $C/s · 추천 행 부하 $RATES /s (0.5 · 1.0 · 1.5 · 2.0 배)"

# 정책은 기동 설정이라 정책마다 앱을 다시 띄운다(run-inference-overload.sh). 부하 단계는 재기동 없이 바꾼다.
for PAIR in admission-observed:ADMISSION unbounded:UNBOUNDED; do
  LABEL=${PAIR%%:*}
  POLICY=${PAIR##*:}
  echo "-- $LABEL ($POLICY)"
  POLICIES="$POLICY" ADMISSION_ESTIMATE=OBSERVED MODEL_KIND=genpage MODEL_URL="$MODEL_URL" \
    ACTIVITY_ITEMS="$ITEMS" RATES="$RATES" OUT_DIR="$OUT/$LABEL" \
    bash tools/run-inference-overload.sh
done

mkdir -p "$OUT/raw"
grep -h '^\[' "$OUT"/*/raw/summary.txt > "$OUT/raw/summary.txt" || true
python3 tools/degradation_report.py "$OUT/raw" | tee "$OUT/summary-table.md"
python3 tools/overload_real_model_report.py "$OUT" --baseline "$BASELINE" | tee "$OUT/report-table.md"
echo "== 원자료: $OUT"
