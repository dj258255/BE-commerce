#!/usr/bin/env bash
# R15 준비 스크립트 — 한정 수량 동시 주문 부하(k6/live-order-flash-sale.js)에 쓸 시청자
# 계정 VIEWER_COUNT명을 만들고 로그인해 토큰을 모아 OUT_FILE에 JSON 배열로 저장한다.
#
# k6 setup()에서 직접 가입·로그인을 하면 가입 IP 제한(5/s) 때문에 1,000명 준비만으로도
# 몇 분이 걸리고, 그 준비 시간이 부하 테스트가 재는 "동시 주문" 구간에 섞여 버린다 — 그래서
# 준비를 이 스크립트로 완전히 분리해 미리 끝내 둔다.
#
# 사용(순서는 docs/performance/live-order-r15.md 참고):
#   BASE_URL=http://localhost:8080 VIEWER_COUNT=1000 ./tools/prepare-live-order-viewers.sh
#
# 가입 IP 제한(5/s) 여유로 계정마다 0.25초씩 띄운다 — 1,000명이면 준비에 대략 5분 걸린다.
#
# 토큰 파일은 시청자 1,000명의 로그인 토큰을 그대로 담은 민감한 산출물이라 저장소 밖
# (기본 /tmp)에 쓴다 — 저장소 안 경로를 쓰고 싶으면 OUT_FILE을 .gitignore에 걸린 경로로 주자.
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
VIEWER_COUNT="${VIEWER_COUNT:-1000}"
OUT_FILE="${OUT_FILE:-/tmp/live-order-viewers-tokens.json}"

log() { printf '\033[36m▶\033[0m %s\n' "$*" >&2; }
ok()  { printf '\033[32m✓\033[0m %s\n' "$*" >&2; }
die() { printf '\033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

json_string() { printf '%s' "$1" | sed -nE "s/.*\"$2\":\"([^\"]*)\".*/\\1/p" | head -1; }

command -v curl >/dev/null 2>&1 || die "curl을 찾을 수 없습니다."

health="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health" || echo ERR)"
[ "$health" = "200" ] || die "commerce($BASE_URL)가 떠 있지 않습니다(health=$health)."

mkdir -p "$(dirname "$OUT_FILE")"
run_tag="$(date +%s%N)"
tmp_file="$(mktemp)"
echo -n "[" > "$tmp_file"

log "시청자 계정 ${VIEWER_COUNT}명 준비 중 — 계정당 0.25초 간격(가입 IP 제한 회피), 예상 소요 약 $(( VIEWER_COUNT * 25 / 100 ))초"
prepared=0
for i in $(seq 1 "$VIEWER_COUNT"); do
  email="live-r15-viewer-${run_tag}-${i}@load.test"
  password="load-only-1234"
  signup_code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/members/signup" \
    -H 'Content-Type: application/json' -d "{\"email\":\"$email\",\"password\":\"$password\"}")"
  if [ "$signup_code" != "201" ]; then
    echo "  경고: 계정 $i 가입 실패(HTTP $signup_code) — 건너뜁니다" >&2
    sleep 0.25
    continue
  fi
  login="$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$email\",\"password\":\"$password\"}")"
  token="$(json_string "$login" token)"
  if [ -z "$token" ]; then
    echo "  경고: 계정 $i 로그인 실패 — 건너뜁니다" >&2
    sleep 0.25
    continue
  fi
  [ "$prepared" -gt 0 ] && echo -n "," >> "$tmp_file"
  printf '"%s"' "$token" >> "$tmp_file"
  prepared=$((prepared + 1))
  [ $((i % 50)) -eq 0 ] && log "  ${i}/${VIEWER_COUNT}명 완료"
  sleep 0.25
done
echo -n "]" >> "$tmp_file"
mv "$tmp_file" "$OUT_FILE"

[ "$prepared" -ge 1 ] || die "준비된 계정이 0명입니다."
ok "시청자 토큰 ${prepared}/${VIEWER_COUNT}명을 ${OUT_FILE}에 저장했습니다"
[ "$prepared" -eq "$VIEWER_COUNT" ] || echo "  (참고: ${VIEWER_COUNT}명 중 ${prepared}명만 준비됨 — k6 실행 시 VUS를 ${prepared}에 맞추세요)" >&2
