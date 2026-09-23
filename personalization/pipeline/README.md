# 파이프라인 (personalization/pipeline)

원본 → 정규화 → Parquet. `docs/04-storage.md`의 결정을 코드로 옮긴 것이다.

## 실행

```bash
python3 personalization/pipeline/fetch_hm.py          # H&M parquet 9개, ~1.08GB
python3 personalization/pipeline/normalize_hm.py      # 정규화 + 품질 리포트

python3 personalization/pipeline/fetch_amazon.py      # Amazon_Fashion 리뷰+메타, ~0.52GB
python3 personalization/pipeline/normalize_amazon.py  # 정규화 + 품질 리포트 (+asin 함정 검증)

python3 personalization/pipeline/features_hm.py       # 시간 스플릿 + point-in-time 피처 + baseline + MAP@12
python3 personalization/pipeline/check_no_leakage.py  # 누출 회귀 테스트 (실패 시 종료 코드 1)

# ALS 학습·평가. implicit 이 필요해 venv 를 쓴다(`personalization/.venv`, .gitignore).
personalization/.venv/bin/python personalization/pipeline/train_als.py           # 스윕 4구성, 약 21분
personalization/.venv/bin/python personalization/pipeline/train_als.py f64-a10   # 하나만
ALS_EXCLUDE_SEEN=1 personalization/.venv/bin/python personalization/pipeline/train_als.py f64-a10  # 대조
```

`train_als.py` 는 **채점기를 따로 만들지 않는다** — `features_hm.py` 의 `split`·`map_at_k` 를 import 해
쓴다. 새로 만들면 기준선과의 비교가 성립하지 않기 때문이다. 결과는
[리포트](../docs/runs/hm-als-report.md)에 있고, **기준선을 못 넘어 모델을 넣지 않았다**
([ADR-048](../../docs/adr/ADR-048-als-model-not-adopted.md)).

데이터는 `personalization/data/`에 쌓이고 **커밋하지 않는다**(.gitignore). 리포트만 `personalization/docs/runs/`에 남는다.

## 측정에서 틀렸던 것 (남겨둔다)

**MAP@12에서 미적중 고객을 평균에서 빼고 있었다.** 처음 구현은 정답이 하나라도 맞은 고객만 모아 평균을 냈다.
그래서 `popular_recent7d`가 0.136으로 나왔는데, 정답이 있는 **모든 고객**(68,984명)을 기준으로 고치니 **0.0087**이었다.
15배 부풀려진 숫자다.

두 번 걸렸다.
1. baseline마다 '평가 고객 수'가 달라서(4,436 / 1,457 / 5,081) 이상함을 알았다 — 같은 홀드아웃인데 다를 수 없다.
2. 고친 뒤에도 숫자가 **그대로**여서 다시 의심했다. 원인은 `reindex(fill_value=)`가 '새 라벨'만 채우고
   **기존 NaN은 못 채운다**는 것이었다. `fillna(0.0)`이 따로 필요했다.

지표가 부풀려지면 모델 개선을 잘못 판단한다. 그래서 이 검사를 `check_no_leakage.py`와 함께 남겨둔다.

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
