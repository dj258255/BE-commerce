#!/usr/bin/env bash
# A-022(#335): 워커 × 상한 스윕과 상한 40 에서 read-timeout 2초. 공유 컨테이너 대신 pf1-mysql(13306)·pf1-redis(16379).
set -uo pipefail
SP=${SP:?작업 폴더(pay-a022 를 둔 곳)}
OUT=${OUT:-$SP/a022/runs}
COMBOS=${COMBOS:-"100:40 100:80 100:120 150:40 150:80 150:120 200:40 200:80 200:120"}
export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:13306/becommerce?serverTimezone=UTC&characterEncoding=UTF-8"
export SPRING_DATASOURCE_USERNAME=becommerce SPRING_DATASOURCE_PASSWORD=becommerce SPRING_DATA_REDIS_PORT=16379
export MYSQL="docker exec pf1-mysql mysql -N -B -ubecommerce -pbecommerce becommerce"
cd "$SP/pay-a022"
reset_db() {
  docker exec pf1-mysql mysql -uroot -proot -e "DROP DATABASE IF EXISTS becommerce; CREATE DATABASE becommerce; GRANT ALL ON becommerce.* TO 'becommerce'@'%';" 2>/dev/null
  docker exec pf1-redis redis-cli FLUSHALL >/dev/null
}
snap() { { ps -Ao pcpu,pmem,etime,comm | sort -k1 -nr | head -12 | awk '{n=split($4,a,"/"); print $1,$2,$3,a[n]}'; top -l 1 -n 0 | grep -E "Load Avg|CPU usage"; } > "$1"; }
for c in $COMBOS; do
  W=${c%%:*}; L=${c##*:}; d="$OUT/w${W}-cap${L}${TAG:-}"; mkdir -p "$d"; reset_db; snap "$d/ps-before.txt"
  echo "== 워커 $W 상한 $L $(date '+%T')"
  OUT="$d" EXTRA="--server.tomcat.threads.max=$W ${APP_EXTRA:-}" DRAIN_S=${DRAIN_S:-0} \
    bash tools/run-pg-brownout.sh 3000 50 45s ${RTO:-5000} "$L" > "$d/run.log" 2>&1
  echo "   exit $?"; sleep 10
done
echo "== 끝 $(date '+%T')"
