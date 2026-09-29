#!/usr/bin/env bash
# D2(#413) 판정 단계 — 검증에서 고른 R(base)과 G(G1)를 final 로 만들어 홀드아웃 전체에서 한 번씩 재고,
# 같은 고객끼리 짝 부트스트랩으로 비교한다. 규칙은 STATUS "D2" 절 판정 2 · 3(측정 전에 고정).
# R 과 G 는 동시에 돌리지 않는다. 홀드아웃 전체에서는 R 이 최대 31GB, G 조각 하나가 약 14GB 라
# G 조각은 EVAL_PARALLEL(기본 1)개씩 돈다(2개씩이면 스왑이 찬다, 2026-09-28 측정).
#
#   PY=/path/to/genpage-venv/bin/python bash tools/run-d2-holdout.sh
set -uo pipefail
cd "$(dirname "$0")/.."
PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-$(pwd)/personalization/data}
OUT=$(pwd)/${OUT:-personalization/docs/runs/$(date +%Y%m%d)-v2-d2-holdout}
CKPT=$GENPAGE_DATA/hm/model/genpage2/final/ckpt/f-base-full-1ep
mkdir -p "$OUT"
cd personalization
note() { echo "== $* $(date +%H:%M)" | tee -a "$OUT/progress.txt"; }

if [ -f "$OUT/r-final/pages.json.gz" ]; then
  note "R final 건너뜀(이미 있다)"
else
  note "R final 시작"
  /usr/bin/time -l "$PY" -m genpage2.ranker --mode final --config base --out "$OUT/r-final" \
    > "$OUT/r-final.log" 2>&1 || { note "R final 실패"; exit 1; }
  note "R final 끝"
fi

note "G1 final 시작"
running=0
for k in 1 2 3 4 5; do
  "$PY" -m genpage2.evaluate --mode final --ckpt "$CKPT" --pin-repeat --repeat-order recency --ranker-candidates \
    --shard "$k/5" --out "$OUT/g1-final-$k.json" > "$OUT/g1-final-$k.log" 2>&1 &
  running=$((running + 1))
  if [ "$running" -ge "${EVAL_PARALLEL:-1}" ]; then wait; running=0; fi
done
wait
"$PY" -m genpage2.merge_eval --inputs "$OUT"/g1-final-[1-5].json --out "$OUT/g1-final.json" \
  > "$OUT/g1-final-merge.log" 2>&1 || { note "G1 final 합치기 실패"; exit 1; }
note "G1 final 끝"

"$PY" -m genpage2.paired_compare --mode final --a "$OUT/r-final/pages.json.gz" \
  --b "$OUT"/g1-final-[1-5].json --bootstrap 2000 --seed 7 --out "$OUT/paired-final.json" \
  > "$OUT/paired-final.log" 2>&1 && note "짝 비교 끝" || note "짝 비교 실패"
note "끝"
