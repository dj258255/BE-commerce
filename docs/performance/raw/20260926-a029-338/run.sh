#!/bin/bash
# A-029(#338): single · rolling · drain 을 차례로. 공유 컨테이너 대신 pf1-mysql(13306)·pf1-redis(16379).
set -uo pipefail
SP=${SP:?작업 폴더(pay-a029 를 둔 곳)}
cd "$SP/pay-a029"
export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:13306/becommerce?serverTimezone=UTC&characterEncoding=UTF-8"
export SPRING_DATASOURCE_USERNAME=becommerce SPRING_DATASOURCE_PASSWORD=becommerce SPRING_DATA_REDIS_PORT=16379
export MYSQL="docker exec pf1-mysql mysql -N -B -ubecommerce -pbecommerce becommerce"
for m in ${MODES:-single rolling drain}; do
  docker exec pf1-mysql mysql -uroot -proot -e "DROP DATABASE IF EXISTS becommerce; CREATE DATABASE becommerce; GRANT ALL ON becommerce.* TO 'becommerce'@'%';" 2>/dev/null
  docker exec pf1-redis redis-cli FLUSHALL >/dev/null
  d="$SP/a029/runs/$m${TAG:-}"; mkdir -p "$d"
  { ps -Ao pcpu,pmem,etime,comm | sort -k1 -nr | head -12 | awk '{n=split($4,a,"/"); print $1,$2,$3,a[n]}'; top -l 1 -n 0 | grep -E "Load Avg|CPU usage"; } > "$d/ps-before.txt"
  echo "== $m $(date '+%T')"
  OUT="$d" bash tools/run-rolling-deploy.sh "$m" > "$d/run.log" 2>&1; echo "   exit $?"
  cat "$d/result.txt" 2>/dev/null
  sleep 10
done
echo "== 끝 $(date '+%T')"
