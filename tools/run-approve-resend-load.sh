#!/usr/bin/env bash
# 승인 재전송(#395)이 느리거나 일부 응답을 잃는 PG 브라운아웃에 얹는 부하를 잰다(#398).
#
#   bash tools/run-approve-resend-load.sh
#
# 조건 여섯: {재전송 0,1} × {PG 느림, PG 일부 손실} 에 "재전송 1 + 예산" 을 두 모드에 더한다(#404).
# 순서대로 하나씩 돈다(동시에 두 조건을 돌리지 않는다).
#
# 예산 조건(payment.pg.approve-resend-min-headroom)은 재전송 시점에 상한 중 빈 자리가 이 수보다
# 적으면 재전송을 걸러 기존 UNKNOWN 을 유지한다. 기본값 4(상한 40 의 10%) — 토큰 버킷 후보였던
# "최근 승인 대비 재전송 10%" 와 자릿수를 맞췄다.
#
# 느림 조건은 기존 하네스 tools/run-pg-brownout.sh 의 지연 주입(payment.fake-pg.approve-latency-ms +
# read-timeout-ms)을 그대로 쓴다. TossPgClient 는 실 PG 라 지연을 주입할 수 없어 Toxiproxy 로 소켓
# 앞에 프록시를 세우는 것보다 가짜 PG 에서 지연을 주입하는 쪽이 재현이 결정적이라는 판단은 ADR-022
# 실측 결과 절의 기존 결정을 그대로 따른다. 지연을 read-timeout 보다 길게 둬 첫 시도가 항상
# 타임아웃되게 한다(기본 지연 5000ms · read-timeout 2000ms).
#
# 손실 조건은 이번에 FakePgClient 에 더한 확률 주입 payment.fake-pg.approve-timeout-lost-rate 를 쓴다.
# 기존 timeout-lost-prefix 는 특정 키 접두어에만 걸려 k6 가 무작위로 만드는 paymentKey 로는 "PG 가
# 일부 응답만 잃는다"를 재현할 수 없어서, 접두어 대신 모든 승인 요청에 독립적으로 걸리는 확률을
# 새로 추가했다(기본 15%).
#
# 일회용 MySQL(tmpfs)·Redis 를 쓰고 조건마다 DB 를 새로 만든다(tools/run-vthreads.sh 의 패턴과 같다).
# 복구 배치(app.recovery.enabled=true)를 켜고 미확정이 0 이 될 때까지 DRAIN_S 초 동안 센다.
set -euo pipefail

OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-approve-resend-load}
RATE=${RATE:-30}; DUR=${DUR:-60s}; DRAIN_S=${DRAIN_S:-300}
LAT=${LAT:-5000}; RTO=${RTO:-2000}          # 느림: 지연이 read-timeout 을 항상 넘겨 첫 시도가 결정적으로 타임아웃
LOSS_RATE=${LOSS_RATE:-0.15}                 # 손실: 승인마다 15% 확률로 완전히 사라짐
LIMIT=${LIMIT:-40}                            # PG 동시 호출 상한. 운영 기본값(ADR-022)
BUDGET=${BUDGET:-4}                           # 재전송 예산: 빈 자리가 이 수보다 적으면 재전송하지 않음(#404)
DB_PORT=${DB_PORT:-13398}; REDIS_PORT=${REDIS_PORT:-16398}; PORT=${PORT:-18398}
# name:resend:mode:headroom  (headroom 은 payment.pg.approve-resend-min-headroom, 0=예산 없음)
CONDITIONS=${CONDITIONS:-"R0-SLOW:0:slow:0 R1-SLOW:1:slow:0 RB-SLOW:1:slow:$BUDGET R0-LOSS:0:loss:0 R1-LOSS:1:loss:0 RB-LOSS:1:loss:$BUDGET"}
mkdir -p "$OUT"

df -h / | tail -1 > "$OUT/df-before.txt"
docker run -d --name ars-mysql --tmpfs /var/lib/mysql:rw,size=2g -e MYSQL_ROOT_PASSWORD=root -p "$DB_PORT":3306 \
  mysql:8.4 --skip-log-bin --innodb-buffer-pool-size=256M --max-connections=400 >/dev/null
docker run -d --name ars-redis -p "$REDIS_PORT":6379 redis:7.4-alpine >/dev/null
cleanup() { docker rm -f -v ars-mysql ars-redis >/dev/null 2>&1 || true; }
trap cleanup EXIT

MYSQL="docker exec -i ars-mysql mysql -uroot -proot"
for _ in $(seq 1 90); do
  [ "$(docker logs ars-mysql 2>&1 | grep -c 'ready for connections')" -ge 2 ] && $MYSQL -e "SELECT 1" >/dev/null 2>&1 && break
  sleep 2
done

for c in $CONDITIONS; do
  IFS=: read -r name resend mode headroom <<< "$c"
  db="ars_$(echo "$name" | tr 'A-Z-' 'a-z_')"
  $MYSQL -e "CREATE DATABASE $db" 2>/dev/null || true
  if [ "$mode" = "slow" ]; then
    lat=$LAT; rto=$RTO
    extra="--app.recovery.enabled=true --payment.pg.approve-resend-max-attempts=$resend --payment.pg.approve-resend-min-headroom=$headroom"
  else
    lat=0; rto=5000
    extra="--app.recovery.enabled=true --payment.pg.approve-resend-max-attempts=$resend --payment.pg.approve-resend-min-headroom=$headroom --payment.fake-pg.approve-timeout-lost-rate=$LOSS_RATE"
  fi
  echo "== 조건 $name: 재전송=$resend 모드=$mode 예산=$headroom"
  SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$DB_PORT/$db?serverTimezone=UTC&characterEncoding=UTF-8" \
  SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=root SPRING_DATA_REDIS_PORT=$REDIS_PORT \
  PORT=$PORT OUT="$OUT/$name" EXTRA="$extra" DRAIN_S=$DRAIN_S \
  MYSQL="$MYSQL -N -B $db" \
    bash tools/run-pg-brownout.sh "$lat" "$RATE" "$DUR" "$rto" "$LIMIT" > "$OUT/run-$name.log" 2>&1 || echo "실패: $name (로그: $OUT/run-$name.log)" >&2
  $MYSQL -N -B "$db" -e "SELECT status, COUNT(*) FROM payments GROUP BY status" > "$OUT/payments-$name.tsv" 2>/dev/null || true
done

echo "== 끝: $OUT"
