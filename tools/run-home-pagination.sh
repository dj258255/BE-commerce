#!/usr/bin/env bash
# 홈 페이지네이션 실측(#237, ADR-052). 전달 방식과 반영 대기를 바꿔 세 조건을 잰다.
set -euo pipefail
cd "$(dirname "$0")/.."
USERS=${USERS:-30}
PORT=${PORT:-18080}
BASE="http://localhost:${PORT}"
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
OUT=${OUT:-docs/performance/runs/$(date +%Y%m%d)-home-pagination}
mkdir -p "$OUT"
APP=""
cleanup() { [ -n "$APP" ] && kill "$APP" 2>/dev/null && wait "$APP" 2>/dev/null || true; APP=""; }
trap cleanup EXIT

run() {   # $1 이름, $2 전달 방식, $3 반영 대기 ms
  APP_RATELIMIT_ENABLED=false APP_PERSONALIZATION_TRANSPORT="$2" \
    "$JAVA" -jar "$JAR" --spring.docker.compose.enabled=false --server.port="$PORT" > "$OUT/app-$1.log" 2>&1 &
  APP=$!
  for _ in $(seq 1 120); do curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
  sleep 3
  echo "== $1 (transport=$2, wait=${3}ms)"
  python3 tools/home_pagination_eval.py "$BASE" "$USERS" "$3" "$OUT/$1.json"
  cleanup
}

# IN_PROCESS 가 나가는 기본값이다. KAFKA 는 kafka 프로파일 없이는 발행도 소비도 없어 재는 의미가 없다
run in-process-wait0 IN_PROCESS 0
run in-process-wait50 IN_PROCESS 50
run in-request IN_REQUEST 0
