#!/bin/bash
# PF-6(#364): ES 8.15.5 단일 노드 · 힙 1GB, 316만 건(×30), 샤드 1 · 3 · 6. 포트 19200(공유 9200 과 겹치지 않게).
set -uo pipefail
SP=${SP:?작업 폴더}
cd "$SP/pay-pf6"
OUT="$SP/pf6/runs"
foreign_wait() { while pgrep -f "genpage_server|genpage2_server" >/dev/null || pgrep -fl "k6 run" | grep -q k6; do
  echo "   다른 측정이 돌고 있어 기다린다 $(date '+%T')"; sleep 30; done; }
for S in ${SHARDS:-1 3 6}; do
  foreign_wait; echo "== 샤드 $S $(date '+%T')"
  docker rm -f pf6-es >/dev/null 2>&1
  docker run -d --name pf6-es -p 19200:9200 -e discovery.type=single-node -e xpack.security.enabled=false \
    -e ES_JAVA_OPTS="-Xms1g -Xmx1g" docker.elastic.co/elasticsearch/elasticsearch:8.15.5 >/dev/null
  for _ in $(seq 1 120); do curl -sf localhost:19200 >/dev/null 2>&1 && break; sleep 2; done
  python3 tools/search/search_index.py http://localhost:19200 --replicate 30 --shards "$S" > "$OUT/es-index-s$S.json"
  cat "$OUT/es-index-s$S.json"
  echo "{\"container_mem\": \"$(docker stats --no-stream --format '{{.MemUsage}}' pf6-es | cut -d/ -f1 | tr -d ' ')\"}" > "$OUT/es-mem-s$S.json"
  { ps -Ao pcpu,pmem,etime,comm | sort -k1 -nr | head -12 | awk '{n=split($4,a,"/"); print $1,$2,$3,a[n]}'; top -l 1 -n 0 | grep -E "Load Avg|CPU usage"; } > "$OUT/ps-s$S.txt"
  python3 tools/search/es_scale_bench.py http://localhost:19200 "$OUT/filter-queries.json" "$OUT/es-scale-s$S.json"
  docker rm -f pf6-es >/dev/null
done
echo "== 끝 $(date '+%T')"
