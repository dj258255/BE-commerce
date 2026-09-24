#!/usr/bin/env bash
# CDC poll.interval.ms 의 비용 실측(#252). 같은 커넥터에서 poll 값만 바꿔 CPU 와 지연을 잰다.
#
#   bash tools/run-cdc-poll-cost.sh [100 500 1000]
# 전제: compose 의 mysql·kafka 가 떠 있다. debezium 은 이 스크립트가 띄우고 멈춘다. 앱은 띄우지 않는다.
set -euo pipefail

POLLS=("$@"); [ $# -eq 0 ] && POLLS=(100 500 1000)
OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-cdc-poll-cost}
CONNECT=http://localhost:8083
mkdir -p "$OUT"
cleanup() {
  curl -s -X DELETE "$CONNECT/connectors/catalog-cdc" >/dev/null 2>&1 || true
  docker compose -p pay -f compose.yaml --profile cdc stop debezium >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker compose -p pay -f compose.yaml --profile cdc up -d --no-deps debezium >/dev/null
for _ in $(seq 1 90); do curl -sf "$CONNECT/connectors" >/dev/null 2>&1 && break; sleep 2; done
docker exec pay-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic catalog.change --partitions 3 --replication-factor 1 >/dev/null

for P in "${POLLS[@]}"; do
  echo "== poll.interval.ms=$P"
  curl -s -X DELETE "$CONNECT/connectors/catalog-cdc" >/dev/null 2>&1 || true
  sleep 3
  python3 -c "
import json; d = json.load(open('cdc/register-catalog-connector.json')); d['config']['poll.interval.ms'] = '$P'
json.dump(d, open('$OUT/connector-$P.json', 'w'))"
  curl -sf -X POST -H 'Content-Type: application/json' --data @"$OUT/connector-$P.json" "$CONNECT/connectors" >/dev/null
  for _ in $(seq 1 60); do
    curl -s "$CONNECT/connectors/catalog-cdc/status" | grep -q '"tasks":\[{"id":0,"state":"RUNNING"' && break; sleep 2
  done
  sleep 15    # 기동 직후의 CPU 를 조용한 구간에 섞지 않는다
  uv run --quiet --with pymysql --with confluent-kafka python3 tools/cdc_poll_cost.py run "$OUT" "$P"
done
python3 tools/cdc_poll_cost.py report "$OUT" | tee "$OUT/report.md"
