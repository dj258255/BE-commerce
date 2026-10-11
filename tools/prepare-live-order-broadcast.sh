#!/usr/bin/env bash
# R15 준비 스크립트 — k6/live-order-flash-sale.js가 쓸 방송을 만들고 상품을 한정 수량
# LIMIT개로 고정한 뒤 BROADCAST_ID를 표준출력에 찍는다(로그는 모두 표준에러로 보내므로
# `BROADCAST_ID=$(./tools/prepare-live-order-broadcast.sh | sed -nE 's/BROADCAST_ID=//p')`
# 처럼 값만 바로 받을 수 있다).
#
# 상품 고정은 LIVE 방송에서만 되므로(R8 인수 조건) 잠깐 실 RTMP 송출을 붙였다가, 고정이
# 끝나면 바로 끊는다 — 주문 생성(R10·R11·R12)은 지금 고정 상태만 보고 판정하므로(LiveOrderService는
# LivePin만 보고 LiveBroadcast 상태는 보지 않는다, ADR-085) 이후 방송이 계속 LIVE일 필요는 없다.
#
# 사용(순서는 docs/performance/live-order-r15.md 참고):
#   LIMIT=50 ./tools/prepare-live-order-broadcast.sh
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SELLER_USERNAME="${SELLER_USERNAME:-3}"
SELLER_PASSWORD="${SELLER_PASSWORD:-seller-local-only}"
PRODUCT_ID="${PRODUCT_ID:-1}"
PRICE="${PRICE:-9900}"
LIMIT="${LIMIT:-50}"
MEDIAMTX_HOST="${MEDIAMTX_HOST:-mediamtx}"
MEDIAMTX_RTMP_PORT="${MEDIAMTX_RTMP_PORT:-1935}"
WORKDIR="$(mktemp -d /tmp/live-order-broadcast.XXXXXX)"

log() { printf '\033[36m▶\033[0m %s\n' "$*" >&2; }
ok()  { printf '\033[32m✓\033[0m %s\n' "$*" >&2; }
die() { printf '\033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

json_string() { printf '%s' "$1" | sed -nE "s/.*\"$2\":\"([^\"]*)\".*/\\1/p" | head -1; }
json_number() { printf '%s' "$1" | sed -nE "s/.*\"$2\":([0-9]+).*/\\1/p" | head -1; }

command -v curl >/dev/null 2>&1 || die "curl을 찾을 수 없습니다."
command -v ffmpeg >/dev/null 2>&1 || die "ffmpeg를 찾을 수 없습니다(상품 고정은 LIVE 방송에서만 됩니다)."

health="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health" || echo ERR)"
[ "$health" = "200" ] || die "commerce($BASE_URL)가 떠 있지 않습니다(health=$health)."

log "판매자 로그인 → 방송 생성 → 실 RTMP 송출로 LIVE 전이 → 상품을 한정 ${LIMIT}개로 고정"
SELLER_LOGIN="$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$SELLER_USERNAME\",\"password\":\"$SELLER_PASSWORD\"}")"
SELLER_TOKEN="$(json_string "$SELLER_LOGIN" token)"
[ -n "$SELLER_TOKEN" ] || die "판매자 로그인 실패: $SELLER_LOGIN"

CREATE_RESPONSE="$(curl -s -X POST "$BASE_URL/api/v1/live/broadcasts" \
  -H "Authorization: Bearer $SELLER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"R15 한정 수량 동시 주문 부하 테스트 방송"}')"
BROADCAST_ID="$(json_number "$CREATE_RESPONSE" id)"
STREAM_KEY="$(json_string "$CREATE_RESPONSE" streamKey)"
[ -n "$BROADCAST_ID" ] && [ -n "$STREAM_KEY" ] || die "방송 생성 실패: $CREATE_RESPONSE"

ffmpeg -hide_banner -loglevel warning -re \
  -f lavfi -i "testsrc2=size=640x360:rate=25" \
  -f lavfi -i "anullsrc=r=44100:cl=stereo" \
  -c:v libx264 -preset veryfast -tune zerolatency -b:v 800k -g 50 \
  -c:a aac -ar 44100 -b:a 128k \
  -f flv "rtmp://${MEDIAMTX_HOST}:${MEDIAMTX_RTMP_PORT}/live/${BROADCAST_ID}?pass=${STREAM_KEY}" \
  >"$WORKDIR/publish.log" 2>&1 &
FFMPEG_PID=$!
trap 'kill "$FFMPEG_PID" 2>/dev/null || true' EXIT

waited=0
while :; do
  STATUS="$(json_string "$(curl -s "$BASE_URL/api/v1/live/broadcasts/$BROADCAST_ID" -H "Authorization: Bearer $SELLER_TOKEN")" status)"
  [ "$STATUS" = "LIVE" ] && break
  waited=$((waited + 1)); [ "$waited" -ge 15 ] && die "LIVE 전이 실패(status=$STATUS) — mediamtx 연결을 확인하세요(로그: $WORKDIR/publish.log)"
  sleep 1
done

PIN_RESPONSE="$(curl -s -X POST "$BASE_URL/api/v1/live/broadcasts/${BROADCAST_ID}/pin" \
  -H "Authorization: Bearer $SELLER_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"productId\":${PRODUCT_ID},\"price\":${PRICE},\"limitedQuantity\":${LIMIT}}")"
echo "$PIN_RESPONSE" | grep -q '"type":"PINNED"' || die "고정 실패: $PIN_RESPONSE"

kill "$FFMPEG_PID" 2>/dev/null || true
wait "$FFMPEG_PID" 2>/dev/null || true
trap - EXIT

ok "방송 ${BROADCAST_ID}, 상품 ${PRODUCT_ID}를 한정 ${LIMIT}개·₩${PRICE}로 고정했습니다"
echo "BROADCAST_ID=${BROADCAST_ID}"
