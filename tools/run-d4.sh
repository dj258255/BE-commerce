#!/usr/bin/env bash
# D4(#448) GenPage 행 선택 + 순위 모델 행 점수 — H(λ) 페이지를 만든다.
# 설계 · 판정은 personalization/docs/genpage-v2/STATUS.md "D4" 절(측정 전에 고정).
# H 는 G-row 와 같되, 행 로그 확률에 λ×z(행 점수 표준화)를 더해 고른다. R 점수는
# D3 가 만든 scores.json.gz 를 그대로 쓴다(여기서 랭커를 다시 돌리지 않는다).
# G 조각은 EVAL_PARALLEL(검증 기본 2, final 기본 1)개씩 돈다. 끝난 조각은 건너뛴다.
#
#   PY=/path/to/genpage-venv/bin/python bash tools/run-d4.sh                     # 검증 1만 명, λ 다섯 개
#   MODE=final LAMBDAS=2 PY=... bash tools/run-d4.sh                             # 홀드아웃, 고른 λ 하나
set -uo pipefail
cd "$(dirname "$0")/.."
PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-$(pwd)/personalization/data}
MODE=${MODE:-validate}
# D3 의 R 점수(파일은 $SCORES_DIR/scores.json.gz). 기본값은 D3 런 아카이브.
SCORES_DIR=${SCORES_DIR:-$GENPAGE_DATA/hm/runs-archive/20260929-v2-d3-page/$MODE}
LAMBDAS=${LAMBDAS:-0.5 1 2 4 8}
if [ "$MODE" = validate ]; then
  LIMIT_ARG="--limit 10000"; CKPT=$GENPAGE_DATA/hm/model/genpage2/validate/ckpt/b-base-full
  PAR=${EVAL_PARALLEL:-2}; SHARDS=${SHARDS:-2}
else
  LIMIT_ARG=""; CKPT=$GENPAGE_DATA/hm/model/genpage2/final/ckpt/f-base-full-1ep
  PAR=${EVAL_PARALLEL:-1}; SHARDS=${SHARDS:-8}
fi
OUT=$(pwd)/${OUT:-personalization/docs/runs/$(date +%Y%m%d)-v2-d4-hybrid-rows/$MODE}
mkdir -p "$OUT"
cd personalization

note() { echo "== $* $(date +%H:%M)" | tee -a "$OUT/progress.txt"; }

SCORES=$SCORES_DIR/scores.json.gz
if [ ! -f "$SCORES" ]; then
  note "R 점수 없음: $SCORES (SCORES_DIR 을 확인하라)"
  exit 1
fi

# λ 마다 조각을 PAR 개씩 만들고 합친다(변형 이름에 λ 를 넣어 섞이지 않게 한다).
for lam in $LAMBDAS; do
  note "H($lam) 시작"
  running=0
  for k in $(seq 1 "$SHARDS"); do
    [ -f "$OUT/H-$lam-$k.json" ] && continue
    /usr/bin/time -l "$PY" -m genpage2.page_compose --mode "$MODE" $LIMIT_ARG --scores "$SCORES" \
      --variant H --row-lambda "$lam" --ckpt "$CKPT" --shard "$k/$SHARDS" \
      --out "$OUT/H-$lam-$k.json" > "$OUT/H-$lam-$k.log" 2>&1 &
    running=$((running + 1))
    if [ "$running" -ge "$PAR" ]; then wait; running=0; fi
  done
  wait
  "$PY" -m genpage2.merge_eval $LIMIT_ARG --inputs "$OUT"/"H-$lam"-*.json \
    --out "$OUT/merged-H-$lam.json" > "$OUT/merged-H-$lam.log" 2>&1 \
    && note "H($lam) 끝" || note "H($lam) 합치기 실패"
done
