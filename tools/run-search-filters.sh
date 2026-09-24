#!/usr/bin/env bash
# 검색어 + 필터·패싯 실측과 앱 안 Lucene 규모 실측(#244, ADR-055).
#
#   bash tools/run-search-filters.sh [filters] [scale]     # 기본: 둘 다
#
# filters: 앱을 세 모드(lucene · lucene 후보 자르기 · elasticsearch)로 띄워 같은 쿼리를 같은 API 로 흘리고 정답과 맞춘다.
# scale:   실제 행을 1·10·30 배 복제한 카탈로그로 Lucene(앱과 같은 클래스)과 ES 를 같은 요청 셋으로 잰다.
# 전제: compose 의 mysql·redis 가 떠 있고 products 가 적재돼 있다. ES 는 이 스크립트가 띄우고 지운다.
set -euo pipefail

PARTS=("$@"); [ $# -eq 0 ] && PARTS=(filters scale)
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
PORT=${PORT:-18080}
BASE="http://localhost:${PORT}"
OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-search-filters}
ES_HEAP=${ES_HEAP:-1g}
BENCH_HEAP=${BENCH_HEAP:-4g}
SCALES=(${SCALES:-1 10 30})
mkdir -p "$OUT"

APP=""
stop_app() { [ -n "$APP" ] && kill "$APP" 2>/dev/null && wait "$APP" 2>/dev/null || true; APP=""; }
trap 'stop_app; docker rm -f pay-es >/dev/null 2>&1 || true' EXIT

start_app() {   # $1 이름, 나머지 앱 인자
  local name=$1; shift
  "$JAVA" -jar "$JAR" --server.port="$PORT" --app.ratelimit.enabled=false "$@" > "$OUT/app-$name.log" 2>&1 &
  APP=$!
  for _ in $(seq 1 240); do curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && return 0; sleep 1; done
  echo "앱이 안 떴다: $name"; tail -30 "$OUT/app-$name.log"; exit 1
}

start_es() {
  docker rm -f pay-es >/dev/null 2>&1 || true
  docker run -d --name pay-es -p 9200:9200 -e discovery.type=single-node -e xpack.security.enabled=false \
    -e ES_JAVA_OPTS="-Xms$ES_HEAP -Xmx$ES_HEAP" docker.elastic.co/elasticsearch/elasticsearch:8.15.5 >/dev/null
  for _ in $(seq 1 120); do curl -sf http://localhost:9200 >/dev/null 2>&1 && return 0; sleep 2; done
  echo "ES 가 안 떴다"; exit 1
}

bench() {   # $1 모드, 나머지 인자
  "$JAVA" -Xmx"$BENCH_HEAP" -Dloader.main=com.beomsu.becommerce.order.catalog.search.LuceneScaleBenchmark \
    -cp "$JAR" org.springframework.boot.loader.launch.PropertiesLauncher "$@"
}

[ -f "$OUT/filter-queries.json" ] || python3 tools/search/filter_eval.py queries "$OUT"

for P in "${PARTS[@]}"; do
  case "$P" in
    filters)
      bench matches "$OUT/catalog.tsv" "$OUT/filter-queries.json" "$OUT/matches-lucene.json"
      start_app lucene --app.catalog.search.engine=lucene
      python3 tools/search/filter_eval.py run "$OUT" "$BASE" lucene; stop_app
      start_app lucene-candidates --app.catalog.search.engine=lucene --app.catalog.search.filters-in-engine=false
      python3 tools/search/filter_eval.py run "$OUT" "$BASE" lucene-candidates; stop_app
      start_es
      python3 tools/search/search_index.py http://localhost:9200 > "$OUT/es-index-1.json"
      python3 tools/search/filter_eval.py es-match "$OUT" http://localhost:9200
      start_app elasticsearch --app.catalog.search.engine=elasticsearch --app.catalog.search.engine-url=http://localhost:9200
      python3 tools/search/filter_eval.py run "$OUT" "$BASE" elasticsearch; stop_app
      docker rm -f pay-es >/dev/null
      python3 tools/search/filter_eval.py report "$OUT" | tee "$OUT/report-filters.md"
      ;;
    scale)
      for N in "${SCALES[@]}"; do
        python3 -c "
import sys; sys.path.insert(0, 'tools/search')
from catalog_dump import load_full, replicate, write_tsv
write_tsv(replicate(load_full(), $N), '$OUT/catalog-x$N.tsv')"
        bench scale "$OUT/catalog-x$N.tsv" "$OUT/filter-queries.json" "$OUT/lucene-scale-x$N.json"
        rm -f "$OUT/catalog-x$N.tsv"
        start_es
        python3 tools/search/search_index.py http://localhost:9200 --replicate "$N" > "$OUT/es-index-x$N.json"
        echo "{\"container_mem\": \"$(docker stats --no-stream --format '{{.MemUsage}}' pay-es | cut -d/ -f1 | tr -d ' ')\"}" \
          > "$OUT/es-mem-x$N.json"
        python3 tools/search/es_scale_bench.py http://localhost:9200 "$OUT/filter-queries.json" "$OUT/es-scale-x$N.json"
        docker rm -f pay-es >/dev/null
      done
      ;;
  esac
done
