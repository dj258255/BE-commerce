#!/usr/bin/env bash
# PG 읽기 타임아웃 5초 대 60초(토스 공식 권장) 실측. 결제 화면과 똑같이 움직이는 고객(k6/checkout-screen.js)으로
# 평소 꼬리와 브라운아웃에서 결과 모름 건수, 최종 판매, 확정까지 걸린 시간, PG 쪽 동시 처리를 잰다.
#
#   bash tools/run-pg-read-timeout-compare.sh
#
# 가짜 PG 는 payment.fake-pg.pg-side-processing=true 로 띄운다. 우리가 끊어도 PG 는 지연만큼 계속 처리하고
# 그동안 같은 멱등키는 409(결과 모름)가 된다. 이걸 끄면 짧은 타임아웃이 PG 부하까지 줄이는 것처럼 보인다.
#
# 조건은 "이름:읽기타임아웃ms:도착률:기본지연ms:느린비율:느린지연ms:PG계약한도" 로 적는다. 순서대로 하나씩 돈다.
# 복구 배치를 켜고 측정이 끝난 뒤 미확정이 0 이 될 때까지(최대 DRAIN_S 초) 기다린 다음 DB 를 센다.
set -euo pipefail

OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-pg-read-timeout}
DUR=${DUR:-60s}
LIMIT=${LIMIT:-40}
DRAIN_S=${DRAIN_S:-480}
DB_PORT=${DB_PORT:-13404}; REDIS_PORT=${REDIS_PORT:-16404}; PORT=${PORT:-18404}
CONDITIONS=${CONDITIONS:-"T-5s:5000:15:1000:0.1:10000:60 T-60s:60000:15:1000:0.1:10000:60 B-5s:5000:50:20000:0:0:60 B-60s:60000:50:20000:0:0:60 Bnc-5s:5000:50:20000:0:0:0 Bnc-60s:60000:50:20000:0:0:0"}
mkdir -p "$OUT"

df -h / | tail -1 > "$OUT/df-before.txt"
docker run -d --name rt-mysql --tmpfs /var/lib/mysql:rw,size=2g -e MYSQL_ROOT_PASSWORD=root -p "$DB_PORT":3306 \
  mysql:8.4 --skip-log-bin --innodb-buffer-pool-size=256M --max-connections=400 >/dev/null
docker run -d --name rt-redis -p "$REDIS_PORT":6379 redis:7.4-alpine >/dev/null
cleanup() { docker rm -f -v rt-mysql rt-redis >/dev/null 2>&1 || true; }
trap cleanup EXIT

MYSQL="docker exec -i rt-mysql mysql -uroot -proot --default-character-set=utf8mb4"
for _ in $(seq 1 90); do
  [ "$(docker logs rt-mysql 2>&1 | grep -c 'ready for connections')" -ge 2 ] && $MYSQL -e "SELECT 1" >/dev/null 2>&1 && break
  sleep 2
done

for c in $CONDITIONS; do
  IFS=: read -r name rto rate lat slow_rate slow_lat contract <<< "$c"
  db="rt_$(echo "$name" | tr 'A-Z-' 'a-z_')"
  $MYSQL -e "CREATE DATABASE $db" 2>/dev/null || true
  echo "== 조건 $name: 읽기=$rto 도착률=$rate 지연=$lat 느린비율=$slow_rate 느린지연=$slow_lat 계약한도=$contract"
  { uptime; pgrep -fl 'k6 run|run-.*\.sh' || true; } > "$OUT/load-before-$name.txt"
  extra="--payment.fake-pg.pg-side-processing=true --payment.fake-pg.contract-concurrency=$contract"
  extra="$extra --payment.fake-pg.approve-slow-rate=$slow_rate --payment.fake-pg.approve-slow-latency-ms=$slow_lat"
  extra="$extra --app.recovery.enabled=true"
  SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$DB_PORT/$db?serverTimezone=UTC&characterEncoding=UTF-8" \
  SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=root SPRING_DATA_REDIS_PORT=$REDIS_PORT \
  PORT=$PORT OUT="$OUT/$name" EXTRA="$extra" DRAIN_S="$DRAIN_S" \
  K6_SCRIPT=k6/checkout-screen.js \
  MYSQL="$MYSQL -N -B $db" \
    bash tools/run-pg-brownout.sh "$lat" "$rate" "$DUR" "$rto" "$LIMIT" > "$OUT/run-$name.log" 2>&1 || echo "실패: $name (로그: $OUT/run-$name.log)" >&2
  # 화면 고객의 최종 상태와 요청부터 승인 확정까지 걸린 초(복구로 확정되면 그 시각). 워밍업 포함
  $MYSQL -N -B "$db" -e "SELECT status, COUNT(*) FROM payments WHERE payment_key LIKE 'screen-%' GROUP BY status" \
    > "$OUT/payments-$name.tsv" 2>>"$OUT/mysql-stderr.txt" || true
  $MYSQL -N -B "$db" -e "SELECT TIMESTAMPDIFF(MICROSECOND, requested_at, approved_at) / 1000000 FROM payments WHERE payment_key LIKE 'screen-%' AND status = 'DONE'" \
    > "$OUT/done-seconds-$name.tsv" 2>>"$OUT/mysql-stderr.txt" || true
done

echo "== 끝: $OUT"
