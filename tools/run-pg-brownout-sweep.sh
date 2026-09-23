#!/usr/bin/env bash
# ADR-022 회귀 — 지연 축을 쓸어 상한이 언제 일하고 언제 소용없어지는지 본다.
#
# ADR-022 는 지연 3초 한 점에서만 쟀다. 한 점으로는 "상한이 좋다/나쁘다" 밖에 못 말한다.
# 지연을 축으로 쓸면 <b>상한이 발동하기 시작하는 지연</b>과 <b>거절이 성공을 삼키는 지연</b>이 보인다.
#
# 사용: tools/run-pg-brownout-sweep.sh [도착률] [지속]
#   기본값은 ADR-022 14.2 절과 같은 도착률 50/s · 45초다(그 표와 한 점이 겹쳐 대조가 된다).
set -euo pipefail

RATE=${1:-50}
DUR=${2:-45s}
LATS=${LATS:-"0 500 1000 2000 3000 5000"}
LIMITS=${LIMITS:-"0 40"}

STAMP=$(date +%Y%m%d-%H%M%S)
OUT="docs/performance/runs/${STAMP}-brownout-sweep-rate${RATE}"
mkdir -p "$OUT"

echo "== 스윕: 지연 [$LATS] × 상한 [$LIMITS] · 도착률 ${RATE}/s · ${DUR}"
echo "== 출력: $OUT"
{
  echo "sweep  rate=${RATE}  dur=${DUR}  lats=[${LATS}]  limits=[${LIMITS}]"
  echo "host   $(uname -sm)  $(sysctl -n hw.ncpu 2>/dev/null || nproc) cores"
  echo "commit $(git rev-parse --short HEAD)"
  echo "start  $(date -Iseconds)"
} > "$OUT/meta.txt"

for lat in $LATS; do
  for lim in $LIMITS; do
    echo "---- 지연 ${lat}ms · 상한 ${lim}"
    # 개별 실행은 기존 하네스를 그대로 쓴다. 새로 만들면 ADR-022 의 값과 비교가 안 된다.
    tools/run-pg-brownout.sh "$lat" "$RATE" "$DUR" 5000 "$lim" > "$OUT/lat${lat}-lim${lim}.log" 2>&1 || {
      echo "     실패 — 로그: $OUT/lat${lat}-lim${lim}.log"; continue; }
    # 하네스가 만든 디렉터리를 찾아 붙여 둔다(나중에 원자료를 되짚을 수 있게).
    src=$(grep -m1 "^== 출력: " "$OUT/lat${lat}-lim${lim}.log" | sed 's/^== 출력: //')
    [ -n "$src" ] && echo "$src" > "$OUT/lat${lat}-lim${lim}.rawdir"
    echo "     끝"
  done
done

echo "end    $(date -Iseconds)" >> "$OUT/meta.txt"
echo "== 스윕 끝: $OUT"
echo "== 표를 만들려면: python3 tools/brownout_sweep_report.py $OUT"
