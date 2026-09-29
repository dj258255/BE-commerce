#!/usr/bin/env bash
# 데드라인 전파(#409, 27절⑦) 실측 — 이미 떠난 고객의 결제가 PG 슬롯을 얼마나 차지하는지,
# 넣은 뒤(payment.deadline-check.enabled=true) 얼마나 줄어드는지 잰다.
#
#   bash tools/run-deadline-load.sh
#
# 조건 둘. 순서대로 하나씩 돈다(동시에 두 조건을 돌리지 않는다).
#   OFF  payment.deadline-check.enabled=false — 헤더는 받지만 무시한다(옛 동작, 대조군)
#   ON   payment.deadline-check.enabled=true  — 기본값(넣은 뒤)
#
# 느린 PG는 기존 하네스 tools/run-pg-brownout.sh 의 지연 주입을 그대로 쓴다(PG 슬롯이 귀해야
# "PG 슬롯을 아꼈는가"가 드러난다). k6 스크립트는 k6/pg-brownout-deadline.js — 요청의
# LATE_FRACTION 만큼 남은 시간 0 의 X-Request-Timeout-Ms 를 보내 "이미 떠난 고객"을 흉내낸다.
#
# 일회용 MySQL(tmpfs)·Redis 를 쓰고 조건마다 DB 를 새로 만든다(tools/run-approve-resend-load.sh 의 패턴과 같다).
set -euo pipefail

OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-deadline-load}
RATE=${RATE:-30}; DUR=${DUR:-60s}
LAT=${LAT:-5000}; RTO=${RTO:-2000}          # 느림: 지연이 read-timeout 을 항상 넘겨 승인이 오래 걸림 확정
LIMIT=${LIMIT:-40}                            # PG 동시 호출 상한. 운영 기본값(ADR-022)
LATE_FRACTION=${LATE_FRACTION:-0.3}
LATE_TIMEOUT_MS=${LATE_TIMEOUT_MS:-0}
NORMAL_TIMEOUT_MS=${NORMAL_TIMEOUT_MS:-30000}
DB_PORT=${DB_PORT:-13399}; REDIS_PORT=${REDIS_PORT:-16399}; PORT=${PORT:-18399}
# name:enabled
CONDITIONS=${CONDITIONS:-"OFF:false ON:true"}
mkdir -p "$OUT"

df -h / | tail -1 > "$OUT/df-before.txt"
docker run -d --name dl-mysql --tmpfs /var/lib/mysql:rw,size=2g -e MYSQL_ROOT_PASSWORD=root -p "$DB_PORT":3306 \
  mysql:8.4 --skip-log-bin --innodb-buffer-pool-size=256M --max-connections=400 >/dev/null
docker run -d --name dl-redis -p "$REDIS_PORT":6379 redis:7.4-alpine >/dev/null
cleanup() { docker rm -f -v dl-mysql dl-redis >/dev/null 2>&1 || true; }
trap cleanup EXIT

MYSQL="docker exec -i dl-mysql mysql -uroot -proot"
for _ in $(seq 1 90); do
  [ "$(docker logs dl-mysql 2>&1 | grep -c 'ready for connections')" -ge 2 ] && $MYSQL -e "SELECT 1" >/dev/null 2>&1 && break
  sleep 2
done

for c in $CONDITIONS; do
  IFS=: read -r name enabled <<< "$c"
  db="dl_$(echo "$name" | tr 'A-Z-' 'a-z_')"
  $MYSQL -e "CREATE DATABASE $db" 2>/dev/null || true
  extra="--payment.deadline-check.enabled=$enabled"
  echo "== 조건 $name: deadline-check.enabled=$enabled"
  SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$DB_PORT/$db?serverTimezone=UTC&characterEncoding=UTF-8" \
  SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=root SPRING_DATA_REDIS_PORT=$REDIS_PORT \
  PORT=$PORT OUT="$OUT/$name" EXTRA="$extra" \
  K6_SCRIPT=k6/pg-brownout-deadline.js \
  K6_EXTRA_ARGS="-e LATE_FRACTION=$LATE_FRACTION -e LATE_TIMEOUT_MS=$LATE_TIMEOUT_MS -e NORMAL_TIMEOUT_MS=$NORMAL_TIMEOUT_MS" \
  MYSQL="$MYSQL -N -B $db" \
    bash tools/run-pg-brownout.sh "$LAT" "$RATE" "$DUR" "$RTO" "$LIMIT" > "$OUT/run-$name.log" 2>&1 || echo "실패: $name (로그: $OUT/run-$name.log)" >&2
  $MYSQL -N -B "$db" -e "SELECT status, COUNT(*) FROM payments GROUP BY status" > "$OUT/payments-$name.tsv" 2>/dev/null || true
  $MYSQL -N -B "$db" -e "SELECT status, COUNT(*) FROM payments WHERE payment_key LIKE 'late-%' GROUP BY status" > "$OUT/payments-late-$name.tsv" 2>/dev/null || true
  $MYSQL -N -B "$db" -e "SELECT status, COUNT(*) FROM payments WHERE payment_key LIKE 'normal-%' GROUP BY status" > "$OUT/payments-normal-$name.tsv" 2>/dev/null || true
done

echo "== 끝: $OUT"
