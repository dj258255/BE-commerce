#!/usr/bin/env bash
#
# 숏폼 개발 시드(R26) — testsrc로 만든 짧은 세로 영상 몇 개를 실제 업로드 API(시작→파일 쓰기→
# 완료)로 올리고 READY가 될 때까지 기다린다. 피드(GET /api/v1/shorts/feed)가 비어 있으면
# 재생 화면을 확인할 방법이 없어 로컬 개발·샌드박스 전용으로 둔다.
#
# 전제:
#   - commerce가 local 프로파일로 떠 있어야 한다(SPRING_PROFILES_ACTIVE=local,
#     application.yml) — 그래야 변환 리스너가 켜져 업로드 완료가 실제로 변환까지 간다.
#     b-studio 샌드박스는 compose.b-studio.yaml이 이미 이 프로파일을 켠다.
#   - ffmpeg가 PATH에 있어야 한다(합성 영상 생성용).
#
# 사용:
#   ./tools/seed-shorts-dev.sh
#   COUNT=2 DURATION=5 ./tools/seed-shorts-dev.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
USERNAME="${USERNAME:-3}"
PASSWORD="${PASSWORD:-seller-local-only}"
COUNT="${COUNT:-4}"
DURATION="${DURATION:-6}"
READY_TIMEOUT_SECONDS="${READY_TIMEOUT_SECONDS:-90}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-1}"

log() { printf '\033[36m▶\033[0m %s\n' "$*"; }
ok()  { printf '\033[32m✓\033[0m %s\n' "$*"; }
warn(){ printf '\033[33m!\033[0m %s\n' "$*"; }
die() { printf '\033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

command -v ffmpeg >/dev/null 2>&1 || die "ffmpeg를 찾을 수 없습니다(합성 영상 생성에 필요)."
command -v curl >/dev/null 2>&1 || die "curl을 찾을 수 없습니다."

json_string() { printf '%s' "$1" | sed -nE "s/.*\"$2\":\"([^\"]*)\".*/\\1/p" | head -1; }
json_number() { printf '%s' "$1" | sed -nE "s/.*\"$2\":([0-9]+).*/\\1/p" | head -1; }

health="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health" || echo ERR)"
[ "$health" = "200" ] || die "commerce($BASE_URL)가 떠 있지 않습니다(health=$health)."

SRC="${TMPDIR:-/tmp}/shorts-seed-src-${DURATION}s.mp4"
if [ -f "$SRC" ]; then
  ok "합성 소스 재사용: $SRC"
else
  log "합성 소스 생성(lavfi testsrc2, ${DURATION}초 1080x1920@30fps + 오디오)"
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc2=size=1080x1920:rate=30:duration=$DURATION" \
    -f lavfi -i "anullsrc=r=44100:cl=stereo" \
    -shortest -c:v libx264 -preset veryfast -c:a aac -b:a 128k \
    "$SRC"
  ok "합성 소스: $(du -h "$SRC" | cut -f1)"
fi
SRC_SIZE="$(wc -c < "$SRC" | tr -d ' ')"

login() {
  local body token
  body="$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")"
  token="$(json_string "$body" token)"
  [ -n "$token" ] || die "로그인 실패: $body"
  echo "$token"
}

TOKEN="$(login)"
ok "로그인 성공(seller=$USERNAME)"

seed_one() {
  local n="$1"
  local start_body short_video_id upload_url object_path
  start_body="$(curl -s -X POST "$BASE_URL/api/v1/shorts" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -d "{\"durationSeconds\":$DURATION,\"fileSizeBytes\":$SRC_SIZE,\"width\":1080,\"height\":1920,\"contentType\":\"video/mp4\"}")"
  short_video_id="$(json_number "$start_body" shortVideoId)"
  upload_url="$(json_string "$start_body" uploadUrl)"
  [ -n "$short_video_id" ] && [ -n "$upload_url" ] || die "업로드 시작 실패(#$n): $start_body"

  case "$upload_url" in
    file://*) object_path="${upload_url#file://}"; mkdir -p "$(dirname "$object_path")"; cp "$SRC" "$object_path" ;;
    *) die "이 스크립트는 로컬 파일 저장소(file://)만 지원합니다 — uploadUrl=$upload_url" ;;
  esac

  curl -s -X POST "$BASE_URL/api/v1/shorts/$short_video_id/complete" -H "Authorization: Bearer $TOKEN" > /dev/null

  local waited=0 status get_body
  status="UPLOADED"
  while :; do
    get_body="$(curl -s "$BASE_URL/api/v1/shorts/$short_video_id" -H "Authorization: Bearer $TOKEN")"
    status="$(json_string "$get_body" status)"
    if [ "$status" = "READY" ] || [ "$status" = "QUARANTINED" ]; then
      break
    fi
    awk -v w="$waited" -v t="$READY_TIMEOUT_SECONDS" 'BEGIN{exit !(w>=t)}' && break
    sleep "$POLL_INTERVAL_SECONDS"
    waited=$((waited + POLL_INTERVAL_SECONDS))
  done

  if [ "$status" = "READY" ]; then
    ok "#$n: id=$short_video_id READY(${waited}초 대기)"
  else
    warn "#$n: id=$short_video_id READY에 도달하지 못함(status=$status) — local 프로파일(SPRING_PROFILES_ACTIVE=local)이 켜져 있는지 확인하세요."
  fi
}

for n in $(seq 1 "$COUNT"); do
  log "시드 업로드 $n/$COUNT"
  seed_one "$n"
done

ok "완료 — GET $BASE_URL/api/v1/shorts/feed 로 확인해 보세요."
curl -s "$BASE_URL/api/v1/shorts/feed?size=$COUNT"
echo
