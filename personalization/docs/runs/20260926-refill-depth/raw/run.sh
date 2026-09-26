#!/bin/bash
# A-045(#344): 되채우기 깊이 1~4(recent_7d) × 3회 + all_time · 깊이 2 × 3회. 일회용 pf1-mysql(카탈로그 · 인기 표 복사본)·pf1-redis.
set -uo pipefail
SP=${SP:?작업 폴더}
cd "$SP/pay-a045"
export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:13306/becommerce?serverTimezone=UTC&characterEncoding=UTF-8"
export SPRING_DATASOURCE_USERNAME=becommerce SPRING_DATASOURCE_PASSWORD=becommerce SPRING_DATA_REDIS_PORT=16379
export MYSQL="docker exec pf1-mysql mysql -N -ubecommerce -pbecommerce becommerce"
docker exec pf1-mysql mysql -uroot -proot -e "DROP DATABASE IF EXISTS becommerce; CREATE DATABASE becommerce; GRANT ALL ON becommerce.* TO 'becommerce'@'%';" 2>/dev/null
docker exec -i pf1-mysql mysql -uroot -proot becommerce < "$SP/a058/template.sql" 2>/dev/null
one() {  # $1=이름 $2=깊이 $3=창
  docker exec pf1-redis redis-cli FLUSHALL >/dev/null
  local d="$SP/a045/runs/$1"; mkdir -p "$d"
  { ps -Ao pcpu,pmem,etime,comm | sort -k1 -nr | head -12 | awk '{n=split($4,a,"/"); print $1,$2,$3,a[n]}'; top -l 1 -n 0 | grep -E "Load Avg|CPU usage"; } > "$d/ps-before.txt"
  APP_RECOMMENDATION_POPULARITY_WINDOW=$3 OUT_DIR="$d" POPULARITY=AUTO REFILL_DEPTH=$2 RULES_LEVELS=FULL USERS=6 \
    bash tools/run-home-composition.sh > "$d/run.log" 2>&1
  echo "== $1 exit $? $(grep -c '인기 신호를 읽었다' "$d"/raw/FULL/app.log 2>/dev/null) $(grep -o '창=[a-z_0-9]*' "$d"/raw/FULL/app.log 2>/dev/null | head -1)"
}
for i in 1 2 3; do
  for dep in 1 2 3 4; do one "refill$dep-r$i" "$dep" recent_7d; done
  one "alltime2-r$i" 2 all_time
done
echo "== 끝 $(date '+%T')"
