#!/usr/bin/env bash
# S1(#454) 순위 모델 + GenPage 줄 구성 서빙 측정. 설계 · 판정은
# personalization/docs/genpage-v2/BACKEND.md "S1" 절(측정 전에 고정).
#
#   PY=/path/to/genpage-venv/bin/python bash tools/run-s1.sh
#
# 서버를 포트 18866 에 final · CPU · 스레드 2 로 띄우고 L1 → (L1 이 전부 일치할 때만)
# L2 → L3 → L4 를 차례로 돌려 personalization/docs/runs/<날짜>-s1-serving/ 에 남기고
# 서버를 끈다. 이미 있는 결과는 건너뛴다.
set -uo pipefail
cd "$(dirname "$0")/.."

PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-$(pwd)/personalization/data}
OUT=${OUT:-personalization/docs/runs/$(date +%Y%m%d)-s1-serving}
case "$OUT" in /*) ;; *) OUT=$(pwd)/$OUT ;; esac
PORT=${PORT:-18866}
URL="http://127.0.0.1:$PORT"
CKPT=${CKPT:-$GENPAGE_DATA/hm/model/genpage2/final/ckpt/f-base-full-1ep}
SCORES=${SCORES:-$GENPAGE_DATA/hm/runs-archive/20260929-v2-d3-page/final/scores.json.gz}
# L1 ~ L4 가 서버로 보내는 최근 구매 이벤트 수. dataset.py 의 이력 상한과 같은 100 이라
# 오프라인(eval_meta.history)과 서버의 다시 사기 행 이력이 같아진다(_events_of 참고).
HISTORY_EVENTS=${HISTORY_EVENTS:-100}
# D3 final B 조각(1개) · D5 final H-thin-4 조각(8개). 서버의 page-store 와 L1 비교에 함께 쓴다.
B_PAGES=${B_PAGES:-$(pwd)/personalization/docs/runs/20260929-v2-d3-page/final/B-1.json.gz}
H_PAGES=${H_PAGES:-$(pwd)/personalization/docs/runs/20261001-v2-d5-thin-rows/final/H-thin-4-*.json.gz}
PAGE_STORE=${PAGE_STORE:-$H_PAGES}
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

note "서버 기동 :$PORT"
"$PY" personalization/serving/genpage2_server.py --mode final --ckpt "$CKPT" --scores "$SCORES" \
  --page-store $PAGE_STORE --hybrid-lambda 4 --threads 2 --device cpu --port "$PORT" \
  > "$OUT/server.log" 2>&1 &
SRV=$!
for _ in $(seq 1 300); do curl -sf "$URL/health" >/dev/null 2>&1 && break; sleep 1; done
curl -sf "$URL/health" >/dev/null 2>&1 || { note "서버가 안 떴다 — $OUT/server.log 를 보라"; exit 1; }
note "서버 준비됨"

if [ ! -f "$OUT/l1.json" ]; then
  note "L1 시작"
  (cd personalization && "$PY" ../tools/s1_serving_bench.py --url "$URL" --level l1 --out "$OUT" \
     --b-pages $B_PAGES --h-pages $H_PAGES --history-events "$HISTORY_EVENTS") | tee "$OUT/l1.txt"
else
  note "L1 건너뜀(결과 있음)"
fi
if ! "$PY" -c "import json,sys; raise SystemExit(0 if json.load(open('$OUT/l1.json'))['all_matched'] else 1)"; then
  note "L1 이 전부 일치하지 않는다 — L2 ~ L4 를 돌리지 않는다"
  exit 1
fi
note "L1 일치"

# L2 는 compose × 동시성마다 홀드아웃 500명을 정확히 한 번씩 보낸다(--l2-requests 0 = 전원).
if [ ! -f "$OUT/l2.json" ]; then
  note "L2 시작"
  (cd personalization && "$PY" ../tools/s1_serving_bench.py --url "$URL" --level l2 --out "$OUT" \
     --customers 500 --history-events "$HISTORY_EVENTS") | tee "$OUT/l2.txt"
else
  note "L2 건너뜀(결과 있음)"
fi

if [ ! -f "$OUT/l3.json" ]; then
  note "L3 시작"
  (cd personalization && "$PY" ../tools/s1_serving_bench.py --url "$URL" --level l3 --out "$OUT" \
     --history-events "$HISTORY_EVENTS") | tee "$OUT/l3.txt"
else
  note "L3 건너뜀(결과 있음)"
fi

# L4 가상 사용자 A/B — 한 서버를 compose 로 가른다. A/A(hybrid 대 hybrid) → A/B(rule 대 hybrid).
if [ ! -f "$OUT/l4-aa/result.json" ]; then
  note "L4 A/A 시작"
  (cd personalization && "$PY" ../tools/v2_virtual_ab.py --mode aa --v2-url "$URL" \
     --a-compose hybrid --aa --customers 5000 --seed 7 --bootstrap 2000 \
     --history-events "$HISTORY_EVENTS" --out "../$OUT/l4-aa") | tee "$OUT/l4-aa.txt"
else
  note "L4 A/A 건너뜀(결과 있음)"
fi
if [ ! -f "$OUT/l4-ab/result.json" ]; then
  note "L4 A/B 시작"
  (cd personalization && "$PY" ../tools/v2_virtual_ab.py --mode aa --v2-url "$URL" \
     --a-compose rule --b-compose hybrid --customers 5000 --seed 7 --bootstrap 2000 \
     --history-events "$HISTORY_EVENTS" --out "../$OUT/l4-ab") | tee "$OUT/l4-ab.txt"
else
  note "L4 A/B 건너뜀(결과 있음)"
fi

note "끝"
