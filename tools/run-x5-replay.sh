#!/usr/bin/env bash
# X5(#328) — 문맥 저장소의 행동이 v2 모델 입력까지 가는가: E2 의 교란 조건 × 전송 × 세션 모양(OFF · RICH).
#
# E2(run-consistency-experiment.sh)가 저장소와 로그를 대조했다면 이 러너는 **로그와 모델이 받은 프롬프트**를
# 대조한다. 조건마다 앱을 다시 띄우고(설정이 기동 시점에 정해진다), 주입 → (대기) → 홈 1쪽 · 2쪽 → 대조를 한 번씩 돈다.
# 모델 서버는 한 번만 띄우고 프롬프트 로그 하나를 같이 쓴다 — 대조 도구가 사용자별 호출 구간으로 줄을 가른다.
#
# 전제: docker compose up -d mysql redis kafka · ./gradlew -p commerce bootJar
# 사용: PY=/path/to/genpage-venv/bin/python bash tools/run-x5-replay.sh
set -euo pipefail
cd "$(dirname "$0")/.."

STAMP=$(date +%Y%m%d-%H%M%S)
OUT=${OUT_DIR:-"personalization/docs/runs/${STAMP}-v2-x5-replay"}
RAW="$OUT/raw"
mkdir -p "$RAW"

USERS=${USERS:-20}
EVENTS=${EVENTS:-12}
PORT=${PORT:-18090}
BASE="http://localhost:${PORT}"
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
PY=${PY:?개인화 가상환경의 파이썬을 PY 로 준다(pandas · torch)}
DATA=${GENPAGE_DATA:-personalization/data}
MODE=${MODE:-final}
CKPT=${CKPT:-$DATA/hm/model/genpage2/$MODE/ckpt/f-base-full-1ep}
MODEL_PORT=${MODEL_PORT:-8766}
MODEL_URL="http://localhost:${MODEL_PORT}"
PROMPTS="$OUT/prompts.jsonl"
SESSIONS=${SESSIONS:-"OFF RICH"}

[ -f "$JAR" ] || { echo "$JAR 가 없다 — ./gradlew -p commerce bootJar 를 먼저 돌려라"; exit 1; }
[ -d "$CKPT" ] || { echo "체크포인트가 없다: $CKPT"; exit 1; }

APP=""; MODEL=""
stop_app() { [ -n "$APP" ] && kill "$APP" 2>/dev/null || true; APP=""; }
cleanup() { stop_app; [ -n "$MODEL" ] && kill "$MODEL" 2>/dev/null || true; MODEL=""; }
trap cleanup EXIT

wait_port_free() {
  for _ in $(seq 1 40); do
    lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1 || return 0
    sleep 1
  done
  # 남의 프로세스일 수 있다 — 죽이지 않고 멈춘다(E2 러너는 kill -9 했다)
  echo "포트 $PORT 가 비지 않는다 — PORT 를 바꿔라"; return 1
}

echo "== 모델 서버: $CKPT ($MODE) → $MODEL_URL · 프롬프트 로그 $PROMPTS"
GENPAGE_DATA="$DATA" GENPAGE2_PROMPT_LOG="$PROMPTS" \
  "$PY" personalization/serving/genpage2_server.py --ckpt "$CKPT" --mode "$MODE" --port "$MODEL_PORT" \
  > "$OUT/model.log" 2>&1 &
MODEL=$!
for _ in $(seq 1 300); do
  curl -sf "$MODEL_URL/health" >/dev/null 2>&1 && break
  kill -0 "$MODEL" 2>/dev/null || { echo "모델 서버가 죽었다 — $OUT/model.log"; exit 1; }
  sleep 1
done
curl -sf "$MODEL_URL/health" >/dev/null || { echo "모델 서버가 안 떴다"; exit 1; }

start_app() {
  local transport=$1 delay=$2 ttl=$3 group=$4 session=$5 log=$6
  stop_app
  wait_port_free
  # 모델 타임아웃을 넉넉히 둔다 — 이 실험은 입력이 가는지를 재지 지연 예산을 재지 않는다(X4 가 쟀다).
  # 타임아웃이 나도 프롬프트 줄은 생성 전에 쓰이므로 대조는 된다.
  APP_RATELIMIT_ENABLED=false \
  APP_PERSONALIZATION_TRANSPORT="$transport" \
  APP_PERSONALIZATION_CONSUMER_DELAY_MS="$delay" \
  APP_PERSONALIZATION_CONTEXT_TTL="$ttl" \
  APP_PERSONALIZATION_CONSUMER_GROUP="$group" \
  APP_RECOMMENDATION_MODEL_KIND=genpage \
  APP_RECOMMENDATION_MODEL_GENPAGE_URL="$MODEL_URL" \
  APP_RECOMMENDATION_MODEL_GENPAGE_TIMEOUT=5s \
  APP_RECOMMENDATION_GENPAGE_SESSION="$session" \
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

run_condition() {
  local name=$1 transport=$2 delay=$3 ttl=$4 settle=$5 session=$6 inject_extra=$7
  local dir="$RAW/$session/$name"
  mkdir -p "$dir"
  echo "-- $session · $name (transport=$transport delay=${delay}ms ttl=$ttl settle=${settle}s)"
  start_app "$transport" "$delay" "$ttl" "x5-${STAMP}-${session}-${name}" "$session" "$dir/app.log" || return 1
  # shellcheck disable=SC2086
  python3 tools/inject-activities.py --base "$BASE" --users "$USERS" --events "$EVENTS" \
    --out "$dir/manifest.json" $inject_extra > "$dir/inject.txt" 2>&1 || true
  tail -1 "$dir/inject.txt" || true
  [ "$settle" -gt 0 ] && sleep "$settle"
  "$PY" tools/x5_prompt_replay.py --manifest "$dir/manifest.json" --prompt-log "$PROMPTS" \
    --mode-dir "$DATA/hm/model/genpage2/$MODE" --session "$session" --condition "$name @$transport" \
    --out "$dir/replay.json" | tee "$dir/replay.txt"
  stop_app
}

CONDITIONS=${CONDITIONS:-"baseline disorder late window ttl duplicate baseline-kafka late-kafka"}
want() { case " $CONDITIONS " in *" $1 "*) return 0;; *) return 1;; esac; }

echo "== X5 시작 (사용자 ${USERS}명 · ${EVENTS}건) → $OUT"
for s in $SESSIONS; do
  # E2 와 같은 조건 · 같은 손잡이. 조건마다 다른 원인만 켠다.
  want baseline       && run_condition baseline       IN_PROCESS 0   7d 0 "$s" "--disorder-ratio 0 --long-users 0"
  want disorder       && run_condition disorder       IN_PROCESS 0   7d 0 "$s" "--disorder-ratio 0.35 --long-users 0"
  want late           && run_condition late           IN_PROCESS 300 7d 0 "$s" "--disorder-ratio 0 --long-users 0"
  want window         && run_condition window         IN_PROCESS 0   7d 0 "$s" "--disorder-ratio 0 --long-users 8 --long-events 30"
  want ttl            && run_condition ttl            IN_PROCESS 0   2s 5 "$s" "--disorder-ratio 0 --long-users 0"
  want duplicate      && run_condition duplicate      IN_PROCESS 0   7d 0 "$s" "--disorder-ratio 0 --long-users 0 --duplicate-ratio 0.4"
  # Kafka 는 소비가 비동기라 따라잡을 시간(3초)을 준다 — 주지 않으면 전부 late 가 된다
  want baseline-kafka && run_condition baseline-kafka KAFKA      0   7d 3 "$s" "--disorder-ratio 0 --long-users 0"
  want late-kafka     && run_condition late-kafka     KAFKA      300 7d 0 "$s" "--disorder-ratio 0 --long-users 0"
done

echo
echo "== 요약"
cat "$RAW"/*/*/replay.txt | tee "$OUT/summary.txt"
