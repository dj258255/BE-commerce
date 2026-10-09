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
LIMIT3="${LIMIT3:-3}"           # R14 매진 WS 지연 측정용 한정 수량(작게 — 순차 주문이라 크면 느리다)
HOLD_TTL_WAIT_SECONDS="${HOLD_TTL_WAIT_SECONDS:-310}"   # R13 — 기본 TTL 5분 + 반환 스캐너 주기(5초) 여유
WS_LISTEN_MS="${WS_LISTEN_MS:-15000}"
MEASURE_WS_LATENCY="${MEASURE_WS_LATENCY:-1}"   # R14: 매진 WS 지연 측정 단계를 돌릴지
VERIFY_UNPAID_RETURN="${VERIFY_UNPAID_RETURN:-1}"   # R13: 미결제 반환 단계를 돌릴지(기본 5분+ 걸림)
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

# R14 WS 지연 측정용 — JDK WebSocket 클라이언트로 수신 시각(ms)을 찍는다(verify-live-pin-events.sh와 같은 기법).
cat > "$WORKDIR/LivePinWsListener.java" <<'JAVA'
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;

public class LivePinWsListener {
    public static void main(String[] args) throws Exception {
        String url = args[0];
        long listenMs = Long.parseLong(args[1]);
        HttpClient client = HttpClient.newHttpClient();
        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder buf = new StringBuilder();
            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                buf.append(data);
                if (last) {
                    System.out.println("RECV_AT_MS:" + System.currentTimeMillis() + " MSG:" + buf);
                    System.out.flush();
                    buf.setLength(0);
                }
                ws.request(1);
                return null;
            }
            @Override
            public void onError(WebSocket ws, Throwable error) {
                System.err.println("WS_ERROR:" + error);
            }
        };
        WebSocket ws = client.newWebSocketBuilder().buildAsync(URI.create(url), listener).join();
        System.out.println("CONNECTED_AT_MS:" + System.currentTimeMillis());
        System.out.flush();
        Thread.sleep(listenMs);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }
}
JAVA
if command -v javac >/dev/null 2>&1; then
  javac -d "$WORKDIR" "$WORKDIR/LivePinWsListener.java" 2>"$WORKDIR/javac.log" || {
    echo "WebSocket 리스너 컴파일 실패 — R14 WS 지연 측정을 건너뜁니다(로그: $WORKDIR/javac.log)" >&2
    MEASURE_WS_LATENCY=0
  }
else
  echo "javac가 없어 R14 WS 지연 측정을 건너뜁니다(JDK 필요)" >&2
  MEASURE_WS_LATENCY=0
fi

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

# R14: 마지막 1개가 확정되는 순간부터 WS로 매진(remainingQuantity=0) 메시지가 올 때까지 걸리는
# 시간을 잰다 — 순차로 limit-1개를 채우고, 마지막 1건을 보내는 그 순간(T0)부터 잰다.
measure_sold_out_ws_latency() {
  local limit="$1" out_prefix="$2"
  echo
  log "[R14 WS 지연 측정] 상품을 한정 ${limit}개로 고정하고 WS 리스너를 연 뒤, 순차로 ${limit}건을 "
  log "주문해 마지막 주문이 매진을 만드는 순간부터 WS 수신까지 걸리는 시간을 잰다"
  pin_product "$limit"
  ok "고정 완료: $PIN_RESPONSE"

  local ws_url="ws://localhost:8080/api/v1/live/broadcasts/${BROADCAST_ID}/pins/ws"
  java -cp "$WORKDIR" LivePinWsListener "$ws_url" "$WS_LISTEN_MS" \
    >"$WORKDIR/${out_prefix}-ws.log" 2>"$WORKDIR/${out_prefix}-ws-err.log" &
  local ws_pid=$!
  sleep 1   # 접속·최초 스냅샷 수신 대기

  local run_tag="$(date +%s%N)"
  for i in $(seq 1 "$limit"); do
    local email="ws-latency-${run_tag}-${i}@load.test"
    curl -s -X POST "$BASE_URL/api/v1/members/signup" -H 'Content-Type: application/json' \
      -d "{\"email\":\"$email\",\"password\":\"test-1234\"}" >/dev/null
    local login
    login="$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
      -d "{\"username\":\"$email\",\"password\":\"test-1234\"}")"
    json_string "$login" token > "$WORKDIR/${out_prefix}-tok_$i.txt"
    sleep 0.25
  done

  for i in $(seq 1 $((limit - 1))); do
    local tok; tok="$(cat "$WORKDIR/${out_prefix}-tok_$i.txt")"
    curl -s -o /dev/null -X POST "$BASE_URL/api/v1/live/broadcasts/${BROADCAST_ID}/orders" \
      -H "Authorization: Bearer $tok" -H "Idempotency-Key: ${out_prefix}-${run_tag}-$i" -H 'Content-Type: application/json' \
      -d "{\"productId\":${PRODUCT_ID}}" >/dev/null
  done

  local tok_last; tok_last="$(cat "$WORKDIR/${out_prefix}-tok_${limit}.txt")"
  local t0_ms; t0_ms=$(($(date +%s%N) / 1000000))
  curl -s -o /dev/null -X POST "$BASE_URL/api/v1/live/broadcasts/${BROADCAST_ID}/orders" \
    -H "Authorization: Bearer $tok_last" -H "Idempotency-Key: ${out_prefix}-${run_tag}-last" -H 'Content-Type: application/json' \
    -d "{\"productId\":${PRODUCT_ID}}" >/dev/null

  sleep 2   # WS 리스너가 매진 메시지를 받을 시간
  kill "$ws_pid" 2>/dev/null; wait "$ws_pid" 2>/dev/null || true

  local sold_out_line
  sold_out_line="$(grep 'remainingQuantity":0' "$WORKDIR/${out_prefix}-ws.log" | tail -1)"
  if [ -z "$sold_out_line" ]; then
    bad "[R14 WS 지연] 매진(remainingQuantity=0) 메시지를 못 받음 — 로그: $WORKDIR/${out_prefix}-ws.log"
    cat "$WORKDIR/${out_prefix}-ws.log" | sed 's/^/  ws> /'
    WS_LATENCY_MS=""
    return 1
  fi
  local recv_ms
  recv_ms="$(echo "$sold_out_line" | sed -nE 's/^RECV_AT_MS:([0-9]+).*/\1/p')"
  WS_LATENCY_MS=$((recv_ms - t0_ms))
  echo "매진 WS 지연: ${WS_LATENCY_MS}ms (한정 ${limit}개, T0=${t0_ms}ms, 수신=${recv_ms}ms)" \
    > "$OUT/${out_prefix}-ws-latency.txt"
  if [ "$WS_LATENCY_MS" -ge 0 ] && [ "$WS_LATENCY_MS" -lt 1000 ]; then
    ok "[R14 WS 지연] 마지막 주문 전송부터 매진 WS 메시지 수신까지 ${WS_LATENCY_MS}ms — 1,000ms 이내"
  else
    bad "[R14 WS 지연] ${WS_LATENCY_MS}ms — 1,000ms를 벗어났거나 시계가 안 맞습니다"
  fi
}

# R13.1: 1건을 선점만 하고(결제 안 함) TTL이 지나도록 기다린 뒤, 그 수량만큼 다시 전부 주문할 수
# 있는지로 "반환"을 확인한다(선점 수를 직접 조회하는 API가 없어 간접적으로 본다).
verify_unpaid_hold_return() {
  local limit="$1" out_prefix="$2"
  echo
  log "[R13 미결제 반환] 상품을 한정 ${limit}개로 고정하고 1건만 주문(결제 안 함) → "
  log "${HOLD_TTL_WAIT_SECONDS}초 대기(기본 TTL 5분 + 반환 스캐너 주기 여유) → 다시 ${limit}건을 주문해 "
  log "전부 확정되는지 본다(반환 안 됐으면 1건은 거절돼야 한다)"
  pin_product "$limit"
  ok "고정 완료: $PIN_RESPONSE"

  local run_tag="$(date +%s%N)"
  local email="unpaid-hold-${run_tag}@load.test"
  curl -s -X POST "$BASE_URL/api/v1/members/signup" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"test-1234\"}" >/dev/null
  local login tok
  login="$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$email\",\"password\":\"test-1234\"}")"
  tok="$(json_string "$login" token)"
  local order_res
  order_res="$(curl -s -X POST "$BASE_URL/api/v1/live/broadcasts/${BROADCAST_ID}/orders" \
    -H "Authorization: Bearer $tok" -H "Idempotency-Key: ${out_prefix}-${run_tag}" -H 'Content-Type: application/json' \
    -d "{\"productId\":${PRODUCT_ID}}")"
  echo "$order_res" | grep -q orderNo || die "[R13 미결제 반환] 선점 주문 생성 실패: $order_res"
  ok "선점 주문 생성(결제는 하지 않는다): $order_res"

  log "[R13 미결제 반환] ${HOLD_TTL_WAIT_SECONDS}초 대기 중…"
  sleep "$HOLD_TTL_WAIT_SECONDS"

  run_round_curl "$limit" "$limit" "미결제 반환 확인" "${out_prefix}-after"
  UNPAID_RETURN_CONFIRMED="${CONFIRMED:-0}"
  if [ "$UNPAID_RETURN_CONFIRMED" = "$limit" ]; then
    ok "[R13 미결제 반환] TTL 뒤 ${limit}건 전부 확정 — 미결제 선점이 반환됐습니다(R13.1)"
    return 0
  else
    bad "[R13 미결제 반환] 확정=${UNPAID_RETURN_CONFIRMED} (기대 ${limit}) — 반환이 안 됐거나 다른 문제"
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

WS_LATENCY_OK=1
WS_LATENCY_MS=""
if [ "$MEASURE_WS_LATENCY" -eq 1 ]; then
  measure_sold_out_ws_latency "$LIMIT3" ws_latency || WS_LATENCY_OK=0
fi

UNPAID_RETURN_OK=1
UNPAID_RETURN_CONFIRMED=""
if [ "$VERIFY_UNPAID_RETURN" -eq 1 ]; then
  verify_unpaid_hold_return "$LIMIT3" unpaid_return || UNPAID_RETURN_OK=0
fi

kill "$FFMPEG_PID" 2>/dev/null || true
wait "$FFMPEG_PID" 2>/dev/null || true
trap - EXIT

echo
log "=== 최종 결과 ==="
echo "  1차(한정 ${LIMIT}개, 동시 ${VUS}명): 확정 ${ROUND1_CONFIRMED}"
echo "  재고정(한정 ${LIMIT2}개, 동시 ${VUS2}명): 확정 ${ROUND2_CONFIRMED}"
[ "$MEASURE_WS_LATENCY" -eq 1 ] && echo "  R14 매진 WS 지연(한정 ${LIMIT3}개): ${WS_LATENCY_MS:-측정 실패}ms"
[ "$VERIFY_UNPAID_RETURN" -eq 1 ] && echo "  R13 미결제 반환(${HOLD_TTL_WAIT_SECONDS}초 대기 후 한정 ${LIMIT3}개 재주문): 확정 ${UNPAID_RETURN_CONFIRMED:-?}/${LIMIT3}"

ALL_OK=1
[ "$ROUND1_OK" -eq 1 ] && [ "$ROUND2_OK" -eq 1 ] || ALL_OK=0
[ "$MEASURE_WS_LATENCY" -eq 1 ] && [ "$WS_LATENCY_OK" -ne 1 ] && ALL_OK=0
[ "$VERIFY_UNPAID_RETURN" -eq 1 ] && [ "$UNPAID_RETURN_OK" -ne 1 ] && ALL_OK=0

if [ "$ALL_OK" -eq 1 ]; then
  ok "전부 통과 — 재고정(R12)·매진 WS 지연(R14)·미결제 반환(R13)이 실 경로에서 모두 확인됐습니다(ADR-085)"
  exit 0
else
  bad "하나 이상의 단계가 기대와 다릅니다 — 위 상세 로그를 확인하세요"
  exit 1
fi
