#!/usr/bin/env bash
# T3(#463) GenPage 행 선택에 순위 통계 머리 — 학습 주 점수 → 머리 학습 → T3 페이지 → merge.
# 설계 · 판정은 personalization/docs/genpage-v2/STATUS.md "T3" 절(측정 전에 고정).
#
# 누설 방지: 학습 주 점수는 r − 14 · r − 21 두 주로만 학습한 순위 모델이 r − 7 주 구매
# 고객의 후보에 매긴다(rankert 의 --train-scores-out · --train-weeks). T3 페이지의
# 디코딩 점수는 D3 가 만든 세 주 학습 R 점수를 그대로 쓴다(여기서 다시 돌리지 않는다).
# 검증은 조각 2개씩, 홀드아웃은 조각 8개 하나씩. 끝난 단계는 건너뛴다.
#
#   PY=/path/to/genpage-venv/bin/python bash tools/run-t3.sh                 # 검증 1만 명
#   MODE=final PY=... bash tools/run-t3.sh                                  # 홀드아웃
#   TRAIN_CUSTOMERS=2000 HEAD_LIMIT=2000 ... bash tools/run-t3.sh           # 스모크
set -uo pipefail
cd "$(dirname "$0")/.."
PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-$(pwd)/personalization/data}
MODE=${MODE:-validate}
# 디코딩에 쓰는 R 점수(세 주 학습). D3 런 아카이브를 기본값으로 둔다.
D3_DIR=${D3_DIR:-$GENPAGE_DATA/hm/runs-archive/20260929-v2-d3-page/$MODE}
# 누설 없는 학습 주 점수: r − 14 · r − 21 로만 학습하고 r − 7 주 구매 고객을 채점한다.
TRAIN_WEEKS=${TRAIN_WEEKS:-14,21}
# 머리 학습 고객 상한(기본 10만 = config 상한). 스모크는 HEAD_LIMIT=2000.
HEAD_LIMIT=${HEAD_LIMIT:-100000}

if [ "$MODE" = validate ]; then
  LIMIT_ARG="--limit 10000"; CKPT=$GENPAGE_DATA/hm/model/genpage2/validate/ckpt/b-base-full
  PAR=${EVAL_PARALLEL:-2}; SHARDS=${SHARDS:-2}
else
  LIMIT_ARG=""; CKPT=$GENPAGE_DATA/hm/model/genpage2/final/ckpt/f-base-full-1ep
  PAR=${EVAL_PARALLEL:-1}; SHARDS=${SHARDS:-8}
fi
OUT=$(pwd)/${OUT:-personalization/docs/runs/$(date +%Y%m%d)-v2-t3-row-head/$MODE}
mkdir -p "$OUT"
cd personalization

note() { echo "== $* $(date +%H:%M)" | tee -a "$OUT/progress.txt"; }
# 누설 없는 학습 주 점수의 학습/채점 고객 상한(스모크면 작게).
TC_ARG=""; [ -n "${TRAIN_CUSTOMERS:-}" ] && TC_ARG="--train-customers $TRAIN_CUSTOMERS"

# 1) 누설 없는 학습 주 점수 (r − 14 · r − 21 로만 학습 → r − 7 주 구매 고객 채점)
if [ ! -f "$OUT/train/r/scores.json.gz" ]; then
  note "학습 주 점수 시작"
  /usr/bin/time -l "$PY" -m genpage2.ranker --mode "$MODE" --train-scores-out "$OUT/train/r" \
    --train-weeks "$TRAIN_WEEKS" --score-week 7 $TC_ARG > "$OUT/train-scores.log" 2>&1 \
    && note "학습 주 점수 끝" || { note "학습 주 점수 실패"; exit 1; }
fi
SCORES_TRAIN=$OUT/train/r/scores.json.gz

# 2) 머리 학습 (GenPage 고정)
if [ ! -f "$OUT/train/head.pt" ]; then
  note "머리 학습 시작"
  /usr/bin/time -l "$PY" -m genpage2.row_side --mode "$MODE" --scores "$SCORES_TRAIN" \
    --ckpt "$CKPT" --out "$OUT/train/head.pt" --limit "$HEAD_LIMIT" > "$OUT/head.log" 2>&1 \
    && note "머리 학습 끝" || { note "머리 학습 실패"; exit 1; }
fi
HEAD=$OUT/train/head.pt

# 3) T3 페이지 (thin 규칙 + 머리 출력을 row_bias 자리에)
D3_SCORES=$D3_DIR/scores.json.gz
if [ ! -f "$D3_SCORES" ]; then
  note "R 점수 없음: $D3_SCORES (D3_DIR 을 확인하라)"
  exit 1
fi
note "T3 페이지 시작"
running=0
for k in $(seq 1 "$SHARDS"); do
  [ -f "$OUT/T3-$k.json" ] && continue
  /usr/bin/time -l "$PY" -m genpage2.page_compose --mode "$MODE" $LIMIT_ARG --scores "$D3_SCORES" \
    --variant T3 --row-head "$HEAD" --ckpt "$CKPT" --shard "$k/$SHARDS" \
    --out "$OUT/T3-$k.json" > "$OUT/T3-$k.log" 2>&1 &
  running=$((running + 1))
  if [ "$running" -ge "$PAR" ]; then wait; running=0; fi
done
wait
"$PY" -m genpage2.merge_eval $LIMIT_ARG --inputs "$OUT"/T3-*.json \
  --out "$OUT/merged-T3.json" > "$OUT/merged-T3.log" 2>&1 \
  && note "T3 끝" || note "T3 합치기 실패"
