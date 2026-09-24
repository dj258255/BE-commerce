#!/usr/bin/env bash
# 쪽을 넘기는 사이 색인이 바뀔 때의 검색 결과(#258). Lucene + CDC 로 띄우고 변경 방식마다 같은 쿼리를 넘긴다.
#
#   NAME=base bash tools/run-paging-churn.sh [none random targeted insert]
# 전제: compose 의 mysql·redis·kafka. debezium 은 이 스크립트가 띄우고 멈춘다.
set -euo pipefail

MODES=("$@"); [ $# -eq 0 ] && MODES=(none random targeted insert)
NAME=${NAME:-base}
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
PORT=${PORT:-18090}; BASE="http://localhost:$PORT"
OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-paging-churn}
CONNECT=http://localhost:8083
mkdir -p "$OUT"
APP=""
cleanup() {
  [ -n "$APP" ] && kill "$APP" 2>/dev/null && wait "$APP" 2>/dev/null || true
  curl -s -X DELETE "$CONNECT/connectors/catalog-cdc" >/dev/null 2>&1 || true
  docker compose -p pay -f compose.yaml --profile cdc stop debezium >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker compose -p pay -f compose.yaml --profile cdc up -d --no-deps debezium >/dev/null
for _ in $(seq 1 90); do curl -sf "$CONNECT/connectors" >/dev/null 2>&1 && break; sleep 2; done
docker exec pay-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic catalog.change --partitions 3 --replication-factor 1 >/dev/null
curl -s -X DELETE "$CONNECT/connectors/catalog-cdc" >/dev/null 2>&1 || true
sleep 3
curl -sf -X POST -H 'Content-Type: application/json' --data @cdc/register-catalog-connector.json "$CONNECT/connectors" >/dev/null
for _ in $(seq 1 60); do curl -s "$CONNECT/connectors/catalog-cdc/status" | grep -q '"tasks":\[{"id":0,"state":"RUNNING"' && break; sleep 2; done

SPRING_PROFILES_ACTIVE=kafka KAFKA_BOOTSTRAP=localhost:9092 "$JAVA" -jar "$JAR" --server.port="$PORT" \
  --app.ratelimit.enabled=false --app.web.browse-shed.enabled=false --app.catalog.search.engine=lucene \
  > "$OUT/app-$NAME.log" 2>&1 &
APP=$!
for _ in $(seq 1 180); do curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
sleep 5

PYRUN=(uv run --quiet --with pymysql python3)
[ -f "$OUT/paging-queries.json" ] || "${PYRUN[@]}" tools/search/paging_churn_eval.py queries "$OUT" "$BASE"
for M in "${MODES[@]}"; do
  "${PYRUN[@]}" tools/search/paging_churn_eval.py run "$OUT" "$NAME-$M" "$BASE" "$M"
done
python3 tools/search/paging_churn_eval.py report "$OUT" | tee "$OUT/report.md"
