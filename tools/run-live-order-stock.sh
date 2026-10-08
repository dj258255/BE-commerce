#!/usr/bin/env bash
# R12.1 실 경로 확인 — 방송 생성 → 상품을 한정 수량 N으로 고정 → k6로 동시 주문 VUS(=N+α)건 →
# 확정 합계·거절 수 보고. R10(서버 상태로 가격·가능 여부 판정)·R11(기존 주문·결제 흐름
# 그대로 호출)도 같은 호출 경로로 자연히 같이 확인된다.
#
# 전제: commerce가 떠 있고(기본 http://localhost:8080), k6가 설치돼 있다.
#
# 사용:
#   ./tools/run-live-order-stock.sh
#   LIMIT=50 VUS=1000 ./tools/run-live-order-stock.sh   # 명세 R12.1의 실제 수치 그대로
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SELLER_USERNAME="${SELLER_USERNAME:-3}"
SELLER_PASSWORD="${SELLER_PASSWORD:-seller-local-only}"
PRODUCT_ID="${PRODUCT_ID:-1}"
PRICE="${PRICE:-9900}"
LIMIT="${LIMIT:-10}"
VUS="${VUS:-40}"
OUT="${OUT:-docs/performance/runs/$(date +%Y%m%d)-live-order-stock}"
mkdir -p "$OUT"

log() { printf '\033[36m▶\033[0m %s\n' "$*"; }
ok()  { printf '\033[32m✓\033[0m %s\n' "$*"; }
bad() { printf '\033[31m✗\033[0m %s\n' "$*"; }
die() { printf '\033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

json_string() { printf '%s' "$1" | sed -nE "s/.*\"$2\":\"([^\"]*)\".*/\\1/p" | head -1; }
json_number() { printf '%s' "$1" | sed -nE "s/.*\"$2\":([0-9]+).*/\\1/p" | head -1; }

command -v k6 >/dev/null 2>&1 || die "k6를 찾을 수 없습니다."
command -v curl >/dev/null 2>&1 || die "curl을 찾을 수 없습니다."
command -v ffmpeg >/dev/null 2>&1 || die "ffmpeg를 찾을 수 없습니다(상품 고정은 LIVE 방송에서만 되므로 실 송출이 필요합니다)."

MEDIAMTX_HOST="${MEDIAMTX_HOST:-mediamtx}"
MEDIAMTX_RTMP_PORT="${MEDIAMTX_RTMP_PORT:-1935}"

health="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health" || echo ERR)"
[ "$health" = "200" ] || die "commerce($BASE_URL)가 떠 있지 않습니다(health=$health)."

log "판매자 로그인 → 방송 생성 → 실 RTMP 송출로 LIVE 전이 → 상품 ${PRODUCT_ID}을 한정 ${LIMIT}개·특가 ${PRICE}원으로 고정"
SELLER_LOGIN="$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$SELLER_USERNAME\",\"password\":\"$SELLER_PASSWORD\"}")"
SELLER_TOKEN="$(json_string "$SELLER_LOGIN" token)"
[ -n "$SELLER_TOKEN" ] || die "판매자 로그인 실패: $SELLER_LOGIN"

CREATE_RESPONSE="$(curl -s -X POST "$BASE_URL/api/v1/live/broadcasts" \
  -H "Authorization: Bearer $SELLER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"한정 수량 동시성 확인 방송"}')"
BROADCAST_ID="$(json_number "$CREATE_RESPONSE" id)"
STREAM_KEY="$(json_string "$CREATE_RESPONSE" streamKey)"
[ -n "$BROADCAST_ID" ] && [ -n "$STREAM_KEY" ] || die "방송 생성 실패: $CREATE_RESPONSE"
ok "방송 생성 id=$BROADCAST_ID"

# 상품 고정은 LIVE 방송에서만 된다(R8 인수 조건) — 경로는 방송 공개 id, 비밀은 쿼리(?pass=,
# ADR-084 보안 수정)로 분리해 실제 RTMP 송출을 붙인다(verify-live-pin-events.sh와 같은 방식).
ffmpeg -hide_banner -loglevel warning -re \
  -f lavfi -i "testsrc2=size=640x360:rate=25" \
  -f lavfi -i "anullsrc=r=44100:cl=stereo" \
  -c:v libx264 -preset veryfast -tune zerolatency -b:v 800k -g 50 \
  -c:a aac -ar 44100 -b:a 128k \
  -f flv "rtmp://${MEDIAMTX_HOST}:${MEDIAMTX_RTMP_PORT}/live/${BROADCAST_ID}?pass=${STREAM_KEY}" \
  >/tmp/live-order-stock-publish.log 2>&1 &
FFMPEG_PID=$!
trap 'kill "$FFMPEG_PID" 2>/dev/null || true' EXIT

waited=0
while :; do
  STATUS="$(json_string "$(curl -s "$BASE_URL/api/v1/live/broadcasts/$BROADCAST_ID" -H "Authorization: Bearer $SELLER_TOKEN")" status)"
  [ "$STATUS" = "LIVE" ] && break
  waited=$((waited + 1)); [ "$waited" -ge 15 ] && die "LIVE 전이 실패(status=$STATUS) — mediamtx 연결을 확인하세요(로그: /tmp/live-order-stock-publish.log)"
  sleep 1
done
ok "실 RTMP 송출로 LIVE 전이 확인"

PIN_RESPONSE="$(curl -s -X POST "$BASE_URL/api/v1/live/broadcasts/${BROADCAST_ID}/pin" \
  -H "Authorization: Bearer $SELLER_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"productId\":${PRODUCT_ID},\"price\":${PRICE},\"limitedQuantity\":${LIMIT}}")"
echo "$PIN_RESPONSE" | grep -q '"type":"PINNED"' || die "고정 실패: $PIN_RESPONSE"
ok "고정 완료: $PIN_RESPONSE"

# 주문 생성(R10·R11·R12)은 지금 고정 상태만 보고 판정한다 — 방송이 계속 LIVE일 필요는 없으므로
# (LiveOrderService는 LivePin만 보고 LiveBroadcast 상태는 보지 않는다, ADR-085) 송출은 여기서 끊는다.
kill "$FFMPEG_PID" 2>/dev/null || true
wait "$FFMPEG_PID" 2>/dev/null || true
trap - EXIT

echo
log "k6로 ${VUS}명이 동시에 각 1개씩 주문(한정 수량 N=${LIMIT})"
k6 run --quiet --summary-export "$OUT/k6-summary.json" \
  -e BASE_URL="$BASE_URL" -e BROADCAST_ID="$BROADCAST_ID" -e PRODUCT_ID="$PRODUCT_ID" -e VUS="$VUS" \
  k6/live-order-stock.js | tee "$OUT/k6-output.txt"

echo
log "결과"
CONFIRMED="$(grep -oE 'live_order_confirmed[.]*: *[0-9]+' "$OUT/k6-output.txt" | grep -oE '[0-9]+$' | head -1 || true)"
SOLD_OUT="$(grep -oE 'live_order_sold_out[.]*: *[0-9]+' "$OUT/k6-output.txt" | grep -oE '[0-9]+$' | head -1 || true)"
RATE_LIMITED="$(grep -oE 'live_order_rate_limited[.]*: *[0-9]+' "$OUT/k6-output.txt" | grep -oE '[0-9]+$' | head -1 || true)"
OTHER="$(grep -oE 'live_order_other[.]*: *[0-9]+' "$OUT/k6-output.txt" | grep -oE '[0-9]+$' | head -1 || true)"
echo "  한정 수량 N=${LIMIT}, 동시 요청 수=${VUS}"
echo "  확정(201)=${CONFIRMED:-0}  매진 거절(409)=${SOLD_OUT:-0}  유입제어(429)=${RATE_LIMITED:-0}  그외=${OTHER:-0}"
if [ "${CONFIRMED:-0}" = "$LIMIT" ]; then
  ok "확정 합계(${CONFIRMED})가 한정 수량(${LIMIT})과 정확히 일치합니다 — R12.1 통과"
else
  bad "확정 합계(${CONFIRMED:-0})가 한정 수량(${LIMIT})과 다릅니다 — rate_limited(${RATE_LIMITED:-0})가 0보다 크면 429가 섞여 들어간 것이니 VUS를 줄이거나 app.ratelimit 설정을 확인하세요"
fi
echo "  원본 결과: $OUT/k6-output.txt, $OUT/k6-summary.json"
