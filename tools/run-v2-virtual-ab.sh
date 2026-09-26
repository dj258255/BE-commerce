#!/usr/bin/env bash
# D(#305) 가상 사용자 A/B — v1 대 v2. 설계 · 판정은 personalization/docs/genpage-v2/BACKEND.md D 절(측정 전에 고정).
#
#   PY=/path/to/genpage-venv/bin/python bash tools/run-v2-virtual-ab.sh
#
# 두 서빙 서버를 띄우고 A/A(둘 다 v2) → A/B(v1 대 v2) 순서로 돈다. A/A 의 구간이 0 을 포함하지 않으면
# A/B 결과를 쓰지 않는다(판정 1).
set -euo pipefail
cd "$(dirname "$0")/.."

PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-$(pwd)/personalization/data}
OUT=${OUT:-personalization/docs/runs/$(date +%Y%m%d)-v2-virtual-ab}
CKPT=${CKPT:-$GENPAGE_DATA/hm/model/genpage2/final/ckpt/f-base-full-1ep}
CUSTOMERS=${CUSTOMERS:-5000}
mkdir -p "$OUT"

V1=""; V2=""
cleanup() { for p in $V1 $V2; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

"$PY" personalization/serving/genpage_server.py 8765 > "$OUT/v1-server.log" 2>&1 & V1=$!
"$PY" personalization/serving/genpage2_server.py --ckpt "$CKPT" --mode final --port 8766 > "$OUT/v2-server.log" 2>&1 & V2=$!
for url in http://localhost:8765 http://localhost:8766; do
  for _ in $(seq 1 300); do curl -sf "$url/health" >/dev/null 2>&1 && break; sleep 1; done
  curl -sf "$url/health" >/dev/null || { echo "$url 가 안 떴다"; exit 1; }
done

for MODE in aa ab; do
  echo "== $MODE $(date +%H:%M)"
  (cd personalization && "$PY" ../tools/v2_virtual_ab.py --mode "$MODE" --customers "$CUSTOMERS" \
     --v1-url http://localhost:8765 --v2-url http://localhost:8766 --out "../$OUT/$MODE") | tee "$OUT/$MODE.txt"
done
echo "== 끝 $(date +%H:%M)"
