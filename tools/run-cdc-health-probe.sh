#!/usr/bin/env bash
# CDC 감시 검증(#252). 커넥터를 toxiproxy 뒤의 MySQL 에 붙이고 장애를 넣어 감시 지표가 언제 바뀌는지 잰다.
#
#   bash tools/run-cdc-health-probe.sh
#
# 순서: 조용한 구간(오탐 없는가) → 연결을 조용히 막음(RUNNING 인데 흐르지 않음) → 풂 → 커넥터 멈춤(pause) → 재개.
# 전제: compose 의 mysql·redis·kafka 가 떠 있다. debezium·toxiproxy·앱은 이 스크립트가 띄우고 지운다.
set -euo pipefail

JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
PORT=${PORT:-18090}
OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-cdc-health-probe}
CONNECT=http://localhost:8083
TOXI=http://localhost:18474      # 8474 는 다른 로컬 프로젝트의 toxiproxy 가 쓰는 일이 있다
IDLE=${IDLE:-150}; STALL=${STALL:-300}; PAUSE=${PAUSE:-120}
mkdir -p "$OUT"

APP=""; SAMPLER=""
cleanup() {
  [ -n "$SAMPLER" ] && kill "$SAMPLER" 2>/dev/null || true
  [ -n "$APP" ] && kill "$APP" 2>/dev/null && wait "$APP" 2>/dev/null || true
  curl -s -X DELETE "$CONNECT/connectors/catalog-cdc" >/dev/null 2>&1 || true
  docker rm -f pay-cdc-toxi >/dev/null 2>&1 || true
  docker compose -p pay -f compose.yaml --profile cdc stop debezium >/dev/null 2>&1 || true
}
trap cleanup EXIT
mark() { echo "$(date +%s) MARK $1" | tee -a "$OUT/samples.txt"; }

docker compose -p pay -f compose.yaml --profile cdc up -d --no-deps debezium >/dev/null
docker rm -f pay-cdc-toxi >/dev/null 2>&1 || true
docker run -d --name pay-cdc-toxi --network pay_default -p 18474:8474 ghcr.io/shopify/toxiproxy:2.11.0 >/dev/null
for _ in $(seq 1 90); do curl -sf "$CONNECT/connectors" >/dev/null 2>&1 && curl -sf "$TOXI/version" >/dev/null 2>&1 && break; sleep 2; done
curl -sf -X POST "$TOXI/proxies" -d '{"name":"mysql_cdc","listen":"0.0.0.0:3307","upstream":"mysql:3306"}' >/dev/null
docker exec pay-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic catalog.change --partitions 3 --replication-factor 1 >/dev/null

curl -s -X DELETE "$CONNECT/connectors/catalog-cdc" >/dev/null 2>&1 || true
sleep 3
python3 -c "
import json; d = json.load(open('cdc/register-catalog-connector.json'))
d['config']['database.hostname'] = 'pay-cdc-toxi'; d['config']['database.port'] = '3307'
json.dump(d, open('$OUT/connector.json', 'w'))"
curl -sf -X POST -H 'Content-Type: application/json' --data @"$OUT/connector.json" "$CONNECT/connectors" >/dev/null
for _ in $(seq 1 60); do curl -s "$CONNECT/connectors/catalog-cdc/status" | grep -q '"tasks":\[{"id":0,"state":"RUNNING"' && break; sleep 2; done

SPRING_PROFILES_ACTIVE=kafka KAFKA_BOOTSTRAP=localhost:9092 APP_CDC_HEALTH_CONNECT_URL=$CONNECT \
  "$JAVA" -jar "$JAR" --server.port="$PORT" --app.ratelimit.enabled=false > "$OUT/app.log" 2>&1 &
APP=$!
for _ in $(seq 1 180); do curl -sf "http://localhost:$PORT/actuator/health" >/dev/null 2>&1 && break; sleep 1; done

# 5초마다: 앱이 낸 감시 지표와 Connect REST 의 상태
( while true; do
    M=$(curl -s -m 2 "http://localhost:$PORT/actuator/prometheus" | grep -E '^cdc_' | awk '{printf "%s=%s ", $1, $NF}')
    S=$(curl -s -m 2 "$CONNECT/connectors/catalog-cdc/status" | python3 -c "import sys,json
try:
  d=json.load(sys.stdin); print('rest='+d['connector']['state']+'/'+','.join(t['state'] for t in d['tasks']))
except Exception: print('rest=?')")
    echo "$(date +%s) $S $M"; sleep 5; done ) >> "$OUT/samples.txt" 2>/dev/null &
SAMPLER=$!

touch_db() { docker exec pay-mysql-1 sh -c "mysql -uroot -proot becommerce -e 'UPDATE products SET price = price + 1 WHERE product_id = 108775015; UPDATE products SET price = price - 1 WHERE product_id = 108775015;'" 2>/dev/null; }
touch_db; sleep 20

mark idle;  sleep "$IDLE"
mark stall; curl -sf -X POST "$TOXI/proxies/mysql_cdc/toxics" -d '{"name":"blackhole","type":"timeout","stream":"downstream","attributes":{"timeout":0}}' >/dev/null
            curl -sf -X POST "$TOXI/proxies/mysql_cdc/toxics" -d '{"name":"blackhole_up","type":"timeout","stream":"upstream","attributes":{"timeout":0}}' >/dev/null
            touch_db; sleep "$STALL"
mark heal;  curl -sf -X DELETE "$TOXI/proxies/mysql_cdc/toxics/blackhole" >/dev/null; curl -sf -X DELETE "$TOXI/proxies/mysql_cdc/toxics/blackhole_up" >/dev/null
            touch_db; sleep 90
mark pause; curl -sf -X PUT "$CONNECT/connectors/catalog-cdc/pause" >/dev/null; sleep "$PAUSE"
mark resume; curl -sf -X PUT "$CONNECT/connectors/catalog-cdc/resume" >/dev/null; touch_db; sleep 60
mark end
python3 tools/cdc_health_probe_report.py "$OUT" | tee "$OUT/report.md"
