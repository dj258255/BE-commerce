#!/bin/sh
# Lakekeeper 부트스트랩과 창고 lake 생성. 두 번 돌려도 된다(이미 있으면 4xx를 무시한다).
set -u
LK=http://lakekeeper:8181
code=$(curl -s -o /tmp/b.txt -w '%{http_code}' -X POST "$LK/management/v1/bootstrap" -H 'Content-Type: application/json' -d '{"accept-terms-of-use": true}')
echo "bootstrap http $code $(head -c 200 /tmp/b.txt)"
code=$(curl -s -o /tmp/w.txt -w '%{http_code}' -X POST "$LK/management/v1/warehouse" -H 'Content-Type: application/json' -d @/init/warehouse.json)
echo "warehouse http $code $(head -c 300 /tmp/w.txt)"
case "$code" in 2*|409|400) exit 0;; *) exit 1;; esac
