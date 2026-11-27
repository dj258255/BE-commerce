#!/usr/bin/env bash
# 토픽의 파티션별 끝 오프셋 합(= 지금까지 쓰인 레코드 수, 컴팩션 전 기준)을 출력한다.
# 예: scripts/count-topic.sh bcd.becommerce.orders
set -euo pipefail
cd "$(dirname "$0")/../compose"
docker compose exec -T kafka1 /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka1:9092 --topic "$1" --time -1 \
  | awk -F: '{s+=$3} END {print s+0}'
