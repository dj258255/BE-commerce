#!/usr/bin/env bash
#
# 숏폼 R24 벤치마크 — "60초 영상 업로드 완료부터 READY까지"를 변환 자체가 아니라 실제 경로로
# 잰다. tools/run-shorts-transcode-bench.sh가 FFmpeg 변환 시간만 쟀다면, 이 스크립트는
# - 업로드 시작 API로 presigned URL을 받고
# - 저장소에 실제 60초 1080x1920 세로 영상 바이트를 쓰고(로컬 저장소 전제, ADR-081)
# - 업로드 완료 API를 부르고(UPLOADED 전이 — 응답의 updatedAt이 그 전이 시각이다)
# - READY(또는 FAILED/QUARANTINED)가 될 때까지 조회 API를 폴링하고(그 응답의 updatedAt이
#   READY 전이 시각이다)
# 두 시각의 차이를 R24 숫자로 쓴다. 둘 다 폴링이 만든 값이 아니라 ShortVideo 엔티티가 상태
# 전이마다 기록하는 updatedAt(= "상태 전이 기록")을 그대로 읽은 값이다 — 폴링 간격은 "언제
# 알아챘는가"에만 영향을 주고 "언제 전이됐는가"에는 영향을 주지 않는다.
#
# 전제:
#   - commerce가 worker 프로파일로 떠 있어야 한다(SPRING_PROFILES_ACTIVE=worker) — 그래야
#     ShortsTranscodeListener가 켜져 업로드 완료를 실제로 변환까지 끌고 간다. 이 스크립트는
#     그 사실을 강제하지 않는다(단지 compose 환경 변수일 뿐이라 이 스크립트가 띄우고 내릴
#     책임을 지지 않는다) — 꺼져 있으면 READY_TIMEOUT_SECONDS 안에 READY가 안 와 그대로
#     실패로 남는다.
#   - ffmpeg가 PATH에 있어야 한다(합성 영상 생성용).
#   - 동시에 하나씩만 올린다("동시 변환 1건") — REPS는 항상 순차로 돈다.
#
# 사용:
#   ./tools/run-shorts-upload-to-ready-bench.sh
#   REPS=1 OUT_DIR=docs/performance/runs/my-run ./tools/run-shorts-upload-to-ready-bench.sh   # 여러 번 나눠 이어붙이기
#   BASE_URL=http://localhost:8080 USERNAME=3 PASSWORD=seller-local-only ./tools/run-shorts-upload-to-ready-bench.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
USERNAME="${USERNAME:-3}"
PASSWORD="${PASSWORD:-seller-local-only}"
REPS="${REPS:-3}"
DURATION="${DURATION:-60}"
READY_TIMEOUT_SECONDS="${READY_TIMEOUT_SECONDS:-180}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-0.5}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -n "${OUT_DIR:-}" ]; then
  mkdir -p "$OUT_DIR"
else
  STAMP="$(date +%Y%m%d-%H%M%S)"
  OUT_DIR="$ROOT/docs/performance/runs/$STAMP-shorts-upload-to-ready"
  mkdir -p "$OUT_DIR"
fi

log() { printf '\033[36m▶\033[0m %s\n' "$*"; }
ok()  { printf '\033[32m✓\033[0m %s\n' "$*"; }
warn(){ printf '\033[33m!\033[0m %s\n' "$*"; }
die() { printf '\033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

command -v ffmpeg >/dev/null 2>&1 || die "ffmpeg를 찾을 수 없습니다(합성 영상 생성에 필요)."
command -v curl >/dev/null 2>&1 || die "curl을 찾을 수 없습니다."

RAW_CSV="$OUT_DIR/raw.csv"
[ -f "$RAW_CSV" ] || echo "rep,short_video_id,status,uploaded_at,ready_at,elapsed_seconds,poll_count" > "$RAW_CSV"
# 폴링 중 처음 관측한 상태마다 한 줄 — PROBING·TRANSCODING 진입 시각(= updatedAt, DB 기록)을
# 남겨 "변환 단독 시간과의 차이(대기·probe·DB·이벤트 전달)"를 나눠 볼 수 있게 한다. 빠르게
# 지나가는 상태는 폴링 간격보다 짧으면 못 볼 수 있다(그 경우 report.md에 그렇게 적는다).
PHASES_CSV="$OUT_DIR/phases.csv"
[ -f "$PHASES_CSV" ] || echo "short_video_id,status,updated_at" > "$PHASES_CSV"

# ── 0. 측정 환경 기록 ──
ENV_FILE="$OUT_DIR/environment.txt"
{
  echo "measured_at    : $(date -Iseconds)"
  echo "base_url       : $BASE_URL"
  echo "ffmpeg_version : $(ffmpeg -version 2>&1 | head -1)"
  echo "os             : $(uname -srm)"
  echo "cpu_cores      : $(nproc 2>/dev/null || echo unknown)"
  echo "duration_sec   : $DURATION"
  echo "reps           : $REPS"
  health="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health" || echo 'ERR')"
  echo "health_check   : $health"
} > "$ENV_FILE"
ok "환경 기록 → ${ENV_FILE#"$ROOT"/}"

# ── 1. 합성 소스 — 60초 1080x1920 세로, R21 상한과 같은 모양. 오디오 트랙 필수
#       (FfmpegTranscodeRunner가 -map 0:a를 쓴다, R23 3단계의 알려진 한계) ──
SRC="${TMPDIR:-/tmp}/shorts-r24-src-${DURATION}s.mp4"
if [ -f "$SRC" ]; then
  ok "합성 소스 재사용: $SRC ($(du -h "$SRC" | cut -f1))"
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

# ── 2. 로그인(토큰은 재사용 — 로그인은 IP당 초당 한도에 걸린다) ──
json_string() {
  printf '%s' "$1" | sed -nE "s/.*\"$2\":\"([^\"]*)\".*/\\1/p" | head -1
}
json_number() {
  printf '%s' "$1" | sed -nE "s/.*\"$2\":([0-9]+).*/\\1/p" | head -1
}

login() {
  local body
  body="$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")"
  local token
  token="$(json_string "$body" token)"
  [ -n "$token" ] || die "로그인 실패: $body"
  echo "$token"
}

epoch() {
  # ISO-8601(나노초 포함 가능) → 초.나노초 — 둘 다 uutils/GNU date -d로 파싱된다.
  date -d "$1" +%s.%N
}

TOKEN="$(login)"
ok "로그인 성공(seller=$USERNAME)"

run_one() {
  local rep="$1"
  local start_body short_video_id upload_url object_path
  start_body="$(curl -s -X POST "$BASE_URL/api/v1/shorts" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -d "{\"durationSeconds\":$DURATION,\"fileSizeBytes\":$SRC_SIZE,\"width\":1080,\"height\":1920,\"contentType\":\"video/mp4\"}")"
  short_video_id="$(json_number "$start_body" shortVideoId)"
  upload_url="$(json_string "$start_body" uploadUrl)"
  [ -n "$short_video_id" ] && [ -n "$upload_url" ] || die "업로드 시작 실패(rep $rep): $start_body"

  case "$upload_url" in
    file://*)
      object_path="${upload_url#file://}"
      mkdir -p "$(dirname "$object_path")"
      cp "$SRC" "$object_path"
      ;;
    *)
      die "이 스크립트는 로컬 파일 저장소(file://)만 지원합니다 — uploadUrl=$upload_url"
      ;;
  esac

  local complete_body uploaded_at
  complete_body="$(curl -s -X POST "$BASE_URL/api/v1/shorts/$short_video_id/complete" \
    -H "Authorization: Bearer $TOKEN")"
  uploaded_at="$(json_string "$complete_body" updatedAt)"
  [ -n "$uploaded_at" ] || die "업로드 완료 실패(rep $rep): $complete_body"

  local waited=0 status get_body ready_at last_seen=""
  status="UPLOADED"
  while :; do
    get_body="$(curl -s "$BASE_URL/api/v1/shorts/$short_video_id" -H "Authorization: Bearer $TOKEN")"
    status="$(json_string "$get_body" status)"
    if [ "$status" != "$last_seen" ]; then
      echo "$short_video_id,$status,$(json_string "$get_body" updatedAt)" >> "$PHASES_CSV"
      last_seen="$status"
    fi
    if [ "$status" = "READY" ] || [ "$status" = "QUARANTINED" ]; then
      break
    fi
    awk -v w="$waited" -v t="$READY_TIMEOUT_SECONDS" 'BEGIN{exit !(w>=t)}' && break
    sleep "$POLL_INTERVAL_SECONDS"
    waited="$(awk -v w="$waited" -v p="$POLL_INTERVAL_SECONDS" 'BEGIN{printf "%.2f", w+p}')"
  done

  if [ "$status" != "READY" ]; then
    local reason
    reason="$(json_string "$get_body" failureReason)"
    warn "rep $rep: id=$short_video_id READY에 도달하지 못함(status=$status, ${waited}초 대기, 사유=${reason:-없음})"
    echo "$rep,$short_video_id,$status,$uploaded_at,,,$(awk -v p="$POLL_INTERVAL_SECONDS" -v w="$waited" 'BEGIN{printf "%d", w/p}')" >> "$RAW_CSV"
    return 1
  fi

  ready_at="$(json_string "$get_body" updatedAt)"
  local elapsed
  elapsed="$(awk -v a="$(epoch "$uploaded_at")" -v b="$(epoch "$ready_at")" 'BEGIN{printf "%.3f", b-a}')"
  ok "rep $rep: id=$short_video_id UPLOADED=$uploaded_at READY=$ready_at 경과=${elapsed}초"
  echo "$rep,$short_video_id,READY,$uploaded_at,$ready_at,$elapsed,$(awk -v p="$POLL_INTERVAL_SECONDS" -v w="$waited" 'BEGIN{printf "%d", w/p}')" >> "$RAW_CSV"
}

FAIL=0
for rep in $(seq 1 "$REPS"); do
  log "측정: rep $rep/$REPS"
  run_one "$rep" || FAIL=$((FAIL+1))
done

ok "원자료 → ${RAW_CSV#"$ROOT"/}"

# ── 보고서 — awk로 성공분의 중앙값·최소·최대를 뽑는다 ──
REPORT="$OUT_DIR/report.md"
{
  echo "# 숏폼 R24 벤치마크 — 업로드 완료 → READY(실제 경로)"
  echo
  echo "측정 시각: $(date -Iseconds)"
  echo
  echo "60초 1080x1920 세로 합성 영상을 실제 업로드 API(시작→파일 쓰기→완료)로 올리고,"
  echo "READY가 될 때까지 걸린 시간이다. 두 시각 모두 ShortVideo가 상태 전이마다 기록하는"
  echo "updatedAt(API 응답에 그대로 노출)을 읽은 값이다 — 폴링 간격이 숫자에 오차를 더하지 않는다."
  echo
  awk -F, 'NR==1{next} $3=="READY"{print}' "$RAW_CSV" > "$OUT_DIR/.ready_rows.csv" || true
  n="$(wc -l < "$OUT_DIR/.ready_rows.csv" | tr -d ' ')"
  if [ "${n:-0}" -gt 0 ]; then
    echo "| rep | short_video_id | 경과(초) |"
    echo "|---|---|---:|"
    awk -F, '{printf "| %s | %s | %s |\n", $1, $2, $6}' "$OUT_DIR/.ready_rows.csv"
    echo
    awk -F, '{print $6}' "$OUT_DIR/.ready_rows.csv" | sort -n > "$OUT_DIR/.sorted.txt"
    median="$(awk '{a[NR]=$1} END{if(NR%2==1) print a[(NR+1)/2]; else print (a[NR/2]+a[NR/2+1])/2}' "$OUT_DIR/.sorted.txt")"
    min="$(head -1 "$OUT_DIR/.sorted.txt")"
    max="$(tail -1 "$OUT_DIR/.sorted.txt")"
    echo "- 중앙값: ${median}초"
    echo "- 최소~최대: ${min}초 ~ ${max}초"
    echo "- 60초 목표 대비 여유(중앙값 기준): $(awk -v m="$median" 'BEGIN{printf "%+.2f", 60-m}')초"
  else
    echo "**성공한 회차가 없습니다 — READY에 도달한 표본이 0건입니다.** raw.csv를 확인하세요."
  fi
  rm -f "$OUT_DIR/.ready_rows.csv" "$OUT_DIR/.sorted.txt"
  echo
  echo "## 실패/타임아웃 회차"
  echo
  fails="$(awk -F, 'NR==1{next} $3!="READY"{print}' "$RAW_CSV")"
  if [ -n "$fails" ]; then
    echo '```'
    echo "$fails"
    echo '```'
  else
    echo "없음 — 전체 ${REPS}회 모두 READY에 도달했다."
  fi
  echo
  echo "## 단계별 분해(포착된 표본만)"
  echo
  echo "폴링(${POLL_INTERVAL_SECONDS}초 간격)이 실제로 관측했을 때만 나온다 — 간격보다 짧게"
  echo "머문 상태는 빠질 수 있다. \"경과\"는 같은 영상의 바로 이전 상태 이후 걸린 시간이다."
  echo
  echo "| short_video_id | 상태 | updatedAt | 이전 상태 이후 경과(초) |"
  echo "|---|---|---|---:|"
  if [ -f "$PHASES_CSV" ]; then
    prev_id=""
    prev_ts=""
    tail -n +2 "$PHASES_CSV" | while IFS=, read -r pid pstatus pts; do
      if [ "$pid" != "$prev_id" ]; then
        delta="-"
      else
        delta="$(awk -v a="$(epoch "$prev_ts")" -v b="$(epoch "$pts")" 'BEGIN{printf "%.3f", b-a}')"
      fi
      printf '| %s | %s | %s | %s |\n' "$pid" "$pstatus" "$pts" "$delta"
      prev_id="$pid"
      prev_ts="$pts"
    done
  fi
  echo
  echo "## 측정 환경"
  echo
  echo '```'
  cat "$ENV_FILE"
  echo '```'
} > "$REPORT"

cat "$REPORT"
echo
ok "리포트: ${REPORT#"$ROOT"/}"

if [ "$FAIL" -gt 0 ]; then
  warn "${FAIL}개 회차가 READY에 도달하지 못했습니다. worker 프로파일(SPRING_PROFILES_ACTIVE=worker)이 켜져 있는지 확인하세요."
  exit 1
fi
