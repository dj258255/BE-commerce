"""가상 사용자와 반합성 노출 로그를 만든다.

H&M 에는 노출 로그가 없어서 원문 GenPage 의 "운영 정책이 보여 준 페이지(노출) +
사용자 반응 → 보상" 자리를 **반합성**으로 채운다. 가상 사용자 = (H&M 고객, 요청
시각 r) 이고, 그 사람이 [r, r+7) 에 실제로 산 상품을 `wanted` 로 삼아 취향을
센다(personas.py 와 같은 규칙). 상품 속성은 articles.parquet 에서 읽는다.

반응 확률은 `tools/virtual_users/policies.py` 의 `RulePolicy` 와 **같은 가중치 ·
곡선**을 쓴다(정의는 그 한 곳에만 있다). 다만 RulePolicy 는 주의를 클릭 확률에
접어 넣어 `unseen` 과 `skip` 을 구분하지 않는다. 여기서는 주의 → 클릭 → 구매를
차례로 뽑는다. 주의와 클릭이 독립이므로 클릭 · 구매의 주변 확률은 RulePolicy 와
같고, 덤으로 `unseen`(주의 없음) 과 `skip`(봤지만 안 누름) 이 나뉜다.

난수는 `random.Random(config.SEED ^ stable_hash(customer, r))` 로 결정적이다.
`stable_hash` 는 프로세스마다 값이 달라지는 파이썬 내장 ``hash`` 대신 blake2b 를 쓴다.

요청 시각 r 은 그 mode 의 학습 구간에서만 고른다(`r + TARGET_DAYS <= 기준 시각`).
문맥 이력은 언제나 r 이전 구매만 넣는다.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import random
import time
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol, Sequence

import numpy as np
import pandas as pd

from . import config
from .dataset import _context_from_arrays, _customer_event_groups, price_bucket, profile_tokens
from .decode import GeneratedRow
from .reward import RewardConfig, item_reward, page_reward, row_reward
from .vocab import Vocab, _article_id, content_rows


FEEDBACK_KINDS = ("unseen", "skip", "click", "buy")
TRANSACTION_COLUMNS = ("t_dat", "customer_id", "article_id", "sales_channel_id", "price")


# ---------------------------------------------------------------------------
# 결정적 난수 · 기기
# ---------------------------------------------------------------------------

def stable_hash(*parts: Any) -> int:
    """프로세스마다 달라지는 내장 hash 대신 쓰는 결정적 해시."""
    payload = "\x1f".join(str(part) for part in parts).encode("utf-8")
    return int.from_bytes(hashlib.blake2b(payload, digest_size=8).digest(), "big")


def _day_string(value: Any) -> str:
    return pd.Timestamp(value).date().isoformat()


def _day(value: Any) -> int:
    return int(pd.Timestamp(value).to_datetime64().astype("datetime64[D]").astype(np.int64))


def rng_for(customer_id: Any, request_date: Any) -> random.Random:
    """`random.Random(SEED ^ hash(customer, r))`. 한 사용자에 난수 하나."""
    return random.Random(config.SEED ^ stable_hash(str(customer_id), _day_string(request_date)))


def device_of(customer_id: Any) -> str:
    """고객 id 해시로 기기를 정한다(결정적)."""
    return "mobile" if stable_hash("device", str(customer_id)) % 2 == 0 else "pc"


# ---------------------------------------------------------------------------
# 반응 모델 (RulePolicy 의 가중치 · 곡선)
# ---------------------------------------------------------------------------

_POLICIES: tuple[type, Any] | None = None


def _virtual_user_policies() -> tuple[type, Any]:
    global _POLICIES
    if _POLICIES is None:
        path = Path(__file__).resolve().parents[2] / "tools" / "virtual_users" / "policies.py"
        spec = importlib.util.spec_from_file_location("genpage2_virtual_user_policies", path)
        if spec is None or spec.loader is None:
            raise ImportError(f"cannot load {path}")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        _POLICIES = (module.RulePolicy, module.sigmoid)
    return _POLICIES


def rule_policy_class() -> type:
    """`tools/virtual_users/policies.py` 의 RulePolicy 클래스(테스트가 상속한다)."""
    return _virtual_user_policies()[0]


class ReactionModel:
    """RulePolicy 의 가중치 · 곡선으로 주의 → 클릭 → 구매를 뽑는다."""

    def __init__(self, policy: Any | None = None) -> None:
        rule_policy, sigmoid = _virtual_user_policies()
        self.policy = rule_policy() if policy is None else policy
        self.sigmoid = sigmoid

    def attention(self, rank: int, pos: int) -> float:
        """페이지의 행 순서 rank · 행 안 위치 pos 에서 이 상품을 볼 확률."""
        return 1.0 / (1.0 + self.policy.ROW_DECAY * rank) / (1.0 + self.policy.POS_DECAY * pos)

    def feedback(self, persona: Any, item: dict, rank: int, pos: int, rng: random.Random) -> str:
        if rng.random() >= self.attention(rank, pos):
            return "unseen"
        thought = self.policy.score(persona, item)
        if rng.random() >= self.sigmoid(thought - self.policy.CLICK_BIAS):
            return "skip"
        if rng.random() < self.sigmoid(thought - self.policy.BUY_BIAS):
            return "buy"
        return "click"


# ---------------------------------------------------------------------------
# 가상 사용자 persona
# ---------------------------------------------------------------------------

@dataclass
class SimPersona:
    """RulePolicy.score 가 읽는 모양(wanted · liked_types · liked_colours · liked_categories)."""

    customer_id: str
    wanted: list[str]
    liked_types: dict[str, int]
    liked_colours: dict[str, int]
    liked_categories: dict[str, int]


def persona_of(customer_id: str, wanted: Sequence[str], attributes: dict[str, tuple]) -> SimPersona:
    """personas.py 와 같은 규칙: 취향은 wanted 에서만 센다."""
    types: Counter[str] = Counter()
    colours: Counter[str] = Counter()
    categories: Counter[str] = Counter()
    for article in dict.fromkeys(wanted):
        entry = attributes.get(article)
        if entry is None:
            continue
        for counter, value in zip((types, colours, categories), entry, strict=True):
            if isinstance(value, str) and value:
                counter[value] += 1
    return SimPersona(str(customer_id), list(dict.fromkeys(wanted)),
                      dict(types), dict(colours), dict(categories))


def item_view(article: str, attributes: dict[str, tuple]) -> dict:
    """RulePolicy.score 가 읽는 보인 상품 하나."""
    ptype, colour, category = attributes.get(article, (None, None, None))
    return {"id": article, "type": ptype, "colour": colour, "category": category}


def load_attributes(articles: pd.DataFrame) -> dict[str, tuple]:
    """article_id → (product_type_name, colour_group_name, index_group_name)."""
    result: dict[str, tuple] = {}
    for row in articles.itertuples(index=False):
        result[_article_id(row.article_id)] = (getattr(row, "product_type_name", None),
                                               getattr(row, "colour_group_name", None),
                                               getattr(row, "index_group_name", None))
    return result


class PriceIndex:
    """세션 행동 이벤트의 `PRICE_구간` 을 정한다.

    화면에 보이는 가격이라는 데이터가 없어서, **요청 시각 r 이전** 거래만 보고 그
    상품의 가장 최근 관측 가격을 쓴다. 그 r 이전에 그 상품 거래가 없으면 r 이전
    전체 가격의 중앙값으로 대체한다. r 마다 따로 구하므로 요청 뒤 가격이 세션
    행동에 섞이지 않는다.
    """

    def __init__(self, vocab: Vocab, frame: pd.DataFrame | None = None) -> None:
        self.vocab = vocab
        self._by_article: dict[str, tuple[np.ndarray, np.ndarray]] = {}
        self._days = np.empty(0, dtype=np.int64)
        self._prices = np.empty(0, dtype=float)
        self._cache: dict[tuple[str, int], float] = {}
        if frame is not None and len(frame):
            self._build(frame)

    def _build(self, frame: pd.DataFrame) -> None:
        ordered = frame.sort_values("day", kind="mergesort")
        self._days = ordered["day"].to_numpy(dtype=np.int64)
        self._prices = ordered["price"].to_numpy(dtype=float)
        for article, group in ordered.groupby("article_id", sort=False):
            self._by_article[str(article)] = (group["day"].to_numpy(dtype=np.int64),
                                              group["price"].to_numpy(dtype=float))

    @classmethod
    def from_transactions(cls, vocab: Vocab, transactions: pd.DataFrame, reference: Any) -> "PriceIndex":
        if transactions.empty:
            return cls(vocab)
        dates = pd.to_datetime(transactions["t_dat"])
        train = dates < pd.Timestamp(reference)
        if not bool(train.any()):
            return cls(vocab)
        frame = pd.DataFrame({"article_id": transactions.loc[train, "article_id"].map(_article_id),
                              "price": pd.to_numeric(transactions.loc[train, "price"], errors="coerce"),
                              "day": dates[train].to_numpy().astype("datetime64[D]").astype(np.int64)}).dropna()
        if frame.empty:
            return cls(vocab)
        return cls(vocab, frame)

    def price_as_of(self, article_id: str, request_date: Any) -> float:
        """요청 시각 r 이전 거래만 본 가격(그 상품의 최근 관측, 없으면 r 이전 중앙값)."""
        day = _day(request_date)
        article = _article_id(article_id)
        key = (article, day)
        if key in self._cache:
            return self._cache[key]
        value = None
        days, prices = self._by_article.get(article, (None, None))
        if days is not None:
            index = int(np.searchsorted(days, day, side="left")) - 1
            if index >= 0:
                value = float(prices[index])
        if value is None:
            cut = int(np.searchsorted(self._days, day, side="left"))
            if cut > 0:
                value = float(np.median(self._prices[:cut]))
        value = 0.0 if value is None else value
        self._cache[key] = value
        return value

    def bucket(self, article_id: str, request_date: Any | None = None) -> int:
        value = 0.0 if request_date is None else self.price_as_of(article_id, request_date)
        return int(price_bucket(self.vocab, value))


# ---------------------------------------------------------------------------
# 사용자 · 요청 · 로그
# ---------------------------------------------------------------------------

@dataclass
class SimUser:
    customer_id: str
    request_date: pd.Timestamp
    ctx_tokens: list[int]
    ctx_content: list[int]
    history: list[str]
    wanted: list[str]
    device: str
    persona: SimPersona


@dataclass
class PageRequest:
    """운영 정책(PageSource)에 주는 한 쪽 요청."""

    customer_id: str
    request_date: pd.Timestamp
    ctx_tokens: list[int]
    ctx_content: list[int]
    history: list[str]
    prev_page: list[int] = field(default_factory=list)
    truth_items: list[str] = field(default_factory=list)


@dataclass
class Impression:
    customer_id: str
    request_date: pd.Timestamp
    page_no: int
    row_rank: int
    row_token: int
    pos: int
    article_id: str
    item_token: int
    feedback: str
    reward: float
    device: str


@dataclass
class PageLog:
    customer_id: str
    request_date: pd.Timestamp
    page_no: int
    device: str
    ctx_tokens: list[int]
    ctx_content: list[int]
    prev_tokens: list[int]
    page_tokens: list[int]
    token_reward: list[float]
    page_reward: float
    impressions: list[Impression]


# ---------------------------------------------------------------------------
# 운영 정책 (노출을 만드는 쪽)
# ---------------------------------------------------------------------------

class PageSource(Protocol):
    def pages(self, examples: list[PageRequest]) -> list[list[GeneratedRow]]: ...


class TruthShuffleSource:
    """테스트 · 스모크용 운영 정책. 정답 페이지(wanted)의 행 순서를 섞어 보여 준다."""

    name = "truth-shuffle"

    def __init__(self, vocab: Vocab) -> None:
        self.vocab = vocab

    def pages(self, examples: list[PageRequest]) -> list[list[GeneratedRow]]:
        return [self._page(example) for example in examples]

    def _page(self, example: PageRequest) -> list[GeneratedRow]:
        history = set(example.history)
        rows: dict[int, list[str]] = {}
        for article in example.truth_items:
            if self.vocab.item(article) is None:
                continue
            row = self.vocab.id("ROW_REPEAT") if article in history else self.vocab.row_of(article)
            rows.setdefault(row, []).append(article)
        ranked = sorted(rows.items(), key=lambda pair: (-len(pair[1]), pair[0]))[:config.MAX_ROWS]
        rng = random.Random(stable_hash("truth-shuffle", example.customer_id, _day_string(example.request_date)))
        rng.shuffle(ranked)
        return [GeneratedRow(int(row), list(items[:config.ITEMS_PER_ROW])) for row, items in ranked]


class CkptSource:
    """사전학습 체크포인트를 운영 정책으로 쓴다(원문: 운영 정책의 노출).

    탐욕 생성 · 6행 × 8개 · 체크포인트의 문맥 수준(디코더가 적용한다).
    """

    name = "ckpt"

    def __init__(self, decoder: Any, batch: int = 256) -> None:
        self.decoder = decoder
        self.batch = max(1, int(batch))

    def pages(self, examples: list[PageRequest]) -> list[list[GeneratedRow]]:
        result: list[list[GeneratedRow]] = []
        for start in range(0, len(examples), self.batch):
            part = examples[start:start + self.batch]
            payload = [{"ctx_tokens": list(example.ctx_tokens), "ctx_content": list(example.ctx_content),
                        "history_articles": list(example.history), "prev_page": list(example.prev_page)}
                       for example in part]
            decoded = self.decoder.generate_batch(payload, n_rows=config.MAX_ROWS,
                                                  items_per_row=config.ITEMS_PER_ROW, prefix=2)
            if len(decoded) != len(part):
                raise ValueError("decoder returned a different number of pages than requests")
            result.extend(rows for rows, _ in decoded)
        return result


# ---------------------------------------------------------------------------
# 반응 · 로그
# ---------------------------------------------------------------------------

def _item_token(vocab: Vocab, article: str) -> int:
    token = vocab.item(article)
    if token is None:
        raise ValueError(f"page contains an out-of-vocabulary article {article!r}")
    return int(token)


def _react(vocab: Vocab, rows: list[GeneratedRow], user: SimUser, page_no: int, rng: random.Random,
           reaction: ReactionModel, attributes: dict[str, tuple], reward_cfg: RewardConfig) -> list[Impression]:
    impressions: list[Impression] = []
    for rank, row in enumerate(rows):
        for pos, article in enumerate(row.items):
            feedback = reaction.feedback(user.persona, item_view(article, attributes), rank, pos, rng)
            impressions.append(Impression(str(user.customer_id), user.request_date, int(page_no), int(rank),
                                          int(row.row_token), int(pos), str(article),
                                          _item_token(vocab, article), feedback,
                                          float(item_reward(feedback, reward_cfg)), user.device))
    return impressions


def _page_log(vocab: Vocab, user: SimUser, page_no: int, ctx_tokens: Sequence[int],
              ctx_content: Sequence[int], prev_tokens: Sequence[int],
              impressions: list[Impression]) -> PageLog:
    grouped: dict[int, list[Impression]] = {}
    for impression in impressions:
        grouped.setdefault(impression.row_rank, []).append(impression)
    page_tokens: list[int] = []
    token_reward: list[float] = []
    for rank in sorted(grouped):
        values = sorted(grouped[rank], key=lambda item: item.pos)
        page_tokens.append(int(values[0].row_token))
        token_reward.append(float(row_reward([item.reward for item in values])))
        for impression in values:
            page_tokens.append(int(impression.item_token))
            token_reward.append(float(impression.reward))
    page_tokens.append(int(vocab.id("EOS")))
    token_reward.append(0.0)
    return PageLog(str(user.customer_id), user.request_date, int(page_no), user.device,
                   list(ctx_tokens), list(ctx_content), list(prev_tokens), page_tokens, token_reward,
                   float(page_reward([item.reward for item in impressions])), list(impressions))


def session_tokens(impressions: Sequence[Impression], *, vocab: Vocab, prices: PriceIndex,
                   content_rows_map: dict[str, int]) -> tuple[list[int], list[int]]:
    """1쪽 반응을 2쪽 프롬프트의 최근 이벤트로 붙인다(클릭 → ACT_CLICK, 봄 → ACT_VIEW).

    클릭한 상품은 `[상품][ACT_CLICK][AGO_0-3][PRICE_구간]`, 봤지만 안 누른 상품은
    `[상품][ACT_VIEW][AGO_0-3][PRICE_구간]` 이 된다. 안 본 상품은 넣지 않는다.
    """
    tokens: list[int] = []
    content: list[int] = []
    click_token = vocab.id("ACT_CLICK")
    view_token = vocab.id("ACT_VIEW")
    ago_token = vocab.id("AGO_0-3")
    for impression in impressions:
        if impression.feedback in ("click", "buy"):
            action = click_token
        elif impression.feedback == "skip":
            action = view_token
        else:
            continue
        tokens.extend([int(impression.item_token), int(action), int(ago_token),
                       int(prices.bucket(impression.article_id, impression.request_date))])
        content.extend([int(content_rows_map.get(impression.article_id, -1)), -1, -1, -1])
    return tokens, content


def _page_prefix(vocab: Vocab, rows: Sequence[GeneratedRow]) -> list[int]:
    """앞 쪽 페이지 토큰(`[행][상품…]…`, EOS 없음)만.

    세션 행동은 여기 넣지 않는다 — 원문 쪽 나누기에서 세션 행동은 이력의 최근
    이벤트로, 앞 쪽 페이지 토큰은 `SEP_PAGE` 뒤 이어 쓰기(prefix)로 들어간다.
    """
    tokens: list[int] = []
    for row in rows:
        tokens.append(int(row.row_token))
        tokens.extend(_item_token(vocab, article) for article in row.items)
    return tokens


def simulate(users: Sequence[SimUser], source: PageSource, *, vocab: Vocab,
             attributes: dict[str, tuple], content_rows_map: dict[str, int],
             reaction: ReactionModel | None = None, prices: PriceIndex | None = None,
             reward_cfg: RewardConfig | None = None) -> list[PageLog]:
    """가상 사용자마다 1쪽을 보여 주고, 안 샀으면 NEXT_PAGE 확률로 2쪽을 이어 본다."""
    reaction = reaction if reaction is not None else ReactionModel()
    reward_cfg = reward_cfg if reward_cfg is not None else RewardConfig()
    prices = prices if prices is not None else PriceIndex(vocab)
    pages: list[PageLog] = []

    sep_page = int(vocab.id("SEP_PAGE"))
    first = source.pages([PageRequest(user.customer_id, user.request_date, list(user.ctx_tokens),
                                      list(user.ctx_content), list(user.history), [], list(user.wanted))
                          for user in users])
    if len(first) != len(users):
        raise ValueError("page source returned a different number of pages than requests")

    pending: list[tuple[SimUser, PageRequest, random.Random]] = []
    for user, rows in zip(users, first):
        rng = rng_for(user.customer_id, user.request_date)
        impressions = _react(vocab, rows, user, 1, rng, reaction, attributes, reward_cfg)
        pages.append(_page_log(vocab, user, 1, user.ctx_tokens, user.ctx_content, [], impressions))
        if any(impression.feedback == "buy" for impression in impressions):
            continue
        if rng.random() >= reaction.policy.NEXT_PAGE:
            continue
        # 2쪽 문맥 = 1쪽 문맥의 SEP_PAGE 바로 앞(가장 최근 이벤트 자리)에 세션 행동을 끼운 것.
        # 이력이 60개를 넘으면 가장 오래된 이벤트부터 빠지는 건 context.truncate 가 한다.
        events, event_content = session_tokens(impressions, vocab=vocab, prices=prices,
                                               content_rows_map=content_rows_map)
        ctx_tokens = [int(token) for token in user.ctx_tokens]
        ctx_content = [int(value) for value in user.ctx_content]
        if not ctx_tokens or ctx_tokens[-1] != sep_page:
            raise AssertionError("page-2 context must end with SEP_PAGE")
        ctx_tokens = ctx_tokens[:-1] + events + [sep_page]
        ctx_content = ctx_content[:-1] + event_content + [-1]
        # prev_page 는 앞 쪽의 행 · 상품 토큰만. 디코더가 SEP_PAGE 뒤에 이어 쓴다.
        prev_tokens = _page_prefix(vocab, rows)
        shown = {article for row in rows for article in row.items}
        request = PageRequest(user.customer_id, user.request_date, ctx_tokens, ctx_content,
                              list(user.history), prev_tokens,
                              [article for article in user.wanted if article not in shown])
        pending.append((user, request, rng))

    if pending:
        second = source.pages([request for _, request, _ in pending])
        if len(second) != len(pending):
            raise ValueError("page source returned a different number of pages than requests")
        for (user, request, rng), rows in zip(pending, second):
            impressions = _react(vocab, rows, user, 2, rng, reaction, attributes, reward_cfg)
            pages.append(_page_log(vocab, user, 2, request.ctx_tokens, request.ctx_content,
                                   request.prev_page, impressions))
    return pages


# ---------------------------------------------------------------------------
# 저장
# ---------------------------------------------------------------------------

_IMPRESSION_DTYPES = {
    "customer_id": "object",
    "request_date": "datetime64[ns]",
    "page_no": "int16",
    "row_rank": "int16",
    "row_token": "int32",
    "pos": "int16",
    "article_id": "object",
    "item_token": "int32",
    "feedback": "object",
    "reward": "float32",
    "device": "object",
}
IMPRESSION_COLUMNS = tuple(_IMPRESSION_DTYPES)


def impressions_frame(pages: Sequence[PageLog]) -> pd.DataFrame:
    records = [{"customer_id": impression.customer_id, "request_date": impression.request_date,
                "page_no": impression.page_no, "row_rank": impression.row_rank,
                "row_token": impression.row_token, "pos": impression.pos,
                "article_id": impression.article_id, "item_token": impression.item_token,
                "feedback": impression.feedback, "reward": impression.reward,
                "device": impression.device}
               for page in pages for impression in page.impressions]
    frame = pd.DataFrame(records, columns=list(IMPRESSION_COLUMNS))
    return frame.astype(_IMPRESSION_DTYPES)


def _stack(sequences: Sequence[Sequence[Any]], dtype: Any) -> tuple[np.ndarray, np.ndarray]:
    offsets = np.zeros(len(sequences) + 1, dtype=np.int64)
    if sequences:
        offsets[1:] = np.cumsum([len(sequence) for sequence in sequences], dtype=np.int64)
        flat = np.concatenate([np.asarray(sequence, dtype=dtype) for sequence in sequences])
    else:
        flat = np.empty(0, dtype=dtype)
    return flat, offsets


def summarize(pages: Sequence[PageLog], elapsed: float) -> dict[str, Any]:
    impressions = [impression for page in pages for impression in page.impressions]
    total = len(impressions)
    counts = Counter(impression.feedback for impression in impressions)
    feedback_ratio = {name: (counts.get(name, 0) / total if total else 0.0) for name in FEEDBACK_KINDS}

    def rates(attribute: str) -> dict[str, dict[str, float]]:
        buckets: dict[Any, list[Impression]] = {}
        for impression in impressions:
            buckets.setdefault(getattr(impression, attribute), []).append(impression)
        return {str(value): {"impressions": len(values),
                             "click_rate": sum(i.feedback in ("click", "buy") for i in values) / len(values),
                             "buy_rate": sum(i.feedback == "buy" for i in values) / len(values)}
                for value, values in sorted(buckets.items())}

    rewards = np.asarray([page.page_reward for page in pages], dtype=float)

    def percentile(q: float) -> float:
        return float(np.percentile(rewards, q)) if rewards.size else 0.0

    first = [page for page in pages if page.page_no == 1]
    second = [page for page in pages if page.page_no == 2]
    devices = Counter(page.device for page in first)
    users = len(first)
    return {
        "users": users,
        "pages": len(pages),
        "impressions": total,
        "feedback_ratio": feedback_ratio,
        "by_row_rank": rates("row_rank"),
        "by_pos": rates("pos"),
        "page_reward": {"p10": percentile(10), "p50": percentile(50), "p90": percentile(90)},
        "page2_ratio": (len(second) / users if users else 0.0),
        "device_ratio": {name: (devices.get(name, 0) / users if users else 0.0) for name in ("mobile", "pc")},
        "elapsed_seconds": float(elapsed),
    }


def write_outputs(directory: Path, pages: Sequence[PageLog], stats: dict[str, Any]) -> None:
    """`OUT/<mode>/impressions/<이름>/` 에 세 파일을 쓴다."""
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    impressions_frame(pages).to_parquet(directory / "impressions.parquet", index=False)
    ctx_tokens, ctx_offsets = _stack([page.ctx_tokens for page in pages], np.int32)
    ctx_content, _ = _stack([page.ctx_content for page in pages], np.int32)
    prev_tokens, prev_offsets = _stack([page.prev_tokens for page in pages], np.int32)
    page_tokens, page_offsets = _stack([page.page_tokens for page in pages], np.int32)
    token_reward, _ = _stack([page.token_reward for page in pages], np.float32)
    np.savez_compressed(
        directory / "pages.npz",
        ctx_tokens=ctx_tokens, ctx_offsets=ctx_offsets, ctx_content=ctx_content,
        prev_tokens=prev_tokens, prev_offsets=prev_offsets,
        page_tokens=page_tokens, page_offsets=page_offsets, token_reward=token_reward,
        customer_id=np.asarray([page.customer_id for page in pages], dtype=str),
        request_date=np.asarray([page.request_date for page in pages], dtype="datetime64[ns]"),
        page_no=np.asarray([page.page_no for page in pages], dtype=np.int8),
    )
    (directory / "stats.json").write_text(json.dumps(stats, ensure_ascii=False, indent=2) + "\n",
                                          encoding="utf-8")


# ---------------------------------------------------------------------------
# 사용자 만들기 (원본 거래 → 가상 사용자)
# ---------------------------------------------------------------------------

def candidate_dates(reference: Any, dates: int) -> list[pd.Timestamp]:
    """그 mode 의 학습 구간에서 고른 요청 시각. `r + 7일 <= 기준 시각`."""
    if dates < 1:
        raise ValueError("--dates must be positive")
    return [pd.Timestamp(reference) - pd.Timedelta(days=7 * k) for k in range(1, dates + 1)]


def build_users(vocab: Vocab, transactions: pd.DataFrame, customers: pd.DataFrame,
                attributes: dict[str, tuple], content_rows_map: dict[str, int],
                request_dates: Sequence[Any], reference: Any) -> list[SimUser]:
    """(고객, r) 마다 wanted · 이력 · 문맥을 만든다. 이력은 r 이전 구매만."""
    reference = pd.Timestamp(reference)
    for request in request_dates:
        if pd.Timestamp(request) + pd.Timedelta(days=config.TARGET_DAYS) > reference:
            raise ValueError(f"request date {request} is not inside the mode's training period")
    profiles = {str(row.customer_id): row._asdict() for row in customers.itertuples(index=False)}
    users: list[SimUser] = []
    for customer_id, events in _customer_event_groups(transactions, vocab, content_rows_map):
        profile_ids = profile_tokens(vocab, profiles.get(str(customer_id)))
        dates = events["dates"]
        articles = events["articles"]
        for request in request_dates:
            request = pd.Timestamp(request)
            start = int(dates.searchsorted(_day(request), side="left"))
            end = int(dates.searchsorted(_day(request) + config.TARGET_DAYS, side="left"))
            if end <= start:
                continue
            wanted = list(dict.fromkeys(articles[start:end].tolist()))
            ctx, ctx_content = _context_from_arrays(vocab, request, profile_ids, dates[:start], articles[:start],
                                                    events["item_tokens"][:start], events["channels"][:start],
                                                    events["content"][:start], events["prices"][:start])
            history = articles[max(0, start - 100):start][::-1].tolist()
            users.append(SimUser(str(customer_id), request, ctx.tolist(), ctx_content.tolist(), history, wanted,
                                 device_of(customer_id), persona_of(str(customer_id), wanted, attributes)))
    return users


def _window_buyers(path: Path, start: Any, end: Any) -> set[str]:
    import pyarrow.parquet as pq

    buyers: set[str] = set()
    for batch in pq.ParquetFile(path).iter_batches(columns=["t_dat", "customer_id"], batch_size=262_144):
        part = batch.to_pandas()
        dates = pd.to_datetime(part["t_dat"])
        mask = (dates >= pd.Timestamp(start)) & (dates < pd.Timestamp(end))
        if bool(mask.any()):
            buyers.update(part.loc[mask, "customer_id"].astype(str).tolist())
    return buyers


def _customer_transactions(path: Path, chosen: set[str], reference: Any) -> pd.DataFrame:
    import pyarrow.parquet as pq

    parts = []
    for batch in pq.ParquetFile(path).iter_batches(columns=list(TRANSACTION_COLUMNS), batch_size=262_144):
        part = batch.to_pandas()
        part["t_dat"] = pd.to_datetime(part["t_dat"])
        part = part[(part["t_dat"] < pd.Timestamp(reference)) & part["customer_id"].astype(str).isin(chosen)]
        if not part.empty:
            parts.append(part)
    if not parts:
        return pd.DataFrame(columns=list(TRANSACTION_COLUMNS))
    return pd.concat(parts, ignore_index=True)


def load_transactions(path: Path, request_dates: Sequence[Any], reference: Any, customers_n: int) -> pd.DataFrame:
    """요청 창에서 산 고객을 `customers_n` 명 뽑아 그들의 학습 구간 거래만 읽는다."""
    buyers = _window_buyers(path, min(pd.Timestamp(request) for request in request_dates), reference)
    if not buyers:
        raise ValueError("no customer bought in the requested windows")
    ids = np.array(sorted(buyers))
    chosen = {str(value) for value in np.random.default_rng(config.SEED).choice(
        ids, size=min(int(customers_n), len(ids)), replace=False)}
    return _customer_transactions(path, chosen, reference)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def run(args: argparse.Namespace) -> tuple[list[PageLog], dict[str, Any]]:
    started = time.monotonic()
    base = Path(args.data_dir) if args.data_dir else config.data_dir()
    mode_dir = base / "hm" / "model" / "genpage2" / args.mode
    normalized = base / "hm" / "normalized"
    reference = config.request_of(args.mode)

    vocab = Vocab.load(mode_dir / "vocab.json")
    articles = pd.read_parquet(normalized / "articles.parquet")
    attributes = load_attributes(articles)
    article_content_rows = content_rows(articles)

    request_dates = candidate_dates(reference, args.dates)
    transactions = load_transactions(normalized / "transactions.parquet", request_dates, reference, args.customers)
    customers = pd.read_parquet(normalized / "customers.parquet")
    users = build_users(vocab, transactions, customers, attributes, article_content_rows, request_dates, reference)
    prices = PriceIndex.from_transactions(vocab, transactions, reference)

    if args.source == "truth-shuffle":
        source: PageSource = TruthShuffleSource(vocab)
    elif args.source == "ckpt":
        if not args.ckpt:
            raise ValueError("--source ckpt requires --ckpt DIR")
        from .evaluate import _load_decoder

        decoder, _, _, _, _ = _load_decoder(mode_dir, Path(args.ckpt), args.device)
        source = CkptSource(decoder, batch=args.batch)
    else:
        raise ValueError(f"unknown source {args.source!r}")

    pages = simulate(users, source, vocab=vocab, attributes=attributes,
                     content_rows_map=article_content_rows, prices=prices)
    stats = summarize(pages, time.monotonic() - started)
    stats.update({"mode": args.mode, "source": args.source, "name": args.name,
                  "customers_requested": int(args.customers), "dates": int(args.dates)})
    return pages, stats


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", required=True, choices=sorted(config.MODES))
    parser.add_argument("--source", required=True, choices=("ckpt", "truth-shuffle"))
    parser.add_argument("--ckpt")
    parser.add_argument("--customers", type=int, required=True)
    parser.add_argument("--dates", type=int, required=True)
    parser.add_argument("--name")
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--batch", type=int, default=256)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--data-dir")
    args = parser.parse_args(argv)
    if not args.name:
        args.name = f"{args.source}-{args.customers}x{args.dates}"
    pages, stats = run(args)
    out_root = Path(args.out) if args.out else config.out_dir()
    write_outputs(out_root / args.mode / "impressions" / args.name, pages, stats)
    print(json.dumps(stats, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
