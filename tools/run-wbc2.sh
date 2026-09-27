#!/usr/bin/env bash
# WBC 재도전(#301) — 변형 W1 → W2 → W3 를 **하나씩** 학습하고 검증 주 1만 명 표본으로 평가한다.
# 설계 · 고르는 규칙은 personalization/docs/genpage-v2/STATUS.md "선 넘기기 재도전 — WBC 2"(측정 전에 고정).
# 첫 실행(2026-09-26)은 셋을 동시에 돌려 스왑 포화로 예산 안에 끝나지 않았다. 그래서 순서대로 돌리고
# 예산(BUDGET 초, 기본 5시간)이 다하면 돌던 프로세스를 멈춘다.
#
#   PY=/path/to/genpage-venv/bin/python bash tools/run-wbc2.sh
set -uo pipefail
cd "$(dirname "$0")/.."
PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-$(pwd)/personalization/data}
OUT=$(pwd)/${OUT:-personalization/docs/runs/$(date +%Y%m%d)-v2-wbc2}
BUDGET=${BUDGET:-18000}
D=$GENPAGE_DATA/hm/model/genpage2/validate
INIT=$D/ckpt/b-base-full
mkdir -p "$OUT"
cd personalization
START=$(date +%s)
note() { echo "== $* $(date +%H:%M) (경과 $(( ($(date +%s) - START) / 60 ))분)" | tee -a "$OUT/progress.txt"; }

# 예산 안에서만 돈다. 예산이 다하면 프로세스를 멈추고 1 을 돌려준다.
limited() {
  "$@" & local pid=$!
  while kill -0 "$pid" 2>/dev/null; do
    if [ $(( $(date +%s) - START )) -ge "$BUDGET" ]; then
      kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null; return 1
    fi
    sleep 10
  done
  wait "$pid"
}

variant() {  # 이름, 노출 수, 추가 인자
  local name=$1 examples=$2; shift 2
  if [ -f "$D/ckpt/$name/model.pt" ]; then
    note "학습 $name 건너뜀(체크포인트가 이미 있다)"
  else
  note "학습 $name 시작"
  if ! limited "$PY" -m genpage2.wbc --mode validate --init "$INIT" --examples "$examples" --pin-repeat \
       --epochs 1 --batch 128 --lr 1e-4 --seed 7 --log-every 10 --name "$name" "$@" \
       > "$OUT/$name-train.log" 2>&1; then
    note "학습 $name 중단(예산 초과 또는 실패)"; return 1
  fi
  note "학습 $name 끝"
  fi
  note "평가 $name 시작"
  local k
  for k in 1 2 3 4 5; do
    limited "$PY" -m genpage2.evaluate --mode validate --ckpt "$D/ckpt/$name" --limit 10000 --pin-repeat \
      --shard "$k/5" --out "$OUT/$name-eval-$k.json" > "$OUT/$name-eval-$k.log" 2>&1 &
  done
  wait
  if ! ls "$OUT"/$name-eval-[1-5].json >/dev/null 2>&1 || [ "$(ls "$OUT"/$name-eval-[1-5].json | wc -l)" -ne 5 ]; then
    note "평가 $name 중단(예산 초과 또는 실패)"; return 1
  fi
  "$PY" -m genpage2.merge_eval --inputs "$OUT"/$name-eval-[1-5].json --limit 10000 --out "$OUT/$name-eval.json" \
    > "$OUT/$name-merge.log" 2>&1
  note "평가 $name 끝"
}

variant wbc2-w1 10000 --pretrain-weight 1 &&
variant wbc2-w2 10000 --pretrain-weight 1 --neg-samples 64 --neg-weight 1 &&
variant wbc2-w3 20000 --pretrain-weight 1 --neg-samples 64 --neg-weight 1 --gen-temperature 1.0
note "끝"
