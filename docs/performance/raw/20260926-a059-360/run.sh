#!/bin/bash
# A-059(#360): 일회용 CDC 스택(cdcexp)에서 커넥터 하나 · 둘 × poll 100 · 500 의 Connect CPU.
set -uo pipefail
SP=${SP:?작업 폴더}
cd "$SP/pay-a059"
CONNECT=http://localhost:18083
OUT="$SP/a059/runs"; mkdir -p "$OUT"
export CONNECT_CONTAINER=cdcexp-debezium-1 MYSQL_CONTAINER=cdcexp-mysql-1 DB_PORT=13326 KAFKA_BOOTSTRAP=localhost:19092
foreign_wait() { while pgrep -f "genpage_server|genpage2_server" >/dev/null || pgrep -fl "k6 run" | grep -q k6 || pgrep -fl "be-commerce-0.0.1-SNAPSHOT.jar" | grep -q jar; do
  echo "   다른 측정이 돌고 있어 기다린다 $(date '+%T')"; sleep 30; done; }
reg() {  # $1=json $2=poll
  python3 -c "import json,sys; d=json.load(open('$1')); d['config']['poll.interval.ms']='$2'; print(json.dumps(d))" > "$OUT/$(basename $1 .json)-$2.json"
  curl -sf -X POST -H 'Content-Type: application/json' --data @"$OUT/$(basename $1 .json)-$2.json" "$CONNECT/connectors" >/dev/null
}
clear_all() { for c in $(curl -s "$CONNECT/connectors" | python3 -c "import sys,json;print(' '.join(json.load(sys.stdin)))"); do curl -s -X DELETE "$CONNECT/connectors/$c" >/dev/null; done; sleep 3; }
wait_running() { for _ in $(seq 1 60); do n=$(curl -s "$CONNECT/connectors?expand=status" | python3 -c "import sys,json;d=json.load(sys.stdin);print(sum(1 for v in d.values() if v['status']['tasks'] and v['status']['tasks'][0]['state']=='RUNNING'))"); [ "$n" = "$1" ] && return; sleep 2; done; echo "RUNNING 이 $1 개가 아니다"; }
docker exec cdcexp-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:19092 --create --if-not-exists --topic user.activity --partitions 1 --replication-factor 1 >/dev/null
run_cond() {  # $1=태그 $2=poll $3=커넥터 수 $4=활동 부하
  foreign_wait; echo "== $1 $(date '+%T')"; clear_all
  reg cdc/register-catalog-connector.json "$2"
  [ "$3" = 2 ] && reg cdc/register-user-activity-connector.json "$2"
  wait_running "$3"; sleep 15
  { ps -Ao pcpu,pmem,etime,comm | sort -k1 -nr | head -12 | awk '{n=split($4,a,"/"); print $1,$2,$3,a[n]}'; } > "$OUT/ps$1.txt"
  TAG="$1" ACTIVITY_RATE="$4" uv run --quiet --with pymysql --with confluent-kafka python3 tools/cdc_poll_cost.py run "$OUT" "$2"
}
run_cond -one-p100 100 1 0
run_cond -one-p100-act 100 1 50
run_cond -two-p100 100 2 50
run_cond -two-p500 500 2 50
clear_all
python3 tools/cdc_poll_cost.py report "$OUT" | tee "$OUT/report.md"
echo "== 끝 $(date '+%T')"
