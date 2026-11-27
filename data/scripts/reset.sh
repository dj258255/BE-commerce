#!/usr/bin/env bash
# 이 프로젝트의 컨테이너와 볼륨을 전부 지운다. 장을 처음 상태에서 다시 시작할 때 쓴다.
# 볼륨까지 지우므로 원본 DB, Kafka 토픽, 레이크 테이블이 모두 사라진다.
set -euo pipefail
cd "$(dirname "$0")/../compose"
docker compose --profile kafka --profile cdc --profile stream --profile lake --profile registry --profile orchestrate down -v --remove-orphans
echo "지웠다. 남은 볼륨:"; docker volume ls --filter label=com.docker.compose.project=bcd -q
