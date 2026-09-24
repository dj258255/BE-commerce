#!/usr/bin/env bash
# #172 — 검색 베이스라인. **동시성 1·10·50**에서 현재 경로(MySQL WHERE+INDEX)의 지연을 잰다.
#
# 왜 동시성을 축으로 두나: 이슈 #172 가 그렇게 고정했고, 판정 기준도 동시성별 p95 로 적혀 있다.
# 부하 단계가 아니라 **동시성 단계**라 재기동 없이 한 앱에서 돈다.
#
# 전제: 실제 카탈로그가 적재돼 있어야 한다(V55 가 데모 카탈로그를 은퇴시켰다).
#   python3 personalization/pipeline/promote_products.py --emit-sql --load
set -euo pipefail
cd "$(dirname "$0")/.."

STAMP=$(date +%Y%m%d-%H%M%S)
OUT=${OUT_DIR:-docs/performance/runs/${STAMP}-검색-베이스라인}
RAW="$OUT/raw"
mkdir -p "$RAW"

VUS_LIST=${VUS_LIST:-"1 10 50"}
DURATION=${DURATION:-30s}
# 패싯 사전 집계(ADR-044). **`0s` 가 기준선(캐시 없음)** 이고, 기본값 60s 와 같은 하네스로 비교한다.
FACET_TTL=${FACET_TTL:-0s}
PORT=${PORT:-18080}
BASE="http://localhost:${PORT}"
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"

[ -f "$JAR" ] || { echo "$JAR 가 없다 — ./gradlew -p commerce bootJar 를 먼저 돌려라"; exit 1; }
command -v k6 >/dev/null || { echo "k6 가 없다"; exit 1; }

ROWS=$(docker exec pay-mysql-1 mysql -N -ubecommerce -pbecommerce becommerce -e "select count(*) from products" 2>/dev/null || echo "?")
[ "$ROWS" != "?" ] && [ "$ROWS" -gt 1000 ] || {
  echo "카탈로그가 작다(${ROWS}행) — promote_products.py --emit-sql --load 를 먼저 돌려라"; exit 1; }
echo "== 코퍼스: products ${ROWS}행"

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

echo "== #172 검색 베이스라인 시작 (동시성: $VUS_LIST, ${DURATION}씩, 패싯 캐시 TTL=$FACET_TTL)"
echo "== 출력: $OUT"
wait_port_free
# 조회 셰딩(#250)은 끈다 — 이 스크립트는 셰딩 없는 경로의 지연을 잰다
APP_WEB_BROWSE_SHED_ENABLED=false APP_RATELIMIT_ENABLED=false APP_CATALOG_FACETS_CACHE_TTL="$FACET_TTL" "$JAVA" -jar "$JAR" \
  --spring.docker.compose.enabled=false --server.port="$PORT" > "$RAW/app.log" 2>&1 &
APP=$!
for _ in $(seq 1 120); do
  curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && { sleep 4; break; }
  kill -0 "$APP" 2>/dev/null || { echo "앱이 죽었다 — $RAW/app.log"; exit 1; }
  sleep 1
done

for vus in $VUS_LIST; do
  echo "-- 동시성 $vus"
  VUS="$vus" BASE_URL="$BASE" DURATION="$DURATION" \
    k6 run k6/storefront-search.js > "$RAW/k6-vus${vus}.txt" 2>&1 || true
  grep '^\[172\]' "$RAW/k6-vus${vus}.txt" | tee -a "$RAW/summary.txt" || true
done

cleanup

cat > "$RAW/meta.txt" <<EOF
corpus_products=$ROWS
vus_list=$VUS_LIST
duration=$DURATION
facet_cache_ttl=$FACET_TTL
EOF

echo
python3 tools/search_baseline_report.py "$RAW" | tee "$OUT/summary-table.md"
echo "== 원자료: $RAW"
