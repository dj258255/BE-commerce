#!/usr/bin/env bash
# D5(#450) 행 규칙을 B 와 같게 — G-row-thin · H-thin 페이지를 만든다.
# 설계 · 판정은 personalization/docs/genpage-v2/STATUS.md "D5" 절(측정 전에 고정).
# D4 의 G-row · H 와 달리 허용 행을 상위 200개에 상품이 1개 이상인 행으로 넓히고,
# 행 안을 그 행의 점수 순 최대 8개로 못박아 모델이 빈 칸을 채우지 않게 한다(짧은 행).
# R 점수는 D3 가 만든 scores.json.gz 를 그대로 쓴다(여기서 랭커를 다시 돌리지 않는다).
# 조각은 EVAL_PARALLEL(검증 기본 2, final 기본 1)개씩 돈다. 끝난 조각은 건너뛴다.
#
#   PY=/path/to/genpage-venv/bin/python bash tools/run-d5.sh                       # 검증 1만 명, G-row-thin · H-thin(λ=4)
#   MODE=final VARIANT=H-thin LAMBDA=4 PY=... bash tools/run-d5.sh                 # 홀드아웃, 고른 하나
set -uo pipefail
cd "$(dirname "$0")/.."
PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-$(pwd)/personalization/data}
MODE=${MODE:-validate}
# D3 의 R 점수(파일은 $SCORES_DIR/scores.json.gz). 기본값은 D3 런 아카이브.
SCORES_DIR=${SCORES_DIR:-$GENPAGE_DATA/hm/runs-archive/20260929-v2-d3-page/$MODE}
# D5 는 D4 검증에서 고른 λ*=4 를 그대로 쓴다(새로 고르지 않는다).
LAMBDA=${LAMBDA:-4}
if [ "$MODE" = validate ]; then
  LIMIT_ARG="--limit 10000"; CKPT=$GENPAGE_DATA/hm/model/genpage2/validate/ckpt/b-base-full
  PAR=${EVAL_PARALLEL:-2}; SHARDS=${SHARDS:-2}
  # 검증은 두 변형을 차례로, 조각 2개씩 병렬. 변형:λ 로 λ 를 붙인다.
  RUNS="G-row-thin H-thin:$LAMBDA"
else
  LIMIT_ARG=""; CKPT=$GENPAGE_DATA/hm/model/genpage2/final/ckpt/f-base-full-1ep
  PAR=${EVAL_PARALLEL:-1}; SHARDS=${SHARDS:-8}
  # final 은 VARIANT · LAMBDA 인자로 하나만, 조각 8개 하나씩. H-thin 일 때만 λ 를 준다.
  VARIANT=${VARIANT:?final 은 VARIANT 를 준다(G-row-thin 또는 H-thin)}
  case "$VARIANT" in
    H-thin) RUNS="H-thin:$LAMBDA" ;;
    *) RUNS="$VARIANT" ;;
  esac
fi
OUT=$(pwd)/${OUT:-personalization/docs/runs/$(date +%Y%m%d)-v2-d5-thin-rows/$MODE}
mkdir -p "$OUT"
cd personalization

note() { echo "== $* $(date +%H:%M)" | tee -a "$OUT/progress.txt"; }

SCORES=$SCORES_DIR/scores.json.gz
if [ ! -f "$SCORES" ]; then
  note "R 점수 없음: $SCORES (SCORES_DIR 을 확인하라)"
  exit 1
fi

# 변형마다 조각을 PAR 개씩 만들고 합친다(이름에 λ 를 넣어 G-row-thin 과 섞이지 않게 한다).
for run in $RUNS; do
  v=${run%%:*}; lam=""
  [ "$run" = "$v" ] || lam=${run#*:}
  tag=$v; [ -n "$lam" ] && tag="$v-$lam"
  lam_arg=""; [ -n "$lam" ] && lam_arg="--row-lambda $lam"
  note "$tag 시작"
  running=0
  for k in $(seq 1 "$SHARDS"); do
    [ -f "$OUT/$tag-$k.json" ] && continue
    /usr/bin/time -l "$PY" -m genpage2.page_compose --mode "$MODE" $LIMIT_ARG --scores "$SCORES" \
      --variant "$v" $lam_arg --ckpt "$CKPT" --shard "$k/$SHARDS" \
      --out "$OUT/$tag-$k.json" > "$OUT/$tag-$k.log" 2>&1 &
    running=$((running + 1))
    if [ "$running" -ge "$PAR" ]; then wait; running=0; fi
  done
  wait
  "$PY" -m genpage2.merge_eval $LIMIT_ARG --inputs "$OUT"/"$tag"-*.json \
    --out "$OUT/merged-$tag.json" > "$OUT/merged-$tag.log" 2>&1 \
    && note "$tag 끝" || note "$tag 합치기 실패"
done
