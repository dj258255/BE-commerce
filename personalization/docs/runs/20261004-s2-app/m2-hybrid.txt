# S2 M2 — 앱 2쪽 지연 · HYBRID (compose `hybrid`)

- 앱 설정: 앱 기본 설정 (모델 타임아웃 300ms · page-capacity shared)
- 앱 `http://localhost:18090` · 계정 200개 · 동시성 1 닫힌 루프
- 응답 200 · 오류 0 · 처리량 10.23/s
- 왕복 p50 99.9 · p95 119.4 · p99 127.7 ms
- GENPAGE 행 비율 100.0% · 규칙 행으로 물러선 비율 0.0% · 그 외 0.0% (전략 `{'GENPAGE': 513}`)

