#!/usr/bin/env bash
# A/B 기반 끝에서 끝까지 검증(#256). A/A 와 주입 A/B 를 같은 합성 사용자로 돌리고 분석과 정답을 맞춘다.
#
#   bash tools/run-ab-e2e.sh            # 사용자 1,000명
# 전제: compose 의 mysql·redis 가 떠 있다. 합성 사용자는 ab-*@load.test 로 가입한다.
set -euo pipefail

N=${N:-1000}
JAR=commerce/build/libs/be-commerce-0.0.1-SNAPSHOT.jar
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
PORT=${PORT:-18090}; BASE="http://localhost:$PORT"
OUT=${OUT:-personalization/docs/runs/$(date +%Y%m%d)-ab-e2e}
mkdir -p "$OUT"
APP=""
stop() { [ -n "$APP" ] && kill "$APP" 2>/dev/null && wait "$APP" 2>/dev/null || true; APP=""; }
trap stop EXIT

start() {   # $1 솔트
  "$JAVA" -jar "$JAR" --server.port="$PORT" --app.ratelimit.enabled=false --app.web.browse-shed.enabled=false \
    --app.personalization.transport=IN_REQUEST \
    --app.experiments.rec-history.enabled=true --app.experiments.rec-history.salt="$1" > "$OUT/app-$1.log" 2>&1 &
  APP=$!
  for _ in $(seq 1 180); do curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && return 0; sleep 1; done
  echo "앱이 안 떴다"; exit 1
}

PYRUN=(uv run --quiet --with pymysql python3)
start aa-1
[ -f "$OUT/users.json" ] || "${PYRUN[@]}" tools/ab_synthetic.py users "$OUT" "$N" "$BASE"
# A/A: 두 변형에 같은 확률
"${PYRUN[@]}" tools/ab_synthetic.py run "$OUT" aa "$BASE" 0.20 0.20 0.05 0.05 0.20 0.03
SINCE=$(python3 -c "import json;print(json.load(open('$OUT/truth-aa.json'))['since'])")
"${PYRUN[@]}" tools/ab_analysis.py rec-history "$SINCE" > "$OUT/analysis-aa.json"
stop

# 주입 A/B: 실험군 클릭 +10%p, 구매 +3%p. 새 실험은 새 솔트
start ab-1
"${PYRUN[@]}" tools/ab_synthetic.py run "$OUT" ab "$BASE" 0.20 0.30 0.05 0.08 0.20 0.03
SINCE=$(python3 -c "import json;print(json.load(open('$OUT/truth-ab.json'))['since'])")
"${PYRUN[@]}" tools/ab_analysis.py rec-history "$SINCE" > "$OUT/analysis-ab.json"
stop

python3 tools/ab_report.py "$OUT" aa ab | tee "$OUT/report-table.md"
