#!/usr/bin/env bash
# Debezium MySQL 커넥터를 등록하고 RUNNING이 될 때까지 기다린다.
set -euo pipefail
cd "$(dirname "$0")/.."
CONNECT=${CONNECT:-http://127.0.0.1:8083}
NAME=becommerce-mysql
code=$(curl -s -o /dev/null -w '%{http_code}' "$CONNECT/connectors/$NAME")
if [ "$code" = "200" ]; then
  echo "이미 등록돼 있다"
else
  curl -sf -X POST -H 'Content-Type: application/json' --data @compose/connectors/becommerce-mysql.json "$CONNECT/connectors" >/dev/null
  echo "등록했다"
fi
for i in $(seq 1 60); do
  s=$(curl -s "$CONNECT/connectors/$NAME/status")
  conn=$(echo "$s" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["connector"]["state"], ",".join(t["state"] for t in d["tasks"]) or "-")' 2>/dev/null || echo "?")
  echo "상태 $conn"
  case "$conn" in "RUNNING RUNNING") exit 0;; *FAILED*) echo "$s"; exit 1;; esac
  sleep 2
done
exit 1
