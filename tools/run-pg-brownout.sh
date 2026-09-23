#!/usr/bin/env bash
# ADR-022 실측 드라이버 — PG 지연을 주입하고 자원 점유를 같이 기록한다.
#
# 사용: tools/run-pg-brownout.sh <지연ms> [도착률/초] [지속] [읽기타임아웃ms]
#   예: tools/run-pg-brownout.sh 3000 30 60s
#
# 전제: docker compose up -d mysql redis · ./gradlew -p commerce bootJar
set -euo pipefail

LAT=${1:?지연 ms 를 달라}
RATE=${2:-30}
DUR=${3:-60s}
RTO=${4:-5000}
LIMIT=${5:-0}     # PG 동시 호출 상한. 0 이면 상한 없음(기본 동작)

JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
# 기본 java 가 17 이면 21 로 빌드한 jar 가 안 뜬다. 8080 은 다른 것이 쓰고 있을 수 있어 비켜 둔다.
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"   # JAVA_HOME 이 17 로 잡혀 있어도 21 로 띄운다
PORT=${PORT:-18080}
BASE="http://localhost:${PORT}"
STAMP=$(date +%Y%m%d-%H%M%S)
OUT="docs/performance/runs/${STAMP}-brownout-lat${LAT}-rate${RATE}-rto${RTO}-lim${LIMIT}"
mkdir -p "$OUT"

echo "== 실행: 지연 ${LAT}ms · 도착률 ${RATE}/s · ${DUR} · read-timeout ${RTO}ms · 상한 ${LIMIT}"
echo "== 출력: $OUT"

"$JAVA" -jar "$JAR" \
  --server.port="$PORT" \
  --payment.fake-pg.approve-latency-ms="$LAT" \
  --payment.fake-pg.read-timeout-ms="$RTO" \
  --payment.pg.max-concurrent-calls="$LIMIT" \
  --app.ratelimit.enabled=false \
  --server.tomcat.mbeanregistry.enabled=true \
  > "$OUT/app.log" 2>&1 &
APP=$!
SAMPLER=""
cleanup() { kill "$APP" 2>/dev/null || true; [ -n "$SAMPLER" ] && kill "$SAMPLER" 2>/dev/null || true; }
trap cleanup EXIT

for _ in $(seq 1 120); do
  if curl -sf "$BASE/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -sf "$BASE/actuator/health" >/dev/null || { echo "앱이 안 떴다"; tail -20 "$OUT/app.log"; exit 1; }
echo "== 앱 기동 완료"

# 자원 점유 샘플러. 커넥션과 워커를 같이 봐야 병목이 어디로 옮겨갔는지 보인다.
# /actuator/metrics 는 인증이 걸려 401 이라 prometheus 텍스트에서 읽는다.
# tomcat_* 는 server.tomcat.mbeanregistry.enabled=true 여야 등록된다. 기본값은 off 라
# 워커가 얼마나 묶였는지를 볼 지표 자체가 없었다.
sample() {
  curl -s "$BASE/actuator/prometheus" | python3 -c '
import sys, re
h = t = ""
for line in sys.stdin:
    if line.startswith("hikaricp_connections_active"):
        h = line.rsplit(" ", 1)[1].strip()
    elif line.startswith("tomcat_threads_busy_threads"):
        t = line.rsplit(" ", 1)[1].strip()
print(f"{h},{t}")'
}
START_SAMPLER() {
{
  echo "t,hikari_active,tomcat_busy"
  while true; do
    echo "$(date +%s),$(sample)"
    sleep 1
  done
} > "$OUT/resources.csv" &
SAMPLER=$!
}

# 워밍업. 기동 직후 첫 요청은 JIT·커넥션 생성·Flyway 뒤끝이 섞여 p95 가 튄다.
# 이걸 빼지 않으면 "지연 0 이 200ms 보다 느리다" 같은 값이 나온다.
echo "== 워밍업 15s"
k6 run --quiet -e BASE_URL="$BASE" -e RATE=10 -e DURATION=15s k6/pg-brownout.js > "$OUT/k6-warmup.txt" 2>&1 || true

START_SAMPLER
echo "== 본 측정"
k6 run -e BASE_URL="$BASE" -e RATE="$RATE" -e DURATION="$DUR" k6/pg-brownout.js 2>&1 | tee "$OUT/k6.txt"

kill "$SAMPLER" 2>/dev/null || true

# 미확정이 얼마나 쌓였는지는 앱이 살아 있을 때 지표로 받는다.
curl -s "$BASE/actuator/prometheus" | grep -E "^payment_|^hikaricp_connections|^tomcat_threads" > "$OUT/prometheus-final.txt" 2>/dev/null || true

python3 - "$OUT/resources.csv" <<'PY'
import csv, sys
rows = list(csv.DictReader(open(sys.argv[1])))
def col(k):
    return [float(r[k]) for r in rows if r.get(k)]
h, t = col('hikari_active'), col('tomcat_busy')
if h: print(f"hikari active  max={max(h):.0f} avg={sum(h)/len(h):.1f}")
if t: print(f"tomcat busy    max={max(t):.0f} avg={sum(t)/len(t):.1f}")
PY

echo "== 끝: $OUT"
