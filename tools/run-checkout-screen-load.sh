#!/usr/bin/env bash
# 결제 화면 경로 실측(#440 후속) — 화면과 똑같이 움직이는 고객(k6/checkout-screen.js)으로
# 데드라인 확인이 실제로 서는지, 화면이 기다림을 멈춘 뒤 조회로 결과를 받는지 잰다.
#
#   bash tools/run-checkout-screen-load.sh
#
# 조건은 "이름:데드라인확인:PG지연ms:읽기타임아웃ms:상한:도착률" 으로 적는다. 순서대로 하나씩 돈다.
#   A5  운영 설정(읽기 5초 · 상한 40)에서 PG 지연 5초. 승인은 5초에 성공한다
#   A8  같은 설정에서 PG 지연 8초. 승인은 5초에 끊겨 UNKNOWN, 빈 자리가 있으면 재전송한다
#   B   PG 지연 20초 · 읽기 25초. 서버 응답이 화면의 15초를 넘는 경우
#   C   상한을 끈 상태(A5 와 같은 지연). 워커 100 이 말라 요청이 서버 앞 대기열에서 기다린다
#
# 일회용 MySQL(tmpfs)·Redis 를 쓰고 조건마다 DB 를 새로 만든다(run-deadline-load.sh 와 같은 패턴).
set -euo pipefail

OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-checkout-screen-load}
DUR=${DUR:-60s}
DB_PORT=${DB_PORT:-13402}; REDIS_PORT=${REDIS_PORT:-16402}; PORT=${PORT:-18402}
CONDITIONS=${CONDITIONS:-"A5-OFF:false:5000:5000:40:50 A5-ON:true:5000:5000:40:50 A8-OFF:false:8000:5000:40:50 A8-ON:true:8000:5000:40:50 B-ON:true:20000:25000:40:5 C-OFF:false:5000:5000:0:50 C-ON:true:5000:5000:0:50"}
mkdir -p "$OUT"

df -h / | tail -1 > "$OUT/df-before.txt"
docker run -d --name cs-mysql --tmpfs /var/lib/mysql:rw,size=2g -e MYSQL_ROOT_PASSWORD=root -p "$DB_PORT":3306 \
  mysql:8.4 --skip-log-bin --innodb-buffer-pool-size=256M --max-connections=400 >/dev/null
docker run -d --name cs-redis -p "$REDIS_PORT":6379 redis:7.4-alpine >/dev/null
cleanup() { docker rm -f -v cs-mysql cs-redis >/dev/null 2>&1 || true; }
trap cleanup EXIT

MYSQL="docker exec -i cs-mysql mysql -uroot -proot --default-character-set=utf8mb4"   # 한글 사유를 비교하려면 필요
for _ in $(seq 1 90); do
  [ "$(docker logs cs-mysql 2>&1 | grep -c 'ready for connections')" -ge 2 ] && $MYSQL -e "SELECT 1" >/dev/null 2>&1 && break
  sleep 2
done

for c in $CONDITIONS; do
  IFS=: read -r name enabled lat rto limit rate <<< "$c"
  db="cs_$(echo "$name" | tr 'A-Z-' 'a-z_')"
  $MYSQL -e "CREATE DATABASE $db" 2>/dev/null || true
  echo "== 조건 $name: deadline-check=$enabled 지연=$lat 읽기=$rto 상한=$limit 도착률=$rate"
  { uptime; pgrep -fl 'k6 run|run-.*\.sh' || true; } > "$OUT/load-before-$name.txt"
  SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$DB_PORT/$db?serverTimezone=UTC&characterEncoding=UTF-8" \
  SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=root SPRING_DATA_REDIS_PORT=$REDIS_PORT \
  PORT=$PORT OUT="$OUT/$name" EXTRA="--payment.deadline-check.enabled=$enabled" \
  K6_SCRIPT=k6/checkout-screen.js \
  MYSQL="$MYSQL -N -B $db" \
    bash tools/run-pg-brownout.sh "$lat" "$rate" "$DUR" "$rto" "$limit" > "$OUT/run-$name.log" 2>&1 || echo "실패: $name (로그: $OUT/run-$name.log)" >&2
  # 워밍업(도착률 10, 15초)도 같은 DB 와 카운터에 들어간다. 접두어로 화면 고객과 대조군을 나눠 센다.
  # payments 에는 실패 사유 칼럼이 없어 생략 건수는 카운터(워밍업 포함)로 본다. 대조군은 전부 생략되므로
  # "카운터 = 대조군 수"면 화면 고객의 생략은 0 이다.
  for p in screen ctrl; do
    $MYSQL -N -B "$db" -e "SELECT status, COUNT(*) FROM payments WHERE payment_key LIKE '$p-%' GROUP BY status" \
      > "$OUT/payments-$p-$name.tsv" 2>&1 || true
  done
done

echo "== 끝: $OUT"
