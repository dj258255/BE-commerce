| 조건 | 사용자 | 컨텍스트 일치율 | 창 집계 일치율 | 불일치 원인 | 대표 사례 |
|---|---:|---:|---:|---|---|
| `redelivery-after` | 20 | **100.0%** | 100.0% | 불일치 없음 | - |
| `baseline @IN_PROCESS` | 20 | **100.0%** | 100.0% | 불일치 없음 | - |
| `disorder @IN_PROCESS` | 20 | **100.0%** | 100.0% | 불일치 없음 | - |
| `duplicate @IN_PROCESS` | 20 | **100.0%** | 100.0% | 불일치 없음 | - |
| `late @IN_PROCESS` | 20 | **95.0%** | 95.0% | behind 5.0% · dropped 5.0% | behind+dropped: user 2291 online 8건(seq 8) / 로그 12건(seq 12) |
| `order-in-process @IN_PROCESS` | 6 | **100.0%** | 100.0% | 불일치 없음 | - |
| `order-kafka @KAFKA` | 6 | **100.0%** | 100.0% | 불일치 없음 | - |
| `ttl @IN_PROCESS` | 20 | **0.0%** | 0.0% | absent 100.0% | absent: user 2312 online 0건(seq None) / 로그 12건(seq 12) |
| `window @IN_PROCESS` | 20 | **100.0%** | 100.0% | 불일치 없음 | - |
