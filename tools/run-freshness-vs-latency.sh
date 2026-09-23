#!/usr/bin/env bash
# E1 실측 드라이버 — 신선도 vs 지연, 그리고 "브로커가 필요한가".
#
# 같은 활동 이벤트를 네 가지 전달 방식(KAFKA / IN_PROCESS / IN_REQUEST / CDC)으로 컨텍스트에 반영하고,
# 대기 정책(waitMs)을 바꿔가며 최신 반영률과 지연을 잰다.
#
# 사용:
#   bash tools/run-freshness-vs-latency.sh                    # 기본 조합
#   WAIT_LIST="0 100" DURATION=30s bash tools/run-freshness-vs-latency.sh   # 좁혀서 빠르게
#   TRANSPORTS="IN_PROCESS" DELAY_LIST="0 300" bash ...       # 한 축만
#
# 전제: docker compose up -d (mysql·redis·kafka) · ./gradlew -p commerce bootJar
#
# 설계 요점(첫 시도에서 실패한 것들 — 다시 밟지 않도록 적어 둔다):
#  ① 토픽을 **지우지 않는다.** 소비자가 구독 중인 토픽을 지우면 메타데이터가 깨져(WARN
#     UNKNOWN_TOPIC_OR_PARTITION) 이후 메시지를 제때 못 받는다. 대신 런마다 **새 컨슈머 그룹 +
#     auto-offset-reset=latest** 로 "지금부터"만 읽는다 — 이전 런의 백로그에 오염되지 않는다.
#  ② 재기동 때 **포트가 비워지길 기다린다.** 이전 프로세스가 죽는 중에도 포트는 잠깐 살아 있어
#     health 가 통과하고, 그 뒤 k6 가 connection refused 를 맞는다(첫 배치에서 3런이 이렇게 날아갔다).
#  ③ 앱은 모든 전달 방식에서 kafka 프로파일로 띄운다. "발행은 항상 일어난다"가 상수가 되고
#     변수는 "누가 적용하는가" 하나로 남는다.
#  ④ 런마다 앱을 재기동한다 — consumer 지연이 프로퍼티라 재기동이 필요하다.
#  ⑤ CDC 런은 커넥터가 떠 있어야 성립한다 — CDC 모드에서 앱은 발행하지 않으므로 커넥터가 없으면
#     아무것도 안 흘러 반영률이 0 이 된다. 그리고 **CDC 가 아닌 런에는 커넥터가 없어야 한다** —
#     남아 있으면 앱 발행과 CDC 가 같이 흘러 한 활동이 두 번 간다(#211).
set -euo pipefail

cd "$(dirname "$0")/.."

STAMP=$(date +%Y%m%d-%H%M%S)
# OUT_DIR 을 주면 그 디렉터리에 이어 쓴다 — 한 실험을 여러 배치로 나눌 때 표를 하나로 모으려고.
OUT=${OUT_DIR:-"personalization/docs/runs/${STAMP}-e1-신선도-지연"}
RAW="$OUT/raw"
mkdir -p "$RAW"

TRANSPORTS=${TRANSPORTS:-"KAFKA IN_PROCESS IN_REQUEST"}
WAIT_LIST=${WAIT_LIST:-"0 50 100 200 500"}
DELAY_LIST=${DELAY_LIST:-"0"}
DURATION=${DURATION:-60s}
WARMUP_MS=${WARMUP_MS:-15000}
RATE=${RATE:-20}
MAX_VUS=${MAX_VUS:-15}
ACCOUNTS=${ACCOUNTS:-15}
PORT=${PORT:-18080}
SETTLE_SECONDS=${SETTLE_SECONDS:-6}     # 컨슈머 그룹 조인을 기다린다(k6 setup 이 그 위에 15초를 더 쓴다)
TOPIC=user.activity
PARTITIONS=${PARTITIONS:-3}
# CDC 런용 — Connect REST 와 커넥터 설정 파일(cdc/register-user-activity-connector.json).
CONNECT_URL=${CONNECT_URL:-http://localhost:8083}
CONNECTOR_NAME=${CONNECTOR_NAME:-user-activity-cdc}
CONNECTOR_JSON=${CONNECTOR_JSON:-cdc/register-user-activity-connector.json}
CONNECT_WAIT_SECONDS=${CONNECT_WAIT_SECONDS:-120}
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"

command -v k6 >/dev/null || { echo "k6 가 없다"; exit 1; }
[ -f "$JAR" ] || { echo "$JAR 가 없다 — ./gradlew -p commerce bootJar 를 먼저 돌려라"; exit 1; }
docker exec pay-kafka-1 true 2>/dev/null || { echo "kafka 컨테이너(pay-kafka-1)가 없다"; exit 1; }

APP=""
cleanup() { [ -n "$APP" ] && kill "$APP" 2>/dev/null || true; APP=""; }
# EXIT 에서는 앱과 함께 커넥터도 내린다 — 중간에 끊겨도 커넥터가 남지 않게(⑤). 커넥터가 없으면 무해하다.
cleanup_all() { cleanup; unregister_connector; }
trap cleanup_all EXIT

echo "== E1 실측 시작"
echo "== 출력: $OUT"
echo "== 전달 방식: $TRANSPORTS / 대기 정책: $WAIT_LIST / 지연: $DELAY_LIST / ${DURATION} @ ${RATE}req/s"

# 토픽을 **한 번만** 만든다(지우지 않는다). 파티션 수는 고정 — 런마다 달라지면 비교가 안 된다.
ensure_topic() {
  if ! docker exec pay-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
      --list 2>/dev/null | grep -qx "$TOPIC"; then
    docker exec pay-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
      --create --topic "$TOPIC" --partitions "$PARTITIONS" --replication-factor 1 >/dev/null
  fi
}

# 컨텍스트 키를 비운다. 계정을 런마다 새로 만들지만, 키가 남아 있으면 "빈 컨텍스트" 가정이 깨진다.
#
# 주의(맥에서 실제로 겪은 함정): 로컬에 Redis 가 6379 를 잡고 있으면(예: Homebrew)
# localhost:6379 는 **컨테이너가 아니라 그쪽**으로 간다 — 바인딩이 더 구체적인 쪽이 이긴다.
# 앱도 같은 곳을 보므로 실험은 일관되지만, 어느 인스턴스인지는 리포트의 환경 절에 적어야 한다.
reset_context() {
  redis-cli -h 127.0.0.1 EVAL \
    "local ks=redis.call('keys','ctx:*') for i=1,#ks do redis.call('del',ks[i]) end return #ks" 0 \
    >/dev/null 2>&1 || echo "  (경고: redis-cli 로 컨텍스트를 못 비웠다 — 실행 중인 Redis 를 확인해라)"
}

context_keys() {
  redis-cli -h 127.0.0.1 EVAL "return #redis.call('keys','ctx:*')" 0 2>/dev/null || echo "?"
}

# 포트가 완전히 비워질 때까지 기다린다 — 이전 프로세스가 죽는 중에 health 가 통과하는 것을 막는다.
wait_port_free() {
  for _ in $(seq 1 40); do
    if ! lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "  (경고: 포트 $PORT 가 아직 잡혀 있다 — 남은 PID 를 죽인다)"
  lsof -nP -tiTCP:"$PORT" -sTCP:LISTEN | xargs -r kill -9 2>/dev/null || true
  sleep 2
}

start_app() {
  local transport=$1 delay=$2 group=$3
  cleanup
  wait_port_free
  APP_RATELIMIT_ENABLED=false \
  APP_PERSONALIZATION_TRANSPORT="$transport" \
  APP_PERSONALIZATION_CONSUMER_DELAY_MS="$delay" \
  APP_PERSONALIZATION_CONSUMER_GROUP="$group" \
  "$JAVA" -jar "$JAR" \
    --spring.profiles.active=kafka \
    --spring.docker.compose.enabled=false \
    --spring.kafka.consumer.auto-offset-reset=latest \
    --server.port="$PORT" \
    > "$LOG" 2>&1 &
  APP=$!
  for _ in $(seq 1 120); do
    if curl -sf "$BASE/actuator/health" >/dev/null 2>&1; then
      sleep "$SETTLE_SECONDS"
      # health 가 떠도 곧바로 죽는 경우가 있다 — 한 번 더 확인한다.
      curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && return 0
    fi
    if ! kill -0 "$APP" 2>/dev/null; then echo "앱이 죽었다 — $LOG"; return 1; fi
    sleep 1
  done
  echo "앱이 안 떴다 — $LOG"; return 1
}

# ── CDC 커넥터 헬퍼 ────────────────────────────────────────────────────────────
# CDC 런에서만 쓴다. Connect 가 없으면 커넥터를 못 올리므로 그 런은 건너뛴다(반영률 0 을 "측정값"으로
# 남기지 않기 위해서다).
connect_up() {
  # 이미 떠 있으면 그대로다(멱등).
  docker compose -p pay --profile cdc up -d debezium >/dev/null 2>&1 || return 1
  for _ in $(seq 1 "$CONNECT_WAIT_SECONDS"); do
    if curl -sf "$CONNECT_URL/" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  return 1
}

# 이미 있으면 409 — 실패로 보지 않고 그대로 쓴다.
register_connector() {
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
      --data @"$CONNECTOR_JSON" "$CONNECT_URL/connectors" 2>/dev/null || echo 000)
  case "$code" in
    200|201) echo "  CDC: 커넥터 등록 ($code)"; return 0 ;;
    409)     echo "  CDC: 커넥터가 이미 있다 ($code) — 그대로 쓴다"; return 0 ;;
    *)       echo "  CDC: 커넥터 등록 실패 (HTTP $code)"; return 1 ;;
  esac
}

# 커넥터 state 뿐 아니라 **모든 task** state 가 RUNNING 이어야 한다 — RUNNING 이어도 task 가 죽어 있을 수 있다.
wait_connector_running() {
  for _ in $(seq 1 "$CONNECT_WAIT_SECONDS"); do
    if curl -sf "$CONNECT_URL/connectors/$CONNECTOR_NAME/status" 2>/dev/null | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(1)
tasks = d.get("tasks") or []
ok = d.get("connector", {}).get("state") == "RUNNING" and tasks \
     and all(t.get("state") == "RUNNING" for t in tasks)
sys.exit(0 if ok else 1)
'; then
      return 0
    fi
    sleep 1
  done
  return 1
}

unregister_connector() {
  # 실패해도 런을 죽이지 않는다(Connect 가 없으면 무해하게 실패한다).
  curl -s -o /dev/null -X DELETE "$CONNECT_URL/connectors/$CONNECTOR_NAME" 2>/dev/null || true
}

# meta.txt 에 적을 커넥터 이미지 태그 — 어느 버전으로 쟀는지 나중에 알아야 한다.
connector_image() {
  local img
  img=$(docker inspect --format '{{.Config.Image}}' pay-debezium-1 2>/dev/null || true)
  if [ -z "$img" ]; then
    img=$(grep -m1 -E '^[[:space:]]*image: debezium/' compose.yaml 2>/dev/null | awk '{print $2}' || true)
  fi
  if [ -n "$img" ]; then echo "$img"; else echo unknown; fi
}

run_one() {
  local transport=$1 delay=$2 wait=$3
  local dir="$RAW/${transport}-d${delay}-w${wait}"
  mkdir -p "$dir"
  LOG="$dir/app.log"
  # 런마다 고유 그룹 + latest — 이전 런의 백로그를 물려받지 않는다.
  local group="pf-e1-${STAMP}-${transport}-d${delay}-w${wait}"

  echo "-- ${transport} · delay=${delay}ms · waitMs=${wait}"

  # CDC 런은 커넥터가 떠 있어야 성립한다(⑤) — 앱은 CDC 모드에서 발행하지 않으므로, 커넥터가 없으면
  # 아무것도 흐르지 않아 반영률이 0 이 된다. 커넥터를 못 올리면 그 런을 건너뛰고 이유를 남긴다.
  if [ "$transport" = "CDC" ]; then
    if ! connect_up; then
      echo "  CDC: Connect($CONNECT_URL)가 ${CONNECT_WAIT_SECONDS}s 안에 응답하지 않는다 — 이 런을 건너뛴다"
      { echo "skipped=connect_unreachable"; echo "connect_url=$CONNECT_URL"; } > "$dir/SKIPPED.txt"
      return 0
    fi
    if ! register_connector; then
      echo "  CDC: 커넥터를 등록하지 못했다 — 이 런을 건너뛴다"
      echo "skipped=connector_registration_failed" > "$dir/SKIPPED.txt"
      return 0
    fi
    if ! wait_connector_running; then
      echo "  CDC: 커넥터가 RUNNING 이 되지 않았다 — 이 런을 건너뛴다"
      echo "skipped=connector_not_running" > "$dir/SKIPPED.txt"
      unregister_connector
      return 0
    fi
    echo "  CDC: $CONNECTOR_NAME RUNNING (커넥터가 $TOPIC 로 흘린다)"
  else
    # CDC 가 아닌 런에는 커넥터가 없어야 한다(⑤) — 남아 있으면 앱 발행과 CDC 가 같이 흘러 두 번 간다.
    unregister_connector
  fi

  reset_context
  start_app "$transport" "$delay" "$group" || return 1

  WAIT_MS="$wait" BASE_URL="$BASE" DURATION="$DURATION" RATE="$RATE" \
  MAX_VUS="$MAX_VUS" ACCOUNTS="$ACCOUNTS" WARMUP_MS="$WARMUP_MS" \
  k6 run --summary-export "$dir/summary.json" \
    --summary-trend-stats='avg,min,med,max,p(90),p(95),p(99)' \
    k6/freshness-vs-latency.js > "$dir/k6.txt" 2>&1 || true

  tail -3 "$dir/k6.txt" | grep '^\[E1\]' || true

  cat > "$dir/meta.txt" <<EOF
transport=$transport
consumer_delay_ms=$delay
wait_ms=$wait
consumer_group=$group
duration=$DURATION
warmup_ms=$WARMUP_MS
rate=$RATE
max_vus=$MAX_VUS
accounts=$ACCOUNTS
context_keys_after=$(context_keys)
EOF

  # CDC 런이면 커넥터 이름과 이미지 태그를 남긴다 — 어느 버전으로 쟀는지 나중에 알아야 한다.
  if [ "$transport" = "CDC" ]; then
    cat >> "$dir/meta.txt" <<EOF
connector=$CONNECTOR_NAME
connector_image=$(connector_image)
EOF
  fi

  cleanup
  # 런이 끝나면 커넥터를 내린다 — 다음 런이 다른 전달 방식이면 남아 있으면 안 된다(⑤). 실패해도 런은 산다.
  if [ "$transport" = "CDC" ]; then
    unregister_connector
  fi
}

BASE="http://localhost:${PORT}"
ensure_topic

for transport in $TRANSPORTS; do
  for delay in $DELAY_LIST; do
    # IN_REQUEST 는 같은 트랜잭션에서 갱신하므로 "consumer 지연"이 없다 — 지연 축은 건너뛴다.
    if [ "$transport" = "IN_REQUEST" ] && [ "$delay" != "0" ]; then
      continue
    fi
    for wait in $WAIT_LIST; do
      run_one "$transport" "$delay" "$wait"
    done
  done
done

echo
echo "== 표 (요약)"
python3 tools/freshness_report.py "$RAW" | tee "$OUT/summary-table.md"
echo
echo "== 원자료: $RAW"
echo "== 리포트를 $OUT/report.md 에 쓴다 (8절 형식: personalization/docs/03-verification.md)"
