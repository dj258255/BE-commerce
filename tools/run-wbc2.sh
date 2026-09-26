#!/usr/bin/env bash
# WBC 재도전(#301) — 변형 셋(W1 · W2 · W3)을 학습하고 검증 주 1만 명 표본으로 평가한다.
# 설계 · 고르는 규칙은 personalization/docs/genpage-v2/STATUS.md "선 넘기기 재도전 — WBC 2"(측정 전에 고정).
#
#   PY=/path/to/genpage-venv/bin/python bash tools/run-wbc2.sh
set -euo pipefail
cd "$(dirname "$0")/.."
PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-$(pwd)/personalization/data}
OUT=$(pwd)/${OUT:-personalization/docs/runs/$(date +%Y%m%d)-v2-wbc2}
D=$GENPAGE_DATA/hm/model/genpage2/validate
INIT=$D/ckpt/b-base-full
mkdir -p "$OUT"
cd personalization

train() {  # 이름, 노출 수, 추가 인자
  local name=$1 examples=$2; shift 2
  echo "== 학습 $name 시작 $(date +%H:%M)" | tee -a "$OUT/progress.txt"
  "$PY" -m genpage2.wbc --mode validate --init "$INIT" --examples "$examples" --pin-repeat \
    --epochs 1 --batch 128 --lr 1e-4 --seed 7 --name "$name" "$@" > "$OUT/$name-train.log" 2>&1
  echo "== 학습 $name 끝 $(date +%H:%M)" | tee -a "$OUT/progress.txt"
}
train wbc2-w1 10000 --pretrain-weight 1 &
train wbc2-w2 10000 --pretrain-weight 1 --neg-samples 64 --neg-weight 1 &
train wbc2-w3 20000 --pretrain-weight 1 --neg-samples 64 --neg-weight 1 --gen-temperature 1.0 &
wait

for name in wbc2-w1 wbc2-w2 wbc2-w3; do
  echo "== 평가 $name 시작 $(date +%H:%M)" | tee -a "$OUT/progress.txt"
  for k in 1 2 3 4 5; do
    "$PY" -m genpage2.evaluate --mode validate --ckpt "$D/ckpt/$name" --limit 10000 --pin-repeat \
      --shard "$k/5" --out "$OUT/$name-eval-$k.json" > "$OUT/$name-eval-$k.log" 2>&1 &
  done
  wait
  "$PY" -m genpage2.merge_eval --inputs "$OUT"/$name-eval-[1-5].json --limit 10000 --out "$OUT/$name-eval.json" \
    > "$OUT/$name-merge.log" 2>&1
  echo "== 평가 $name 끝 $(date +%H:%M)" | tee -a "$OUT/progress.txt"
done
echo "== 전부 끝 $(date +%H:%M)" | tee -a "$OUT/progress.txt"
