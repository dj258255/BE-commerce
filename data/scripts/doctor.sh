#!/usr/bin/env bash
# 실험 전에 본다. 디스크, 다른 부하, 도커 대상, 떠 있는 컨테이너.
# 측정값을 기록할 때 이 출력도 결과 파일에 같이 붙인다.
set -u
echo "== 시각";       date -u +%FT%TZ
echo "== 디스크";     df -h / | tail -1
echo "== 부하";       uptime
echo "== 도커 대상";  docker context show 2>/dev/null; echo "DOCKER_HOST=${DOCKER_HOST:-}"
echo "== 도커 디스크"; docker system df 2>/dev/null | head -5
echo "== 이 프로젝트 컨테이너"
docker ps --filter label=com.docker.compose.project=bcd --format '{{.Names}}\t{{.Status}}' 2>/dev/null
echo "== 다른 부하 도구"; pgrep -fl 'k6|loadgen|jmeter|wrk' || echo "없음"
