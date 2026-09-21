| 조건 | 사용자 | 컨텍스트 일치율 | 창 집계 일치율 | 불일치 원인 | 대표 사례 |
|---|---:|---:|---:|---|---|
| `baseline` | 20 | **100.0%** | 100.0% | 불일치 없음 | - |
| `disorder` | 20 | **0.0%** | 0.0% | dropped 100.0% | dropped: user 1874 online 8건(seq 12) / 로그 12건(seq 12) |
| `late` | 20 | **30.0%** | 30.0% | behind 5.0% · dropped 70.0% | dropped: user 1895 online 11건(seq 12) / 로그 12건(seq 12) |
| `window` | 20 | **100.0%** | 60.0% | truncated 40.0% | truncated: user 1914 online 20건(seq 30) / 로그 30건(seq 30) |
| `ttl` | 20 | **0.0%** | 0.0% | absent 100.0% | absent: user 1934 online 0건(seq None) / 로그 12건(seq 12) |
| `duplicate` | 20 | **100.0%** | 100.0% | 불일치 없음 | - |
| `redelivery-after` | 20 | **95.0%** | 95.0% | dropped 5.0% | dropped: user 1986 online 10건(seq 12) / 로그 12건(seq 12) |
| `order-in-process` | 6 | **33.3%** | 0.0% | dropped 66.7% · truncated 100.0% | truncated: user 1974 online 20건(seq 40) / 로그 40건(seq 40) |
| `order-kafka` | 6 | **100.0%** | 0.0% | truncated 100.0% | truncated: user 1980 online 20건(seq 40) / 로그 40건(seq 40) |
