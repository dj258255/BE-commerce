#!/usr/bin/env bash
# S2(#455) 결합 방식을 앱 홈 2쪽에 기능 플래그로 붙였을 때의 검증 · 지연 측정 실행기.
# 설계 · 판정은 personalization/docs/genpage-v2/BACKEND.md "S2" 절(측정 전에 고정).
#
#   PY=/path/to/genpage-venv/bin/python bash tools/run-s2.sh
#
# 전제: compose 의 mysql(3306)·redis 가 떠 있고, personalization/data 에 H&M 데이터 · 저장된
# GenPage 모델(–ckpt)·점수(–scores), 그리고 products/stock 이 적재돼 있다. 모델 서버(18868) →
# 앱 bootJar · 기동(18090) → 계정 시드 → M1 → M2 → M3 → M4 순서로 돌려
# personalization/docs/runs/<날짜>-s2-app/ 에 남기고, 끝나면 앱 · 모델 서버를 끈다. 이미 있는
# 결과는 건너뛴다. M4 는 origin/main 의 별도 작업 트리를 만들어 앱 jar 를 두 번 띄운다.
#
# **앱 설정**: M2 · M3 · M4 는 실제로 나갈 앱 기본 설정(모델 타임아웃 300ms · page-capacity
# shared)으로 잰다. M1(앱 = 서버 대조)만은 타임아웃 대체가 섞이지 않게 긴 타임아웃(M1_TIMEOUT)을
# 주고 그 사실을 M1 표에 적는다.
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)

PY=${PY:?GenPage 가상환경의 python 경로를 PY 로 준다}
export GENPAGE_DATA=${GENPAGE_DATA:-$ROOT/personalization/data}
OUT=${OUT:-personalization/docs/runs/$(date +%Y%m%d)-s2-app}
case "$OUT" in /*) ;; *) OUT=$ROOT/$OUT ;; esac
mkdir -p "$OUT"

PORT=${PORT:-18090}; BASE="http://localhost:$PORT"
MODEL_PORT=${MODEL_PORT:-18868}; MODEL_URL="http://127.0.0.1:$MODEL_PORT"
CKPT=${CKPT:-$GENPAGE_DATA/hm/model/genpage2/final/ckpt/f-base-full-1ep}
SCORES=${SCORES:-$GENPAGE_DATA/hm/runs-archive/20260929-v2-d3-page/final/scores.json.gz}
JAR=${JAR:-$ROOT/commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar}
MAIN_WT=${MAIN_WT:-$(dirname "$ROOT")/wt-s2-main}
MAIN_JAR=${MAIN_JAR:-$MAIN_WT/commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar}
JAVA=${JAVA:-"$(/usr/libexec/java_home -v 21)/bin/java"}

# 표본 크기는 환경변수로 줄일 수 있다(스모크: MAPPED=5 UNMAPPED=2 NOSCORES=2 M2_LIMIT=5).
MAPPED=${MAPPED:-200}; UNMAPPED=${UNMAPPED:-20}; NOSCORES=${NOSCORES:-20}
M1_LIMIT=${M1_LIMIT:-0}; M2_LIMIT=${M2_LIMIT:-0}; M4_LIMIT=${M4_LIMIT:-20}
M4=${M4:-1}
# 측정은 실제로 나갈 앱 기본 설정으로 한다 — 날것을 재야 한다(이 저장소에서 두 번 어긋났다).
APP_TIMEOUT=${APP_TIMEOUT:-300ms}
APP_CAPACITY=${APP_CAPACITY:-shared}
# M1(앱 = 서버 대조)만은 타임아웃 대체가 섞이지 않게 길게 준다 — 표에 그 사실을 적는다.
M1_TIMEOUT=${M1_TIMEOUT:-3s}
APP_SETTINGS_NOTE=${APP_SETTINGS_NOTE:-"앱 기본 설정 (모델 타임아웃 $APP_TIMEOUT · page-capacity $APP_CAPACITY)"}
BENCH="$PY $ROOT/tools/s2_app_bench.py"

note() { echo "== $* $(date +%H:%M)" | tee -a "$OUT/progress.txt"; }

for f in $CKPT $SCORES; do
  [ -e "$f" ] || { note "입력이 없다: $f"; exit 1; }
done

APP=""; MODEL=""
cleanup() { [ -n "$APP" ] && kill "$APP" 2>/dev/null; APP=""; }
stop_model() { [ -n "$MODEL" ] && kill "$MODEL" 2>/dev/null; MODEL=""; }
trap 'cleanup; stop_model' EXIT

wait_port_free() {
  for _ in $(seq 1 40); do
    lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1 || return 0
    sleep 1
  done
  lsof -nP -tiTCP:"$PORT" -sTCP:LISTEN | xargs -r kill -9 2>/dev/null || true
  sleep 2
}

start_app() {   # $1 jar, $2 결합 방식, $3 로그, [$4 모델 타임아웃], [$5 page-capacity]
  local jar=$1 setting=$2 log=$3 timeout=${4:-$APP_TIMEOUT} capacity=${5:-$APP_CAPACITY}
  cleanup; wait_port_free
  APP_RATELIMIT_ENABLED=false \
  APP_PERSONALIZATION_TRANSPORT=IN_REQUEST \
  APP_RECOMMENDATION_MODEL_KIND=genpage \
  APP_RECOMMENDATION_MODEL_GENPAGE_URL="$MODEL_URL" \
  APP_RECOMMENDATION_MODEL_GENPAGE_TIMEOUT="$timeout" \
  APP_RECOMMENDATION_MODEL_PAGE_CAPACITY="$capacity" \
  APP_RECOMMENDATION_HISTORY_SOURCE=purchases \
  APP_RECOMMENDATION_GENPAGE_COMPOSE="$setting" \
  "$JAVA" -jar "$jar" --spring.docker.compose.enabled=false --server.port="$PORT" > "$log" 2>&1 &
  APP=$!
  for _ in $(seq 1 180); do
    curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && { sleep 3; return 0; }
    kill -0 "$APP" 2>/dev/null || { echo "앱이 죽었다 — $log"; return 1; }
    sleep 1
  done
  echo "앱이 안 떴다 — $log"; return 1
}

start_model() {
  note "모델 서버 기동 :$MODEL_PORT (이력 저장소 켬)"
  "$PY" "$ROOT/personalization/serving/genpage2_server.py" --mode final --ckpt "$CKPT" --scores "$SCORES" \
    --history-store --threads 2 --device cpu --port "$MODEL_PORT" > "$OUT/model-server.log" 2>&1 &
  MODEL=$!
  for _ in $(seq 1 900); do curl -sf "$MODEL_URL/health" >/dev/null 2>&1 && break; sleep 1; done
  curl -sf "$MODEL_URL/health" >/dev/null 2>&1 || { note "모델 서버가 안 떴다 — $OUT/model-server.log"; exit 1; }
  note "모델 서버 준비됨"
}

# --- 0. 앱 jar ---
[ -f "$JAR" ] || { note "앱 bootJar"; ./gradlew -p commerce bootJar || { note "bootJar 실패"; exit 1; }; }

# --- 1. 표본 · 계정 · 매핑 ---
if [ ! -f "$OUT/sample.json" ]; then
  note "표본 준비 (매핑 $MAPPED · 매핑없음 $UNMAPPED · 점수없음 $NOSCORES)"
  $BENCH prepare --data-dir "$GENPAGE_DATA" --scores "$SCORES" \
    --mapped "$MAPPED" --unmapped "$UNMAPPED" --no-scores "$NOSCORES" --seed 7 --out "$OUT" | tee "$OUT/sample.txt"
fi

start_model

if [ ! -f "$OUT/accounts.json" ]; then
  note "회원가입 → 로그인"
  start_app "$JAR" OFF "$OUT/app-signup.log" || exit 1
  $BENCH signup --app-url "$BASE" --out "$OUT" | tee "$OUT/accounts.txt"
  cleanup
fi
if [ ! -f "$OUT/map.txt" ]; then
  note "매핑 적재 (personalization_user_map)"
  $BENCH map --out "$OUT" | tee "$OUT/map.txt"
fi

# --- 2. M1 (앱 = 서버 대조) — 타임아웃 대체가 섞이지 않게 긴 타임아웃 ---
if [ ! -f "$OUT/m1.json" ]; then
  note "M1 시작 (앱 타임아웃 $M1_TIMEOUT)"
  start_app "$JAR" HYBRID "$OUT/app-m1.log" "$M1_TIMEOUT" "$APP_CAPACITY" || exit 1
  $BENCH m1 --app-url "$BASE" --model-url "$MODEL_URL" --limit "$M1_LIMIT" \
    --app-timeout "$M1_TIMEOUT" --out "$OUT" | tee "$OUT/m1.txt"
  cleanup
fi

# --- 3. M2 (HYBRID) · M3 (a·b·c) — 앱 기본 설정 ---
if [ ! -f "$OUT/m2-hybrid.json" ] || [ ! -f "$OUT/m3-c.json" ]; then
  start_app "$JAR" HYBRID "$OUT/app-hybrid.log" || exit 1
  [ -f "$OUT/m2-hybrid.json" ] || { note "M2 HYBRID 시작"; $BENCH m2 --app-url "$BASE" \
    --setting HYBRID --limit "$M2_LIMIT" --app-settings "$APP_SETTINGS_NOTE" --out "$OUT" | tee "$OUT/m2-hybrid.txt"; }
  [ -f "$OUT/m3-a.json" ] || { note "M3 (a) 매핑 없음"; $BENCH m3 --app-url "$BASE" --case a --out "$OUT" \
    | tee "$OUT/m3-a.txt"; }
  [ -f "$OUT/m3-b.json" ] || { note "M3 (b) 점수 없음"; $BENCH m3 --app-url "$BASE" --case b --out "$OUT" \
    | tee "$OUT/m3-b.txt"; }
  if [ ! -f "$OUT/m3-c.json" ]; then
    note "M3 (c) 모델 서버 정지"
    stop_model
    $BENCH m3 --app-url "$BASE" --case c --out "$OUT" | tee "$OUT/m3-c.txt"
    start_model
  fi
  cleanup
fi

# --- 4. M2 (RULE · OFF) — 설정마다 앱을 다시 띄운다(앱 기본 설정) ---
for SETTING in RULE OFF; do
  LOWER=$(echo "$SETTING" | tr 'A-Z' 'a-z')
  if [ ! -f "$OUT/m2-$LOWER.json" ]; then
    note "M2 $SETTING 시작"
    start_app "$JAR" "$SETTING" "$OUT/app-$LOWER.log" || exit 1
    $BENCH m2 --app-url "$BASE" --setting "$SETTING" --limit "$M2_LIMIT" \
      --app-settings "$APP_SETTINGS_NOTE" --out "$OUT" | tee "$OUT/m2-$LOWER.txt"
    cleanup
  fi
done

# --- 5. M4 OFF 회귀 — origin/main jar 와 이 브랜치 jar (앱 기본 설정) ---
if [ "$M4" = "1" ] && [ ! -f "$OUT/m4.json" ]; then
  note "M4 — main 작업 트리 준비"
  if [ ! -f "$MAIN_JAR" ]; then
    [ -d "$MAIN_WT" ] || git worktree add "$MAIN_WT" main || { note "worktree 생성 실패"; exit 1; }
    ./gradlew -p "$MAIN_WT/commerce" bootJar || { note "main bootJar 실패"; exit 1; }
  fi
  note "M4 — main jar (OFF)"
  start_app "$MAIN_JAR" OFF "$OUT/app-m4-main.log" || exit 1
  $BENCH m4-capture --app-url "$BASE" --label main --limit "$M4_LIMIT" --out "$OUT" | tee "$OUT/m4-main.txt"
  cleanup
  note "M4 — 이 브랜치 jar (OFF)"
  start_app "$JAR" OFF "$OUT/app-m4-branch.log" || exit 1
  $BENCH m4-capture --app-url "$BASE" --label branch --limit "$M4_LIMIT" --out "$OUT" | tee "$OUT/m4-branch.txt"
  cleanup
  note "M4 — 비교"
  $BENCH m4-report --out "$OUT" | tee "$OUT/m4.txt"
fi

note "끝"
