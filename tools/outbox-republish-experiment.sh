#!/usr/bin/env bash
# 커밋은 됐는데 발행이 안 된 구간을 재기동이 메우는지 검증 (성능 리포트 17절)
#
# 시나리오: Kafka 를 내린 채 결제를 승인한다 → DB 커밋은 성공하고 발행은 실패해
# event_publication 에 미완료 행이 남는다 → 앱을 SIGKILL(프로세스 강제 종료, 프로듀서 버퍼 소실)
# → Kafka 를 올리고 앱을 재기동 → republish-outstanding-events-on-restart 가 발행하는지,
# 몇 초 만에 나가는지 잰다.
#
# 설계 근거: Kafka 를 내린 채 커밋해야 "커밋됨 + 발행 안 됨" 상태가 결정적으로 만들어진다.
# (Kafka 가 살아 있으면 프로듀서 버퍼가 재시도로 즉시 메워 버려 그 상태를 붙잡을 수 없다.)
#
# 실행: tools/outbox-republish-experiment.sh
set -uo pipefail

JAVA=/opt/homebrew/Cellar/openjdk@21/21.0.9/libexec/openjdk.jdk/Contents/Home/bin/java
JAR=build/libs/be-commerce-0.0.1-SNAPSHOT.jar
OUT=${OUT:-/tmp/outbox-republish}
mkdir -p "$OUT"
cd "$(dirname "$0")/.."

log() { echo "[$(date -u +%H:%M:%S)] $*"; }

start_app() {
  $JAVA -Xmx1g -Xms256m -jar "$JAR" --server.port=8080 \
    --spring.docker.compose.enabled=false --app.ratelimit.enabled=false \
    --spring.profiles.active=kafka > "$1" 2>&1 &
  echo $!
}
wait_health() {
  for _ in $(seq 1 60); do curl -sf localhost:8080/actuator/health >/dev/null 2>&1 && return 0; sleep 1; done
  return 1
}
wait_kafka() {
  for _ in $(seq 1 40); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' pay-kafka-1 2>/dev/null)" = healthy ] && return 0; sleep 1
  done
}

pkill -9 -f 'be-commerce-0.0.1-SNAPSHOT.jar' 2>/dev/null; sleep 1
docker start pay-kafka-1 >/dev/null 2>&1; wait_kafka
APP=$(start_app "$OUT/app1.log"); wait_health; log "app up pid=$APP, kafka up"

docker stop pay-kafka-1 >/dev/null; log "kafka stopped (장애 주입)"

TOK=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
        -d '{"username":"1","password":"user-local-only"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
ORDER=$(curl -s -X POST localhost:8080/api/v1/orders -H "Authorization: Bearer $TOK" \
        -H 'Content-Type: application/json' -d '{"items":[{"productId":1,"quantity":1}]}')
ORDNO=$(echo "$ORDER" | python3 -c 'import sys,json;print(json.load(sys.stdin)["orderNo"])')
AMT=$(echo "$ORDER" | python3 -c 'import sys,json;print(json.load(sys.stdin)["totalAmount"])')
RESP=$(curl -s -X POST localhost:8080/api/v1/payments/confirm -H "Authorization: Bearer $TOK" \
        -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
        -d "{\"paymentKey\":\"rp-$(date +%s)\",\"orderNo\":\"$ORDNO\",\"amount\":$AMT}")
log "committed orderNo=$ORDNO resp=$(echo "$RESP" | head -c 70)"
echo "$ORDNO" > "$OUT/ordno"

kill -9 "$APP" 2>/dev/null; log "app SIGKILLed (pid=$APP, 프로듀서 버퍼 소실)"

INC=$(docker exec -i pay-mysql-1 mysql -ubecommerce -pbecommerce becommerce -N -B 2>/dev/null \
      -e "SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL")
log "미완료 발행 행수=$INC"

docker start pay-kafka-1 >/dev/null; wait_kafka; log "kafka healthy"

# 재기동 전부터 토픽을 구독해, 재발행된 메시지가 나타나는 시각을 잡는다.
docker exec pay-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic payment.confirmed --from-beginning > "$OUT/consumer.out" 2>/dev/null &
CONS=$!
sleep 2
T0=$(date +%s)
APP2=$(start_app "$OUT/app2.log")
for _ in $(seq 1 90); do
  if grep -q "$ORDNO" "$OUT/consumer.out" 2>/dev/null; then
    log "재발행 확인 — 앱 재기동 후 $(( $(date +%s) - T0 ))초 만에 토픽에 나타남"
    break
  fi
  sleep 1
done
kill "$CONS" 2>/dev/null

docker exec -i pay-mysql-1 mysql -ubecommerce -pbecommerce becommerce -t 2>/dev/null -e "
  SELECT publication_date, completion_date,
         TIMESTAMPDIFF(SECOND, publication_date, completion_date) AS gap_s
  FROM event_publication_archive ORDER BY completion_date DESC LIMIT 1;
  SELECT COUNT(*) AS still_incomplete FROM event_publication WHERE completion_date IS NULL;"

kill -9 "$APP2" 2>/dev/null; log "done"
