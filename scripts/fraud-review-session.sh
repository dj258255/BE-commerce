#!/usr/bin/env bash
# 심사 초안 블라인드 비교 세션을 연다 (27 문서 7절).
#
# 왜 있는가: 상담 초안(blind-review-session.sh)과 같다. 표본이 0건이던 이유는 판단의 어려움이
# 아니라 준비 비용이었다. 여기서 재는 것은 심사 초안 화면 공개 조건 — 판정 12건 이상에서
# 모델 초안의 편집률 중앙값이 템플릿보다 낮은가.
#
# <모델을 켜지만 화면은 안 바꾼다.> fraud-review-provider 는 template 그대로 두고
# fraud-review-model-enabled 만 켠다. 하나로 두면 표본을 모으려고 켜는 순간 화면까지 바뀐다.
#
#   ./scripts/fraud-review-session.sh          연다
#   ./scripts/fraud-review-session.sh next     다음 한 건을 진행한다
#   ./scripts/fraud-review-session.sh stats    지금까지의 집계를 본다
#   ./scripts/fraud-review-session.sh down     내린다
set -euo pipefail
cd "$(dirname "$0")/.."

BASE=http://localhost:8080/api/v1/admin/fraud-reviews
REVIEWER=${ADMIN_USER:-admin}

token() {
  curl -s -m 10 -X POST localhost:8080/api/v1/auth/login \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${ADMIN_USER:-admin}\",\"password\":\"${ADMIN_PASS:-admin-local-only}\"}" \
  | python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('accessToken') or d.get('token') or '')"
}

api() { curl -s -m 20 -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$@"; }

show_stats() {
  TOKEN=$(token)
  api "$BASE/0/draft-review/stats" | python3 -c '
import json,sys
d=json.load(sys.stdin)
pct=lambda x: "재는 중" if x is None else "%.1f%%" % (x*100)
print()
print("  판정 %d건 / 최소 12건" % d["judged"])
print("    모델   편집률 중앙값  %s" % pct(d.get("modelMedian")))
print("    템플릿 편집률 중앙값  %s" % pct(d.get("baselineMedian")))
m,b = d.get("modelMedian"), d.get("baselineMedian")
if d["judged"] >= 12 and m is not None and b is not None:
    print("    공개 조건: %s" % ("통과 — provider 를 ollama 로 바꿀 근거가 생겼다" if m < b else "미달 — 템플릿 그대로 둔다"))
else:
    print("    공개 조건: 아직 못 쟀다")
print()
print("  개선 폭은 수치로 인용하지 않는다. 상담 초안에서 같은 12건을 두 번 돌렸더니")
print("  회차마다 편집률이 두 배 벌어졌다. 말할 수 있는 것은 방향뿐이다.")
print()
'
}

next_one() {
  TOKEN=$(token)
  ID=$(api "$BASE?status=PENDING&size=1" | python3 -c '
import json,sys
d=json.load(sys.stdin)
rows=d.get("content") or d.get("items") or (d if isinstance(d,list) else [])
print(rows[0]["id"] if rows else "")')
  [ -n "$ID" ] || { echo "  대기 중인 심사가 없다. up 으로 표본을 심는다."; exit 1; }

  echo; echo "  심사 $ID — 사실을 먼저 본다"
  api -X POST "$BASE/$ID/draft-review/open" -d "{\"reviewer\":\"$REVIEWER\"}" >/dev/null
  # 원문 JSON 을 그대로 뿌리면 읽는 데 시간이 걸려 열두 건이 그만큼 늘어난다.
  api "$BASE/$ID/facts" | python3 tools/render-fraud-facts.py

  echo; echo "  1) 초안을 보기 전에 <직접> 심사 메모를 쓴다. 다 쓰면 빈 줄에서 Ctrl-D."
  MEMO=$(cat)
  api -X POST "$BASE/$ID/draft-review/blind" \
      -d "$(python3 -c 'import json,sys;print(json.dumps({"reviewer":sys.argv[1],"reply":sys.argv[2]}))' "$REVIEWER" "$MEMO")" >/dev/null

  echo; echo "  2) 초안 A·B 다. 어느 쪽이 모델인지는 안 알려준다."
  # json.tool 은 기본이 ASCII 이스케이프라 한글이 \uce74\ub4dc 로 나온다.
  api -X POST "$BASE/$ID/draft-review/reveal" -d "{\"reviewer\":\"$REVIEWER\"}" | python3 tools/render-fraud-pair.py

  echo; echo "  3) A 를 발송 가능하게 고친다. Ctrl-D."; A=$(cat)
  echo;    echo "  4) B 를 발송 가능하게 고친다. Ctrl-D."; B=$(cat)
  api -X POST "$BASE/$ID/draft-review/edit" \
      -d "$(python3 -c 'import json,sys;print(json.dumps({"reviewer":sys.argv[1],"editedModel":sys.argv[2],"editedBaseline":sys.argv[3]}))' "$REVIEWER" "$A" "$B")" >/dev/null
  echo; echo "  기록했다."
  show_stats
}

case "${1:-up}" in
  stats) show_stats ;;
  next)  next_one ;;
  down)
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

    # 이 기계에는 도커 런타임이 둘이라 포트 표기로는 어느 쪽이 3306 을 잡았는지 안 갈린다.
    # 스키마가 있는 쪽이 앱이 붙은 곳이다. blind-review-session.sh 와 같은 이유다.
    has_schema() {
      [ "$(docker --context "$1" exec pay-mysql-1 mysql -N -B -uroot -proot pay \
            -e "SHOW TABLES LIKE 'flyway_schema_history'" 2>/dev/null | wc -l)" -gt 0 ]
    }
    find_db_ctx() {
      local c
      for c in $(docker context ls --format '{{.Name}}' 2>/dev/null); do
        has_schema "$c" && { echo "$c"; return 0; }
      done
      return 1
    }
    healthy() { curl -s -m 2 localhost:8080/actuator/health 2>/dev/null | grep -q UP; }

    if ! healthy || ! find_db_ctx >/dev/null; then
      lsof -ti:8080 | xargs -r kill -9 2>/dev/null || true
      # 모델은 켜고 화면은 템플릿 그대로 둔다. 이 둘을 붙이면 근거 모으는 순간 켜 버리는 셈이다.
      APP_ASSIST_FRAUD_REVIEW_MODEL_ENABLED=true \
      APP_ASSIST_FRAUD_REVIEW_PROVIDER=template \
        nohup ./gradlew -p commerce bootRun --args='--spring.profiles.active=local' > /tmp/pay-app.log 2>&1 &
    fi
    ready=no
    for _ in $(seq 1 60); do
      if healthy && DB_CTX=$(find_db_ctx); then ready=yes; break; fi
      sleep 5
    done
    if [ "$ready" != yes ]; then
      echo; echo "  앱이 안 떴거나 스키마가 없다. 여기서 멈춘다."
      echo "  준비됐다고 하고 넘어가면 뒤가 전부 헛돈다."
      echo "  로그: tail -50 /tmp/pay-app.log"
      exit 1
    fi
    db() { docker --context "$DB_CTX" exec -i pay-mysql-1 mysql --default-character-set=utf8mb4 -uroot -proot pay "$@"; }

    pending=$(db -N -B -e "SELECT COUNT(*) FROM fraud_reviews WHERE status='PENDING'" 2>/dev/null || echo 0)
    if [ "${pending:-0}" -lt 12 ]; then
      echo "  대기 중인 심사 ${pending:-0}건. 표본을 심는다."
      db < tools/seed-fraud-review.sql 2>&1 | grep -v Warning || true
      # <b>심고 나서 다시 센다.</b> 시드가 깨져도 파이프 뒤의 grep 이 0 으로 끝나 성공처럼
      # 보인다. 실제로 그렇게 "준비됐다"를 찍고 표본 0 건으로 넘어간 적이 있다.
      pending=$(db -N -B -e "SELECT COUNT(*) FROM fraud_reviews WHERE status='PENDING'" 2>/dev/null || echo 0)
      if [ "${pending:-0}" -lt 12 ]; then
        echo
        echo "  표본이 ${pending:-0}건이다. 시드가 실패했다. 여기서 멈춘다."
        echo "  스키마가 바뀌었을 수 있다: docker exec pay-mysql-1 mysql -uroot -proot pay -e 'SHOW COLUMNS FROM payments'"
        exit 1
      fi
    fi

    cat <<MSG

  준비됐다. 목표 12건.

    ./scripts/fraud-review-session.sh next     한 건 진행
    ./scripts/fraud-review-session.sh stats    집계 확인

  한 건의 순서 (엔티티가 이 순서를 강제한다)
    1. 사실만 보고 심사 메모를 직접 쓴다 — 초안은 아직 안 보인다
    2. 제출하면 초안 A·B 가 나온다. 어느 쪽이 모델인지 서버가 안 내보낸다
    3. A 와 B 를 <각각> 고친다. 둘 다 고쳐야 그 건이 표본이 된다

  고치는 기준 (2026-09-09 에 고정. 회차가 바뀌어도 이 기준이다)
    발송 가능하게 고친다. 사실이 틀렸거나 빠졌으면 고치고, 심사자를 오해시킬 표현도 고친다.
    <뜻이 통하는데 어색하기만 한 문장은 안 고친다.>

    마지막 줄이 상담 초안에서 비어 있던 자리다. 비문을 고칠지 안 고칠지를 안 정해 둔 탓에
    회차별 편집률이 두 배 벌어졌고 개선 폭을 확정하지 못했다. 같은 실수를 두 번 하지 않는다.

  메모를 먼저 받는 것은 초안을 보고 나면 사람 답이 초안을 닮기 때문이다.
  모델이 이겨도 <심사 판단이 정확해졌다>는 뜻은 아니다. 고친 양이 적다는 뜻이다.

MSG
    ;;
esac
