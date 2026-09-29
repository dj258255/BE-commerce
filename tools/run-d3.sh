#!/usr/bin/env bash
# D3(#437) 페이지 대 페이지 — 같은 상위 200개로 규칙 줄 구성(B)과 GenPage 줄 구성(G-row · G-full)을 만든다.
# 설계 · 판정은 personalization/docs/genpage-v2/STATUS.md "D3" 절(측정 전에 고정).
# R 은 한 프로세스(최대 약 10GB). G 조각은 EVAL_PARALLEL(검증 기본 2, final 기본 1)개씩 돈다.
#
#   PY=/path/to/genpage-venv/bin/python bash tools/run-d3.sh                         # 검증 1만 명, 세 변형
#   MODE=final VARIANTS="B G-row" PY=... bash tools/run-d3.sh                        # 홀드아웃, 고른 G 와 B
set -uo pipefail
cd "$(dirname "$0")/.."
PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-$(pwd)/personalization/data}
MODE=${MODE:-validate}
if [ "$MODE" = validate ]; then
  LIMIT_ARG="--limit 10000"; CKPT=$GENPAGE_DATA/hm/model/genpage2/validate/ckpt/b-base-full
  PAR=${EVAL_PARALLEL:-2}; SHARDS=${SHARDS:-2}
else
  LIMIT_ARG=""; CKPT=$GENPAGE_DATA/hm/model/genpage2/final/ckpt/f-base-full-1ep
  PAR=${EVAL_PARALLEL:-1}; SHARDS=${SHARDS:-8}
fi
VARIANTS=${VARIANTS:-B G-row G-full}
OUT=$(pwd)/${OUT:-personalization/docs/runs/$(date +%Y%m%d)-v2-d3-page/$MODE}
mkdir -p "$OUT"
cd personalization

note() { echo "== $* $(date +%H:%M)" | tee -a "$OUT/progress.txt"; }

# 1. 순위 모델 점수(있으면 다시 돌리지 않는다)
if [ ! -f "$OUT/r/scores.json.gz" ]; then
  note "R 시작"
  /usr/bin/time -l "$PY" -m genpage2.ranker --mode "$MODE" $LIMIT_ARG --config base --dump-scores 200 \
    --out "$OUT/r" > "$OUT/r.log" 2>&1 && note "R 끝" || { note "R 실패"; exit 1; }
fi

# 2. 변형마다 조각을 PAR 개씩 만들고 합친다
for v in $VARIANTS; do
  ckpt_arg=""; [ "$v" = B ] || ckpt_arg="--ckpt $CKPT"
  shards=$SHARDS; [ "$v" = B ] && shards=1
  note "$v 시작"
  running=0
  for k in $(seq 1 "$shards"); do
    [ -f "$OUT/$v-$k.json" ] && continue
    /usr/bin/time -l "$PY" -m genpage2.page_compose --mode "$MODE" $LIMIT_ARG --scores "$OUT/r/scores.json.gz" \
      --variant "$v" $ckpt_arg --shard "$k/$shards" --out "$OUT/$v-$k.json" > "$OUT/$v-$k.log" 2>&1 &
    running=$((running + 1))
    if [ "$running" -ge "$PAR" ]; then wait; running=0; fi
  done
  wait
  "$PY" -m genpage2.merge_eval $LIMIT_ARG --inputs "$OUT"/"$v"-*.json \
    --out "$OUT/merged-$v.json" > "$OUT/merged-$v.log" 2>&1 && note "$v 끝" || note "$v 합치기 실패"
done
