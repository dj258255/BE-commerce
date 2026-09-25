#!/usr/bin/env bash
# A-058(#329): 조회 셰딩 문턱 × 커넥션 풀 스윕. 공유 컨테이너 대신 pf1-mysql(13306)·pf1-redis(16379).
# 실행마다 같은 카탈로그 상태(template.sql)로 DB 를 되돌린다.
set -uo pipefail
SP=${SP:?작업 폴더(template.sql·pay-docs 를 둔 곳)}
OUT=${OUT:-$SP/a058/runs}
COMBOS=${COMBOS:-"16:20 8:20 12:20 20:20 16:30 8:30 12:30 20:30"}
RATES=${RATES:-"200 400"}
export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:13306/becommerce?serverTimezone=UTC&characterEncoding=UTF-8"
export SPRING_DATASOURCE_USERNAME=becommerce SPRING_DATASOURCE_PASSWORD=becommerce SPRING_DATA_REDIS_PORT=16379
export MYSQL="docker exec pf1-mysql mysql -N -B -ubecommerce -pbecommerce becommerce"
cd "$SP/pay-docs"
for c in $COMBOS; do
  T=${c%%:*}; P=${c##*:}
  for B in $RATES; do
    NAME="t${T}p${P}${TAG:-}"
    docker exec pf1-mysql mysql -uroot -proot -e "DROP DATABASE IF EXISTS becommerce; CREATE DATABASE becommerce;" 2>/dev/null
    docker exec -i pf1-mysql mysql -uroot -proot becommerce < "$SP/a058/template.sql" 2>/dev/null
    docker exec pf1-redis redis-cli FLUSHALL >/dev/null
    mkdir -p "$OUT/$NAME-browse$B"
    { ps -Ao pcpu,pmem,etime,comm | sort -k1 -nr | head -12 | awk '{n=split($4,a,"/"); print $1,$2,$3,a[n]}'
      top -l 1 -n 0 | grep -E "Load Avg|CPU usage"; } > "$OUT/$NAME-browse$B/ps-before.txt"
    echo "== $NAME 조회 $B/s $(date '+%T')"
    OUT="$OUT" NAME="$NAME" BROWSE_RATES="$B" \
      EXTRA="--app.web.browse-shed.enabled=true --app.web.browse-shed.max-in-flight=$T --spring.datasource.hikari.maximum-pool-size=$P" \
      bash tools/run-webhook-under-browse.sh 2>&1 | tail -1
    sleep 5
  done
done
python3 tools/webhook_browse_report.py "$OUT" > "$OUT/report.md"
echo "== 끝 $(date '+%T')"
