#!/bin/bash
# PF-8 · PF-7(#354): 일회용 CDC 스택(cdcexp: MySQL 13326 · Kafka 19092 · Connect 18083)에서 lucene-cdc 앱 두 대(18190 · 18191).
set -uo pipefail
SP=${SP:?작업 폴더}
cd "$SP/pay-cdc"
OUT="$SP/cdc/runs"; mkdir -p "$OUT"
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
P1=18190; P2=18191
export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:13326/becommerce?serverTimezone=UTC&characterEncoding=UTF-8"
export SPRING_DATASOURCE_USERNAME=becommerce SPRING_DATASOURCE_PASSWORD=becommerce SPRING_DATA_REDIS_PORT=16379
export DB_PORT=13326
APPS=(); SAMPLER=""
stop_apps() { [ -n "$SAMPLER" ] && kill "$SAMPLER" 2>/dev/null; SAMPLER=""; for p in "${APPS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null && wait "$p" 2>/dev/null; done; APPS=(); }
trap stop_apps EXIT
foreign_wait() {  # 다른 세션의 측정(GenPage 모델 서버 · 이 러너가 띄우지 않은 k6 · 앱)이 있으면 끝날 때까지 기다린다
  while pgrep -f "genpage_server|genpage2_server" >/dev/null || pgrep -fl "k6 run" | grep -v ":1819" | grep -q k6 \
        || pgrep -fl "be-commerce-0.0.1-SNAPSHOT.jar" | grep -v "port=1819" | grep -q jar; do
    echo "   다른 측정이 돌고 있어 기다린다 $(date '+%T')"; sleep 30; done
}
start_apps() {  # $1=갱신 주기
  for P in $P1 $P2; do
    SPRING_PROFILES_ACTIVE=kafka KAFKA_BOOTSTRAP=localhost:19092 "$JAVA" -jar "$JAR" --server.port="$P" --app.ratelimit.enabled=false \
      --app.catalog.search.engine=lucene --app.catalog.search.cdc.enabled=true --app.catalog.search.lucene-nrt-refresh="$1" \
      > "$OUT/app-$TAG-$P.log" 2>&1 &
    APPS+=($!)
  done
  for P in $P1 $P2; do for _ in $(seq 1 180); do curl -sf "localhost:$P/actuator/health" >/dev/null 2>&1 && break; sleep 1; done; done
  sleep 10    # 컨슈머가 파티션을 받을 때까지
}
cpu_sampler() {  # 두 앱의 process_cpu_usage 를 1초마다
  ( while true; do echo "$(date +%s) $(for P in $P1 $P2; do curl -s -m1 localhost:$P/actuator/prometheus | awk '/^process_cpu_usage/{print $NF}'; done | tr '\n' ' ')"; sleep 1; done ) > "$OUT/cpu-$TAG.txt" 2>/dev/null &
  SAMPLER=$!
}
snap() { { ps -Ao pcpu,pmem,etime,comm | sort -k1 -nr | head -12 | awk '{n=split($4,a,"/"); print $1,$2,$3,a[n]}'; top -l 1 -n 0 | grep -E "Load Avg|CPU usage"; } > "$OUT/ps-$TAG.txt"; }
# PF-8: 갱신 주기
for R in ${REFRESHES:-1s 250ms 100ms}; do
  TAG="refresh-$R"; foreign_wait; echo "== $TAG $(date '+%T')"; snap
  start_apps "$R"; cpu_sampler
  uv run --quiet --with pymysql python3 tools/search/freshness_eval.py run "$OUT" "lucene-cdc-$R" "http://localhost:$P1,http://localhost:$P2" "${PER_TYPE:-100}"
  stop_apps; sleep 5
done
# PF-7: 몰림
for N in ${BULKS:-0 5000 20000}; do
  TAG="bulk-$N"; foreign_wait; echo "== $TAG $(date '+%T')"; snap
  start_apps 1s; cpu_sampler
  uv run --quiet --with pymysql python3 tools/search/freshness_eval.py bulk "$OUT" "lucene-cdc-bulk$N" "http://localhost:$P1,http://localhost:$P2" "$N"
  stop_apps; sleep 5
done
echo "== 끝 $(date '+%T')"
