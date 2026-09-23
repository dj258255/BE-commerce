#!/usr/bin/env bash
# 미확정(UNKNOWN) 복구 실측 — 만들고, 풀리는 과정을 DB 에서 본다.
#
# 14.3 은 미확정을 <b>만드는 것까지</b>만 쟀다. 그 일거리가 언제 어떻게 풀리는지는 안 쟀다.
# 이 스크립트는 같은 조건으로 미확정을 만들고, 복구 배치가 푸는 곡선을 payments 테이블에서 찍는다.
#
# 사용: tools/run-unknown-recovery.sh [도착률] [지속] [복구주기ms] [청크]
#   기본: 30/s · 45s · 5000ms · 500 (청크 기본값)
#
# 전제: docker compose up -d mysql redis · ./gradlew -p commerce bootJar
set -euo pipefail

RATE=${1:-30}
DUR=${2:-45s}
INTERVAL=${3:-5000}
CHUNK=${4:-500}
LAT=${LAT:-5000}      # PG 주입 지연
RTO=${RTO:-2000}      # read-timeout — LAT 보다 작아야 미확정이 난다
WATCH_MAX=${WATCH_MAX:-300}   # 해소를 기다리는 최대 초

JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
PORT=${PORT:-18080}
BASE="http://localhost:${PORT}"
MYSQL="docker exec pay-mysql-1 mysql -N -B -ubecommerce -pbecommerce becommerce"
STAMP=$(date +%Y%m%d-%H%M%S)
OUT="docs/performance/runs/${STAMP}-unknown-recovery-rate${RATE}-int${INTERVAL}-chunk${CHUNK}"
mkdir -p "$OUT"

echo "== 미확정 복구 실측: 지연 ${LAT}ms · rto ${RTO}ms · 도착률 ${RATE}/s · ${DUR}"
echo "==   복구 주기 ${INTERVAL}ms · 청크 ${CHUNK} · 출력 $OUT"

counts() { $MYSQL -e "SELECT status, COUNT(*) FROM payments GROUP BY status" 2>/dev/null | tr '\t' '=' | tr '\n' ' '; }
unknowns() { $MYSQL -e "SELECT COUNT(*) FROM payments WHERE status='UNKNOWN'" 2>/dev/null | tr -d '[:space:]'; }

{
  echo "rate=$RATE dur=$DUR lat=${LAT}ms rto=${RTO}ms interval=${INTERVAL}ms chunk=$CHUNK"
  echo "host  $(uname -sm) $(sysctl -n hw.ncpu) cores"
  echo "commit $(git rev-parse --short HEAD)"
  echo "before $(counts)"
} > "$OUT/meta.txt"

# 복구를 켜고 띄운다. MIN_AGE(1분)는 코드 상수라 설정으로 못 줄인다 — 그것이 하한이다.
"$JAVA" -jar "$JAR" \
  --server.port="$PORT" \
  --payment.fake-pg.approve-latency-ms="$LAT" \
  --payment.fake-pg.read-timeout-ms="$RTO" \
  --app.ratelimit.enabled=false \
  --app.recovery.enabled=true \
  --app.recovery.interval-ms="$INTERVAL" \
  --app.batch.read-chunk-size="$CHUNK" \
  > "$OUT/app.log" 2>&1 &
APP=$!
trap 'kill "$APP" 2>/dev/null || true' EXIT

for _ in $(seq 1 120); do curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
curl -sf "$BASE/actuator/health" >/dev/null || { echo "앱이 안 떴다"; tail -20 "$OUT/app.log"; exit 1; }
echo "== 앱 기동 완료"

BEFORE_UNKNOWN=$(unknowns)
echo "== 부하 전 미확정 $BEFORE_UNKNOWN 건"

k6 run --quiet -e BASE_URL="$BASE" -e RATE="$RATE" -e DURATION="$DUR" k6/pg-brownout.js > "$OUT/k6.txt" 2>&1 || true
LOAD_END=$(date +%s)
PEAK=$(unknowns)
echo "== 부하 끝 — 미확정 $PEAK 건 (증가 $((PEAK - BEFORE_UNKNOWN)))"

# 해소 곡선. 상태 수는 DB 에서 직접 읽는다 — 응답 코드도 지표도 아니고 payments 테이블이 진실이다.
{
  echo "t_sec,unknown,done,aborted"
  while true; do
    now=$(date +%s); el=$((now - LOAD_END))
    u=$(unknowns)
    d=$($MYSQL -e "SELECT COUNT(*) FROM payments WHERE status='DONE'" 2>/dev/null | tr -d '[:space:]')
    a=$($MYSQL -e "SELECT COUNT(*) FROM payments WHERE status='ABORTED'" 2>/dev/null | tr -d '[:space:]')
    echo "${el},${u},${d},${a}"
    [ "$u" = "$BEFORE_UNKNOWN" ] && break
    [ "$el" -ge "$WATCH_MAX" ] && { echo "# ${WATCH_MAX}s 안에 안 끝났다" >&2; break; }
    sleep 2
  done
} | tee "$OUT/drain.csv"

{
  echo "peak_unknown=$PEAK"
  echo "created=$((PEAK - BEFORE_UNKNOWN))"
  echo "after $(counts)"
} >> "$OUT/meta.txt"

grep -aE "복구|recover" "$OUT/app.log" | tail -40 > "$OUT/recovery.log" || true
echo "== 끝: $OUT"
