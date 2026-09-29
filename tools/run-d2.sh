#!/usr/bin/env bash
# D2(#413) 검증 단계 — 순위 모델 R(base · wide · narrow)과 GenPage G1 을 검증 주 1만 명 표본으로 잰다.
# 설계 · 판정은 personalization/docs/genpage-v2/STATUS.md "D2" 절(측정 전에 고정).
# R 은 한 프로세스씩(최대 약 10GB), G 평가 조각은 EVAL_PARALLEL(기본 2)개씩 돈다.
#
#   PY=/path/to/genpage-venv/bin/python bash tools/run-d2.sh
set -uo pipefail
cd "$(dirname "$0")/.."
PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-$(pwd)/personalization/data}
OUT=$(pwd)/${OUT:-personalization/docs/runs/$(date +%Y%m%d)-v2-d2-ranker}
CKPT=$GENPAGE_DATA/hm/model/genpage2/validate/ckpt/b-base-full
mkdir -p "$OUT"
cd personalization
note() { echo "== $* $(date +%H:%M)" | tee -a "$OUT/progress.txt"; }
want() { case " ${STEPS:-r-base r-wide r-narrow g1} " in *" $1 "*) return 0;; *) return 1;; esac; }

for cfg in base wide narrow; do
  want "r-$cfg" || continue
  note "R $cfg 시작"
  /usr/bin/time -l "$PY" -m genpage2.ranker --mode validate --limit 10000 --config "$cfg" \
    --out "$OUT/r-$cfg" > "$OUT/r-$cfg.log" 2>&1 && note "R $cfg 끝" || note "R $cfg 실패"
done

if want g1; then
  note "G1 시작"
  running=0
  for k in 1 2 3 4 5; do
    "$PY" -m genpage2.evaluate --mode validate --ckpt "$CKPT" --limit 10000 --pin-repeat --repeat-order recency \
      --ranker-candidates --shard "$k/5" --out "$OUT/g1-eval-$k.json" > "$OUT/g1-eval-$k.log" 2>&1 &
    running=$((running + 1))
    if [ "$running" -ge "${EVAL_PARALLEL:-2}" ]; then wait; running=0; fi
  done
  wait
  "$PY" -m genpage2.merge_eval --inputs "$OUT"/g1-eval-[1-5].json --limit 10000 --out "$OUT/g1-eval.json" \
    > "$OUT/g1-merge.log" 2>&1 && note "G1 끝" || note "G1 실패"
fi
note "끝"
