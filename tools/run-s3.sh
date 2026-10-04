#!/usr/bin/env bash
# S3(#462) 짝 설계 가상 A/B — 한 v2 서버를 compose 로 가르고 모든 사용자에게 두 정책을
# 차례로 돌린다. 설계 · 판정은 personalization/docs/genpage-v2/BACKEND.md "S3" 절(측정 전에 고정).
#
#   PY=/path/to/genpage-venv/bin/python bash tools/run-s3.sh
#
# 서버를 S1 과 같은 설정(포트 18866 · final · --scores · --page-store · 스레드 2 · CPU)으로
# 띄우고 `--paired --a-compose rule --b-compose hybrid --customers 0 --history-events 100` 로
# personalization/docs/runs/<날짜>-s3-paired-ab/ 에 남긴 뒤 서버를 끈다. 이미 있으면 건너뛴다.
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)

PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-$ROOT/personalization/data}
OUT=${OUT:-personalization/docs/runs/$(date +%Y%m%d)-s3-paired-ab}
case "$OUT" in /*) ;; *) OUT=$ROOT/$OUT ;; esac
PORT=${PORT:-18866}
URL="http://127.0.0.1:$PORT"
CKPT=${CKPT:-$GENPAGE_DATA/hm/model/genpage2/final/ckpt/f-base-full-1ep}
SCORES=${SCORES:-$GENPAGE_DATA/hm/runs-archive/20260929-v2-d3-page/final/scores.json.gz}
# dataset.py 의 이력 상한(100)과 같은 값. S1 L4 와 같은 요청을 보낸다.
HISTORY_EVENTS=${HISTORY_EVENTS:-100}
# 서버의 page-store · L1 비교에 쓰던 S1 의 입력들(final B 조각 1개 · H-thin-4 조각 8개).
B_PAGES=${B_PAGES:-$ROOT/personalization/docs/runs/20260929-v2-d3-page/final/B-1.json.gz}
H_PAGES=${H_PAGES:-$ROOT/personalization/docs/runs/20261001-v2-d5-thin-rows/final/H-thin-4-*.json.gz}
PAGE_STORE=${PAGE_STORE:-$H_PAGES}
# 고객별 A · B 값은 저장소 밖(GENPAGE_DATA 는 gitignore)에 남긴다 — 다음에 짝 상관을 다시 본다.
PAIRED_CUSTOMERS=${PAIRED_CUSTOMERS:-$GENPAGE_DATA/hm/runs-s3-paired-ab/paired-customers.parquet}
mkdir -p "$OUT"

note() { echo "== $* $(date +%H:%M)" | tee -a "$OUT/progress.txt"; }

for f in $B_PAGES $H_PAGES $SCORES; do
  [ -f "$f" ] || { note "입력 파일이 없다: $f"; exit 1; }
done
for f in $PAGE_STORE; do
  [ -f "$f" ] || { note "page-store 파일이 없다: $f"; exit 1; }
done

SRV=""
cleanup() { if [ -n "$SRV" ]; then kill "$SRV" 2>/dev/null || true; fi; }
trap cleanup EXIT

if [ -f "$OUT/result.json" ]; then
  note "이미 결과가 있다 — 건너뛴다"
  exit 0
fi

note "서버 기동 :$PORT"
"$PY" personalization/serving/genpage2_server.py --mode final --ckpt "$CKPT" --scores "$SCORES" \
  --page-store $PAGE_STORE --hybrid-lambda 4 --threads 2 --device cpu --port "$PORT" \
  > "$OUT/server.log" 2>&1 &
SRV=$!
for _ in $(seq 1 300); do curl -sf "$URL/health" >/dev/null 2>&1 && break; sleep 1; done
curl -sf "$URL/health" >/dev/null 2>&1 || { note "서버가 안 떴다 — $OUT/server.log 를 보라"; exit 1; }
note "서버 준비됨"

note "짝 설계 A/B 시작 (A rule · B hybrid · --customers 0 전원)"
(cd personalization && "$PY" ../tools/v2_virtual_ab.py --paired --v2-url "$URL" \
   --a-compose rule --b-compose hybrid --customers 0 --history-events "$HISTORY_EVENTS" \
   --paired-customers "$PAIRED_CUSTOMERS" --out "$OUT") | tee "$OUT/paired.txt"

note "끝"
