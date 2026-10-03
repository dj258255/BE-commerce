# S2 M2 — 앱 2쪽 지연 · RULE (compose `rule`)

- 앱 설정: 앱 기본 설정 (모델 타임아웃 300ms · page-capacity shared)
- 앱 `http://localhost:18090` · 계정 200개 · 동시성 1 닫힌 루프
- 응답 200 · 오류 0 · 처리량 37.62/s
- 왕복 p50 26.0 · p95 31.0 · p99 36.5 ms
- GENPAGE 행 비율 99.0% · 규칙 행으로 물러선 비율 0.0% · 그 외 1.0% (전략 `{'GENPAGE': 471}`)

