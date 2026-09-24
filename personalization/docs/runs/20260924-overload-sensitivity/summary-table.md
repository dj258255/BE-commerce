| 조건 | 정책 | 부하 | 달성 | coverage | serving p95 | model p95 | 거절 | 대기 초과 |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| A-admission-100 | ADMISSION | 160 | 141 | 44.5% | 168.0ms | 164.0ms | 2666 | 0 |
| A-admission-100 | ADMISSION | 320 | 284 | 22.6% | 164.0ms | 160.0ms | 7431 | 0 |
| A-admission-200 | ADMISSION | 160 | 141 | 44.5% | 281.0ms | 277.0ms | 2664 | 0 |
| A-admission-200 | ADMISSION | 320 | 283 | 22.6% | 276.0ms | 272.0ms | 7434 | 0 |
| A-admission-50 | ADMISSION | 160 | 138 | 44.6% | 111.0ms | 106.0ms | 2659 | 0 |
| A-admission-50 | ADMISSION | 320 | 278 | 22.7% | 108.0ms | 104.0ms | 7426 | 0 |
| A-bounded-12 | BOUNDED | 160 | 141 | 44.6% | 168.0ms | 163.0ms | 2662 | 0 |
| A-bounded-12 | BOUNDED | 320 | 283 | 22.6% | 164.0ms | 160.0ms | 7432 | 0 |
| A-bounded-24 | BOUNDED | 160 | 138 | 40.8% | 348.0ms | 336.0ms | 2803 | 41 |
| A-bounded-24 | BOUNDED | 320 | 277 | 22.6% | 332.0ms | 327.0ms | 7432 | 0 |
| A-bounded-48 | BOUNDED | 160 | 140 | 44.2% | 461.0ms | 457.0ms | 1583 | 1095 |
| A-bounded-48 | BOUNDED | 320 | 281 | 22.6% | 455.0ms | 451.0ms | 6327 | 1102 |
| B-misestimate | ADMISSION | 160 | 141 | 23.6% | 315.0ms | 312.0ms | 3668 | 0 |
| B-misestimate | BOUNDED | 160 | 140 | 23.6% | 503.0ms | 500.0ms | 3282 | 386 |
| C-observed-correct | ADMISSION | 160 | 141 | 44.7% | 153.0ms | 150.0ms | 2656 | 0 |
| C-observed-correct | ADMISSION | 320 | 284 | 22.7% | 153.0ms | 149.0ms | 7422 | 0 |
| C-observed-misestimate | ADMISSION | 160 | 141 | 23.6% | 193.0ms | 189.0ms | 3669 | 0 |
