#!/usr/bin/env bash
# 검색 색인 반영 지연 실측(#246). 같은 변경 셋을 세 모드에 흘린다.
#
#   bash tools/run-search-freshness.sh [lucene-rebuild] [lucene-cdc] [elasticsearch-cdc]     # 기본: 셋 다
#
# lucene-rebuild     지금 동작(10분 주기 재색인, 인스턴스 하나). 30초 안에 반영되는지만 본다
# lucene-cdc         인스턴스 둘, 각자 모든 변경을 받아 자기 색인에 반영
# elasticsearch-cdc  인스턴스 둘이 ES 하나를 같이 쓰고, 색인기는 공유 그룹 하나
# 전제: compose 의 mysql·redis·kafka 가 떠 있다. debezium·ES 는 이 스크립트가 띄우고 지운다.
set -euo pipefail

MODES=("$@"); [ $# -eq 0 ] && MODES=(lucene-rebuild lucene-cdc elasticsearch-cdc)
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-search-freshness}
PER_TYPE=${PER_TYPE:-200}
REBUILD_PER_TYPE=${REBUILD_PER_TYPE:-20}
CONNECT=http://localhost:8083
P1=${P1:-18090}; P2=${P2:-18091}   # 18081 은 다른 로컬 프로젝트가 쓰는 일이 있다
TOPIC=catalog.change
mkdir -p "$OUT"
KAFKA=(docker exec pay-kafka-1 /opt/kafka/bin)

APPS=()
stop_apps() { for p in "${APPS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null && wait "$p" 2>/dev/null || true; done; APPS=(); }
cleanup() {
  stop_apps
  curl -s -X DELETE "$CONNECT/connectors/catalog-cdc" >/dev/null 2>&1 || true
  docker rm -f pay-es >/dev/null 2>&1 || true
  docker compose -p pay -f compose.yaml --profile cdc stop debezium >/dev/null 2>&1 || true
}
trap cleanup EXIT

start_app() {   # $1 이름, $2 포트, 나머지 앱 인자
  local name=$1 port=$2; shift 2
  SPRING_PROFILES_ACTIVE=kafka KAFKA_BOOTSTRAP=localhost:9092 \
    "$JAVA" -jar "$JAR" --server.port="$port" --app.ratelimit.enabled=false "$@" > "$OUT/app-$name-$port.log" 2>&1 &
  APPS+=($!)
  for _ in $(seq 1 240); do curl -sf "http://localhost:$port/actuator/health" >/dev/null 2>&1 && return 0; sleep 1; done
  echo "앱이 안 떴다: $name $port"; tail -30 "$OUT/app-$name-$port.log"; exit 1
}

metric() { curl -s "http://localhost:$1/actuator/prometheus" | grep "^$2" || true; }

# CDC: debezium 을 띄우고(다른 서비스는 건드리지 않는다) 카탈로그 커넥터를 등록한다
docker compose -p pay -f compose.yaml --profile cdc up -d --no-deps debezium >/dev/null
for _ in $(seq 1 90); do curl -sf "$CONNECT/connectors" >/dev/null 2>&1 && break; sleep 2; done
"${KAFKA[@]}/kafka-topics.sh" --bootstrap-server localhost:9092 --create --if-not-exists --topic "$TOPIC" \
  --partitions 3 --replication-factor 1 >/dev/null
curl -s -X DELETE "$CONNECT/connectors/catalog-cdc" >/dev/null 2>&1 || true
curl -sf -X POST -H 'Content-Type: application/json' --data @cdc/register-catalog-connector.json "$CONNECT/connectors" >/dev/null
for _ in $(seq 1 60); do
  curl -s "$CONNECT/connectors/catalog-cdc/status" | grep -q '"tasks":\[{"id":0,"state":"RUNNING"' && break; sleep 2
done
curl -s "$CONNECT/connectors/catalog-cdc/status" > "$OUT/connector-status.json"

for M in "${MODES[@]}"; do
  echo "== $M"
  case "$M" in
    lucene-rebuild)
      start_app "$M" "$P1" --app.catalog.search.engine=lucene
      uv run --quiet --with pymysql python3 tools/search/freshness_eval.py run "$OUT" "$M" http://localhost:$P1 "$REBUILD_PER_TYPE"
      ;;
    lucene-cdc)
      for P in $P1 $P2; do start_app "$M" "$P" --app.catalog.search.engine=lucene --app.catalog.search.cdc.enabled=true; done
      sleep 10    # 컨슈머가 파티션을 받을 때까지
      uv run --quiet --with pymysql python3 tools/search/freshness_eval.py run "$OUT" "$M" http://localhost:$P1,http://localhost:$P2 "$PER_TYPE"
      sleep 3    # 되돌림 이벤트까지 반영된 뒤에 센다
      for P in $P1 $P2; do metric "$P" catalog_search_lucene_changes_total > "$OUT/$M-$P-changes.txt"; done
      ;;
    elasticsearch-cdc)
      docker rm -f pay-es >/dev/null 2>&1 || true
      docker run -d --name pay-es -p 9200:9200 -e discovery.type=single-node -e xpack.security.enabled=false \
        -e ES_JAVA_OPTS="-Xms1g -Xmx1g" docker.elastic.co/elasticsearch/elasticsearch:8.15.5 >/dev/null
      for _ in $(seq 1 120); do curl -sf http://localhost:9200 >/dev/null 2>&1 && break; sleep 2; done
      python3 tools/search/search_index.py http://localhost:9200 > "$OUT/es-index.json"
      for P in $P1 $P2; do
        start_app "$M" "$P" --app.catalog.search.engine=elasticsearch --app.catalog.search.engine-url=http://localhost:9200 \
          --app.catalog.search.cdc.enabled=true
      done
      sleep 10
      uv run --quiet --with pymysql python3 tools/search/freshness_eval.py run "$OUT" "$M" http://localhost:$P1,http://localhost:$P2 "$PER_TYPE"
      docker rm -f pay-es >/dev/null
      ;;
  esac
  stop_apps
done

python3 tools/search/freshness_eval.py report "$OUT" | tee "$OUT/report.md"
