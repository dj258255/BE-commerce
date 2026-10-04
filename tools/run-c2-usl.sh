#!/usr/bin/env bash
# C2(#466) 수평 확장(USL) 측정. 예측은 docs/40-capacity-model.md "(i) 수평 확장 예측(측정 전)" 절에
# 측정 전에 커밋돼 있다. 이 스크립트는 그 예측을 재는 하네스만 돌린다.
#
#   PY=/path/to/genpage-venv/bin/python bash tools/run-c2-usl.sh
#
# 같은 ckpt · scores 로 서버 2대(포트 18866 · 18867, 스레드 2 · CPU)를 띄우고
# 1대 동시성 {1,4,8} → 2대 동시성 {4,8} 을 차례로 재서
# personalization/docs/runs/<날짜>-c2-usl/ 에 남기고 서버를 끈다. 이미 있는 결과는 건너뛴다.
set -uo pipefail
cd "$(dirname "$0")/.."

PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-$(pwd)/personalization/data}
OUT=${OUT:-personalization/docs/runs/$(date +%Y%m%d)-c2-usl}
case "$OUT" in /*) ;; *) OUT=$(pwd)/$OUT ;; esac
PORT1=${PORT1:-18866}
PORT2=${PORT2:-18867}
URL1="http://127.0.0.1:$PORT1"
URL2="http://127.0.0.1:$PORT2"
CKPT=${CKPT:-$GENPAGE_DATA/hm/model/genpage2/final/ckpt/f-base-full-1ep}
SCORES=${SCORES:-$GENPAGE_DATA/hm/runs-archive/20260929-v2-d3-page/final/scores.json.gz}
CUSTOMERS=${CUSTOMERS:-500}
mkdir -p "$OUT"

note() { echo "== $* $(date +%H:%M)" | tee -a "$OUT/progress.txt"; }

for f in "$SCORES"; do
  [ -f "$f" ] || { note "입력 파일이 없다: $f"; exit 1; }
done
[ -d "$CKPT" ] || { note "ckpt 디렉터리가 없다: $CKPT"; exit 1; }

SRV1=""
SRV2=""
cleanup() {
  if [ -n "$SRV1" ]; then kill "$SRV1" 2>/dev/null || true; fi
  if [ -n "$SRV2" ]; then kill "$SRV2" 2>/dev/null || true; fi
}
trap cleanup EXIT

note "서버 2대 기동 :$PORT1 :$PORT2"
"$PY" personalization/serving/genpage2_server.py --mode final --ckpt "$CKPT" --scores "$SCORES" \
  --hybrid-lambda 4 --threads 2 --device cpu --port "$PORT1" \
  > "$OUT/server-1.log" 2>&1 &
SRV1=$!
"$PY" personalization/serving/genpage2_server.py --mode final --ckpt "$CKPT" --scores "$SCORES" \
  --hybrid-lambda 4 --threads 2 --device cpu --port "$PORT2" \
  > "$OUT/server-2.log" 2>&1 &
SRV2=$!

for url in "$URL1" "$URL2"; do
  for _ in $(seq 1 300); do curl -sf "$url/health" >/dev/null 2>&1 && break; sleep 1; done
  curl -sf "$url/health" >/dev/null 2>&1 || { note "서버가 안 떴다($url) — $OUT/server-*.log 를 보라"; exit 1; }
done
note "서버 준비됨"

# 1대는 포트 18866 만 쓴다(18867 은 떠 있지만 재지 않는다). 동시성 1 · 4 · 8.
if [ ! -f "$OUT/usl-1srv.json" ]; then
  note "1대 동시성 {1,4,8} 시작"
  (cd personalization && "$PY" ../tools/usl_bench.py --urls "$URL1" --concurrency 1 4 8 \
     --customers "$CUSTOMERS" --out "$OUT") | tee "$OUT/1srv.txt"
else
  note "1대 건너뜀(결과 있음)"
fi

# 2대는 두 포트를 번갈아 쓴다. 동시성 4 · 8.
if [ ! -f "$OUT/usl-2srv.json" ]; then
  note "2대 동시성 {4,8} 시작"
  (cd personalization && "$PY" ../tools/usl_bench.py --urls "$URL1" "$URL2" --concurrency 4 8 \
     --customers "$CUSTOMERS" --out "$OUT") | tee "$OUT/2srv.txt"
else
  note "2대 건너뜀(결과 있음)"
fi

note "끝"
