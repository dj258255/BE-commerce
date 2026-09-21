#!/usr/bin/env bash
# M7 — 홈 조립. **조립 규칙 수준 × 사용자**를 돌려 "규칙이 화면에서 무엇을 바꾸는가"를 낸다.
#
# 왜 규칙마다 앱을 다시 띄우나: 규칙은 기동 설정이고, 실행 중에 바꾸면 그 사이 요청이 섞인다
# (E3·E4·E5 와 같은 이유).
#
# 왜 사용자가 여러 명인가: 화면 구조는 사용자마다 다르다(최근 활동·모델 순위가 다르다).
# 한 명만 보면 규칙의 효과가 그 한 명의 데이터에 묻힌다.
#
# 왜 활동을 **실제 상품 id** 로 심나: 홈은 카탈로그에 있는 상품만 카드로 그린다. 실험용 합성 id 를
# 심으면 "최근 본 상품" 행이 통째로 비고, 그러면 규칙이 아니라 **id 공간의 불일치**를 재게 된다.
#
# 사용:
#   ./gradlew bootJar
#   bash tools/run-home-composition.sh
set -euo pipefail
cd "$(dirname "$0")/.."

STAMP=$(date +%Y%m%d-%H%M%S)
OUT=${OUT_DIR:-personalization/docs/runs/${STAMP}-m7-홈-조립}
RAW="$OUT/raw"
mkdir -p "$RAW"

RULES_LEVELS=${RULES_LEVELS:-"NONE DEDUP FULL"}
USERS=${USERS:-6}
ACTIVITY_PER_USER=${ACTIVITY_PER_USER:-8}
ROW_CAP=${ROW_CAP:-5}
ITEM_CAP=${ITEM_CAP:-8}
MIN_ITEMS=${MIN_ITEMS:-3}
MAX_PER_CATEGORY=${MAX_PER_CATEGORY:-3}
PORT=${PORT:-18080}
BASE="http://localhost:${PORT}"
JAR=build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
JQ() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null || echo ""; }

[ -f "$JAR" ] || { echo "$JAR 가 없다 — ./gradlew bootJar 를 먼저 돌려라"; exit 1; }

APP=""
cleanup() { [ -n "$APP" ] && kill "$APP" 2>/dev/null || true; APP=""; }
trap cleanup EXIT

wait_port_free() {
  for _ in $(seq 1 40); do
    lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1 || return 0
    sleep 1
  done
  lsof -nP -tiTCP:"$PORT" -sTCP:LISTEN | xargs -r kill -9 2>/dev/null || true
  sleep 2
}

start_app() {
  local rules=$1 log=$2
  cleanup
  wait_port_free
  APP_RATELIMIT_ENABLED=false \
  APP_HOME_RULES="$rules" \
  APP_HOME_ROW_CAP="$ROW_CAP" \
  APP_HOME_ITEM_CAP="$ITEM_CAP" \
  APP_HOME_MIN_ITEMS="$MIN_ITEMS" \
  APP_HOME_MAX_PER_CATEGORY="$MAX_PER_CATEGORY" \
  APP_PERSONALIZATION_TRANSPORT=IN_REQUEST \
  "$JAVA" -jar "$JAR" \
    --spring.docker.compose.enabled=false \
    --server.port="$PORT" > "$log" 2>&1 &
  APP=$!
  for _ in $(seq 1 120); do
    curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && { sleep 4; return 0; }
    kill -0 "$APP" 2>/dev/null || { echo "앱이 죽었다 — $log"; return 1; }
    sleep 1
  done
  echo "앱이 안 떴다 — $log"; return 1
}

echo "== M7 홈 조립 실측 시작"
echo "== 규칙: $RULES_LEVELS / 사용자 ${USERS}명 / 활동 ${ACTIVITY_PER_USER}건"
echo "== 행 상한 $ROW_CAP · 항목 상한 $ITEM_CAP · 최소 항목 $MIN_ITEMS · 카테고리 상한 $MAX_PER_CATEGORY"
echo "== 전달 방식 IN_REQUEST(동기) — 활동을 심은 직후 홈을 부르므로 컨텍스트가 반영돼 있어야 한다"
echo "== 출력: $OUT"

# 활동에 심을 **실제 상품 id** 를 DB 에서 읽는다. V55 가 데모 카탈로그(4~36)를 은퇴시켰으므로
# 하드코딩한 id 는 이제 없다 — 홈은 카탈로그에 있는 상품만 카드로 그리므로, 없는 id 를 심으면
# "최근 본 상품" 행이 통째로 비고, 그러면 규칙이 아니라 **id 공간의 불일치**를 재게 된다.
PRODUCT_IDS=$(docker exec pay-mysql-1 mysql -N -ubecommerce -pbecommerce becommerce \
  -e "select product_id from products where category_code is not null order by product_id limit 200" 2>/dev/null \
  | tr '\n' ' ')
[ -n "$PRODUCT_IDS" ] || { echo "카탈로그가 비었다 — promote_products.py --emit-sql --load 를 먼저 돌려라"; exit 1; }
echo "== 활동에 쓸 실제 상품 id: $(echo $PRODUCT_IDS | wc -w | tr -d ' ')개"

for rules in $RULES_LEVELS; do
  mkdir -p "$RAW/$rules"
  echo "-- $rules"
  start_app "$rules" "$RAW/$rules/app.log" || exit 1

  # 상품 id 범위(4~36 중 재고가 있는 것) — 실제 카탈로그에 있는 id 여야 홈이 카드를 그린다.
  for u in $(seq 1 "$USERS"); do
    email="home-${rules}-${u}-$(date +%s)@load.test"
    signup=$(curl -s -X POST "$BASE/api/v1/members/signup" -H 'Content-Type: application/json' \
      -d "{\"email\":\"$email\",\"password\":\"home-load-only-1234\"}")
    token=$(curl -s -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
      -d "{\"username\":\"$email\",\"password\":\"home-load-only-1234\"}" | JQ "d['token']")
    if [ -z "$token" ]; then
      echo "   사용자 $u 로그인 실패 — 로그인 한도(5/s)에 걸렸을 수 있다"; sleep 1; continue
    fi
    for seq in $(seq 1 "$ACTIVITY_PER_USER"); do
      # 사용자마다 다른 구간에서 뽑는다 — 모든 사용자가 같은 상품을 보면 화면이 전부 같아진다.
      item=$(echo "$PRODUCT_IDS" | tr ' ' '\n' | awk -v u="$u" -v s="$seq" -v n="$(echo $PRODUCT_IDS | wc -w | tr -d ' ')" 'NR==((u*7+s*3)%n)+1')
      curl -s -o /dev/null -X POST "$BASE/api/v1/personalization/activity" \
        -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
        -d "{\"itemId\":$item,\"type\":\"VIEW\",\"seq\":$seq}"
    done
    # 한 번 데워서 버리고(첫 요청은 JIT·커넥션 풀이 지배한다) 두 번째를 기록한다.
    curl -s -o /dev/null -H "Authorization: Bearer $token" "$BASE/api/v1/personalization/homepage"
    curl -s -H "Authorization: Bearer $token" "$BASE/api/v1/personalization/homepage" \
      > "$RAW/$rules/home-user-${u}.json"
    sleep 0.3
  done

  cat > "$RAW/$rules/meta.txt" <<EOF
rules=$rules
users=$USERS
activity_per_user=$ACTIVITY_PER_USER
row_cap=$ROW_CAP
item_cap=$ITEM_CAP
min_items=$MIN_ITEMS
max_per_category=$MAX_PER_CATEGORY
EOF
  cleanup
done

echo
python3 tools/home_report.py "$RAW" | tee "$OUT/summary-table.md"
echo "== 원자료: $RAW"
