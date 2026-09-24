#!/usr/bin/env bash
# 과부하 게이트를 실제 GenPage 모델 서버로 다시 잰다(#271). 판정 기준은 이슈에 측정 전에 적었다.
#
#   PY=/path/to/genpage-venv/bin/python bash tools/run-overload-real-model.sh
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

MODEL=""
trap '[ -n "$MODEL" ] && kill "$MODEL" 2>/dev/null; wait 2>/dev/null || true' EXIT

# 활동에 쓸 상품: 모델 어휘 안 · 재고 있음. 400개를 id 순서로 고르게 뽑는다(무작위가 아니라 재현되게)
"$PY" - "$ITEMS" <<'PY'
import json, os, pathlib, sys, pymysql
data = pathlib.Path(os.environ["GENPAGE_DATA"])
vocab = {int(a) for a in json.loads((data / "hm" / "model" / "genpage" / "vocab.json").read_text())["items"]}
conn = pymysql.connect(host="127.0.0.1", port=3306, user="root", password="root", database="becommerce")
with conn.cursor() as c:
    c.execute("SELECT product_id FROM stock WHERE quantity > 0 ORDER BY product_id")
    ids = [r[0] for r in c.fetchall() if r[0] in vocab]
step = max(len(ids) // 400, 1)
pathlib.Path(sys.argv[1]).write_text(json.dumps(ids[::step][:400]))
print(f"활동 상품 {len(ids[::step][:400])}개(후보 {len(ids):,})")
PY

"$PY" personalization/serving/genpage_server.py 8765 > "$OUT/model-server.log" 2>&1 &
MODEL=$!
for _ in $(seq 1 120); do curl -sf http://localhost:8765/health >/dev/null 2>&1 && break; sleep 1; done

[ -f "$OUT/capacity.json" ] || python3 tools/genpage_capacity.py "$ITEMS" "$OUT/capacity.json" 20 | tee "$OUT/capacity.txt"
read -r P1 P2 < <(python3 -c "
import json; d = json.load(open('$OUT/capacity.json'))
print(round(d['Cp']), round(2 * d['Cp']))")
REC_RATES=${REC_RATES:-"300 600"}
echo "== 추천 행 $REC_RATES /s · 2쪽 1Cp=$P1 2Cp=$P2 /s"

for EST in ${ESTIMATES:-CONFIGURED OBSERVED}; do
  POLICIES=ADMISSION ADMISSION_ESTIMATE=$EST MODEL_KIND=genpage ACTIVITY_ITEMS="$ITEMS" RATES="$REC_RATES" \
    OUT_DIR="$OUT/A-$EST" bash tools/run-inference-overload.sh
done
for PR in $P1 $P2; do
  POLICIES=ADMISSION ADMISSION_ESTIMATE=OBSERVED MODEL_KIND=genpage ACTIVITY_ITEMS="$ITEMS" RATES=300 PAGE_RATE="$PR" \
    OUT_DIR="$OUT/B-page-$PR${B_SUFFIX:-}" bash tools/run-inference-overload.sh
done

python3 tools/overload_real_model_report.py "$OUT" | tee "$OUT/report-table.md"
