#!/usr/bin/env bash
# API 롤링 배포 중 결제가 끊기는지(#338, ADR-029 의 "API 롤링 자체는 재지 않았다").
#
#   bash tools/run-rolling-deploy.sh single    # LB 없이 한 대를 재시작(ADR-029 실험 3 의 기준선)
#   bash tools/run-rolling-deploy.sh rolling   # nginx 뒤 두 대를 차례로 재시작
#   bash tools/run-rolling-deploy.sh drain     # 재시작 전에 그 리플리카를 nginx 에서 빼고(reload) 뜬 뒤 다시 넣는다
#
# 체크아웃 30VU 를 3분 깔고 60초 지점에서 재시작을 시작한다(k6/redeploy-blast-radius.js). 전제: mysql · redis · docker.
# 끝나면 [FAIL] 로그 수, 중복 결제(같은 주문에 결제 둘 이상), 단계별 시각을 남긴다.
set -euo pipefail

MODE=${1:?single · rolling · drain}
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
A_PORT=${A_PORT:-18101}; B_PORT=${B_PORT:-18102}; LB_PORT=${LB_PORT:-18100}
MYSQL=${MYSQL:-"docker exec pay-mysql-1 mysql -N -B -ubecommerce -pbecommerce becommerce"}
OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d-%H%M%S)-rolling-$MODE}
VUS=${VUS:-30}; DURATION=${DURATION:-3m}; AT=${AT:-60}
# nginx 가 host.docker.internal 을 IPv4 · IPv6 둘로 풀어 리플리카마다 upstream 이 둘이 된다. IPv6 쪽은 닿지 않아
# IPv4 가 잠깐 실패로 표시되면 502 가 난다(#338 첫 측정을 버린 이유). IPv4 주소 하나로 고정한다
LB_HOST=${LB_HOST:-$(docker run --rm nginx:1.27-alpine getent ahostsv4 host.docker.internal | awk 'NR==1{print $1}')}
mkdir -p "$OUT"
EVENTS="$OUT/events.log"; : > "$EVENTS"
now() { perl -MTime::HiRes=time -e 'printf "%.2f", time'; }
ev() { echo "$(now) $*" | tee -a "$EVENTS"; }

# macOS 기본 bash(3.2)에는 연관 배열이 없어 포트마다 변수를 따로 둔다
PID_A=""; PID_B=""
start_app() {  # $1=포트
  "$JAVA" -Xmx768m -jar "$JAR" --server.port="$1" --app.ratelimit.enabled=false > "$OUT/app-$1-$(date +%s).log" 2>&1 &
  if [ "$1" = "$A_PORT" ]; then PID_A=$!; else PID_B=$!; fi
}
pid_of() { if [ "$1" = "$A_PORT" ]; then echo "$PID_A"; else echo "$PID_B"; fi; }
wait_up() { for _ in $(seq 1 120); do curl -sf "http://localhost:$1/actuator/health" >/dev/null 2>&1 && return 0; sleep 0.5; done; return 1; }
lb_conf() {  # $1=A 상태 $2=B 상태 (비움 또는 down)
  sed -e "s#__A__#$LB_HOST:$A_PORT $1#" -e "s#__B__#$LB_HOST:$B_PORT $2#" tools/rolling/nginx.conf.tmpl > "$OUT/nginx.conf"
}
lb_reload() { lb_conf "$1" "$2"; docker cp "$OUT/nginx.conf" rolling-lb:/etc/nginx/nginx.conf >/dev/null; docker exec rolling-lb nginx -s reload; }
cleanup() {
  for p in $PID_A $PID_B; do kill "$p" 2>/dev/null || true; done
  docker logs rolling-lb > "$OUT/nginx.log" 2>&1 || true   # 502 의 원인(no live upstreams · 연결 실패)을 가르려면 필요하다
  docker rm -f rolling-lb >/dev/null 2>&1 || true
}
trap cleanup EXIT

restart() {  # $1=포트
  local port=$1 pid
  pid=$(pid_of "$port")
  if [ "$MODE" = drain ]; then
    [ "$port" = "$A_PORT" ] && lb_reload down "" || lb_reload "" down
    ev "lb: $port 뺌"; sleep 2
  fi
  ev "SIGTERM $port"; kill -TERM "$pid"; wait "$pid" 2>/dev/null || true; ev "종료 $port"
  start_app "$port"; wait_up "$port"; ev "기동 완료 $port"
  if [ "$MODE" = drain ]; then lb_reload "" ""; ev "lb: $port 넣음"; fi
}

start_app "$A_PORT"
if [ "$MODE" != single ]; then
  start_app "$B_PORT"
  lb_conf "" ""
  docker rm -f rolling-lb >/dev/null 2>&1 || true
  docker create --name rolling-lb -p "$LB_PORT":80 nginx:1.27-alpine >/dev/null
  docker cp "$OUT/nginx.conf" rolling-lb:/etc/nginx/nginx.conf >/dev/null
  docker start rolling-lb >/dev/null
  BASE="http://localhost:$LB_PORT"
else
  BASE="http://localhost:$A_PORT"
fi
wait_up "$A_PORT"; [ "$MODE" != single ] && wait_up "$B_PORT"
for _ in $(seq 1 40); do curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && break; sleep 0.5; done
ev "준비 완료 base=$BASE lb_host=${LB_HOST:-}"
k6 run --quiet -e BASE_URL="$BASE" -e VUS=10 -e DURATION=15s k6/redeploy-blast-radius.js > "$OUT/k6-warmup.txt" 2>&1 || true

k6 run -e BASE_URL="$BASE" -e VUS="$VUS" -e DURATION="$DURATION" k6/redeploy-blast-radius.js > "$OUT/k6.txt" 2>&1 &
K6=$!
ev "k6 시작"; sleep "$AT"
restart "$A_PORT"
[ "$MODE" != single ] && restart "$B_PORT"
ev "재시작 끝"
wait "$K6" || true
ev "k6 끝"

{
  echo "fail_lines=$(grep -c '\[FAIL\]' "$OUT/k6.txt" || true)"
  echo "fail_first=$(grep -o '\[FAIL\] [^ ]*' "$OUT/k6.txt" | head -1 | cut -d' ' -f2)"
  echo "fail_last=$(grep -o '\[FAIL\] [^ ]*' "$OUT/k6.txt" | tail -1 | cut -d' ' -f2)"
  echo "fail_kinds=$(grep -o '\[FAIL\] [^ ]* [a-z]* status=[0-9]*' "$OUT/k6.txt" | awk '{print $3"_"$4}' | sort | uniq -c | tr -s ' ' | tr '\n' ';')"
  echo "duplicate_orders=$($MYSQL -e "SELECT COUNT(*) FROM (SELECT order_no FROM payments GROUP BY order_no HAVING COUNT(*) > 1) d" 2>/dev/null | tr -d '[:space:]')"
  echo "payments_done=$($MYSQL -e "SELECT COUNT(*) FROM payments WHERE status = 'DONE'" 2>/dev/null | tr -d '[:space:]')"
  grep -E "http_req_failed|iterations\.|http_reqs\." "$OUT/k6.txt" | tr -s ' '
} | tee "$OUT/result.txt"
