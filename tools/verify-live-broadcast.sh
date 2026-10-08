#!/usr/bin/env bash
#
# 라이브 방송 R1~R3을 "실제 송출 경로"로 확인한다 — 훅 엔드포인트에 직접 curl만 날리는 것이
# 아니라, 진짜 ffmpeg로 MediaMTX에 RTMP 송출을 하고, MediaMTX의 HTTP 인증 훅·runOnReady·
# runOnNotReady가 commerce를 실제로 불러 상태가 바뀌는지를 본다.
#
# 확인 범위:
#   - R1.1: 방송 생성 → 201 + SCHEDULED + streamKey
#   - (사전 점검) commerce의 인증 엔드포인트 자체가 옳게 허용/거절하는가 — MediaMTX를 거치지
#     않고 직접 불러 "거절 사유"를 분리해 본다(R2.1이 가짜로 통과하는 것을 막는다, 아래 참고)
#   - R3.1: 올바른 키로 송출 → LIVE로 전이 (+ MediaMTX Control API로 자신이 보는 상태도 같이 찍는다)
#   - (참고) MediaMTX HLS 재생목록(m3u8)을 받을 수 있는지 — 지연 측정(R6)은 범위 밖
#   - R2.1: 틀린 스트림 키로 송출 → MediaMTX가 거절(인증 훅 401), 방송은 SCHEDULED 유지
#   - R3.2: 끊고 30초 안에 같은 키로 재접속 → 같은 방송이 LIVE 유지
#   - R3.3: 끊고 30초를 넘기면 → ENDED(재접속 유예 스캐너가 5초 주기로 돈다, local 프로파일)
#   - R2.2: ENDED 방송의 (올바른) 키로 다시 송출 → MediaMTX가 또 거절
#
# 왜 R3.1을 R2.1보다 먼저 보는가(중요): MediaMTX ↔ commerce 사이의 인증 훅 배관 자체가
# 깨지면(예: mediamtx.yml의 호스트 치환 버그) "틀린 키" 송출도 "맞는 키" 송출도 **똑같이**
# LIVE가 안 된다 — 그러면 R2.1만 따로 보면 "거절됐다"고 오판(가짜 통과)한다. 그래서 이
# 스크립트는 맞는 키로 LIVE가 되는 것(R3.1)을 먼저 확인해 "배관 자체는 살아 있다"를 증명한
# 뒤에만 R2.1의 "거절됨" 판정을 신뢰한다 — R3.1이 실패하면 R2.1은 PASS가 아니라 UNKNOWN으로
# 보고한다(판정 불가, 원인이 다를 수 있다는 뜻). 그와 별개로, MediaMTX를 거치지 않고
# commerce의 인증 엔드포인트를 직접 불러보는 사전 점검도 추가해 "commerce 쪽 결정 로직
# 자체"는 MediaMTX 상태와 무관하게 옳은지 따로 확인한다.
#
# 전제:
#   - commerce 컨테이너 안에서 돈다(ffmpeg·curl이 거기 있다. studio.yaml의
#     systemPackages: [ffmpeg]). 이 스크립트 자체는 commerce 컨테이너의 ffmpeg로 mediamtx
#     컨테이너에 네트워크로 RTMP 송출한다 — b-studio의 run_in_service(commerce)로 돌린다.
#   - mediamtx 컨테이너가 같은 compose 네트워크에 떠 있어야 한다(서비스 이름 "mediamtx").
#     설정(media/mediamtx.yml)을 고친 뒤에는 컨테이너를 재시작해야 반영된다(MediaMTX가
#     설정을 시작 시점에만 읽는다 — 핫 리로드 없음). 이 샌드박스에서 mediamtx는 studio.yaml
#     관리 서비스가 아니라 b-studio 도구로 재시작할 수 없다 — 사람이 직접
#     `docker compose -f compose.b-studio.yaml restart mediamtx`를 해 줘야 한다.
#   - mediamtx.yml에 `api: yes`(Control API, :9997)가 켜져 있어야 진단 출력이 나온다.
#   - commerce가 local(또는 worker) 프로파일로 떠 있어야 한다(app.live.grace-scheduler.enabled,
#     R3.3의 ENDED 자동 전이에 필요). b-studio 샌드박스는 compose.b-studio.yaml이 켠다.
#
# 사용:
#   ./tools/verify-live-broadcast.sh
#   BASE_URL=http://localhost:8080 MEDIAMTX_HOST=mediamtx ./tools/verify-live-broadcast.sh
#
# 이 스크립트가 만드는 방송은 실행마다 새로 생긴다(멱등하지 않다) — 반복 실행해도 안전하지만
# 쌓인 방송을 지우지는 않는다.
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
MEDIAMTX_HOST="${MEDIAMTX_HOST:-mediamtx}"
MEDIAMTX_RTMP_PORT="${MEDIAMTX_RTMP_PORT:-1935}"
MEDIAMTX_HLS_PORT="${MEDIAMTX_HLS_PORT:-8888}"
MEDIAMTX_API_PORT="${MEDIAMTX_API_PORT:-9997}"
USERNAME="${USERNAME:-3}"
PASSWORD="${PASSWORD:-seller-local-only}"
RECONNECT_GRACE_SECONDS="${RECONNECT_GRACE_SECONDS:-30}"
WORKDIR="$(mktemp -d /tmp/live-verify.XXXXXX)"

PASS=0
FAIL=0
UNKNOWN=0

log() { printf '\033[36m▶\033[0m %s\n' "$*"; }
ok()  { printf '\033[32m✓\033[0m %s\n' "$*"; PASS=$((PASS + 1)); }
bad() { printf '\033[31m✗\033[0m %s\n' "$*"; FAIL=$((FAIL + 1)); }
unk() { printf '\033[35m?\033[0m %s\n' "$*"; UNKNOWN=$((UNKNOWN + 1)); }
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

json_string() { printf '%s' "$1" | sed -nE "s/.*\"$2\":\"([^\"]*)\".*/\\1/p" | head -1; }
json_number() { printf '%s' "$1" | sed -nE "s/.*\"$2\":([0-9]+).*/\\1/p" | head -1; }

health="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health" || echo ERR)"
[ "$health" = "200" ] || die "commerce($BASE_URL)가 떠 있지 않습니다(health=$health)."

if ! getent hosts "$MEDIAMTX_HOST" >/dev/null 2>&1; then
  die "mediamtx($MEDIAMTX_HOST)가 네트워크에서 안 보입니다 — 컨테이너가 꺼져 있을 가능성이 높습니다. \
이 샌드박스에서는 mediamtx가 studio.yaml 관리 서비스가 아니라 로그 확인·재시작 도구가 없습니다. \
사람이 수동으로 띄운 뒤 재실행하세요."
fi

login() {
  local body token
  body="$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")"
  token="$(json_string "$body" token)"
  [ -n "$token" ] || die "로그인 실패: $body"
  echo "$token"
}

get_broadcast() {
  curl -s "$BASE_URL/api/v1/live/broadcasts/$1" -H "Authorization: Bearer $TOKEN"
}

wait_for_status() {
  local id="$1" expected="$2" timeout="$3" waited=0 status body
  while :; do
    body="$(get_broadcast "$id")"
    status="$(json_string "$body" status)"
    if [ "$status" = "$expected" ]; then
      echo "$status"
      return 0
    fi
    waited=$((waited + 1))
    [ "$waited" -ge "$timeout" ] && { echo "$status"; return 1; }
    sleep 1
  done
}

# 끝없이 흐르는 테스트 패턴(testsrc2)을 RTMP로 송출한다. 백그라운드로 띄우고 PID를 돌려준다.
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

# 한 번 붙었다가 즉시(또는 거절돼) 끝나는 짧은 송출 시도 — 거절 확인(R2.1·R2.2)에 쓴다.
try_publish_once() {
  local key="$1" logfile="$2"
  timeout 8 ffmpeg -hide_banner -loglevel warning -re \
    -f lavfi -i "testsrc2=size=640x360:rate=25:duration=8" \
    -c:v libx264 -preset veryfast -f flv \
    "rtmp://${MEDIAMTX_HOST}:${MEDIAMTX_RTMP_PORT}/live/${key}" \
    >"$logfile" 2>&1
  echo $?
}

# MediaMTX Control API(진단용, 셸 없이도 항상 동작) — 그 경로가 지금 ready(송출 수신 중)인지.
# api가 꺼져 있거나(:9997 안 열림) 경로가 아예 없으면 "unknown"을 돌려준다(연결 실패와 "false"를
# 구분해야 "훅은 깨졌지만 RTMP 수신 자체는 된다"는 진단이 의미가 있다).
mediamtx_path_ready() {
  local key="$1" body
  body="$(curl -s -m 3 "http://${MEDIAMTX_HOST}:${MEDIAMTX_API_PORT}/v3/paths/get/live/${key}" 2>/dev/null)"
  if [ -z "$body" ]; then
    echo "unknown(API 응답 없음 — api: yes가 꺼져 있거나 재시작 전일 수 있다)"
    return
  fi
  case "$body" in
    *'"ready":true'*) echo "true" ;;
    *'"ready":false'*) echo "false" ;;
    *) echo "unknown(응답: $body)" ;;
  esac
}

echo
log "=== R1.1: 방송 생성 ==="
TOKEN="$(login)"
ok "로그인 성공(seller=$USERNAME)"

CREATE_BODY="$(curl -s -o "$WORKDIR/create.json" -w '%{http_code}' -X POST "$BASE_URL/api/v1/live/broadcasts" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"실 송출 확인 방송"}')"
CREATE_STATUS_CODE="$CREATE_BODY"
CREATE_RESPONSE="$(cat "$WORKDIR/create.json")"
BROADCAST_ID="$(json_number "$CREATE_RESPONSE" id)"
STREAM_KEY="$(json_string "$CREATE_RESPONSE" streamKey)"
INITIAL_STATUS="$(json_string "$CREATE_RESPONSE" status)"

if [ "$CREATE_STATUS_CODE" = "201" ] && [ -n "$BROADCAST_ID" ] && [ -n "$STREAM_KEY" ] && [ "$INITIAL_STATUS" = "SCHEDULED" ]; then
  ok "R1.1: 201 + id=$BROADCAST_ID + status=SCHEDULED + streamKey 발급됨"
else
  bad "R1.1: 생성 응답이 기대와 다름 — http=$CREATE_STATUS_CODE body=$CREATE_RESPONSE"
  die "방송 생성에 실패해 나머지 단계를 진행할 수 없습니다."
fi

echo
log "=== 사전 점검: commerce 인증 엔드포인트를 MediaMTX 없이 직접 불러 거절 사유를 분리 확인 ==="
DIRECT_AUTH_OK_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/live/hooks/auth" \
  -H 'Content-Type: application/json' -d "{\"path\":\"live/${STREAM_KEY}\",\"action\":\"publish\"}")"
DIRECT_AUTH_WRONG_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/live/hooks/auth" \
  -H 'Content-Type: application/json' -d '{"path":"live/definitely-not-a-real-key","action":"publish"}')"
if [ "$DIRECT_AUTH_OK_CODE" = "200" ]; then
  ok "사전 점검: commerce는 올바른 키를 직접 물어보면 200(허용)을 준다"
else
  bad "사전 점검: commerce가 올바른 키인데도 직접 호출에서 $DIRECT_AUTH_OK_CODE를 줌(기대 200)"
fi
if [ "$DIRECT_AUTH_WRONG_CODE" = "401" ]; then
  ok "사전 점검: commerce는 틀린 키를 직접 물어보면 401(거절)을 준다"
else
  bad "사전 점검: commerce가 틀린 키인데도 직접 호출에서 $DIRECT_AUTH_WRONG_CODE를 줌(기대 401)"
fi
warn "이 사전 점검은 MediaMTX를 거치지 않는다 — commerce 쪽 결정 로직만 본다. \
아래 R3.1·R2.1은 MediaMTX가 그 결정을 실제로 받아 쓰는지(배관)를 본다."

echo
log "=== R3.1: 올바른 키로 송출 → LIVE (먼저 본다 — 위 '왜 순서를 바꿨는지' 참고) ==="
RUNNING_FFMPEG_PID="$(start_publish "$STREAM_KEY" "$WORKDIR/publish-1.log")"
STATUS_AFTER_PUBLISH="$(wait_for_status "$BROADCAST_ID" LIVE 15)"
PIPELINE_PROVEN=false
if [ "$STATUS_AFTER_PUBLISH" = "LIVE" ]; then
  ok "R3.1: 송출 시작 후 LIVE로 전이됨"
  PIPELINE_PROVEN=true
else
  bad "R3.1: 15초를 기다려도 LIVE가 안 됨(status=$STATUS_AFTER_PUBLISH) — ffmpeg 로그: $WORKDIR/publish-1.log"
fi
READY_PER_MEDIAMTX="$(mediamtx_path_ready "$STREAM_KEY")"
log "진단: MediaMTX 자신이 보는 이 경로의 ready 상태 = $READY_PER_MEDIAMTX \
(commerce 상태=$STATUS_AFTER_PUBLISH) — 공식 이미지에 셸이 없어 명령 훅은 못 쓰므로(ADR-082), \
LIVE 전환은 commerce의 MediaMtxPathPoller(app.live.mediamtx-poller.enabled)가 이 Control API를 \
폴링해서 한다. true인데 commerce가 LIVE가 아니면 그 폴러가 꺼져 있거나(app.live.mediamtx-poller.enabled) \
api-base-url(app.live.mediamtx.api-base-url)이 틀렸다는 뜻이다. 'path not found'/unknown이면 \
mediamtx.yml의 paths 선언이 없거나 api: yes가 아직 반영 안 됐을 수 있다(컨테이너 재시작 필요)."

echo
log "=== 참고: MediaMTX HLS 재생목록(m3u8)을 받을 수 있는가(R6 지연 측정은 범위 밖) ==="
if [ "$STATUS_AFTER_PUBLISH" = "LIVE" ]; then
  sleep 3  # 세그먼트가 몇 개 쌓일 시간을 준다
  HLS_URL="http://${MEDIAMTX_HOST}:${MEDIAMTX_HLS_PORT}/live/${STREAM_KEY}/index.m3u8"
  # MediaMTX HLS는 첫 요청에 302(쿠키 체크, LL-HLS 세션 확립용)로 응답한다 — -L로 따라간다.
  HLS_CODE="$(curl -s -L -o "$WORKDIR/hls.m3u8" -w '%{http_code}' -m 5 "$HLS_URL")"
  if [ "$HLS_CODE" = "200" ] && grep -q '#EXTM3U' "$WORKDIR/hls.m3u8"; then
    ok "HLS 재생목록 수신: $HLS_URL (#EXTM3U 확인)"
  else
    bad "HLS 재생목록을 못 받음 — http=$HLS_CODE url=$HLS_URL (응답은 $WORKDIR/hls.m3u8)"
  fi
else
  warn "LIVE가 아니라 HLS 확인을 건너뜀"
fi

echo
log "=== R2.1: 틀린 스트림 키로 송출하면 거절되는가 ==="
WRONG_KEY="not-the-real-key-$(date +%s)"
try_publish_once "$WRONG_KEY" "$WORKDIR/wrong-key.log" >/dev/null
# 판정은 "원래 방송의 상태"가 아니라 "틀린 키 자신의 경로가 ready가 됐는가"로 한다 — 원래
# 방송(id=$BROADCAST_ID)은 R3.1에서 이미 LIVE라 그 상태만 보면 틀린 키 때문인지 구분이 안 된다
# (처음 버전의 실수 — 틀린 키 전용 ffmpeg가 원래 스트림과 무관하게 떠 있는 동안에도 원래
# 방송은 계속 LIVE이므로, 그 상태를 보는 건 틀린 키 거절과 아무 상관이 없었다).
WRONG_KEY_READY="$(mediamtx_path_ready "$WRONG_KEY")"
WRONG_KEY_NOT_LIVE=false
[ "$WRONG_KEY_READY" != "true" ] && WRONG_KEY_NOT_LIVE=true
if [ "$PIPELINE_PROVEN" = true ] && [ "$WRONG_KEY_NOT_LIVE" = true ]; then
  ok "R2.1: 올바른 키는 LIVE가 됐는데(R3.1) 틀린 키는 거절됨(MediaMTX 자신도 그 경로를 ready로 보지 않음: $WRONG_KEY_READY) — 배관이 살아있는 상태에서의 진짜 거절"
elif [ "$PIPELINE_PROVEN" = true ] && [ "$WRONG_KEY_NOT_LIVE" = false ]; then
  bad "R2.1: 배관은 살아있는데(R3.1 통과) 틀린 키인데도 MediaMTX가 그 경로를 ready로 봄 — 거절 실패"
else
  unk "R2.1: 판정 불가 — R3.1(올바른 키)부터 LIVE가 안 돼 배관 자체가 증명되지 않았다. \
이 상태의 '거절됨'은 키 검사 때문인지 배관 전체가 깨진 것인지 구분할 수 없다(이전 실행에서 \
MediaMTX의 \${LIVE_HOOKS_HOST} 치환 버그로 실제로 이렇게 가짜 통과가 났었다)."
fi
warn "ffmpeg 종료 코드·출력은 $WORKDIR/wrong-key.log 참고(참고용 — 판정 근거는 위 상태+순서)"

echo
log "=== R3.2: ${RECONNECT_GRACE_SECONDS}초 안에 재접속하면 같은 방송이 LIVE 유지 ==="
if [ "$PIPELINE_PROVEN" != true ]; then
  unk "R3.2: R3.1이 실패해 건너뜀(의미 없음)"
else
  kill "$RUNNING_FFMPEG_PID" 2>/dev/null; wait "$RUNNING_FFMPEG_PID" 2>/dev/null
  RUNNING_FFMPEG_PID=""
  RECONNECT_GAP=$((RECONNECT_GRACE_SECONDS / 3))
  log "끊고 ${RECONNECT_GAP}초 대기 후 재접속(유예 ${RECONNECT_GRACE_SECONDS}초 안)"
  sleep "$RECONNECT_GAP"
  RUNNING_FFMPEG_PID="$(start_publish "$STREAM_KEY" "$WORKDIR/publish-2.log")"
  STATUS_AFTER_RECONNECT="$(wait_for_status "$BROADCAST_ID" LIVE 10)"
  RECONNECT_BODY="$(get_broadcast "$BROADCAST_ID")"
  RECONNECT_ID="$(json_number "$RECONNECT_BODY" id)"
  if [ "$STATUS_AFTER_RECONNECT" = "LIVE" ] && [ "$RECONNECT_ID" = "$BROADCAST_ID" ]; then
    ok "R3.2: 재접속 후에도 같은 방송 id=$BROADCAST_ID가 LIVE 유지됨"
  else
    bad "R3.2: 재접속 후 상태=$STATUS_AFTER_RECONNECT id=$RECONNECT_ID(기대: LIVE, id=$BROADCAST_ID)"
  fi
fi

echo
log "=== R3.3: ${RECONNECT_GRACE_SECONDS}초를 넘기면 ENDED ==="
if [ "$PIPELINE_PROVEN" != true ]; then
  unk "R3.3: R3.1이 실패해 건너뜀(의미 없음)"
  STATUS_AFTER_GRACE="SCHEDULED"
else
  kill "$RUNNING_FFMPEG_PID" 2>/dev/null; wait "$RUNNING_FFMPEG_PID" 2>/dev/null
  RUNNING_FFMPEG_PID=""
  WAIT_FOR_ENDED=$((RECONNECT_GRACE_SECONDS + 20))
  log "끊고 최대 ${WAIT_FOR_ENDED}초 대기(유예 ${RECONNECT_GRACE_SECONDS}초 + 스캐너 주기 여유)"
  STATUS_AFTER_GRACE="$(wait_for_status "$BROADCAST_ID" ENDED "$WAIT_FOR_ENDED")"
  if [ "$STATUS_AFTER_GRACE" = "ENDED" ]; then
    ok "R3.3: 재접속 유예 만료 후 ENDED로 전이됨"
    warn "live.ended 이벤트 발행 자체는 이 스크립트가 직접 못 본다 — service_logs(commerce)에서 \
\"재접속 유예 만료로 종료 id=$BROADCAST_ID\" 로그로 agent가 별도 확인한다(README 참고)."
  else
    bad "R3.3: ${WAIT_FOR_ENDED}초를 기다려도 ENDED가 안 됨(status=$STATUS_AFTER_GRACE)"
  fi
fi

echo
log "=== R2.2: ENDED 방송의 (올바른) 키로 다시 송출하면 거절되는가 ==="
if [ "$STATUS_AFTER_GRACE" = "ENDED" ]; then
  try_publish_once "$STREAM_KEY" "$WORKDIR/after-ended.log" >/dev/null
  STATUS_AFTER_RETRY="$(json_string "$(get_broadcast "$BROADCAST_ID")" status)"
  if [ "$STATUS_AFTER_RETRY" = "ENDED" ]; then
    ok "R2.2: ENDED 방송에 올바른 키로 다시 송출해도 ENDED 유지됨(= 거절된 것으로 판정)"
  else
    bad "R2.2: ENDED 방송에 재송출했는데 상태가 $STATUS_AFTER_RETRY로 바뀜(거절 실패?)"
  fi
else
  unk "R2.2: 방송이 ENDED까지 못 가 건너뜀(의미 없음)"
fi

echo
log "=== 결과: PASS=$PASS FAIL=$FAIL UNKNOWN=$UNKNOWN (workdir=$WORKDIR) ==="
[ "$FAIL" -eq 0 ] && [ "$UNKNOWN" -eq 0 ]
