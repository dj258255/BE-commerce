#!/bin/bash
# A-067a(#342): GenPage v1 모델을 CPU 2 컨테이너로 떼어 놓고 2쪽 none 대 shared. 일회용 pf1-mysql(카탈로그 복사본)·pf1-redis.
set -uo pipefail
SP=${SP:?작업 폴더}
DATA=${DATA:?GenPage 데이터 폴더(personalization/data)}
cd "$SP/pay-a067"
export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:13306/becommerce?serverTimezone=UTC&characterEncoding=UTF-8"
export SPRING_DATASOURCE_USERNAME=becommerce SPRING_DATASOURCE_PASSWORD=becommerce SPRING_DATA_REDIS_PORT=16379
model_up() {
  docker rm -f a067-model >/dev/null 2>&1
  docker run -d --name a067-model --cpus=2 -e GENPAGE_THREADS=2 -e GENPAGE_DATA=/data -e GENPAGE_HOST=0.0.0.0 -p 18765:8765 \
    -v "$SP/pay-a067/personalization/serving:/app/personalization/serving:ro" \
    -v "$SP/pay-a067/personalization/pipeline:/app/personalization/pipeline:ro" \
    -v "$DATA:/data:ro" genpage-serving:a067 python /app/personalization/serving/genpage_server.py 8765 >/dev/null
  for _ in $(seq 1 90); do curl -sf localhost:18765/health >/dev/null 2>&1 && return 0; sleep 1; done; return 1
}
for PR in ${PAGE_RATES:-9 18}; do
  for CAP in none shared; do
    d="$SP/a067/runs/$CAP-page-$PR"; mkdir -p "$d"
    docker exec pf1-mysql mysql -uroot -proot -e "DROP DATABASE IF EXISTS becommerce; CREATE DATABASE becommerce; GRANT ALL ON becommerce.* TO 'becommerce'@'%';" 2>/dev/null
    docker exec -i pf1-mysql mysql -uroot -proot becommerce < "$SP/a058/template.sql" 2>/dev/null
    docker exec pf1-redis redis-cli FLUSHALL >/dev/null
    model_up || { echo "모델이 안 떴다"; exit 1; }
    { ps -Ao pcpu,pmem,etime,comm | sort -k1 -nr | head -12 | awk '{n=split($4,a,"/"); print $1,$2,$3,a[n]}'; top -l 1 -n 0 | grep -E "Load Avg|CPU usage"; } > "$d/ps-before.txt"
    echo "== $CAP page=$PR $(date '+%T')"
    APP_RECOMMENDATION_MODEL_PAGE_CAPACITY=$CAP POLICIES=ADMISSION ADMISSION_ESTIMATE=OBSERVED MODEL_KIND=genpage \
      MODEL_URL=http://localhost:18765 ACTIVITY_ITEMS="$SP/a067/runs/activity-items.json" RATES=40 PAGE_RATE="$PR" \
      OUT_DIR="$d" bash tools/run-inference-overload.sh > "$d/run.log" 2>&1
    docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' a067-model > "$d/model-stats-after.txt" 2>/dev/null
    docker logs a067-model > "$d/model-server.log" 2>&1
    grep -h '^\[\(E3\|PAGE\)\]' "$d/raw/summary.txt" 2>/dev/null | sed "s/^/   /" || echo "   결과 없음"
  done
done
docker rm -f a067-model >/dev/null 2>&1
echo "== 끝 $(date '+%T')"
