#!/usr/bin/env bash
# 개인화 프론트를 정적으로 띄운다. 백엔드 없이 fixtures/로 동작한다.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
PORT="${1:-8088}"
echo "http://localhost:${PORT}/  — Ctrl+C 로 종료"
exec python3 -m http.server "$PORT"
