| 조건 | 사용자 | 컨텍스트 일치율 | 창 집계 일치율 | 불일치 원인 | 대표 사례 |
|---|---:|---:|---:|---|---|
| `order-in-process` | 6 | **83.3%** | 0.0% | dropped 16.7% · truncated 100.0% | truncated: user 2006 online 20건(seq 40) / 로그 40건(seq 40) |
| `order-kafka` | 6 | **100.0%** | 0.0% | truncated 100.0% | truncated: user 2012 online 20건(seq 40) / 로그 40건(seq 40) |
