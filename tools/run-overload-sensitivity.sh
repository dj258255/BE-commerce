#!/usr/bin/env bash
# 과부하 정책 파라미터 민감도와 용량 오판(#264). run-inference-overload.sh 를 설정만 바꿔 여러 번 부른다.
#
#   bash tools/run-overload-sensitivity.sh [A] [B] [C]      # 기본: 셋 다
# A: ADMISSION 예산 50·100·200ms, BOUNDED 상한 12·24·48 — 부하 160·320/s
# B: 모델 실제 지연 100ms, 게이트가 믿는 지연 50ms — 부하 160/s
# C: 관측 추정(OBSERVED) — B 와 같은 오판 조건, 그리고 제대로 알 때(예산 100ms, 160·320/s)
set -euo pipefail
PARTS=("$@"); [ $# -eq 0 ] && PARTS=(A B C)
OUT=${OUT:-personalization/docs/runs/$(date +%Y%m%d)-overload-sensitivity}
mkdir -p "$OUT"
for P in "${PARTS[@]}"; do
  case "$P" in
    A)
      for B in 50 100 200; do
        POLICIES=ADMISSION ADMISSION_BUDGET_MS=$B RATES="160 320" OUT_DIR="$OUT/A-admission-$B" bash tools/run-inference-overload.sh
      done
      for M in 12 24 48; do
        POLICIES=BOUNDED MAX_IN_FLIGHT=$M RATES="160 320" OUT_DIR="$OUT/A-bounded-$M" bash tools/run-inference-overload.sh
      done
      ;;
    B)
      POLICIES="ADMISSION BOUNDED" MODEL_LATENCY_MS=50 STUB_LATENCY_MS=100 RATES="160" \
        OUT_DIR="$OUT/B-misestimate" bash tools/run-inference-overload.sh
      ;;
    C)
      POLICIES=ADMISSION ADMISSION_ESTIMATE=OBSERVED MODEL_LATENCY_MS=50 STUB_LATENCY_MS=100 RATES="160" \
        OUT_DIR="$OUT/C-observed-misestimate" bash tools/run-inference-overload.sh
      POLICIES=ADMISSION ADMISSION_ESTIMATE=OBSERVED RATES="160 320" \
        OUT_DIR="$OUT/C-observed-correct" bash tools/run-inference-overload.sh
      ;;
  esac
done
python3 tools/overload_sensitivity_report.py "$OUT" | tee "$OUT/report.md"
