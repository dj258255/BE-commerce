#!/bin/bash
# A-065(#339): 관측 창 0.5·2·8초 × {지연 전환, 고정} + 전환 조건의 CONFIGURED. 일회용 pf1-mysql(카탈로그 복사본)·pf1-redis.
set -uo pipefail
SP=${SP:?작업 폴더(pay-a065 · a058/template.sql 을 둔 곳)}
cd "$SP/pay-a065"
export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:13306/becommerce?serverTimezone=UTC&characterEncoding=UTF-8"
export SPRING_DATASOURCE_USERNAME=becommerce SPRING_DATASOURCE_PASSWORD=becommerce SPRING_DATA_REDIS_PORT=16379
reset_db() {
  docker exec pf1-mysql mysql -uroot -proot -e "DROP DATABASE IF EXISTS becommerce; CREATE DATABASE becommerce; GRANT ALL ON becommerce.* TO 'becommerce'@'%';" 2>/dev/null
  docker exec -i pf1-mysql mysql -uroot -proot becommerce < "$SP/a058/template.sql" 2>/dev/null
  docker exec pf1-redis redis-cli FLUSHALL >/dev/null
}
run() {  # $1=이름 $2=창ms $3=전환(1/0) $4=추정
  local d="$SP/a065/runs/$1"; mkdir -p "$d"; reset_db
  { ps -Ao pcpu,pmem,etime,comm | sort -k1 -nr | head -12 | awk '{n=split($4,a,"/"); print $1,$2,$3,a[n]}'; top -l 1 -n 0 | grep -E "Load Avg|CPU usage"; } > "$d/ps-before.txt"
  echo "== $1 $(date '+%T')"
  if [ "$3" = 1 ]; then export APP_RECOMMENDATION_MODEL_STUB_LATENCY_ALT_MS=100 APP_RECOMMENDATION_MODEL_STUB_LATENCY_PERIOD_MS=10000
  else unset APP_RECOMMENDATION_MODEL_STUB_LATENCY_ALT_MS APP_RECOMMENDATION_MODEL_STUB_LATENCY_PERIOD_MS; fi
  APP_RECOMMENDATION_ADMISSION_WINDOW_MS=$2 ADMISSION_ESTIMATE=$4 POLICIES=ADMISSION ADMISSION_BUDGET_MS=100 \
    MODEL_LATENCY_MS=50 RATES=120 DURATION=60s OUT_DIR="$d" bash tools/run-inference-overload.sh > "$d/run.log" 2>&1
  echo "   exit $? $(grep '^\[E3\]' "$d/run.log" | tail -1)"
}
run alt-w500 500 1 OBSERVED
run alt-w2000 2000 1 OBSERVED
run alt-w8000 8000 1 OBSERVED
run alt-configured 2000 1 CONFIGURED
run steady-w500 500 0 OBSERVED
run steady-w2000 2000 0 OBSERVED
run steady-w8000 8000 0 OBSERVED
echo "== 끝 $(date '+%T')"
