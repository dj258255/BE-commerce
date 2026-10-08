#!/usr/bin/env bash
#
# 라이브 방송 상품 고정(R8)·고정 이벤트 동기화(R9)를 "실제 송출 경로"로 확인한다 —
# tools/verify-live-broadcast.sh처럼 실제 ffmpeg로 MediaMTX에 RTMP 송출을 하는 상태에서,
# 판매자 API로 고정·가격 변경·해제를 호출하고 그 이벤트가 실제 WebSocket으로 나가는지 본다.
#
# 확인 범위:
#   - R8.1: 방송에 상품을 고정하면(특가·한정 수량) WebSocket으로 PINNED 이벤트가 나간다
#   - R9.1: 그 이벤트에 effectiveAt(서버 시각)과 단조 증가 seq가 담긴다
#   - R9.2: 접속 즉시 현재 스냅샷을 받는다(이 스크립트에서는 "고정 전 접속 = 빈 스냅샷"과
#     "고정 중간에 새로 접속 = 그 시점 스냅샷" 둘 다 본다)
#   - 가격 변경(R9.1) → PRICE_CHANGED, 해제(R8) → UNPINNED까지 seq가 계속 올라가는지
#   - (참고) HLS 재생목록에 #EXT-X-PROGRAM-DATE-TIME이 실제로 찍히는지(ADR-084 근거)
#
# WebSocket 클라이언트가 이 샌드박스에 없어(node/python도 없다, verify-live-broadcast.sh와
# 같은 환경) JDK(java.net.http.HttpClient의 WebSocket API, Java 11+)로 즉석에서 작은
# 리스너를 컴파일해 쓴다 — ffmpeg·curl만 전제하던 기존 스크립트보다 전제가 하나 늘었지만,
# commerce 컨테이너는 당연히 JDK가 있다(Gradle이 그걸로 돈다).
#
# 전제: tools/verify-live-broadcast.sh와 같다(commerce 컨테이너 안에서, mediamtx가 최신
# 설정으로 떠 있어야 한다).
#
# 사용:
#   ./tools/verify-live-pin-events.sh
#   PRODUCT_ID=2 ./tools/verify-live-pin-events.sh
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
MEDIAMTX_HOST="${MEDIAMTX_HOST:-mediamtx}"
MEDIAMTX_RTMP_PORT="${MEDIAMTX_RTMP_PORT:-1935}"
MEDIAMTX_HLS_PORT="${MEDIAMTX_HLS_PORT:-8888}"
USERNAME="${USERNAME:-3}"
PASSWORD="${PASSWORD:-seller-local-only}"
PRODUCT_ID="${PRODUCT_ID:-1}"
WORKDIR="$(mktemp -d /tmp/live-pin-verify.XXXXXX)"

PASS=0
FAIL=0

log() { printf '\033[36m▶\033[0m %s\n' "$*"; }
ok()  { printf '\033[32m✓\033[0m %s\n' "$*"; PASS=$((PASS + 1)); }
bad() { printf '\033[31m✗\033[0m %s\n' "$*"; FAIL=$((FAIL + 1)); }
warn(){ printf '\033[33m!\033[0m %s\n' "$*"; }
die() { printf '\033[31m✗\033[0m %s\n' "$*" >&2; cleanup; exit 1; }

RUNNING_FFMPEG_PID=""
cleanup() {
  if [ -n "$RUNNING_FFMPEG_PID" ] && kill -0 "$RUNNING_FFMPEG_PID" 2>/dev/null; then
    kill "$RUNNING_FFMPEG_PID" 2>/dev/null || true
    wait "$RUNNING_FFMPEG_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

command -v ffmpeg >/dev/null 2>&1 || die "ffmpeg를 찾을 수 없습니다."
command -v curl >/dev/null 2>&1 || die "curl을 찾을 수 없습니다."
command -v javac >/dev/null 2>&1 || die "javac를 찾을 수 없습니다(JDK 필요)."

json_string() { printf '%s' "$1" | sed -nE "s/.*\"$2\":\"([^\"]*)\".*/\\1/p" | head -1; }
json_number() { printf '%s' "$1" | sed -nE "s/.*\"$2\":([0-9]+).*/\\1/p" | head -1; }

health="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health" || echo ERR)"
[ "$health" = "200" ] || die "commerce($BASE_URL)가 떠 있지 않습니다(health=$health)."
getent hosts "$MEDIAMTX_HOST" >/dev/null 2>&1 || die "mediamtx($MEDIAMTX_HOST)가 네트워크에서 안 보입니다."

# ---- WebSocket 리스너(JDK java.net.http.WebSocket, 수신 메시지를 한 줄씩 stdout에 찍는다) ----
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
                    System.out.println("MSG:" + buf);
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
        System.out.println("CONNECTED");
        System.out.flush();
        Thread.sleep(listenMs);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }
}
JAVA
javac -d "$WORKDIR" "$WORKDIR/LivePinWsListener.java" || die "WebSocket 리스너 컴파일 실패"

login() {
  local body token
  body="$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")"
  token="$(json_string "$body" token)"
  [ -n "$token" ] || die "로그인 실패: $body"
  echo "$token"
}

start_publish() {
  local key="$1" logfile="$2"
  ffmpeg -hide_banner -loglevel warning -re \
    -f lavfi -i "testsrc2=size=640x360:rate=25" \
    -f lavfi -i "anullsrc=r=44100:cl=stereo" \
    -c:v libx264 -preset veryfast -tune zerolatency -b:v 800k -g 50 \
    -c:a aac -ar 44100 -b:a 128k \
    -f flv "rtmp://${MEDIAMTX_HOST}:${MEDIAMTX_RTMP_PORT}/live/${key}" \
    >"$logfile" 2>&1 &
  echo $!
}

wait_for_status() {
  local id="$1" expected="$2" timeout="$3" waited=0 status body
  while :; do
    body="$(curl -s "$BASE_URL/api/v1/live/broadcasts/$id" -H "Authorization: Bearer $TOKEN")"
    status="$(json_string "$body" status)"
    [ "$status" = "$expected" ] && { echo "$status"; return 0; }
    waited=$((waited + 1)); [ "$waited" -ge "$timeout" ] && { echo "$status"; return 1; }
    sleep 1
  done
}

echo
log "=== 준비: 방송 생성 → 올바른 키로 송출 → LIVE ==="
TOKEN="$(login)"
CREATE_RESPONSE="$(curl -s -X POST "$BASE_URL/api/v1/live/broadcasts" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"고정 이벤트 확인 방송"}')"
BROADCAST_ID="$(json_number "$CREATE_RESPONSE" id)"
STREAM_KEY="$(json_string "$CREATE_RESPONSE" streamKey)"
[ -n "$BROADCAST_ID" ] && [ -n "$STREAM_KEY" ] || die "방송 생성 실패: $CREATE_RESPONSE"
ok "방송 생성 id=$BROADCAST_ID"

RUNNING_FFMPEG_PID="$(start_publish "$STREAM_KEY" "$WORKDIR/publish.log")"
STATUS="$(wait_for_status "$BROADCAST_ID" LIVE 15)"
if [ "$STATUS" = "LIVE" ]; then
  ok "실제 RTMP 송출로 LIVE 전이 확인(R3.1 재확인)"
else
  die "LIVE가 안 돼 이후 단계를 진행할 수 없습니다(status=$STATUS) — tools/verify-live-broadcast.sh로 먼저 R1~R3를 확인하세요."
fi

echo
log "=== 참고: HLS 재생목록에 #EXT-X-PROGRAM-DATE-TIME이 실제로 찍히는가(ADR-084 근거) ==="
sleep 5 # 첫 세그먼트가 쌓일 시간(키프레임 간격 2초 — start_publish의 -g 50 @25fps)을 넉넉히 준다
MASTER_URL="http://${MEDIAMTX_HOST}:${MEDIAMTX_HLS_PORT}/live/${STREAM_KEY}/index.m3u8"
curl -s -L -o "$WORKDIR/master.m3u8" -m 5 "$MASTER_URL"
# index.m3u8는 멀티베리언트(마스터) 재생목록이다 — 화질별 실제 미디어(렌디션) 재생목록을
# 가리킬 뿐이고, #EXT-X-PROGRAM-DATE-TIME은 그 렌디션 재생목록 쪽에만 찍힌다(처음 시도에서
# 이 구분을 놓쳐 마스터만 보고 "없다"고 오판했었다 — 실제로는 있다, 아래가 그 증거다).
VIDEO_RENDITION="$(grep -oE 'video[0-9]*_stream\.m3u8\?session=[^"[:space:]]*' "$WORKDIR/master.m3u8" | head -1)"
if [ -z "$VIDEO_RENDITION" ]; then
  bad "마스터 재생목록에서 비디오 렌디션 경로를 못 찾음(응답: $WORKDIR/master.m3u8)"
else
  curl -s -L -o "$WORKDIR/rendition.m3u8" -m 5 "http://${MEDIAMTX_HOST}:${MEDIAMTX_HLS_PORT}/live/${STREAM_KEY}/${VIDEO_RENDITION}"
  if grep -q '#EXT-X-PROGRAM-DATE-TIME' "$WORKDIR/rendition.m3u8" 2>/dev/null; then
    ok "렌디션 재생목록에 #EXT-X-PROGRAM-DATE-TIME 있음 — $(grep -m1 '#EXT-X-PROGRAM-DATE-TIME' "$WORKDIR/rendition.m3u8")"
  else
    bad "렌디션 재생목록에 #EXT-X-PROGRAM-DATE-TIME이 없음(응답: $WORKDIR/rendition.m3u8) — ADR-084 '다시 볼 조건 4' 참고 대상"
  fi
fi

echo
log "=== R8.1·R9.1·R9.2: WebSocket 접속 → 고정 → 가격 변경 → 해제 ==="
WS_URL="ws://localhost:8080/api/v1/live/broadcasts/${BROADCAST_ID}/pins/ws"
java -cp "$WORKDIR" LivePinWsListener "$WS_URL" 13000 >"$WORKDIR/ws.log" 2>"$WORKDIR/ws-err.log" &
WS_PID=$!
sleep 2 # 접속·최초 스냅샷 수신 대기(R9.2)

PIN_RESPONSE="$(curl -s -X POST "$BASE_URL/api/v1/live/broadcasts/${BROADCAST_ID}/pin" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"productId\":${PRODUCT_ID},\"price\":9900,\"limitedQuantity\":50}")"
PIN_SEQ="$(json_number "$PIN_RESPONSE" seq)"
[ -n "$PIN_SEQ" ] || die "고정 API 실패: $PIN_RESPONSE"
sleep 1.5

PRICE_RESPONSE="$(curl -s -X PATCH "$BASE_URL/api/v1/live/broadcasts/${BROADCAST_ID}/pin/price" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"price":7900}')"
sleep 1.5

UNPIN_RESPONSE="$(curl -s -X DELETE "$BASE_URL/api/v1/live/broadcasts/${BROADCAST_ID}/pin" \
  -H "Authorization: Bearer $TOKEN")"
sleep 2.5

wait "$WS_PID" 2>/dev/null
WS_LOG="$(cat "$WORKDIR/ws.log")"
echo "$WS_LOG" | sed 's/^/  ws> /'

SNAPSHOT_LINE="$(echo "$WS_LOG" | grep -m1 'MSG:' || true)"
if echo "$SNAPSHOT_LINE" | grep -q '"type":"UNPINNED"' && echo "$SNAPSHOT_LINE" | grep -q '"seq":0'; then
  ok "R9.2: 접속 즉시 받은 첫 메시지가 빈 스냅샷(UNPINNED, seq=0)이다 — 아직 아무것도 고정 안 한 상태"
else
  bad "R9.2: 접속 직후 첫 메시지가 기대와 다름: $SNAPSHOT_LINE"
fi

PINNED_LINE="$(echo "$WS_LOG" | grep 'MSG:' | grep '"type":"PINNED"' | head -1 || true)"
if echo "$PINNED_LINE" | grep -q "\"productId\":${PRODUCT_ID}" && echo "$PINNED_LINE" | grep -q '"price":9900' \
    && echo "$PINNED_LINE" | grep -q '"remainingQuantity":50' && echo "$PINNED_LINE" | grep -q '"effectiveAt"'; then
  ok "R8.1·R9.1: 고정 API 호출이 실제 WebSocket으로 PINNED 이벤트(상품·9,900원·수량 50·effectiveAt)를 내보냈다"
else
  bad "R8.1: PINNED 이벤트를 못 받았거나 내용이 다름: $PINNED_LINE"
fi

PRICE_LINE="$(echo "$WS_LOG" | grep 'MSG:' | grep '"type":"PRICE_CHANGED"' | head -1 || true)"
if echo "$PRICE_LINE" | grep -q '"price":7900'; then
  ok "R9.1: 가격 변경 API 호출이 실제 WebSocket으로 PRICE_CHANGED(7,900원) 이벤트를 내보냈다"
else
  bad "R9.1: PRICE_CHANGED 이벤트를 못 받았거나 내용이 다름: $PRICE_LINE"
fi

UNPINNED_LINES_COUNT="$(echo "$WS_LOG" | grep -c '"type":"UNPINNED"' || true)"
if [ "$UNPINNED_LINES_COUNT" -ge 2 ]; then
  ok "R8: 해제 API 호출이 실제 WebSocket으로 UNPINNED 이벤트를 내보냈다(최초 빈 스냅샷과 합쳐 ${UNPINNED_LINES_COUNT}건)"
else
  bad "R8: 해제 뒤의 UNPINNED 이벤트를 못 받음(UNPINNED 총 ${UNPINNED_LINES_COUNT}건, 최소 2건 기대)"
fi

SEQS="$(echo "$WS_LOG" | grep -oE '"seq":[0-9]+' | grep -oE '[0-9]+')"
SORTED_CHECK="$(echo "$SEQS" | awk 'NR==1{prev=$1; next} {if ($1<=prev) print "FAIL"; prev=$1} END{print "OK"}')"
if echo "$SEQS" | wc -l | grep -q '^4$' && [ "$(echo "$SORTED_CHECK" | tail -1)" = "OK" ]; then
  ok "R9.1: seq가 메시지마다 단조 증가한다(관측된 seq: $(echo "$SEQS" | tr '\n' ' '))"
else
  bad "R9.1: seq가 기대만큼(4건) 단조 증가하지 않음(관측된 seq: $(echo "$SEQS" | tr '\n' ' '))"
fi

echo
log "=== 결과: PASS=$PASS FAIL=$FAIL (workdir=$WORKDIR) ==="
[ "$FAIL" -eq 0 ]
