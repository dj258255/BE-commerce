#!/usr/bin/env bash
# R12.1·R12.2 실 경로 확인 — 방송 생성 → 상품을 한정 수량 N으로 고정 → 동시 주문 VUS(=N+α)건 →
# 확정 합계·거절 수 보고. 이어서 같은 방송에서 상품을 "다시 고정"(완판 후 재고정)해 2차로 한 번
# 더 돌린다 — 재고정 결함 수정(ADR-085, generation 분리)이 실 경로에서도 지켜지는지 확인한다.
# R10(서버 상태로 가격·가능 여부 판정)·R11(기존 주문·결제 흐름 그대로 호출)도 같은 호출 경로로
# 자연히 같이 확인된다.
#
# k6가 있으면 k6(k6/live-order-stock.js)를 쓰고, 없으면(이 b-studio 샌드박스처럼) curl
# 백그라운드 프로세스로 동등한 동시 요청을 만든다 — 둘 다 "서로 다른 계정이 동시에 각 1건씩
# 주문"이라는 같은 조건이다.
#
# 전제: commerce가 떠 있고(기본 http://localhost:8080), ffmpeg가 있다(상품 고정은 LIVE
# 방송에서만 되므로 실 송출이 필요하다).
#
# 사용:
#   ./tools/run-live-order-stock.sh
#   LIMIT=50 LIMIT2=80 VUS=1000 ./tools/run-live-order-stock.sh   # 명세 R12.1의 실제 수치에 가깝게
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SELLER_USERNAME="${SELLER_USERNAME:-3}"
SELLER_PASSWORD="${SELLER_PASSWORD:-seller-local-only}"
PRODUCT_ID="${PRODUCT_ID:-1}"
PRICE="${PRICE:-9900}"
LIMIT="${LIMIT:-5}"            # 1차 고정 한정 수량
LIMIT2="${LIMIT2:-8}"          # 재고정(2차) 한정 수량 — 1차와 다른 값이어야 "섞이지 않음"이 드러난다
VUS="${VUS:-15}"               # 1차 동시 주문 수(N+α)
VUS2="${VUS2:-20}"              # 2차 동시 주문 수
MEDIAMTX_HOST="${MEDIAMTX_HOST:-mediamtx}"
MEDIAMTX_RTMP_PORT="${MEDIAMTX_RTMP_PORT:-1935}"
OUT="${OUT:-docs/performance/runs/$(date +%Y%m%d)-live-order-stock}"
WORKDIR="$(mktemp -d /tmp/live-order-stock.XXXXXX)"
mkdir -p "$OUT"

log() { printf '\033[36m▶\033[0m %s\n' "$*"; }
ok()  { printf '\033[32m✓\033[0m %s\n' "$*"; }
bad() { printf '\033[31m✗\033[0m %s\n' "$*"; }
die() { printf '\033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

json_string() { printf '%s' "$1" | sed -nE "s/.*\"$2\":\"([^\"]*)\".*/\\1/p" | head -1; }
json_number() { printf '%s' "$1" | sed -nE "s/.*\"$2\":([0-9]+).*/\\1/p" | head -1; }

command -v curl >/dev/null 2>&1 || die "curl을 찾을 수 없습니다."
command -v ffmpeg >/dev/null 2>&1 || die "ffmpeg를 찾을 수 없습니다(상품 고정은 LIVE 방송에서만 되므로 실 송출이 필요합니다)."
HAVE_K6=0
command -v k6 >/dev/null 2>&1 && HAVE_K6=1

health="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health" || echo ERR)"
[ "$health" = "200" ] || die "commerce($BASE_URL)가 떠 있지 않습니다(health=$health)."

log "판매자 로그인 → 방송 생성 → 실 RTMP 송출로 LIVE 전이"
SELLER_LOGIN="$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$SELLER_USERNAME\",\"password\":\"$SELLER_PASSWORD\"}")"
SELLER_TOKEN="$(json_string "$SELLER_LOGIN" token)"
[ -n "$SELLER_TOKEN" ] || die "판매자 로그인 실패: $SELLER_LOGIN"

CREATE_RESPONSE="$(curl -s -X POST "$BASE_URL/api/v1/live/broadcasts" \
  -H "Authorization: Bearer $SELLER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"한정 수량 동시성·재고정 확인 방송"}')"
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
ok "실 RTMP 송출로 LIVE 전이 확인"

# 주문 생성(R10·R11·R12)은 지금 고정 상태만 보고 판정한다 — 방송이 계속 LIVE일 필요는 없으므로
# (LiveOrderService는 LivePin만 보고 LiveBroadcast 상태는 보지 않는다, ADR-085) 송출은 고정이
# 끝나면 끊는다.
pin_product() {
  local limit="$1"
  PIN_RESPONSE="$(curl -s -X POST "$BASE_URL/api/v1/live/broadcasts/${BROADCAST_ID}/pin" \
    -H "Authorization: Bearer $SELLER_TOKEN" -H 'Content-Type: application/json' \
    -d "{\"productId\":${PRODUCT_ID},\"price\":${PRICE},\"limitedQuantity\":${limit}}")"
  echo "$PIN_RESPONSE" | grep -q '"type":"PINNED"' || die "고정 실패: $PIN_RESPONSE"
}

# k6 모드: k6/live-order-stock.js가 자체적으로 계정 풀을 만든다(signup/login).
run_round_k6() {
  local limit="$1" vus="$2" label="$3" out_prefix="$4"
  k6 run --quiet --summary-export "$OUT/${out_prefix}-k6-summary.json" \
    -e BASE_URL="$BASE_URL" -e BROADCAST_ID="$BROADCAST_ID" -e PRODUCT_ID="$PRODUCT_ID" -e VUS="$vus" \
    k6/live-order-stock.js | tee "$OUT/${out_prefix}-k6-output.txt"
  CONFIRMED="$(grep -oE 'live_order_confirmed[.]*: *[0-9]+' "$OUT/${out_prefix}-k6-output.txt" | grep -oE '[0-9]+$' | head -1 || echo 0)"
  REJECTED="$(grep -oE 'live_order_sold_out[.]*: *[0-9]+' "$OUT/${out_prefix}-k6-output.txt" | grep -oE '[0-9]+$' | head -1 || echo 0)"
}

# curl 모드: 계정을 미리 만들고(signup+login, IP 제한 회피용 간격 포함) 백그라운드 curl로 동시 주문.
run_round_curl() {
  local limit="$1" vus="$2" label="$3" out_prefix="$4"
  local run_tag="$(date +%s%N)"
  rm -f "$WORKDIR/${out_prefix}-tok_"* "$WORKDIR/${out_prefix}-code_"*

  for i in $(seq 1 "$vus"); do
    local email="live-order-${run_tag}-${i}@load.test"
    curl -s -X POST "$BASE_URL/api/v1/members/signup" -H 'Content-Type: application/json' \
      -d "{\"email\":\"$email\",\"password\":\"test-1234\"}" >/dev/null
    local login
    login="$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
      -d "{\"username\":\"$email\",\"password\":\"test-1234\"}")"
    json_string "$login" token > "$WORKDIR/${out_prefix}-tok_$i.txt"
    sleep 0.25
  done

  local pids=()
  for i in $(seq 1 "$vus"); do
    local tok
    tok="$(cat "$WORKDIR/${out_prefix}-tok_$i.txt")"
    ( curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/live/broadcasts/${BROADCAST_ID}/orders" \
        -H "Authorization: Bearer $tok" -H "Idempotency-Key: ${out_prefix}-${run_tag}-$i" -H 'Content-Type: application/json' \
        -d "{\"productId\":${PRODUCT_ID}}" > "$WORKDIR/${out_prefix}-code_$i.txt" ) &
    pids+=("$!")
  done
  # 주의: 맨 위에서 백그라운드로 띄운 ffmpeg(무한 testsrc2)도 이 셸의 백그라운드 작업이다 —
  # 맨 wait(인자 없음)를 쓰면 ffmpeg가 절대 안 끝나므로 영원히 멈춘다. 방금 띄운 curl PID만 기다린다.
  for p in "${pids[@]}"; do
    wait "$p" 2>/dev/null || true
  done

  CONFIRMED=0
  REJECTED=0
  local other=0
  for i in $(seq 1 "$vus"); do
    local code
    code="$(cat "$WORKDIR/${out_prefix}-code_$i.txt" 2>/dev/null || echo '?')"
    case "$code" in
      201) CONFIRMED=$((CONFIRMED + 1)) ;;
      409) REJECTED=$((REJECTED + 1)) ;;
      *) other=$((other + 1)) ;;
    esac
  done
  echo "  확정(201)=$CONFIRMED  매진 거절(409)=$REJECTED  그외=$other" | tee "$OUT/${out_prefix}-curl-output.txt"
}

run_round() {
  local limit="$1" vus="$2" label="$3" out_prefix="$4"
  echo
  log "[$label] 상품 ${PRODUCT_ID}을 한정 ${limit}개·특가 ${PRICE}원으로 고정"
  pin_product "$limit"
  ok "고정 완료: $PIN_RESPONSE"
  log "[$label] ${vus}명이 동시에 각 1개씩 주문(한정 수량 N=${limit})"
  if [ "$HAVE_K6" -eq 1 ]; then
    run_round_k6 "$limit" "$vus" "$label" "$out_prefix"
  else
    run_round_curl "$limit" "$vus" "$label" "$out_prefix"
  fi
  echo "  한정 수량 N=${limit}, 동시 요청 수=${vus}, 확정=${CONFIRMED:-0}, 거절=${REJECTED:-0}"
  if [ "${CONFIRMED:-0}" = "$limit" ]; then
    ok "[$label] 확정 합계(${CONFIRMED})가 한정 수량(${limit})과 정확히 일치합니다"
    return 0
  else
    bad "[$label] 확정 합계(${CONFIRMED:-0})가 한정 수량(${limit})과 다릅니다"
    return 1
  fi
}

ROUND1_OK=1
ROUND2_OK=1
run_round "$LIMIT" "$VUS" "1차 고정" round1 || ROUND1_OK=0
ROUND1_CONFIRMED="${CONFIRMED:-0}"

log "판매자가 고정을 해제했다가 같은 상품을 새 한정 수량으로 다시 고정합니다(재고정 — ADR-085 수정 확인)"
UNPIN_RESPONSE="$(curl -s -X DELETE "$BASE_URL/api/v1/live/broadcasts/${BROADCAST_ID}/pin" -H "Authorization: Bearer $SELLER_TOKEN")"
echo "$UNPIN_RESPONSE" | grep -q '"type":"UNPINNED"' || die "해제 실패: $UNPIN_RESPONSE"
ok "해제 완료"

run_round "$LIMIT2" "$VUS2" "재고정(2차)" round2 || ROUND2_OK=0
ROUND2_CONFIRMED="${CONFIRMED:-0}"

kill "$FFMPEG_PID" 2>/dev/null || true
wait "$FFMPEG_PID" 2>/dev/null || true
trap - EXIT

echo
log "=== 최종 결과 ==="
echo "  1차(한정 ${LIMIT}개, 동시 ${VUS}명): 확정 ${ROUND1_CONFIRMED}"
echo "  재고정(한정 ${LIMIT2}개, 동시 ${VUS2}명): 확정 ${ROUND2_CONFIRMED}"
if [ "$ROUND1_OK" -eq 1 ] && [ "$ROUND2_OK" -eq 1 ]; then
  ok "재고정 전후 모두 확정 합계가 그 라운드의 한정 수량과 정확히 일치합니다 — 재고정 결함이 고쳐졌습니다(R12, ADR-085)"
  exit 0
else
  bad "하나 이상의 라운드에서 확정 합계가 한정 수량과 다릅니다 — 위 상세 로그를 확인하세요"
  exit 1
fi
