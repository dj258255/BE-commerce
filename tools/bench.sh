#!/usr/bin/env bash
#
# 성능 실측 재현 스크립트 — 누구나 같은 절차로 다시 잴 수 있게 한다.
#
# 왜 이게 필요한가: 이 저장소의 성능 수치는 한 번 틀렸었다. 닫힌 루프(ramping-vus)로 재고
# "용량 ~120/s"라고 결론냈는데, 닫힌 루프는 응답이 늦으면 부하 자체가 줄어 스스로 스로틀링한다.
# 열린 루프로 다시 재서 뒤집었다. 그 외에도 두 번 더 측정을 망쳤다.
#
#   ① pkill 이 Gradle 래퍼만 죽이고 포크된 JVM 은 살아남아, "대조군"이 사실 2회차였다
#      → 결과가 0.2% 안에서 일치해서 겨우 알아챘다
#   ② 회차 사이에 DB 를 안 지워서 데이터가 누적돼 회차 비교가 무의미했다
#
# 그래서 이 스크립트가 실제로 막는 것은 느린 코드가 아니라 <잘못된 측정>이다.
#   - 매 회차 DB 볼륨을 지우고 새로 만든다            (②)
#   - 우리가 띄운 PID 가 정말 8080 을 잡았는지 확인한다 (①)
#   - 측정 환경을 리포트에 박아 넣는다 — 맥북에서 잰 수치는 맥북 수치다
#
# 사용법:
#   ./gradlew bench -Pprofile=smoke          # 배관 검증(1분) — 본 측정 전에 먼저
#   ./gradlew bench                          # 기본 프로파일(capacity)
#   ./gradlew bench -Pprofile=spike
#   ./gradlew bench -Pprofile=all
#
# 로컬 Docker 가 불안정하거나 CI 가 서비스를 따로 제공하면 외부 인프라 모드를 쓴다:
#   BENCH_INFRA=external BENCH_DB_PORT=3307 BENCH_ALLOW_DB_RESET=1 ./gradlew bench -Pprofile=smoke
#
set -uo pipefail

PROFILE="${1:-capacity}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="$ROOT/docs/performance/runs"
RUN_DIR="$OUT_DIR/$STAMP-$PROFILE"
APP_PID=""

# 인프라를 어디서 얻는가.
#   docker  (기본) — compose 로 띄우고 매 회차 볼륨째 지운다
#   external       — 이미 떠 있는 MySQL·Redis 를 쓴다. CI 가 서비스를 따로 제공하거나,
#                    로컬 Docker 가 불안정할 때. 초기화는 스키마 드롭으로 대신한다
INFRA="${BENCH_INFRA:-docker}"
DB_HOST="${BENCH_DB_HOST:-127.0.0.1}"
DB_PORT="${BENCH_DB_PORT:-3306}"
DB_NAME="${BENCH_DB_NAME:-pay}"
DB_USER="${BENCH_DB_USER:-pay}"
DB_PASS="${BENCH_DB_PASS:-pay}"
REDIS_HOST="${BENCH_REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${BENCH_REDIS_PORT:-6379}"

cd "$ROOT"

log()  { printf '\033[36m▶\033[0m %s\n' "$*"; }
ok()   { printf '\033[32m✓\033[0m %s\n' "$*"; }
die()  { printf '\033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

# ── 정리: 어떤 경로로 끝나든 앱과 컨테이너를 내린다 ──────────────────────
cleanup() {
  local code=$?
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    log "앱 종료 (PID $APP_PID)"
    kill "$APP_PID" 2>/dev/null
    for _ in $(seq 1 20); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 0.5; done
    kill -9 "$APP_PID" 2>/dev/null
  fi
  [[ "$INFRA" == "docker" ]] && docker compose down -v >/dev/null 2>&1
  exit $code
}
trap cleanup EXIT INT TERM

# ── 1. 전제 확인 — 없으면 즉시 멈춘다 ────────────────────────────────────
# 조용히 건너뛰면 "돌긴 돌았는데 뭘 쟀는지 모르는" 결과가 남는다.
log "전제 확인 (인프라: $INFRA)"
for c in k6 java; do
  command -v "$c" >/dev/null 2>&1 || die "$c 가 없습니다. 설치 후 다시 실행하세요."
done
if [[ "$INFRA" == "docker" ]]; then
  command -v docker >/dev/null 2>&1 || die "docker 가 없습니다."
  docker info >/dev/null 2>&1 || die "Docker 데몬이 떠 있지 않습니다."
  ok "docker · k6 · java 확인"
else
  # 외부 인프라 모드에서도 <초기화>는 여전히 필요하다. 누적된 데이터로 재면
  # 회차 비교가 무의미해진다(과거에 실제로 겪은 문제). 다만 남의 DB 를 말없이 지우면 안 되므로
  # 명시적 동의를 요구한다.
  command -v mysql >/dev/null 2>&1 || die "mysql 클라이언트가 없습니다 (외부 인프라 모드에 필요)."
  [[ "${BENCH_ALLOW_DB_RESET:-0}" == "1" ]] || die \
"외부 인프라 모드는 측정 전에 스키마를 비웁니다.
     대상: $DB_HOST:$DB_PORT/$DB_NAME (사용자 $DB_USER)
     지워도 되면 BENCH_ALLOW_DB_RESET=1 을 주고 다시 실행하세요."
  ok "k6 · java · mysql 확인 (외부 인프라)"
fi

mkdir -p "$RUN_DIR"

# ── 2. 측정 환경 기록 — 수치는 환경과 함께여야 의미가 있다 ────────────────
ENV_FILE="$RUN_DIR/environment.txt"
{
  echo "measured_at   : $(date -Iseconds)"
  echo "git_commit    : $(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "git_dirty     : $([[ -n "$(git status --porcelain 2>/dev/null)" ]] && echo yes || echo no)"
  echo "os            : $(uname -srm)"
  if [[ "$(uname -s)" == "Darwin" ]]; then
    echo "cpu           : $(sysctl -n machdep.cpu.brand_string 2>/dev/null)"
    echo "cores         : $(sysctl -n hw.ncpu 2>/dev/null)"
    echo "memory_gb     : $(( $(sysctl -n hw.memsize 2>/dev/null) / 1024 / 1024 / 1024 ))"
  else
    echo "cpu           : $(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2- | xargs)"
    echo "cores         : $(nproc 2>/dev/null)"
    echo "memory_gb     : $(( $(awk '/MemTotal/{print $2}' /proc/meminfo 2>/dev/null) / 1024 / 1024 ))"
  fi
  echo "java          : $(java -version 2>&1 | head -1)"
  echo "docker        : $(docker --version)"
  echo "k6            : $(k6 version 2>&1 | head -1)"
  echo "profile       : $PROFILE"
  echo "infra         : $INFRA"
  echo "db            : $DB_HOST:$DB_PORT/$DB_NAME"
} > "$ENV_FILE"
ok "환경 기록 → ${ENV_FILE#$ROOT/}"

# 외부 인프라 준비 확인 — docker 모드와 <같은 기준>으로 본다.
# "떴다"가 아니라 "앱이 쓰는 계정으로 질의가 된다"가 준비의 정의다.
_wait_infra_external() {
  local name="$1" ready=0
  for _ in $(seq 1 30); do
    if mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" \
         -e "SELECT 1" >/dev/null 2>&1; then ready=1; break; fi
    sleep 1
  done
  [[ $ready -eq 1 ]] || die "[$name] MySQL 에 붙을 수 없습니다 ($DB_HOST:$DB_PORT/$DB_NAME)"

  if ! (exec 3<>/dev/tcp/"$REDIS_HOST"/"$REDIS_PORT") 2>/dev/null; then
    die "[$name] Redis 에 붙을 수 없습니다 ($REDIS_HOST:$REDIS_PORT)"
  fi
  exec 3<&- 3>&- 2>/dev/null
  ok "[$name] 외부 MySQL·Redis 준비 확인"
}

# ── 3. 한 회차 실행 ─────────────────────────────────────────────────────
run_one() {
  local name="$1" script="$2" ratelimit="$3" extra_env="$4" prehook="${5:-}"

  if [[ "$INFRA" == "external" ]]; then
    # 볼륨을 지울 수 없으니 스키마를 비우는 것으로 대신한다. 목적은 같다 —
    # 회차 사이에 데이터가 남아 비교가 무의미해지는 것을 막는다.
    log "[$name] 스키마 초기화 (외부 인프라: $DB_HOST:$DB_PORT/$DB_NAME)"
    mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" \
      -e "DROP DATABASE IF EXISTS \`$DB_NAME\`; CREATE DATABASE \`$DB_NAME\`;" \
      >/dev/null 2>&1 || die "스키마 초기화 실패 ($DB_HOST:$DB_PORT)"
    ok "[$name] 스키마 초기화 완료"
    _wait_infra_external "$name"
  else
  log "[$name] 인프라 초기화 (볼륨 삭제 — 회차 간 누적을 막는다)"
  docker compose down -v >/dev/null 2>&1
  # --wait: compose 에 선언된 healthcheck 가 통과할 때까지 기다린다.
  docker compose up -d --wait mysql redis >/dev/null 2>&1 || die "인프라 기동 실패"

  # healthcheck 만으로는 부족하다. MySQL 이미지는 초기화 중 <임시 서버>를 띄우는데
  # 그때도 mysqladmin ping 이 통과한다. 그래서 "떴다"와 "쓸 수 있다"가 다르다.
  #
  # 앱이 실제로 쓰는 pay 계정으로 질의가 되는지를 본다. 그 계정과 스키마는 초기화가
  # 끝나야 만들어지므로, 이게 통과하면 초기화가 진짜 끝난 것이다.
  local ready=0
  for _ in $(seq 1 60); do
    if docker compose exec -T mysql \
         mysql -ubecommerce -pbecommerce becommerce -e "SELECT 1" >/dev/null 2>&1; then
      ready=1; break
    fi
    sleep 2
  done
  [[ $ready -eq 1 ]] || die "MySQL 이 준비되지 않았습니다 (pay 계정으로 질의 실패)"

  # 호스트에서 포트가 실제로 열렸는지도 본다. 앱은 컨테이너 안이 아니라 여기서 붙는다.
  local port_ok=0
  for _ in $(seq 1 30); do
    if (exec 3<>/dev/tcp/127.0.0.1/3306) 2>/dev/null; then port_ok=1; exec 3<&- 3>&-; break; fi
    sleep 1
  done
  [[ $port_ok -eq 1 ]] || die "호스트에서 127.0.0.1:3306 에 붙을 수 없습니다"
  ok "[$name] MySQL·Redis 준비 (pay 계정 질의 + 호스트 포트 확인)"
  fi

  # 앞선 실행이 남긴 프로세스가 있으면 지금 정리한다.
  # (이걸 안 해서 '대조군'이 실은 2회차였던 적이 있다)
  for p in $(lsof -nP -iTCP:8080 -sTCP:LISTEN -t 2>/dev/null); do
    log "[$name] 8080 을 쓰던 기존 프로세스 종료: $p"
    kill -9 "$p" 2>/dev/null
  done
  sleep 1

  log "[$name] 앱 기동 (ratelimit=$ratelimit)"
  env APP_RATELIMIT_ENABLED="$ratelimit" SPRING_DOCKER_COMPOSE_ENABLED=false \
      SPRING_DATASOURCE_URL="jdbc:mysql://$DB_HOST:$DB_PORT/$DB_NAME?serverTimezone=UTC&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false" \
      SPRING_DATASOURCE_USERNAME="$DB_USER" SPRING_DATASOURCE_PASSWORD="$DB_PASS" \
      SPRING_DATA_REDIS_HOST="$REDIS_HOST" SPRING_DATA_REDIS_PORT="$REDIS_PORT" \
      $extra_env \
      ./gradlew bootRun --console=plain > "$RUN_DIR/$name-app.log" 2>&1 &
  local wrapper=$!

  local healthy=0
  for _ in $(seq 1 90); do
    if curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; then healthy=1; break; fi
    kill -0 "$wrapper" 2>/dev/null || break     # 래퍼가 죽었으면 기다릴 이유가 없다
    sleep 2
  done
  # 앱이 안 떴을 때 <원인을 구분해서> 말한다.
  # 그냥 "앱이 안 떴다"고 하면 코드를 뒤지게 되는데, 실제로는 인프라가 사라진 경우가 있다.
  # (이 머신에서 Docker 데몬이 측정 도중 죽는 일이 반복됐다 — 자동 업데이트가 백엔드를 내린다)
  [[ $healthy -eq 1 ]] || {
    if [[ "$INFRA" == "docker" ]] && ! docker info >/dev/null 2>&1; then
      die "[$name] Docker 데몬이 측정 도중 죽었습니다. 앱 문제가 아닙니다.
     Docker Desktop 을 다시 띄우고 재실행하세요. 자동 업데이트가 원인인 경우가 있습니다."
    fi
    if [[ "$INFRA" == "docker" ]] && \
       ! docker compose ps --status running --quiet mysql 2>/dev/null | grep -q .; then
      die "[$name] MySQL 컨테이너가 측정 도중 내려갔습니다. 앱 문제가 아닙니다.
     docker compose logs mysql 으로 확인하세요."
    fi
    grep -v '^\s*at ' "$RUN_DIR/$name-app.log" | tail -20 >&2
    die "[$name] 앱이 뜨지 않았습니다 (로그: ${RUN_DIR#$ROOT/}/$name-app.log)"
  }

  # 여기가 핵심이다. bootRun 은 JVM 을 <포크>하므로 래퍼 PID 는 실제 서버가 아니다.
  # 포트를 실제로 잡은 PID 를 찾아 두 가지를 확인한다:
  #   (1) 그게 우리가 방금 띄운 것의 자식인가  → 남의 프로세스에 대고 재는 것을 막는다
  #   (2) 종료할 때 그 PID 를 직접 죽인다      → 좀비 JVM 이 다음 회차를 오염시키는 것을 막는다
  APP_PID="$(lsof -nP -iTCP:8080 -sTCP:LISTEN -t 2>/dev/null | head -1)"
  [[ -n "$APP_PID" ]] || die "[$name] 8080 을 잡은 프로세스를 찾지 못했습니다"

  local started_at
  started_at="$(ps -o lstart= -p "$APP_PID" 2>/dev/null | xargs)"
  ok "[$name] 앱 기동 확인 — PID $APP_PID (시작: $started_at)"
  echo "$name: pid=$APP_PID started=$started_at" >> "$RUN_DIR/process.txt"

  # 조회 부하는 읽을 것이 있어야 한다. 앱이 떠서 Flyway 가 돈 뒤, k6 전에 채운다.
  if [[ -n "$prehook" ]]; then
    log "[$name] 사전 시드 — $prehook"
    "$prehook" | tee -a "$RUN_DIR/$name-seed.log" || die "[$name] 시드 실패"
  fi

  log "[$name] k6 실행 — $script"
  # p(99) 는 k6 기본 통계에 없다. 명시하지 않으면 리포트의 p99 칸이 비고,
  # 꼬리 지연을 못 본다 — 무릎을 판단할 때 정작 필요한 값이다.
  k6 run --summary-export "$RUN_DIR/$name-summary.json" \
      --summary-trend-stats='avg,min,med,max,p(90),p(95),p(99)' "$script" \
      2>&1 | tee "$RUN_DIR/$name-k6.log"
  local k6_code=${PIPESTATUS[0]}

  log "[$name] 앱 종료 (PID $APP_PID 직접 종료)"
  kill "$APP_PID" 2>/dev/null
  for _ in $(seq 1 20); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 0.5; done
  kill -9 "$APP_PID" 2>/dev/null
  wait "$wrapper" 2>/dev/null
  APP_PID=""

  # 포트가 정말 비었는지 확인한다. 안 비었으면 다음 회차가 오염된다.
  sleep 2
  if lsof -nP -iTCP:8080 -sTCP:LISTEN -t >/dev/null 2>&1; then
    die "[$name] 8080 이 여전히 점유돼 있습니다 — 다음 회차를 신뢰할 수 없습니다"
  fi
  ok "[$name] 완료 (k6 exit=$k6_code)"
  return 0
}


# ── 조회 부하용 시드 ────────────────────────────────────────────────────
# 조회 경로를 재려면 읽을 것이 있어야 한다. 두 단계로 넣는다.
#   (1) 결정적 이메일로 계정을 만든다 — k6 는 같은 이메일로 로그인만 한다
#   (2) 그 계정들 앞으로 주문을 SQL 로 대량 삽입한다
#
# API 로 30만 건을 만들 수는 없어서 SQL 로 넣는다. 대신 <컬럼 구성은 실제 스키마 그대로>이고
# 인덱스도 마이그레이션이 만든 것을 그대로 쓴다 — 재는 대상은 조회지 삽입이 아니다.
#
# 사용자를 라운드로빈으로 흩뿌리는 것이 중요하다. 한 사용자의 주문을 연속으로 넣으면
# id 가 뭉쳐서 인덱스 없이도 빨리 찾아진다 — 실제와 다르고, 측정이 좋게 나오도록 속이는 셈이 된다.
READ_ACCOUNTS="${BENCH_READ_ACCOUNTS:-40}"
READ_USERS="${BENCH_READ_USERS:-10000}"        # 주문을 나눠 가질 전체 사용자 수
READ_ORDERS_PER_USER="${BENCH_READ_ORDERS:-30}" # 1인당 주문 수 — LIMIT 50 을 못 채우는 흔한 사용자

mysql_run() {
  if [[ "$INFRA" == "docker" ]]; then
    docker compose exec -T mysql mysql -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" -N -B -e "$1"
  else
    mysql -h "$DB_HOST" -P "$DB_PORT" -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" -N -B -e "$1"
  fi
}

seed_read_load() {
  local pw="k6-load-only-1234"
  echo "  계정 $READ_ACCOUNTS 개 가입"
  for i in $(seq 0 $((READ_ACCOUNTS - 1))); do
    curl -sf -X POST http://localhost:8080/api/v1/members/signup \
      -H 'Content-Type: application/json' \
      -d "{\"email\":\"k6-read-$i@load.test\",\"password\":\"$pw\"}" >/dev/null 2>&1 || true
  done

  local ids
  ids="$(mysql_run "SELECT id FROM members WHERE email LIKE 'k6-read-%@load.test' ORDER BY id" | tr '\n' ',' | sed 's/,$//')"
  [[ -n "$ids" ]] || { echo "  가입된 계정을 못 찾았다"; return 1; }
  local n_acc; n_acc="$(echo "$ids" | tr ',' '\n' | grep -c .)"
  echo "  계정 $n_acc 개 확인"

  local existing
  existing="$(mysql_run "SELECT COUNT(*) FROM orders WHERE order_no LIKE 'K6READ-%'")"
  if [[ "$existing" -ge $((READ_USERS * READ_ORDERS_PER_USER)) ]]; then
    echo "  주문 $existing 건이 이미 있다 — 시드 건너뜀"
    return 0
  fi

  echo "  주문 $((READ_USERS * READ_ORDERS_PER_USER)) 건 삽입 (사용자 $READ_USERS × $READ_ORDERS_PER_USER)"
  # 반드시 한 번의 mysql 호출로 끝낸다. TEMPORARY 테이블은 세션이 끝나면 사라져서,
  # 라운드마다 mysql 을 새로 부르면 앞 세션이 만든 테이블이 없다(여기서 한 번 실패했다).
  #
  # 라운드를 CROSS JOIN 으로 펼치고 ORDER BY round, seq 로 넣는 것이 핵심이다.
  # 사용자별로 몰아 넣으면 한 사람의 주문 id 가 뭉쳐서 인덱스 없이도 금방 찾아진다 —
  # 실제 주문 순서와 다르고, 측정이 좋게 나오도록 데이터를 고르는 셈이 된다.
  mysql_run "
    SET SESSION cte_max_recursion_depth = 100000000;
    CREATE TEMPORARY TABLE k6_users (seq INT PRIMARY KEY, user_id BIGINT);
    INSERT INTO k6_users (seq, user_id)
      WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x < $READ_USERS)
      SELECT x, IF(x <= $n_acc, ELT((x - 1) % $n_acc + 1, $ids), 1000000 + x) FROM n;
    CREATE TEMPORARY TABLE k6_rounds (r INT PRIMARY KEY);
    INSERT INTO k6_rounds (r)
      WITH RECURSIVE m(y) AS (SELECT 1 UNION ALL SELECT y+1 FROM m WHERE y < $READ_ORDERS_PER_USER)
      SELECT y FROM m;
    INSERT INTO orders (user_id, order_no, status, total_amount, currency, version, created_at, updated_at, expires_at)
      SELECT u.user_id, CONCAT('K6READ-', r.r, '-', u.seq), 'PAID', 10000, 'KRW', 0, NOW(6), NOW(6), NOW(6)
      FROM k6_rounds r CROSS JOIN k6_users u
      ORDER BY r.r, u.seq;
    ANALYZE TABLE orders;
  " >/dev/null || return 1
  echo "  주문 총 $(mysql_run "SELECT COUNT(*) FROM orders") 건"
}

# ── 4. 프로파일 ─────────────────────────────────────────────────────────
# capacity: 제어를 끄고 서버가 어디서 꺾이는지(무릎)를 본다 — 열린 루프
# spike   : 제어를 켜고 넘치는 부하가 어떻게 버려지는지를 본다
case "$PROFILE" in
  # smoke 는 성능을 재지 않는다 — 배관(기동·헬스·PID 검증·요약 파싱)이 맞는지만 1분에 확인한다.
  smoke)    run_one smoke    k6/bench-smoke.js        false "" ;;
  capacity) run_one capacity k6/capacity-knee.js      false "" ;;
  spike)    run_one spike    k6/spike-multi-account.js true  "" ;;
  checkout) run_one checkout k6/checkout-load.js      false "" ;;
  # read: 조회 경로가 어디서 꺾이는지 본다. 이 저장소의 부하 측정이 전부 쓰기 경로였다.
  read)     run_one read     k6/read-capacity.js       false "" seed_read_load ;;
  all)
    run_one capacity k6/capacity-knee.js      false ""
    run_one spike    k6/spike-multi-account.js true  ""
    run_one checkout k6/checkout-load.js      false ""
    ;;
  *) die "알 수 없는 프로파일: $PROFILE (smoke|capacity|spike|checkout|read|all)" ;;
esac

# ── 5. 요약 ─────────────────────────────────────────────────────────────
echo
log "결과 요약"
python3 "$ROOT/tools/bench_report.py" "$RUN_DIR" | tee "$RUN_DIR/report.md"
echo
ok "리포트: ${RUN_DIR#$ROOT/}/report.md"
echo
echo "  이 수치는 위 environment.txt 의 환경에서 잰 값입니다."
echo "  다른 환경의 수치와 직접 비교하지 마세요 — 같은 스크립트로 다시 재십시오."
