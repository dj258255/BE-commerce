#!/usr/bin/env bash
# E2 실측 드라이버 — 같은 로그의 offline 재계산과 online 서빙을 대조한다.
#
# E2는 부하 실험이 아니다. **원인 하나씩을 통제된 조건으로 재현**하고, 그 조건에서
# 컨텍스트가 얼마나 갈라지는지 본다. 그래서 조건마다 앱을 필요한 설정으로 띄우고,
# 주입 → (필요하면 대기) → 대조를 한 번씩 돈다.
#
# 조건(#125의 원인 목록과 대응):
#   baseline    순서 정상 · 지연 0 · TTL 충분 · 20건 이하   → 기대: 일치
#   disorder    인접 쌍을 뒤집어 주입                        → 기대: dropped(순서 역전)
#   late        컨슈머 지연 300ms + 즉시 대조                → 기대: behind(late event)
#   window      사용자당 max-items 초과(30건)                → 기대: truncated(창 집계)
#   ttl         TTL 2초 + 5초 대기                           → 기대: expired
#   duplicate   같은 이벤트를 두 번 보냄                     → 기대: 일치(409가 막는다)
#   redelivery  Kafka 토픽을 되감아 재소비시킴               → 기대: 일치(seq 게이트가 막는다)
#
# 전제: docker compose up -d mysql redis kafka · ./gradlew bootJar
# 사용: bash tools/run-consistency-experiment.sh
set -euo pipefail

cd "$(dirname "$0")/.."

STAMP=$(date +%Y%m%d-%H%M%S)
OUT=${OUT_DIR:-"personalization/docs/runs/${STAMP}-e2-online-offline-일치율"}
RAW="$OUT/raw"
mkdir -p "$RAW"

USERS=${USERS:-20}
EVENTS=${EVENTS:-12}
LONG_USERS=${LONG_USERS:-5}
LONG_EVENTS=${LONG_EVENTS:-30}
PORT=${PORT:-18080}
BASE="http://localhost:${PORT}"
JAR=build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
TOPIC=user.activity

[ -f "$JAR" ] || { echo "$JAR 가 없다 — ./gradlew bootJar 를 먼저 돌려라"; exit 1; }
command -v redis-cli >/dev/null || { echo "redis-cli 가 없다"; exit 1; }

APP=""
cleanup() { [ -n "$APP" ] && kill "$APP" 2>/dev/null || true; APP=""; }
trap cleanup EXIT

wait_port_free() {
  for _ in $(seq 1 40); do
    lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1 || return 0
    sleep 1
  done
  lsof -nP -tiTCP:"$PORT" -sTCP:LISTEN | xargs -r kill -9 2>/dev/null || true
  sleep 2
}

start_app() {
  local transport=$1 delay=$2 ttl=$3 group=$4 log=$5
  cleanup
  wait_port_free
  APP_RATELIMIT_ENABLED=false \
  APP_PERSONALIZATION_TRANSPORT="$transport" \
  APP_PERSONALIZATION_CONSUMER_DELAY_MS="$delay" \
  APP_PERSONALIZATION_CONTEXT_TTL="$ttl" \
  APP_PERSONALIZATION_CONSUMER_GROUP="$group" \
  "$JAVA" -jar "$JAR" \
    --spring.profiles.active=kafka \
    --spring.docker.compose.enabled=false \
    --spring.kafka.consumer.auto-offset-reset=latest \
    --server.port="$PORT" > "$log" 2>&1 &
  APP=$!
  for _ in $(seq 1 120); do
    curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && { sleep 5; return 0; }
    kill -0 "$APP" 2>/dev/null || { echo "앱이 죽었다 — $log"; return 1; }
    sleep 1
  done
  echo "앱이 안 떴다 — $log"; return 1
}

# 주입 → (대기) → 대조. 조건마다 다른 것은 인자뿐이다.
run_condition() {
  local name=$1 transport=$2 delay=$3 ttl=$4 settle=$5 inject_extra=$6
  local dir="$RAW/$name"
  mkdir -p "$dir"
  echo "-- $name (transport=$transport delay=${delay}ms ttl=$ttl settle=${settle}s)"
  start_app "$transport" "$delay" "$ttl" "e2-${STAMP}-${name}" "$dir/app.log" || return 1

  # shellcheck disable=SC2086
  python3 tools/inject-activities.py --base "$BASE" \
    --users "$USERS" --events "$EVENTS" --long-users "$LONG_USERS" --long-events "$LONG_EVENTS" \
    --out "$dir/manifest.json" $inject_extra > "$dir/inject.txt" 2>&1 || true
  tail -2 "$dir/inject.txt" || true

  [ "$settle" -gt 0 ] && sleep "$settle"

  python3 tools/compare-contexts.py --manifest "$dir/manifest.json" \
    --condition "$name @$transport" --out "$dir/compare.json" | tee "$dir/compare.txt"
  cleanup
}

echo "== E2 실측 시작 (사용자 ${USERS}명 · 보통 ${EVENTS}건 · 긴 사용자 ${LONG_USERS}명×${LONG_EVENTS}건)"
echo "== 출력: $OUT"

# 조건을 골라 돌릴 수 있게 한다 — 첫 배치에서 드러난 "원인 없는 dropped" 를 분리 확인할 때 쓴다.
CONDITIONS=${CONDITIONS:-"baseline disorder late window ttl duplicate order-in-process order-kafka redelivery"}
want() { case " $CONDITIONS " in *" $1 "*) return 0;; *) return 1;; esac; }

# 조건마다 **다른 원인만** 켠다 — 기준 조건에서 긴 사용자를 빼야 잘림이 섞이지 않는다.
want baseline  && run_condition baseline  IN_PROCESS 0   7d 0 "--disorder-ratio 0 --long-users 0"
want disorder  && run_condition disorder  IN_PROCESS 0   7d 0 "--disorder-ratio 0.35 --long-users 0"
want late      && run_condition late      IN_PROCESS 300 7d 0 "--disorder-ratio 0 --long-users 0"
want window    && run_condition window    IN_PROCESS 0   7d 0 "--disorder-ratio 0 --long-users 8 --long-events 30"
want ttl       && run_condition ttl       IN_PROCESS 0   2s 5 "--disorder-ratio 0 --long-users 0"
want duplicate && run_condition duplicate IN_PROCESS 0   7d 0 "--disorder-ratio 0 --long-users 0 --duplicate-ratio 0.4"

# 순서 보존 분리 확인 — 순서를 **주입하지 않고** 컨슈머만 느리게 만들어 dropped 가 나오는지 본다.
# IN_PROCESS 는 @Async 풀이라 사용자별 직렬성이 없다. KAFKA 는 userId 파티션 키로 같은 사용자의
# 이벤트가 같은 파티션 → 같은 스레드에 들어간다. 그 차이가 드러나는지가 이 두 조건의 질문이다.
ORDER_USERS=${ORDER_USERS:-6}
ORDER_EVENTS=${ORDER_EVENTS:-40}
# 컨슈머가 **따라잡을 시간**을 준다. 안 주면 "못 따라옴(behind/absent)"이 섞여
# 순서·경합으로 인한 dropped 를 분리할 수 없다(첫 시도에서 3초로 두었다가 그렇게 됐다).
ORDER_SETTLE=${ORDER_SETTLE:-25}
want order-in-process && run_condition order-in-process IN_PROCESS 60 7d "$ORDER_SETTLE" \
  "--disorder-ratio 0 --users $ORDER_USERS --events $ORDER_EVENTS --long-users 0"
want order-kafka      && run_condition order-kafka      KAFKA     60 7d "$ORDER_SETTLE" \
  "--disorder-ratio 0 --users $ORDER_USERS --events $ORDER_EVENTS --long-users 0"

if want redelivery; then
echo "-- redelivery (Kafka 오프셋 되감기)"
dir="$RAW/redelivery"; mkdir -p "$dir"
group="e2-${STAMP}-redelivery"
start_app KAFKA 0 7d "$group" "$dir/app.log" || true
python3 tools/inject-activities.py --base "$BASE" --users "$USERS" --events "$EVENTS" \
  --long-users 0 --long-events 0 --out "$dir/manifest.json" > "$dir/inject.txt" 2>&1 || true
sleep 5
python3 tools/compare-contexts.py --manifest "$dir/manifest.json" --condition "redelivery-before" \
  --out "$dir/compare-before.json" | tee "$dir/compare-before.txt"
cleanup
wait_port_free
docker exec pay-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group "$group" --reset-offsets --to-earliest --topic "$TOPIC" --execute > "$dir/rewind.txt" 2>&1 || true
start_app KAFKA 0 7d "$group" "$dir/app.log" || true
sleep 10
python3 tools/compare-contexts.py --manifest "$dir/manifest.json" --condition "redelivery-after" \
  --out "$dir/compare.json" | tee "$dir/compare.txt"
cleanup
fi

echo
echo "== 조건별 요약"
python3 tools/consistency_report.py "$RAW" | tee "$OUT/summary-table.md"
echo "== 원자료: $RAW"
