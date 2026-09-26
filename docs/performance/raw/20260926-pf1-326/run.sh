#!/usr/bin/env bash
# PF-1(#326): 두 커밋에서 같은 하네스·같은 인자로 3회씩. 공유 컨테이너 대신 pf1-mysql(13306)·pf1-redis(16379).
set -uo pipefail
SP=${SP:?옛 커밋 worktree(pf1-old)와 현재 main worktree(pay-docs)를 둔 작업 폴더}
export SPRING_DATA_REDIS_PORT=16379
run_set() {  # $1=라벨 $2=worktree $3=db
  local label=$1 wt=$2 db=$3
  docker exec pf1-mysql mysql -uroot -proot -e "DROP DATABASE IF EXISTS $db; CREATE DATABASE $db; GRANT ALL ON $db.* TO '$db'@'%';" 2>/dev/null
  for i in 1 2 3; do
    echo "== $label 회차 $i $(date '+%T')"
    ps -Ao pid,pcpu,pmem,etime,command | sort -k2 -nr | head -15 > "$SP/pf1-ps-$label-$i.txt"
    top -l 1 -n 0 | grep -E "Load Avg|CPU usage|PhysMem" >> "$SP/pf1-ps-$label-$i.txt"
    ( cd "$wt" && SPRING_DATASOURCE_URL="jdbc:mysql://localhost:13306/$db?serverTimezone=UTC&characterEncoding=UTF-8" \
      SPRING_DATASOURCE_USERNAME=$db SPRING_DATASOURCE_PASSWORD=$db \
      bash tools/run-pg-brownout.sh 3000 50 45s 5000 40 ) > "$SP/pf1-$label-$i.log" 2>&1
    echo "   exit $? · 출력: $(grep -m1 '^== 출력:' "$SP/pf1-$label-$i.log" | sed 's/== 출력: //')"
    sleep 10
  done
}
run_set old "$SP/pf1-old" pay
run_set new "$SP/pay-docs" becommerce
echo "== 끝 $(date '+%T')"
