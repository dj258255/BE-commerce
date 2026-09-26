#!/bin/bash
# PF-9(#350): 타임라인 요청당 쿼리 수와 처리량 무릎. 일회용 pf1-mysql · pf1-redis.
set -uo pipefail
SP=${SP:?작업 폴더}
cd "$SP/pay-pf9"
export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:13306/becommerce?serverTimezone=UTC&characterEncoding=UTF-8"
export SPRING_DATASOURCE_USERNAME=becommerce SPRING_DATASOURCE_PASSWORD=becommerce SPRING_DATA_REDIS_PORT=16379
Q() { docker exec pf1-mysql mysql -N -B -uroot -proot -e "$1" 2>/dev/null; }
Q "DROP DATABASE IF EXISTS becommerce; CREATE DATABASE becommerce; GRANT ALL ON becommerce.* TO 'becommerce'@'%';"
docker exec pf1-redis redis-cli FLUSHALL >/dev/null
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
APP_RATELIMIT_ENABLED=false "$JAVA" -jar commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar --server.port=18099 > "$SP/pf9/app.log" 2>&1 & APP=$!
trap 'kill $APP 2>/dev/null' EXIT
for _ in $(seq 1 120); do curl -sf localhost:18099/actuator/health >/dev/null && break; sleep 1; done
echo "== 주문 만들기 $(date '+%T')"
k6 run --quiet -e BASE_URL=http://localhost:18099 -e VUS=30 -e DURATION=60s k6/redeploy-blast-radius.js > "$SP/pf9/seed-k6.txt" 2>&1
Q "SELECT o.order_no FROM becommerce.orders o JOIN becommerce.payments p ON p.order_no = o.order_no WHERE p.status = 'DONE'" | python3 -c "import sys,json;print(json.dumps([l.strip() for l in sys.stdin if l.strip()]))" > "$SP/pf9/orders.json"
echo "결제 끝난 주문 $(python3 -c "import json;print(len(json.load(open('$SP/pf9/orders.json'))))")건"
Q "SELECT 'orders',COUNT(*) FROM becommerce.orders UNION ALL SELECT 'payments',COUNT(*) FROM becommerce.payments UNION ALL SELECT 'ledger_entries',COUNT(*) FROM becommerce.ledger_entries UNION ALL SELECT 'audit_logs',COUNT(*) FROM becommerce.audit_logs" > "$SP/pf9/data-counts.txt"
T=$(curl -s -X POST localhost:18099/api/v1/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin-local-only"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
ORD=$(python3 -c "import json;print(json.load(open('$SP/pf9/orders.json'))[0])")
curl -s -H "Authorization: Bearer $T" "localhost:18099/api/v1/admin/orders/$ORD/timeline" > "$SP/pf9/sample-timeline.json"
sleep 3
q0=$(Q "SHOW GLOBAL STATUS LIKE 'Questions'" | awk '{print $2}')
python3 - "$SP/pf9/orders.json" "$T" <<'PY'
import json, sys, urllib.request
orders = json.load(open(sys.argv[1]))[:200]
for o in orders:
    urllib.request.urlopen(urllib.request.Request(f"http://localhost:18099/api/v1/admin/orders/{o}/timeline", headers={"Authorization": f"Bearer {sys.argv[2]}"})).read()
PY
q1=$(Q "SHOW GLOBAL STATUS LIKE 'Questions'" | awk '{print $2}')
echo "요청당 Questions $(python3 -c "print(round(($q1-$q0-2)/200,2))") (전체 $((q1-q0)), 측정용 SHOW 2 제외)" | tee "$SP/pf9/queries-per-request.txt"
{ ps -Ao pcpu,pmem,etime,comm | sort -k1 -nr | head -12 | awk '{n=split($4,a,"/"); print $1,$2,$3,a[n]}'; top -l 1 -n 0 | grep -E "Load Avg|CPU usage"; } > "$SP/pf9/ps-before.txt"
echo "== 처리량 $(date '+%T')"
k6 run --summary-export "$SP/pf9/summary.json" -e BASE_URL=http://localhost:18099 -e ORDERS="$SP/pf9/orders.json" \
  -e STEPS="${STEPS:-50,100,200,400,700,1000}" -e STEP_SECONDS=60 k6/timeline-capacity.js > "$SP/pf9/k6.txt" 2>&1
echo "== 끝 $(date '+%T')"
