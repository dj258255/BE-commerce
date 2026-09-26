#!/bin/bash
# PF-11(#352): 버퍼 풀 W(256MB, 데움) · S(8MB) · C(256MB, 막 다시 띄운 직후). 디스크 볼륨 MySQL(pf11-mysql, 13317).
set -uo pipefail
SP=${SP:?작업 폴더}
cd "$SP/pay-pf11"
export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:13317/becommerce?serverTimezone=UTC&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false"
export SPRING_DATASOURCE_USERNAME=becommerce SPRING_DATASOURCE_PASSWORD=becommerce SPRING_DATA_REDIS_PORT=16379
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
APP=""
Q() { docker exec -i pf11-mysql mysql -N -B -uroot -proot becommerce -e "$1" 2>/dev/null; }
mysql_up() {  # $1=버퍼 풀
  docker rm -f pf11-mysql >/dev/null 2>&1
  docker run -d --name pf11-mysql -p 13317:3306 -v pf11-data:/var/lib/mysql \
    -e MYSQL_DATABASE=becommerce -e MYSQL_USER=becommerce -e MYSQL_PASSWORD=becommerce -e MYSQL_ROOT_PASSWORD=root \
    mysql:8.4 --skip-log-bin --innodb-buffer-pool-size="$1" --innodb-flush-method=O_DIRECT \
    --innodb-buffer-pool-load-at-startup=OFF --innodb-buffer-pool-dump-at-shutdown=OFF >/dev/null
  for _ in $(seq 1 90); do docker exec pf11-mysql mysql -uroot -proot -e "select 1" >/dev/null 2>&1 && break; sleep 2; done
  sleep 3
  Q "SELECT @@innodb_buffer_pool_size, @@innodb_flush_method, @@innodb_buffer_pool_load_at_startup"
}
app_up() { APP_RATELIMIT_ENABLED=false "$JAVA" -jar commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar --server.port=18099 > "$1" 2>&1 & APP=$!
  for _ in $(seq 1 120); do curl -sf localhost:18099/actuator/health >/dev/null && return 0; sleep 1; done; return 1; }
app_down() { [ -n "$APP" ] && kill "$APP" 2>/dev/null; wait "$APP" 2>/dev/null; APP=""; }
trap 'app_down' EXIT
snap() { { ps -Ao pcpu,pmem,etime,comm | sort -k1 -nr | head -12 | awk '{n=split($4,a,"/"); print $1,$2,$3,a[n]}'; top -l 1 -n 0 | grep -E "Load Avg|CPU usage"; } > "$1"; }
bp() { Q "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_buffer_pool_read_requests','Innodb_buffer_pool_reads','Innodb_data_reads')" | tr '\n' ' '; }

seed() {  # $1=계정 수
  echo "== 시드(계정 $1) $(date '+%T')"
  docker rm -f pf11-mysql >/dev/null 2>&1          # 볼륨을 쥔 컨테이너부터 지워야 볼륨이 지워진다(첫 1,000계정 시드가 여기서 틀렸다)
  if docker volume inspect pf11-data >/dev/null 2>&1; then docker volume rm pf11-data >/dev/null || { echo "볼륨을 못 지웠다"; exit 1; }; fi
  mysql_up 256M
  app_up "$SP/pf11/app-seed-$1.log"
  for i in $(seq 0 $(( $1 - 1 ))); do curl -sf -X POST localhost:18099/api/v1/members/signup -H 'Content-Type: application/json' \
    -d "{\"email\":\"k6-read-$i@load.test\",\"password\":\"k6-load-only-1234\"}" >/dev/null 2>&1 || true; done
  ids=$(Q "SELECT id FROM members WHERE email REGEXP '^k6-read-[0-9]+@load.test$' ORDER BY id" | tr '\n' ',' | sed 's/,$//')
  n_acc=$(echo "$ids" | tr ',' '\n' | grep -c .)
  Q "SET SESSION cte_max_recursion_depth = 100000000;
    CREATE TEMPORARY TABLE k6_users (seq INT PRIMARY KEY, user_id BIGINT);
    INSERT INTO k6_users (seq, user_id) WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x < 10000)
      SELECT x, IF(${SPREAD:-0} = 1,
                   IF(x % 10 = 0 AND x / 10 <= $n_acc, ELT(x / 10, $ids), 1000000 + x),
                   IF(x <= $n_acc, ELT((x - 1) % $n_acc + 1, $ids), 1000000 + x)) FROM n;
    CREATE TEMPORARY TABLE k6_rounds (r INT PRIMARY KEY);
    INSERT INTO k6_rounds (r) WITH RECURSIVE m(y) AS (SELECT 1 UNION ALL SELECT y+1 FROM m WHERE y < 30) SELECT y FROM m;
    INSERT INTO orders (user_id, order_no, status, total_amount, currency, version, created_at, updated_at, expires_at)
      SELECT u.user_id, CONCAT('K6READ-', r.r, '-', u.seq), 'PAID', 10000, 'KRW', 0, NOW(6), NOW(6), NOW(6)
      FROM k6_rounds r CROSS JOIN k6_users u ORDER BY r.r, u.seq;
    ANALYZE TABLE orders;" >/dev/null
  echo "흩음 ${SPREAD:-0} 계정 $n_acc 주문 $(Q 'SELECT COUNT(*) FROM orders') 시드 계정 주문 $(Q "SELECT COUNT(*) FROM orders o JOIN members m ON m.id=o.user_id WHERE m.email REGEXP '^k6-read-[0-9]+@load.test$'") 크기MB $(Q "SELECT ROUND(SUM(data_length+index_length)/1024/1024,1) FROM information_schema.tables WHERE table_schema='becommerce' AND table_name='orders'")" | tee "$SP/pf11/data-size-$1-spread${SPREAD:-0}.txt"
  app_down
}

run_cond() {  # $1=이름 $2=풀 $3=데우기(1/0) $4=단계 $5=단계 초 $6=계정 수
  echo "== $1 $(date '+%T')"
  local d="$SP/pf11/$1"; mkdir -p "$d"
  mysql_up "$2" | tee "$d/mysql-vars.txt"
  app_up "$d/app.log"
  if [ "$3" = 1 ]; then k6 run --quiet -e BASE_URL=http://localhost:18099 -e ACCOUNTS="$6" -e STEPS=400 -e STEP_SECONDS=30 k6/read-capacity.js > "$d/k6-warmup.txt" 2>&1; fi
  echo "before $(bp)" > "$d/bp-status.txt"; snap "$d/ps-before.txt"
  k6 run --summary-export "$d/summary.json" -e BASE_URL=http://localhost:18099 -e ACCOUNTS="$6" -e STEPS="$4" -e STEP_SECONDS="$5" k6/read-capacity.js > "$d/k6.txt" 2>&1
  echo "after $(bp)" >> "$d/bp-status.txt"
  app_down
}
if [ -z "${ONLY_1000:-}" ]; then
seed 40
run_cond W40 256M 1 "400,1000,1800" 60 40
run_cond S40 8M 1 "400,1000,1800" 60 40
run_cond C40 256M 0 "1000,1001,1002,1003,1004,1005" 10 40
fi
if [ -z "${ONLY_SPREAD:-}" ]; then
seed 1000
run_cond W1000 256M 1 "400,1000,1800" 60 1000
run_cond S1000 8M 1 "400,1000,1800" 60 1000
fi
# 실제 계정을 10번째 사용자마다 흩어 작업 집합을 풀보다 크게(#352 세 번째 댓글)
SPREAD=1 seed 1000
run_cond W1000${TAG:-s} 256M 1 "400,1000,1800" 60 1000
run_cond S1000${TAG:-s} 8M 1 "400,1000,1800" 60 1000
docker rm -f pf11-mysql >/dev/null 2>&1; docker volume rm pf11-data >/dev/null 2>&1
echo "== 끝 $(date '+%T')"
