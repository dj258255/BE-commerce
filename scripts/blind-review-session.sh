#!/usr/bin/env bash
# 블라인드 리뷰 세션을 연다. 인프라를 올리고 초안을 미리 고정해 두는 데까지 한다.
#
# 이 스크립트가 있는 이유는 쌍 비교(compare-session.sh)와 같다. 표본이 0건이던 실제 이유가
# 판단의 어려움이 아니라 준비 비용이었다. 초안을 그 자리에서 만들면 건당 10초 안팎이라,
# 12건을 쓰는 동안 제출할 때마다 그만큼 기다린다.
#
# 여기서 재는 것: 상담 초안 활성화 조건 1번 — 편집률 중앙값이 <템플릿보다> 낮은가.
# 한 건마다 사실만 보고 먼저 쓰고, 그 다음 초안 두 개(A·B)를 각각 고친다.
# 어느 쪽이 모델인지는 안 알려준다. 알고 고치면 편집량이 그것에 끌려간다.
#
#   ./scripts/blind-review-session.sh          연다
#   ./scripts/blind-review-session.sh stats    지금까지의 집계를 본다
#   ./scripts/blind-review-session.sh down     내린다
set -euo pipefail
cd "$(dirname "$0")/.."

API=http://localhost:8080/api/v1/admin/assist/reviews
COUNT=${BLIND_COUNT:-12}

token() {
  curl -s -m 10 -X POST localhost:8080/api/v1/auth/login \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${ADMIN_USER:-admin}\",\"password\":\"${ADMIN_PASS:-admin-local-only}\"}" \
  | python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('accessToken') or d.get('token') or '')"
}

show_stats() {
  curl -s -m 10 -H "Authorization: Bearer $(token)" "$API/stats" | python3 -c '
import json,sys
d=json.load(sys.stdin)
pct=lambda x: "%.1f%%" % (x*100)
print()
print("  표본 %d건 · 쌍 비교 %d건" % (d["samples"], d["pairedSamples"]))
if d["pairedSamples"]:
    m,b = d["pairedModelMedian"], d["pairedBaselineMedian"]
    print("    모델   편집률 중앙값  %s" % pct(m))
    print("    템플릿 편집률 중앙값  %s" % pct(b))
    print("    조건 1 (템플릿보다 낮은가): %s" % ("통과" if m<b else "미달"))
else:
    print("    조건 1: 아직 못 쟀다. 초안 둘을 다 고친 건이 없다")
print()
for c in d["caveat"]:
    print("  · " + c)
print()
'
}

case "${1:-up}" in
  stats) show_stats ;;
  down)
    echo "내리기 전에 집계부터 본다. 판정은 DB 에 남지만 눈으로 확인해 둔다."
    show_stats || echo "  (앱이 이미 내려가 있다)"
    lsof -ti:8080 | xargs -r kill -9 2>/dev/null || true
    docker compose down
    ;;
  up)
    colima status >/dev/null 2>&1 || colima start --cpu 4 --memory 6
    docker compose up -d
    for _ in $(seq 1 30); do
      docker exec pay-mysql-1 mysql -uroot -proot -e "select 1" >/dev/null 2>&1 && break
      sleep 3
    done

    # 앱이 실제로 쓰는 DB 컨테이너를 찾는다. <이 기계에는 도커 런타임이 둘 있다>
    # (colima 와 Docker Desktop). 양쪽에 pay-mysql-1 이 뜨고 <둘 다 :3306->3306 이라고
    # 보고한다>. 실제로 호스트 3306 을 잡은 것은 하나뿐인데 그게 어느 쪽인지 포트 표기로는
    # 안 갈린다. 그래서 앱은 A 에 쓰고 스크립트는 B 를 보면서 "표본이 0건"이라고 했다.
    #
    # 포트가 아니라 <스키마가 있는 쪽>으로 고른다. Flyway 가 만든 흔적이 곧 앱이 붙은 곳이다.
    ctxs() { docker context ls --format '{{.Name}}' 2>/dev/null; }
    has_schema() {
      [ "$(docker --context "$1" exec pay-mysql-1 mysql -N -B -uroot -proot pay \
            -e "SHOW TABLES LIKE 'flyway_schema_history'" 2>/dev/null | wc -l)" -gt 0 ]
    }
    find_db_ctx() {
      local c
      for c in $(ctxs); do has_schema "$c" && { echo "$c"; return 0; }; done
      return 1
    }
    healthy() { curl -s -m 2 localhost:8080/actuator/health 2>/dev/null | grep -q UP; }

    # 앱을 먼저 띄운다. 스키마를 만드는 것은 앱 기동의 Flyway 이므로, 그전에는 어느
    # 컨텍스트에도 흔적이 없어 고를 수가 없다.
    if ! healthy || ! find_db_ctx >/dev/null; then
      lsof -ti:8080 | xargs -r kill -9 2>/dev/null || true
      # 초안 고정은 모델을 부르므로 provider 를 켠 채로 띄운다. 템플릿 쪽은 항상 있다.
      APP_ASSIST_DRAFT_PROVIDER=${APP_ASSIST_DRAFT_PROVIDER:-ollama} \
        nohup ./gradlew -p commerce bootRun --args='--spring.profiles.active=local' > /tmp/pay-app.log 2>&1 &
    fi
    ready=no
    for _ in $(seq 1 60); do
      if healthy && DB_CTX=$(find_db_ctx); then ready=yes; break; fi
      sleep 5
    done
    if [ "$ready" != yes ]; then
      echo
      echo "  앱이 안 떴거나 스키마가 없다. 여기서 멈춘다."
      echo "  준비됐다고 하고 넘어가면 뒤가 전부 헛돈다."
      echo "  로그: tail -50 /tmp/pay-app.log"
      exit 1
    fi
    [ "$DB_CTX" = "$(docker context show 2>/dev/null)" ] || echo "  DB 컨텍스트: $DB_CTX (현재 컨텍스트와 다르다)"
    db() { docker --context "$DB_CTX" exec -i pay-mysql-1 mysql --default-character-set=utf8mb4 -uroot -proot pay "$@"; }

    # 표본은 <스키마가 생긴 뒤>에 심는다. 스키마를 만드는 것은 앱 기동의 Flyway 다.
    pending=$(db -N -B -e \
      "SELECT COUNT(*) FROM reconciliation_results WHERE status='PENDING'" 2>/dev/null || echo 0)
    if [ "${pending:-0}" -lt "$COUNT" ]; then
      echo "  미해결 대사 ${pending:-0}건. 표본을 심는다."
      db < tools/seed-blind-review.sql 2>&1 | grep -v Warning || true
    fi

    # 초안을 미리 고정한다. 블라인드 답은 안 채우므로 화면은 1단계부터 시작한다.
    ./gradlew -p commerce captureTest --tests '*BlindReviewSeedTest*' --rerun -q \
      -Dseed.count="$COUNT" -Dseed.reviewer="${ADMIN_USER:-admin}" || \
      echo "  (초안 고정 실패 — 앱 로그를 본다. 리뷰는 그래도 되지만 건마다 30초 기다린다)"

    cat <<MSG

  준비됐다. 목표 ${COUNT}건.

  http://localhost:8080/admin.html  →  블라인드 리뷰 패널
  로그인 ${ADMIN_USER:-admin} / ${ADMIN_PASS:-admin-local-only}

  한 건의 순서
    1. 사실만 보고 고객 답변을 직접 쓴다 (초안은 아직 안 보인다)
    2. 제출하면 초안 A·B 가 나온다. 어느 쪽이 모델인지는 안 알려준다
    3. A 와 B 를 <각각> 발송 가능하게 고친다. 둘 다 고쳐야 그 건이 표본이 된다

  중간 집계:  ./scripts/blind-review-session.sh stats
  내릴 때는:  ./scripts/blind-review-session.sh down
MSG
    ;;
  *) echo "쓰임: $0 [up|stats|down]"; exit 1 ;;
esac
