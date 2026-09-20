#!/usr/bin/env bash
# E1-c — 다중 인스턴스가 Kafka를 강제하는가.
#
# 흔한 답("서버가 여러 대면 Kafka가 필요하다")을 재본다. **A 인스턴스로 활동을 발행하고
# B 인스턴스로 컨텍스트를 읽어** 반영됐는지 본다. 컨텍스트가 공유 저장소(Redis)에 있고 적용이
# seq 기준 멱등이면, 어느 인스턴스가 적용하든 결과는 같아야 한다.
#
# 전달 방식마다 두 대를 띄운다. KAFKA 는 두 인스턴스가 **같은 컨슈머 그룹**을 쓴다(실제 다중 인스턴스
# 배포의 모습) — 파티션이 갈리고, 어느 쪽이 소비하든 컨텍스트는 하나다.
#
# 전제: docker compose up -d mysql redis kafka · ./gradlew bootJar
# 사용: bash tools/check-multi-instance.sh
set -euo pipefail

cd "$(dirname "$0")/.."

A_PORT=18080
B_PORT=18081
A="http://localhost:${A_PORT}"
B="http://localhost:${B_PORT}"
JAR=build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
STAMP=$(date +%Y%m%d-%H%M%S)

[ -f "$JAR" ] || { echo "$JAR 가 없다 — ./gradlew bootJar 를 먼저 돌려라"; exit 1; }

PIDS=()
cleanup() { for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

wait_port_free() {
  for _ in $(seq 1 40); do
    lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1 || return 0
    sleep 1
  done
  lsof -nP -tiTCP:"$1" -sTCP:LISTEN | xargs -r kill -9 2>/dev/null || true
  sleep 2
}

start_instance() {
  local port=$1 transport=$2 group=$3 log=$4
  wait_port_free "$port"
  APP_RATELIMIT_ENABLED=false \
  APP_PERSONALIZATION_TRANSPORT="$transport" \
  APP_PERSONALIZATION_CONSUMER_GROUP="$group" \
  "$JAVA" -jar "$JAR" \
    --spring.profiles.active=kafka \
    --spring.docker.compose.enabled=false \
    --spring.kafka.consumer.auto-offset-reset=latest \
    --server.port="$port" > "$log" 2>&1 &
  PIDS+=($!)
  for _ in $(seq 1 120); do
    curl -sf "http://localhost:${port}/actuator/health" >/dev/null 2>&1 && { sleep 6; return 0; }
    sleep 1
  done
  echo "  인스턴스 $port 가 안 떴다 — $log"; return 1
}

login() {
  curl -s -X POST "$1/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"1","password":"user-local-only"}' \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])'
}

# 계정을 매번 새로 만든다 — 계정을 재사용하면 seq 가 1로 돌아가 유니크 제약에 부딪힌다.
# **같은 사용자로 두 인스턴스에서 각각 로그인해야** 한다: 검사 대상은 "A가 발행한 그 사용자의
# 컨텍스트를 B가 보는가"다. 서로 다른 계정을 쓰면 각자의 빈 컨텍스트를 볼 뿐이다(처음에 그렇게 짰다가
# 셋 다 EMPTY 가 나왔다 — 검사가 틀렸지 시스템이 틀린 게 아니었다).
signup_account() {
  local port=$1 email="mi-${STAMP}-$RANDOM@load.test"
  curl -s -o /dev/null -X POST "$port/api/v1/members/signup" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"k6-load-only-1234\"}" || true
  echo "$email"
}

# 로그인 본문의 식별자 필드는 username 이다(가입만 email 을 받는다).
login_on() {
  local port=$1 email=$2
  curl -s -X POST "$port/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$email\",\"password\":\"k6-load-only-1234\"}" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])'
}

echo "== E1-c 다중 인스턴스 — A(${A_PORT})로 발행 · B(${B_PORT})로 조회"
printf '%-12s %-10s %-10s %s\n' "전달 방식" "발행(A)" "조회(B)" "판정"

for transport in IN_PROCESS IN_REQUEST KAFKA; do
  GROUP="pf-mi-${STAMP}-${transport}"
  start_instance "$A_PORT" "$transport" "$GROUP" "/tmp/mi-a-${transport}.log"
  start_instance "$B_PORT" "$transport" "$GROUP" "/tmp/mi-b-${transport}.log"

  EMAIL=$(signup_account "$A")
  TOKEN_A=$(login_on "$A" "$EMAIL")
  TOKEN_B=$(login_on "$B" "$EMAIL")   # 같은 사용자, 다른 인스턴스

  WRITE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$A/api/v1/personalization/activity" \
    -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
    -d '{"itemId":7,"type":"CLICK","seq":1}')

  # B 로 읽는다. 200ms 까지 기다려 준다(전달 방식이 아니라 "다중 인스턴스가 되는가"를 본다).
  RESP=$(curl -s "$B/api/v1/personalization/context?expectSeq=1&waitMs=200" -H "Authorization: Bearer $TOKEN_B")
  REFLECTED=$(echo "$RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["reflected"])' 2>/dev/null || echo "?")
  SOURCE=$(echo "$RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["source"])' 2>/dev/null || echo "?")

  VERDICT=$([ "$REFLECTED" = "True" ] && echo "OK — A의 이벤트가 B에 반영" || echo "FAIL — B가 못 봄")
  printf '%-12s %-10s %-10s %s (source=%s)\n' "$transport" "$WRITE" "$REFLECTED" "$VERDICT" "$SOURCE"

  cleanup
  PIDS=()
  sleep 2
done

echo
echo "== 해석: 셋 다 통과하면 '다중 인스턴스'는 Kafka를 강제하는 근거가 되지 못한다 —"
echo "   컨텍스트가 공유 저장소에 있고 적용이 seq 기준 멱등이라 어느 인스턴스가 적용하든 같다."
