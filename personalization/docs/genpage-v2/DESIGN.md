# GenPage v2 설계 — 모듈 사이의 약속

이 문서는 여러 구현자가 동시에 작업해도 맞물리도록 **형식과 함수 모양**을 정한다. 바꿔야 하면 이 문서를 먼저 고치고 영향받는 묶음에 알린다.

코드 위치: `personalization/genpage2/`(파이썬 패키지, `__init__.py`). `personalization/` 을 작업 디렉터리로 두고 `genpage2.<모듈>` 로 부른다. 기존 `pipeline/features_hm.py` · `serving/genpage_server.py` 는 `sys.path` 에 그 디렉터리를 넣어 import 한다. 테스트: `personalization/genpage2/tests/`, 표준 라이브러리 `unittest`.

```bash
PY=/private/tmp/claude-501/genpage-venv/bin/python        # torch 2.14 · pandas · pyarrow · pymysql
export GENPAGE_DATA=/Users/beomsu/Desktop/pay/personalization/data   # H&M 원본(normalized/*.parquet)과 산출물
cd personalization && $PY -m unittest discover -s genpage2/tests -t .
cd personalization && $PY -m genpage2.evaluate --mode validate --ckpt ...
```

산출물 디렉터리: `$GENPAGE_DATA/hm/model/genpage2/`(저장소 밖, 커밋하지 않는다). 아래 `OUT` 은 이 경로다.

## 0. 공통 상수 (`genpage2/config.py`)

| 이름 | 값 | 뜻 |
|---|---|---|
| `VALIDATE_REQUEST` | 2020-09-09 | 검증 주 요청 시각. 이력 < 이 날, 정답 = 09-09 ~ 09-15 구매 |
| `FINAL_REQUEST` | 2020-09-16 | 홀드아웃 요청 시각. 정답 = 09-16 ~ 09-22 구매(v1 과 같은 기간) |
| `TARGET_DAYS` | 7 | 정답 페이지 기간 |
| `MIN_COUNT` | 10 | 어휘: 기준 시각 이전 10회 이상 팔린 상품(v1 과 같다) |
| `HISTORY_EVENTS` | 60 | 프롬프트에 넣는 최근 구매 이벤트 수 |
| `MAX_ROWS`, `ITEMS_PER_ROW` | 6, 8 | 정답 페이지 · 생성 페이지의 크기 |
| `MAXLEN` | 256 | 모델 시퀀스 길이 |
| `SEED` | 7 | |

`mode` 는 `validate` 또는 `final`. 학습 데이터와 어휘는 그 mode 의 요청 시각 **이전**만 본다.

## 1. 토큰 (`genpage2/vocab.py`, A1)

id 배치는 아래 순서로 고정한다(앞에서부터 이어 붙인다).

1. 특수: `PAD=0, BOS, EOS, SEP_PROFILE, SEP_REQUEST, SEP_HISTORY, SEP_PAGE, ITEM_FALLBACK, ROW_FALLBACK, UNK`
2. 프로필: `AGE_<구간>`(`<20, 20-24, 25-29, 30-39, 40-49, 50-59, 60+, NA`), `CLUB_<값>`(ACTIVE · PRE-CREATE · LEFT CLUB · NA), `NEWS_<값>`(NONE · Regularly · Monthly · NA), `FN_1 · FN_NA`, `ACTIVE_1 · ACTIVE_NA`
3. 요청: `DOW_0..6`, `MONTH_1..12`
4. 행동 종류: `ACT_STORE`(sales_channel 1), `ACT_ONLINE`(2), `ACT_VIEW`, `ACT_CLICK` — 뒤 둘은 H&M 에 없어 학습되지 않는다(서빙에서 앱의 세션 행동용)
5. 시각 구간(요청 시각 기준 며칠 전): `AGO_0-3, AGO_4-7, AGO_8-14, AGO_15-30, AGO_31-60, AGO_61-120, AGO_121-365, AGO_366+`
6. 행: `ROW_REPEAT`(다시 사기), `ROW_S<section_no>`(articles 에 있는 섹션 전부 57개, section_no 오름차순)
7. 상품: `ITEM_<article_id>`(어휘 상품, article_id 오름차순)

`article_id` 는 parquet 그대로 **10자리 문자열**(`"0108775015"`)로 다룬다. 정수로 바꾸면 앞자리 0이 사라져 v1 산출물 · 앱 카탈로그와 어긋난다. **콘텐츠 행 번호**는 articles.parquet 의 article_id 를 오름차순 정렬한 순위(0부터)이고 A1 · A2 가 같은 규칙으로 계산한다.

```python
class Vocab:
    tokens: list[str]                      # id → 이름
    def id(self, name: str) -> int
    def item(self, article_id: str) -> int | None      # 어휘 밖이면 None
    def row_of(self, article_id: str) -> int           # 그 상품의 섹션 행 토큰 id(모든 105,542개 상품에 정의)
    item_ids: range; row_ids: range; context_ids: range   # 종류별 연속 구간
    article_of: dict[int, str]             # 상품 토큰 id → article_id
    def save(self, path) / @staticmethod load(path)     # JSON
```

## 2. 학습 예시 (`genpage2/dataset.py`, A1)

한 예시 = (고객, 요청 시각 r). **문맥** = r 이전 정보, **정답 페이지** = [r, r+7) 에 산 상품을 행으로 묶은 것.

**문맥 토큰**

```
BOS SEP_PROFILE AGE CLUB NEWS FN ACTIVE SEP_REQUEST DOW MONTH SEP_HISTORY
  (이벤트 × 최근 60개, 오래된 것부터: [상품 토큰 또는 ITEM_FALLBACK] [ACT_STORE|ACT_ONLINE] [AGO_구간])
SEP_PAGE
```

**정답 페이지 토큰**: `[행][상품]…[행][상품]… EOS`

- 행 배정: 이력에 이미 있는 상품이면 `ROW_REPEAT`, 아니면 그 상품의 섹션 행
- 행 순서: 행 안 상품 수 내림차순, 같으면 그 행의 첫 구매가 이른 순. 최대 6행
- 행 안 순서: 구매 시각 순, 같은 상품은 한 번. 최대 8개
- 어휘 밖 상품: 문맥에서는 `ITEM_FALLBACK`(콘텐츠 id 를 붙인다), 정답 페이지에서는 **뺀다**(생성할 수 없는 토큰을 정답으로 두지 않는다)

**요청 시각**: 학습 구간 안에서 7일 간격(`r = 기준 − 7k`, k = 1..8). 정답 페이지가 비면 버린다. 고객당 최대 4개(최근 것부터). 학습 예시 총수 상한은 인자로 받는다(기본 200만).

**평가 예시**: mode 의 요청 시각 하나. 대상 고객 = **그 주에 산 고객 전부**(final 에서 68,984명). v1 의 `map_at_k` 는 정답이 있는 모든 고객을 분모로 두고 추천이 없으면 0으로 센다. 이력이 없거나 어휘 상품이 없는 고객도 빼지 않는다 — v2 는 프로필 · 요청 토큰만으로도 페이지를 만들 수 있고, 빼면 v1 · 선과 다른 집합을 채점하게 된다. `eval_meta` 에 `has_vocab_history`(이력에 어휘 상품이 있는가)를 두어 부분 집합 지표도 낼 수 있게 한다.

**저장 형식** (`OUT/<mode>/`)

| 파일 | 내용 |
|---|---|
| `vocab.json` | Vocab |
| `train.npz`, `eval.npz` | `ctx_tokens`(int32 평탄) · `ctx_offsets`(int64, n+1) · `ctx_content`(int32, 문맥 위치별 콘텐츠 행 번호, 없으면 −1) · `page_tokens` · `page_offsets` |
| `train_meta.parquet`, `eval_meta.parquet` | `customer_id` · `request_date` · `truth`(그 주에 산 article_id 목록, 어휘 밖 포함) · `history`(이력 article_id, 최근 것부터 최대 100) |

## 3. 콘텐츠 임베딩 (`genpage2/content.py`, A2)

원문의 의미 임베딩 융합에 쓴다. 상품마다 텍스트를 만들어 `intfloat/multilingual-e5-small`(로컬 HF 캐시, 384차원)로 벡터를 만든다. 오프라인으로만 읽는다(`HF_HUB_OFFLINE=1`). `transformers` 가 없으면 가상환경에 설치한다. **모델을 못 읽으면 다른 방법으로 조용히 대체하지 않고 실패한다.**

- 텍스트: `passage: {prod_name}. {product_type_name}, {product_group_name}. {colour_group_name}, {perceived_colour_value_name}. {section_name}, {garment_group_name}, {index_name}. {detail_desc}`
- 평균 풀링 + L2 정규화
- 저장: `OUT/content/content_e5.npy`(float16, [105542, 384], article_id 오름차순) · `OUT/content/articles.json`(행 번호 → article_id)
- `def load_content(out) -> (np.ndarray, dict[str, int])` — 행렬과 article_id → 행 번호

## 4. 모델 (`genpage2/model.py`, A3)

```python
@dataclass
class ModelConfig:
    vocab_size: int; dim: int = 256; layers: int = 6; heads: int = 8; ffn: int = 1024
    dropout: float = 0.1; maxlen: int = 256; content_dim: int = 384
    fallback_prob: float = 0.05          # 학습 중 상품 입력을 ITEM_FALLBACK 으로 바꿀 확률(원문의 대체 토큰)
    content_output: bool = True          # 출력에도 콘텐츠 점수를 더해 어휘 밖 상품을 점수 매길 수 있게

class GenPageV2(nn.Module):
    def __init__(self, cfg: ModelConfig, content: torch.Tensor | None)   # content: [n_articles, 384]
    def forward(self, tokens, content_idx) -> torch.Tensor               # B×T → B×T×dim (causal)
    def logits(self, hidden) -> torch.Tensor                             # B×T×vocab (분리된 출력 가중치)
    def item_content_logits(self, hidden, article_rows) -> torch.Tensor  # 어휘 밖 상품 점수(content_output)
```

- decoder-only(causal) transformer, pre-norm, 위치 임베딩 + 토큰 종류 임베딩(특수 · 프로필 · 요청 · 행동 · 시각 · 행 · 상품)
- **입력 · 출력 가중치 분리**(원문과 같다): 입력 `nn.Embedding`, 출력 `nn.Linear(dim, vocab, bias=False)`
- **의미 임베딩 융합**: 상품 위치의 입력 = ID 임베딩 + `Linear(384→dim)(content[row])`. `ITEM_FALLBACK` 위치도 콘텐츠를 더한다
- 크기 프리셋: `small`(dim 128 · 2층), `base`(256 · 6층), `large`(384 · 8층)

## 5. 사전학습 (`genpage2/train_pretrain.py`, A3)

- 입력 = 문맥 + 정답 페이지, 너무 길면 문맥의 **이력 앞쪽**을 자른다(특수 · 프로필 · 요청 토큰은 남긴다)
- 손실 = 다음 토큰 교차 엔트로피, **페이지 토큰을 예측하는 위치에만**(원문: 문맥이 프롬프트, 페이지가 응답)
- 대체 토큰: 입력의 상품 토큰을 `fallback_prob` 로 `ITEM_FALLBACK` 으로 바꾼다(정답은 그대로)
- 인자: `--mode validate|final --preset small|base|large --context full|history --epochs --max-steps --batch --lr`. `--context history` 는 프로필 · 요청 · 행동 · 시각 토큰을 빼고 상품 토큰만 남긴다(원문 발견 1의 절제)
- 저장: `OUT/<mode>/ckpt/<이름>/model.pt` · `config.json` · `train_log.jsonl`(단계 · 손실 · 초당 토큰)

## 6. 디코딩 (`genpage2/decode.py`, A4)

```python
@dataclass
class GeneratedRow: row_token: int; items: list[str]        # 상품은 article_id

class PageDecoder:
    def __init__(self, model, vocab, content_rows, device)
    def generate(self, ctx_tokens: list[int], ctx_content: list[int], *, history_articles: list[str],
                 prev_page: list[int] | None = None, exclude_items: set[str] = frozenset(),
                 exclude_rows: set[int] = frozenset(), pinned: dict[int, int] | None = None,
                 n_rows: int = 3, items_per_row: int = 8, prefix: int = 2) -> tuple[list[GeneratedRow], int]
                 # 반환: 행 목록, 규칙 위반 수(마스크가 맞으면 0)
    def generate_batch(...)   # 평가용 일괄
```

규칙은 매 단계 로짓 마스크로 지킨다(원문의 제약 디코딩).

- 행 자리: 행 토큰만. 이미 쓴 행 · `exclude_rows` 금지. `pinned[위치]` 가 있으면 그 행만. 뽑을 상품이 `min_items`(3) 미만인 행 금지
- 상품 자리: 그 행 소속만(`ROW_REPEAT` 는 `history_articles`), 페이지 안 중복 · `exclude_items` 금지. 이력 상품은 `ROW_REPEAT` 와 원래 섹션 행 **양쪽에** 속한다(`ROW_REPEAT` 는 소속을 바꾸는 것이 아니라 다시 보여 주는 추가 행이다). 한 페이지에 두 번 나오는 것은 중복 금지로 막힌다. 행 recall 도 둘 중 하나가 페이지에 있으면 맞춘 것으로 센다
- 문맥 자르기는 학습과 같다: `BOS ~ SEP_HISTORY` 와 `SEP_PAGE` 를 남기고 가장 오래된 이벤트부터 3토큰씩 뺀다. 생성할 페이지 길이만큼 자리를 먼저 비운다
- 로짓은 다음 토큰을 고를 위치에서만 계산한다(전 위치 × 어휘 8만 3천은 배치 256 에서 약 21GB)
- 하이브리드 행 디코딩: 행마다 앞 `prefix` 개는 한 칸씩, 나머지는 마지막 분포에서 한 번에 top-k. 뽑은 토큰은 시퀀스에 붙여 다음 행이 본다
- 쪽 나누기: `prev_page` 토큰을 `SEP_PAGE` 뒤에 붙이고 이어 생성한다
- WBC · RL 모델도 같은 디코더를 쓴다(로짓을 값으로 읽고 가장 큰 것을 고른다)

## 7. 평가 (`genpage2/evaluate.py`, A4)

`cd personalization && $PY -m genpage2.evaluate --mode validate --ckpt <경로> [--limit N]` → `OUT/<mode>/eval/<이름>.json`

- MAP@12: 페이지를 배치 순서로 편 앞 12개, `pipeline/features_hm.map_at_k` 를 그대로 쓴다
- 페이지 recall · 행 recall · 다양성(페이지 안 상품 쌍의 콘텐츠 코사인 거리 평균) · 규칙 위반 · 평균 행 수 · 상품 수 · 페이지당 생성 시간
- 기준선(같은 하네스): 마지막 구매 재추천(선 0.0234 재현), 지난주 인기, v1 엔진(`serving/genpage_server.Engine`, 0.0209 재현)

## 8. 후학습 · 증분 · 서빙 (B 뒤에 자세히 적는다)

- **WBC**(C1): 학습 고객 · 요청 시각마다 사전학습 모델로 페이지를 생성해 "노출"로 둔다. 노출된 상품 토큰의 라벨 = 다음 7일에 샀는가(부호), 가중치 = 산 횟수(양성) 또는 고정값(음성). 행 토큰의 보상 = 그 행 상품 보상의 합. 로짓에 가중 이진 교차 엔트로피
- **RL**(C2): 보상 모델(사전학습 체크포인트에서 시작, 페이지 보상 = 산 상품 수 예측) → Dr. GRPO(그룹 G 개 생성, 이점 = 보상 − 그룹 평균, 길이 정규화 없음) + 참조 모델 KL + 형식 보상
- **증분**(C3): 기준 시점까지 전체 학습 → 하루씩(그날 예시 + 과거 표본 10%) 이어 학습. 새 상품은 `ITEM_FALLBACK` 임베딩으로 시작
- **서빙**(C4): `serving/genpage2_server.py` — v1 과 같은 HTTP 계약(`/recommend`, `/page`)에 행 토큰 · 제목을 더한다. 앱은 `model.genpage-version` 과 실험 변형별 모델 주소를 받는다
