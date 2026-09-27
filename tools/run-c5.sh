#!/usr/bin/env bash
# C5(#382) 종합몰식 구성 — M0(기준 재측정) · M1 · M2 · M3 를 검증 주 1만 명 표본으로 평가한다.
# 변형 · 판정은 personalization/docs/genpage-v2/STATUS.md "C5 종합몰식 구성"(측정 전에 고정).
# 평가 조각 하나가 약 9.7GB 라 EVAL_PARALLEL(기본 2)개씩 돈다.
#
#   PY=/path/to/genpage-venv/bin/python bash tools/run-c5.sh
set -uo pipefail
cd "$(dirname "$0")/.."
PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-$(pwd)/personalization/data}
OUT=$(pwd)/${OUT:-personalization/docs/runs/$(date +%Y%m%d)-v2-c5-mall}
CKPT=$GENPAGE_DATA/hm/model/genpage2/validate/ckpt/b-base-full
mkdir -p "$OUT"
cd personalization
note() { echo "== $* $(date +%H:%M)" | tee -a "$OUT/progress.txt"; }

variant() {  # 이름, 추가 인자
  local name=$1; shift
  note "평가 $name 시작"
  local k running=0
  for k in 1 2 3 4 5; do
    "$PY" -m genpage2.evaluate --mode validate --ckpt "$CKPT" --limit 10000 --pin-repeat "$@" \
      --shard "$k/5" --out "$OUT/$name-eval-$k.json" > "$OUT/$name-eval-$k.log" 2>&1 &
    running=$((running + 1))
    if [ "$running" -ge "${EVAL_PARALLEL:-2}" ]; then wait; running=0; fi
  done
  wait
  "$PY" -m genpage2.merge_eval --inputs "$OUT"/$name-eval-[1-5].json --limit 10000 --out "$OUT/$name-eval.json" \
    > "$OUT/$name-merge.log" 2>&1 || { note "평가 $name 실패"; return 1; }
  note "평가 $name 끝"
}

want() { case " ${VARIANTS:-m0 m1 m2 m3} " in *" $1 "*) return 0;; *) return 1;; esac; }
{ ! want m0 || variant m0; } &&
{ ! want m1 || variant m1 --repeat-order recency; } &&
{ ! want m2 || variant m2 --repeat-order recency --candidates 200,20 --similar 5,20; } &&
{ ! want m3 || variant m3 --repeat-order recency --candidates 200,20 --similar 5,20 --repeat-gate; }
note "끝"
