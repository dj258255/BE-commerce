#!/usr/bin/env bash
# 쌍 비교 세션을 연다. 인프라를 올리고 표본을 심어 고를 준비까지 한다.
#
# 이 스크립트가 있는 이유: 표본이 0건이던 실제 이유가 판단의 어려움이 아니라 준비 비용이었다.
# 다시 올릴 때마다 네 줄을 기억해야 하면 그 자체가 다음 미룸의 이유가 된다.
#
#   ./scripts/compare-session.sh          연다
#   ./scripts/compare-session.sh export   고른 것을 CSV 로 꺼낸다
#   ./scripts/compare-session.sh down     꺼낸 뒤 내린다
set -euo pipefail
cd "$(dirname "$0")/.."

API=http://localhost:8080/api/v1/admin/orders/narrative/compare
OUT=${COMPARE_EXPORT:-narrative-preferences.csv}

token() {
  curl -s -m 10 -X POST localhost:8080/api/v1/auth/login \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${ADMIN_USER:-admin}\",\"password\":\"${ADMIN_PASS:-admin-local-only}\"}" \
  | python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('accessToken') or d.get('token') or '')"
}

case "${1:-up}" in
  export)
    # 판정은 컨테이너보다 오래 살아야 한다. 꺼내지 않고 내리면 볼륨과 함께 사라진다.
    curl -s -m 10 -H "Authorization: Bearer $(token)" "$API/export" -o "$OUT"
    echo "꺼냈다 -> $OUT ($(($(wc -l < "$OUT") - 1))건)"
    ;;
  down)
    echo "내리기 전에 판정부터 꺼낸다."
    "$0" export || echo "  (앱이 이미 내려가 있으면 실패한다. 그러면 판정은 이미 잃었다)"
    lsof -ti:8080 | xargs -r kill -9 2>/dev/null || true
    docker compose down
    colima stop
    ;;
  up)
    colima status >/dev/null 2>&1 || colima start --cpu 4 --memory 6
    docker compose up -d
    for _ in $(seq 1 30); do
      docker exec pay-mysql-1 mysql -uroot -proot -e "select 1" >/dev/null 2>&1 && break
      sleep 3
    done
    lsof -ti:8080 >/dev/null 2>&1 || (nohup ./gradlew -p commerce bootRun --args='--spring.profiles.active=local' > /tmp/pay-app.log 2>&1 &)
    for _ in $(seq 1 60); do
      curl -s -m 2 localhost:8080/actuator/health 2>/dev/null | grep -q UP && break
      sleep 5
    done

    left=$(curl -s -m 5 -H "Authorization: Bearer $(token)" "$API/pending" \
           | python3 -c "import json,sys;print(json.load(sys.stdin).get('pending',0))" 2>/dev/null || echo 0)
    if [ "$left" = "0" ]; then
      ./gradlew -p commerce captureTest --tests '*NarrativeComparisonSeedTest*' --rerun -q
      left=30
    fi

    echo
    echo "  준비됐다. 남은 비교 ${left}건"
    echo "  http://localhost:8080/narrative-compare.html"
    echo "  로그인 ${ADMIN_USER:-admin} / ${ADMIN_PASS:-admin-local-only}"
    echo
    echo "  다 고르면:  ./scripts/compare-session.sh export"
    echo "  내릴 때는:  ./scripts/compare-session.sh down"
    ;;
  *) echo "쓰임: $0 [up|export|down]"; exit 1 ;;
esac
