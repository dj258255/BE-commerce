# 파이프라인 (personalization/pipeline)

원본 → 정규화 → Parquet. `docs/04-storage.md`의 결정을 코드로 옮긴 것이다.

## 실행

```bash
python3 personalization/pipeline/fetch_hm.py          # H&M parquet 9개, ~1.08GB
python3 personalization/pipeline/normalize_hm.py      # 정규화 + 품질 리포트

python3 personalization/pipeline/fetch_amazon.py      # Amazon_Fashion 리뷰+메타, ~0.52GB
python3 personalization/pipeline/normalize_amazon.py  # 정규화 + 품질 리포트 (+asin 함정 검증)
```

데이터는 `personalization/data/`에 쌓이고 **커밋하지 않는다**(.gitignore). 리포트만 `personalization/docs/runs/`에 남는다.

## 원칙

1. **멱등** — 이미 받은 파일은 크기로 확인해 건너뛴다. 재실행이 같은 결과를 내야 한다.
2. **원본을 고치지 않는다** — 정규화 결과를 따로 쓴다. 규칙이 틀렸다는 걸 나중에 알면 원본에서 다시 돌린다.
3. **실측을 남긴다** — 품질 리포트의 수치는 스크립트가 원본에서 직접 계산한다. 손으로 적지 않는다.

## 정규화에서 조심한 것 (실측 근거)

| 무엇 | 왜 |
|---|---|
| `article_id` `zfill(10)` | 원본 parquet이 `int64`라 **앞의 0이 이미 날아가 있다**(9자리). 이미지 파일명·`docs/00-data.md` 규칙과 맞춘다 |
| `product_code` `zfill(7)` | 여기도 6자리로 들어온다. 10자리로 복원하면 값이 틀어진다 |
| `customer_id`는 그대로 | 64자 hex 문자열이다 — 자릿수 복원 대상이 아니다 |
| `-1` → 결측 | `product_type_no` 등 번호 컬럼의 센티널을 결측으로 |
| `price`는 금액 아님 | 실측 median 0.025 · max 0.59. 원장·정산의 KRW로 쓸 수 없다 |
| Amazon은 `parent_asin`으로 조인 | 실측: parent 100% vs asin 87.7% — asin으로 찾으면 12.3%가 조용히 유실된다 |

## 다음 (M1 나머지)

- 시간 스플릿과 point-in-time 피처 (#123)
- 커머스 `products` 승격(매핑은 `docs/04-storage.md` §3)
- 이미지 수집(린 슬라이스에서는 제외 — 필요해질 때)
