#!/usr/bin/env bash
# E1 실측 드라이버 — 신선도 vs 지연, 그리고 "브로커가 필요한가".
#
# 같은 활동 이벤트를 세 가지 전달 방식(KAFKA / IN_PROCESS / IN_REQUEST)으로 컨텍스트에 반영하고,
# 대기 정책(waitMs)을 바꿔가며 최신 반영률과 지연을 잰다.
#
# 사용:
#   bash tools/run-freshness-vs-latency.sh                    # 기본 조합
#   WAIT_LIST="0 100" DURATION=30s bash tools/run-freshness-vs-latency.sh   # 좁혀서 빠르게
#   TRANSPORTS="IN_PROCESS" DELAY_LIST="0 300" bash ...       # 한 축만
#
# 전제: docker compose up -d (mysql·redis·kafka) · ./gradlew bootJar
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
JAR=build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"

command -v k6 >/dev/null || { echo "k6 가 없다"; exit 1; }
[ -f "$JAR" ] || { echo "$JAR 가 없다 — ./gradlew bootJar 를 먼저 돌려라"; exit 1; }
docker exec pay-kafka-1 true 2>/dev/null || { echo "kafka 컨테이너(pay-kafka-1)가 없다"; exit 1; }

APP=""
cleanup() { [ -n "$APP" ] && kill "$APP" 2>/dev/null || true; APP=""; }
trap cleanup EXIT

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

run_one() {
  local transport=$1 delay=$2 wait=$3
  local dir="$RAW/${transport}-d${delay}-w${wait}"
  mkdir -p "$dir"
  LOG="$dir/app.log"
  # 런마다 고유 그룹 + latest — 이전 런의 백로그를 물려받지 않는다.
  local group="pf-e1-${STAMP}-${transport}-d${delay}-w${wait}"

  echo "-- ${transport} · delay=${delay}ms · waitMs=${wait}"
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

  cleanup
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
