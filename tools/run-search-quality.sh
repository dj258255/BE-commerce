#!/usr/bin/env bash
# 검색 품질 실측(#236, ADR-051). 엔진마다 앱을 다시 띄워 같은 쿼리셋을 같은 API 로 흘린다.
#
#   bash tools/run-search-quality.sh [엔진 ...]     # 기본: 여섯 개 전부
#
# 전제: compose 의 mysql·redis 가 떠 있고 products 가 적재돼 있다. ES·OpenSearch 는 이 스크립트가 띄우고 지운다.
set -euo pipefail

ENGINES=("${@:-like like-fields fulltext lucene elasticsearch opensearch}")
[ $# -eq 0 ] && ENGINES=(like like-fields fulltext lucene elasticsearch opensearch)
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
PORT=${PORT:-18080}
BASE="http://localhost:${PORT}"
OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-search-quality}
HEAP=${ENGINE_HEAP:-512m}
mkdir -p "$OUT"
MYSQL=(docker exec pay-mysql-1 sh -c)

[ -f "$OUT/queries.json" ] || python3 tools/search/search_quality_eval.py queries "$OUT/queries.json"

APP=""
stop_app() { [ -n "$APP" ] && kill "$APP" 2>/dev/null && wait "$APP" 2>/dev/null || true; APP=""; }
trap 'stop_app; docker rm -f pay-es pay-os >/dev/null 2>&1 || true' EXIT

start_app() {   # $1 엔진, $2 엔진 URL
  "$JAVA" -jar "$JAR" --server.port="$PORT" --app.ratelimit.enabled=false \
    --app.catalog.search.engine="$1" --app.catalog.search.engine-url="${2:-http://localhost:9200}" \
    > "$OUT/app-$1.log" 2>&1 &
  APP=$!
  for _ in $(seq 1 180); do curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && return 0; sleep 1; done
  echo "앱이 안 떴다: $1"; tail -30 "$OUT/app-$1.log"; exit 1
}

wait_engine() { for _ in $(seq 1 120); do curl -sf "$1" >/dev/null 2>&1 && return 0; sleep 2; done; echo "엔진이 안 떴다: $1"; exit 1; }

engine_mem() { docker stats --no-stream --format '{{.MemUsage}}' "$1" | cut -d/ -f1 | tr -d ' '; }

for E in "${ENGINES[@]}"; do
  echo "== $E"
  URL=""
  case "$E" in
    fulltext)
      S=$(date +%s.%N)
      "${MYSQL[@]}" 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" becommerce -e "ALTER TABLE products ADD FULLTEXT INDEX ft_products_search (name, product_type, description)"' 2>/dev/null
      echo "{\"fulltext_index_seconds\": $(python3 -c "import time;print(round(time.time()-$S,2))")}" > "$OUT/fulltext-index.json"
      ;;
    elasticsearch)
      docker run -d --name pay-es -p 9200:9200 -e discovery.type=single-node -e xpack.security.enabled=false \
        -e ES_JAVA_OPTS="-Xms$HEAP -Xmx$HEAP" docker.elastic.co/elasticsearch/elasticsearch:8.15.5 >/dev/null
      URL=http://localhost:9200; wait_engine "$URL"
      python3 tools/search/search_index.py "$URL" > "$OUT/elasticsearch-index.json"
      ;;
    opensearch)
      docker run -d --name pay-os -p 9201:9200 -e discovery.type=single-node -e DISABLE_SECURITY_PLUGIN=true \
        -e DISABLE_INSTALL_DEMO_CONFIG=true -e OPENSEARCH_JAVA_OPTS="-Xms$HEAP -Xmx$HEAP" \
        opensearchproject/opensearch:2.19.1 >/dev/null
      URL=http://localhost:9201; wait_engine "$URL"
      python3 tools/search/search_index.py "$URL" > "$OUT/opensearch-index.json"
      ;;
  esac
  start_app "$E" "$URL"
  python3 tools/search/search_quality_eval.py run "$OUT/queries.json" "$BASE" "$E" "$OUT/$E.json"
  if [ "$E" = lucene ]; then
    grep -o 'Lucene 색인 [^ ]*건 · [^ ]*ms · [^ ]*KB' "$OUT/app-lucene.log" > "$OUT/lucene-index.txt" || true
  fi
  if [ "$E" = elasticsearch ] || [ "$E" = opensearch ]; then
    C=$([ "$E" = elasticsearch ] && echo pay-es || echo pay-os)
    echo "{\"container_mem\": \"$(engine_mem $C)\"}" > "$OUT/$E-mem.json"
    # 물러서기 확인: 엔진을 멈추고 검색이 200 으로 답하는지, 지표가 오르는지
    docker stop "$C" >/dev/null
    CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/products?q=dress&size=10")
    FB=$(curl -s "$BASE/actuator/prometheus" | grep '^catalog_search_fallback_total' || true)
    echo "{\"status_after_engine_stop\": $CODE, \"metric\": \"$FB\"}" > "$OUT/$E-fallback.json"
    docker rm -f "$C" >/dev/null
  fi
  stop_app
  if [ "$E" = fulltext ]; then
    "${MYSQL[@]}" 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" becommerce -e "ALTER TABLE products DROP INDEX ft_products_search"' 2>/dev/null
  fi
done

python3 tools/search/search_quality_eval.py report "$OUT" | tee "$OUT/report-table.md"
