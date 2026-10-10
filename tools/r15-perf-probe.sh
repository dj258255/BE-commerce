#!/usr/bin/env bash
# R15 성능 조사용 임시 스크립트(본 요청 전용) — 서비스 코드는 바꾸지 않고, 거절 경로
# (LiveOrderService.order → pinRepository.findByBroadcastId → 거절)에 동시 요청을 걸어
# Tomcat 스레드 풀(max 100)·Hikari DB 풀(20)의 큐잉이 지연에 얼마나 기여하는지를 잰다.
#
# 사용: TOKEN=... CONCURRENCY=300 ./tools/r15-perf-probe.sh
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TOKEN="${TOKEN:?TOKEN env var required}"
BROADCAST_ID="${BROADCAST_ID:-999002}"
PRODUCT_ID="${PRODUCT_ID:-1}"
CONCURRENCY="${CONCURRENCY:-300}"
OUT_DIR="$(mktemp -d /tmp/r15-probe.XXXXXX)"

for i in $(seq 1 "$CONCURRENCY"); do
  (
    t0=$(date +%s%N)
    code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/live/broadcasts/${BROADCAST_ID}/orders" \
      -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      -H "Idempotency-Key: probe-$$-$i" -d "{\"productId\":${PRODUCT_ID}}")
    t1=$(date +%s%N)
    echo "$(( (t1 - t0) / 1000000 )) $code" > "$OUT_DIR/$i.out"
  ) &
done
wait

cat "$OUT_DIR"/*.out | awk '{print $1}' | sort -n > "$OUT_DIR/sorted_ms.txt"
n=$(wc -l < "$OUT_DIR/sorted_ms.txt")
p50_line=$(( (n * 50 + 99) / 100 ))
p95_line=$(( (n * 95 + 99) / 100 ))
p99_line=$(( (n * 99 + 99) / 100 ))
echo "n=$n"
echo "p50=$(sed -n "${p50_line}p" "$OUT_DIR/sorted_ms.txt")ms"
echo "p95=$(sed -n "${p95_line}p" "$OUT_DIR/sorted_ms.txt")ms"
echo "p99=$(sed -n "${p99_line}p" "$OUT_DIR/sorted_ms.txt")ms"
echo "max=$(tail -1 "$OUT_DIR/sorted_ms.txt")ms"
echo "status 분포:"
cat "$OUT_DIR"/*.out | awk '{print $2}' | sort | uniq -c
rm -rf "$OUT_DIR"
