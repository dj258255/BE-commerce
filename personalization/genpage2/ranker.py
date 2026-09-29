"""업계 표준(여러 출처 후보 + 특징 + LightGBM LambdaRank) 기준선.

STATUS.md 의 "D2 GenPage 대 업계 표준" 절에 **측정 전에** 적은 후보 · 특징 ·
하이퍼파라미터를 그대로 구현한다. 요청 시각 r 이전 거래만 쓴다.

- 후보: C1 재구매(4주) · C2 전체 인기(7일 상위 100) · C3 섹션 인기(7일 상위 20)
  · C4 비슷한 상품(e5 최근 5개 각 20개) · C5 같이 산 상품(4주 안에 산 상품마다
  같은 고객 · 같은 날 함께 산 상품 상위 10)
- 특징: 출처 5 + 고객×상품 3 + 상품 7 + 고객 4 + 교차 3
- 라벨: [r, r + 7일) 에 샀는가
- 학습: r − 7 · r − 14 · r − 21 세 주. r − 14 · r − 21 로 학습하고 r − 7 로
  조기 종료한 뒤, 그 반복 수로 세 주를 합쳐 다시 학습한다.
"""
from __future__ import annotations

import argparse
import gzip
import json
import time
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from . import config
from .candidates import _as_articles, build_similar_candidates, popular_candidates
from .evaluate import evaluate_pages, load_eval_assets

SOURCE_NAMES = ("C1", "C2", "C3", "C4", "C5")

# STATUS 에 적은 값이 base 다. 나머지 둘은 검증 표본에서 볼 수 있는 대안 설정이다.
RANKER_CONFIGS: dict[str, dict[str, Any]] = {
    "base": {
        "c1_days": 28, "c2_top": 100, "c3_top": 20, "c4_recent": 5, "c4_neighbors": 20,
        "c5_top": 10, "c5_days": 28,
        "num_leaves": 63, "learning_rate": 0.05, "n_estimators": 1000,
        "min_child_samples": 50, "feature_fraction": 0.8, "bagging_fraction": 0.8,
        "max_train_customers": 100_000, "negative_ratio": 20, "early_stopping": 50, "seed": 7,
    },
    "wide": {
        "c1_days": 28, "c2_top": 200, "c3_top": 40, "c4_recent": 5, "c4_neighbors": 40,
        "c5_top": 20, "c5_days": 28,
        "num_leaves": 127, "learning_rate": 0.03, "n_estimators": 1000,
        "min_child_samples": 50, "feature_fraction": 0.8, "bagging_fraction": 0.8,
        "max_train_customers": 100_000, "negative_ratio": 20, "early_stopping": 50, "seed": 7,
    },
    "narrow": {
        "c1_days": 28, "c2_top": 50, "c3_top": 10, "c4_recent": 5, "c4_neighbors": 10,
        "c5_top": 5, "c5_days": 28,
        "num_leaves": 31, "learning_rate": 0.05, "n_estimators": 1000,
        "min_child_samples": 20, "feature_fraction": 0.8, "bagging_fraction": 0.8,
        "max_train_customers": 100_000, "negative_ratio": 20, "early_stopping": 50, "seed": 7,
    },
}
DEFAULT_CONFIG = "base"

FEATURE_NAMES = (
    "src_C1", "src_C2", "src_C3", "src_C4", "src_C5",
    "cust_item_count", "cust_item_count_4w", "cust_item_days_since_last",
    "item_sales_1d", "item_sales_7d", "item_sales_28d", "item_trend_7_28",
    "item_price_median", "item_section", "item_days_since_first_sale",
    "cust_age", "cust_count", "cust_count_4w", "cust_days_since_last", "cust_avg_price",
    "price_ratio", "cust_section_share", "max_cosine_recent5",
)


def _day_number(value: object) -> int:
    return int(pd.Timestamp(value).to_datetime64().astype("datetime64[D]").astype(np.int64))


def _article_ids(values: pd.Series) -> np.ndarray:
    """10자리 문자열 id 로 맞춘다(숫자는 앞을 0으로 채운다)."""
    text = values.astype(str)
    digits = text.str.fullmatch(r"\d+").fillna(False)
    return np.where(digits.to_numpy(), text.str.zfill(10).to_numpy(), text.to_numpy())


@dataclass
class Transactions:
    """거래를 정수 코드로 압축해 들고 있는다. 문자열 열은 여기서 한 번만 만든다."""

    day: np.ndarray
    cust: np.ndarray
    art: np.ndarray
    price: np.ndarray
    customers: np.ndarray
    articles: np.ndarray

    def __post_init__(self) -> None:
        self._customer_code = {str(c): i for i, c in enumerate(self.customers)}
        self._article_code = {str(a): i for i, a in enumerate(self.articles)}

    @property
    def customer_codes(self) -> dict[str, int]:
        return self._customer_code

    @property
    def article_codes(self) -> dict[str, int]:
        return self._article_code

    @classmethod
    def from_frame(cls, frame: pd.DataFrame) -> "Transactions":
        if "t_dat" not in frame or "customer_id" not in frame or "article_id" not in frame:
            raise ValueError("transactions 에 t_dat · customer_id · article_id 열이 필요합니다")
        day = pd.to_datetime(frame["t_dat"]).to_numpy().astype("datetime64[D]").astype(np.int64)
        cust, customers = pd.factorize(frame["customer_id"].astype(str), sort=True)
        art, articles = pd.factorize(pd.Series(_article_ids(frame["article_id"])), sort=True)
        if "price" in frame:
            price = pd.to_numeric(frame["price"], errors="coerce").to_numpy(dtype=np.float32)
        else:
            price = np.zeros(len(frame), dtype=np.float32)
        return cls(day.astype(np.int32), cust.astype(np.int32), art.astype(np.int32), price,
                   np.asarray(customers, dtype=object), np.asarray(articles, dtype=object))

    def before(self, request_day: int) -> "Transactions":
        mask = self.day < request_day
        return Transactions(self.day[mask], self.cust[mask], self.art[mask], self.price[mask],
                            self.customers, self.articles)

    def article_string(self, code: int) -> str:
        return str(self.articles[code])


# --------------------------------------------------------------------------- 데이터 준비


def _normalized_content(content: np.ndarray) -> np.ndarray:
    matrix = np.asarray(content, dtype=np.float32)
    matrix = matrix / np.maximum(np.linalg.norm(matrix, axis=1, keepdims=True), 1e-12)
    return matrix


def _section_number(vocab: Any, article: str) -> int:
    """상품의 섹션 번호를 특징값으로 쓴다. 없으면 -1."""
    try:
        row = int(vocab.row_of(article))
    except (KeyError, TypeError):
        return -1
    tokens = getattr(vocab, "tokens", None)
    if tokens is None or not 0 <= row < len(tokens):
        return row
    name = str(tokens[row])
    if name.startswith("ROW_S"):
        try:
            return int(name[len("ROW_S"):])
        except ValueError:
            return -1
    return row


def _events_for(txn: Transactions, request_day: int | None, customer_ids: list[str]) -> dict[str, dict[str, np.ndarray]]:
    """요청 전 거래를 고객별로 모아 (day 오름차순) 돌려준다."""
    coded = txn.customer_codes
    target = np.fromiter((coded[c] for c in customer_ids if c in coded), dtype=np.int32)
    if target.size == 0:
        return {}
    mask = np.isin(txn.cust, np.unique(target))
    if request_day is not None:
        mask &= txn.day < request_day
    cust, day, art, price = (txn.cust[mask], txn.day[mask], txn.art[mask], txn.price[mask])
    order = np.lexsort((day, cust))
    cust, day, art, price = cust[order], day[order], art[order], price[order]
    boundaries = np.flatnonzero(np.diff(cust)) + 1
    result: dict[str, dict[str, np.ndarray]] = {}
    for start, end in zip(np.r_[0, boundaries], np.r_[boundaries, len(cust)]):
        result[str(txn.customers[cust[start]])] = {
            "day": day[start:end], "art": art[start:end], "price": price[start:end]}
    return result


@dataclass
class ItemStats:
    sales_1d: np.ndarray
    sales_7d: np.ndarray
    sales_28d: np.ndarray
    first_day: np.ndarray
    median_price: np.ndarray
    section: np.ndarray


def _item_stats(txn: Transactions, request_day: int, vocab: Any) -> ItemStats:
    """요청 이전 거래에서 상품별 판매 · 가격 · 첫 판매 · 섹션을 모은다."""
    size = len(txn.articles)
    art, day, price = txn.art, txn.day, txn.price
    mask = day < request_day
    art, day, price = art[mask], day[mask], price[mask]
    sales_1d = np.bincount(art[day >= request_day - 1], minlength=size)
    sales_7d = np.bincount(art[day >= request_day - 7], minlength=size)
    sales_28d = np.bincount(art[day >= request_day - 28], minlength=size)
    frame = pd.DataFrame({"art": art, "day": day, "price": price})
    grouped = frame.groupby("art")
    first = grouped["day"].min().reindex(range(size)).to_numpy(dtype=float)
    median = grouped["price"].median().reindex(range(size)).to_numpy(dtype=float)
    first = np.nan_to_num(first, nan=-1.0)
    median = np.nan_to_num(median, nan=0.0)
    section = np.asarray([_section_number(vocab, str(a)) for a in txn.articles], dtype=np.int32)
    return ItemStats(sales_1d.astype(np.float32), sales_7d.astype(np.float32), sales_28d.astype(np.float32),
                     first.astype(np.float32), median.astype(np.float32), section)


# --------------------------------------------------------------------------- 후보


def _window_frame(txn: Transactions, request_day: int, days: int) -> pd.DataFrame:
    mask = (txn.day < request_day) & (txn.day >= request_day - days)
    return pd.DataFrame({"t_dat": pd.to_datetime(txn.day[mask], unit="D"),
                         "article_id": txn.articles[txn.art[mask]]})


def _recent_codes(events: dict[str, np.ndarray] | None, request_day: int, cfg: dict[str, Any]) -> list[int]:
    """r 전 ``c1_days`` 일 안에 산 상품 코드(중복 없이)를 돌려준다."""
    if events is None or len(events["day"]) == 0:
        return []
    days, arts = events["day"], events["art"]
    return [int(c) for c in np.unique(arts[days >= request_day - cfg["c1_days"]])]


def _copurchase_counts(txn: Transactions, request_day: int, query_codes: np.ndarray,
                       cfg: dict[str, Any]) -> dict[int, Counter[int]]:
    """장바구니(고객 · 같은 날) 안에서 함께 산 상품 쌍을 센다.

    C5 의 "같은 고객 · 같은 날 함께 산 상품"을 그대로 구현한다. 다만 한 고객의
    같은 날 구매만 보면 C1(4주 재구매)에 이미 들어가므로, 모든 고객의 장바구니를
    모아 **상품 대 상품** 동시 구매로 센 뒤 ``r`` 전 ``c5_days`` 일 안에서 질의
    상품(고객이 4주 안에 산 상품)마다 상위 ``c5_top`` 개를 고른다.
    """
    if len(query_codes) == 0 or cfg["c5_top"] <= 0:
        return {}
    wanted = set(int(c) for c in query_codes.tolist())
    mask = (txn.day < request_day) & (txn.day >= request_day - cfg["c5_days"])
    cust, day, art = txn.cust[mask], txn.day[mask], txn.art[mask]
    order = np.lexsort((art, day, cust))
    cust, day, art = cust[order], day[order], art[order]
    if len(cust) == 0:
        return {}
    boundary = np.ones(len(cust), dtype=bool)
    boundary[1:] = (cust[1:] != cust[:-1]) | (day[1:] != day[:-1])
    starts = np.flatnonzero(boundary)
    ends = np.r_[starts[1:], len(cust)]
    counts: dict[int, Counter[int]] = {}
    for start, end in zip(starts.tolist(), ends.tolist()):
        items = np.unique(art[start:end])
        if len(items) < 2:
            continue
        queries = [int(c) for c in items.tolist() if int(c) in wanted]
        if not queries:
            continue
        for query in queries:
            bucket = counts.setdefault(query, Counter())
            for other in items.tolist():
                if other != query:
                    bucket[other] += 1
    return counts


def build_ranker_candidates(meta: pd.DataFrame, txn: Transactions, request: object, *, vocab: Any,
                            content: np.ndarray, content_rows: dict[str, int], cfg: dict[str, Any],
                            events: dict[str, dict[str, np.ndarray]] | None = None
                            ) -> dict[str, dict[str, set[str]]]:
    """고객마다 C1 ~ C5 후보를 만들고 출처별 집합으로 돌려준다.

    C2 · C3 는 :func:`candidates.popular_candidates`, C4 는
    :func:`candidates.build_similar_candidates` 를 그대로 쓴다.
    """
    request_day = _day_number(request)
    if "customer_id" not in meta or "history" not in meta:
        raise ValueError("eval_meta 에 customer_id 와 history 열이 필요합니다")
    overall, section_popular = popular_candidates(_window_frame(txn, request_day, 7), request,
                                                  top_n=cfg["c2_top"], per_section_m=cfg["c3_top"], vocab=vocab)
    similar = build_similar_candidates(meta, vocab=vocab, content=content, content_rows=content_rows,
                                       recent_k=cfg["c4_recent"], neighbors=cfg["c4_neighbors"])
    if events is None:
        events = _events_for(txn, request_day, [str(c) for c in meta["customer_id"]])

    recent_by_customer = {str(c): _recent_codes(events.get(str(c)), request_day, cfg) for c in meta["customer_id"]}
    parts = [np.asarray(values, dtype=np.int64) for values in recent_by_customer.values()]
    query_codes = np.unique(np.concatenate(parts)) if parts else np.empty(0, dtype=np.int64)
    copurchase = _copurchase_counts(txn, request_day, query_codes, cfg)

    overall_set = set(overall)
    result: dict[str, dict[str, set[str]]] = {}
    for row in meta.itertuples(index=False):
        customer = str(getattr(row, "customer_id"))
        recent = recent_by_customer[customer]
        c1 = {txn.article_string(c) for c in recent if vocab.item(txn.article_string(c)) is not None}
        c5: set[str] = set()
        for query in recent:
            for other, _ in sorted(copurchase.get(query, {}).items(), key=lambda pair: (-pair[1], pair[0]))[: cfg["c5_top"]]:
                article = txn.article_string(other)
                if vocab.item(article) is not None:
                    c5.add(article)
        sections: set[int] = set()
        for article in _as_articles(getattr(row, "history")):
            try:
                sections.add(int(vocab.row_of(article)))
            except (KeyError, TypeError):
                continue
        c3: set[str] = set()
        for section in sections:
            c3.update(section_popular.get(section, ()))
        result[customer] = {"C1": set(c1), "C2": set(overall_set), "C3": c3,
                            "C4": set(similar.get(customer, set())), "C5": set(c5)}
    return result


def candidate_pool(candidates: dict[str, dict[str, set[str]]]) -> dict[str, set[str]]:
    """출처별 후보를 C1 ~ C5 합집합으로 합친다(GenPage 허용 후보가 쓸 풀)."""
    return {customer: set().union(*sources.values()) for customer, sources in candidates.items()}


# --------------------------------------------------------------------------- 특징


@dataclass
class CustomerProfile:
    count: int
    count_4w: int
    days_since_last: float
    avg_price: float
    age: float


def _customer_profile(events: dict[str, np.ndarray] | None, request_day: int, age: float,
                      cfg: dict[str, Any]) -> CustomerProfile:
    if events is None or len(events["day"]) == 0:
        return CustomerProfile(0, 0, -1.0, 0.0, age)
    days, price = events["day"], events["price"]
    return CustomerProfile(len(days), int((days >= request_day - cfg["c1_days"]).sum()),
                           float(request_day - days.max()), float(price.mean()), age)


def _per_item_history(events: dict[str, np.ndarray] | None, request_day: int,
                      cfg: dict[str, Any]) -> dict[int, tuple[int, int, float]]:
    """상품 코드 -> (전체 산 횟수, 4주 산 횟수, 마지막으로 산 뒤 일수)."""
    result: dict[int, tuple[int, int, float]] = {}
    if events is None or len(events["day"]) == 0:
        return result
    counts: Counter[int] = Counter()
    counts_4w: Counter[int] = Counter()
    last: dict[int, int] = {}
    cut = request_day - cfg["c1_days"]
    counts.update(int(c) for c in events["art"])
    for day, code in zip(events["day"].tolist(), events["art"].tolist()):
        if day >= cut:
            counts_4w[code] += 1
        last[code] = max(last.get(code, -1), day)
    return {code: (counts[code], counts_4w.get(code, 0), float(request_day - last[code])) for code in last}


def build_features(meta: pd.DataFrame, txn: Transactions, request: object, selected: dict[str, list[str]],
                   candidates: dict[str, dict[str, set[str]]], *, vocab: Any, content: np.ndarray,
                   content_rows: dict[str, int], ages: dict[str, float], cfg: dict[str, Any],
                   events: dict[str, dict[str, np.ndarray]] | None = None,
                   item_stats: ItemStats | None = None) -> tuple[np.ndarray, list[tuple[str, str]]]:
    """(고객, 후보 상품)마다 STATUS 의 특징을 계산한다."""
    request_day = _day_number(request)
    if item_stats is None:
        item_stats = _item_stats(txn, request_day, vocab)
    if events is None:
        events = _events_for(txn, request_day, [str(c) for c in meta["customer_id"]])
    normalized = _normalized_content(content)
    article_codes = txn.article_codes

    rows: list[tuple[str, str]] = []
    values: list[list[float]] = []
    for row in meta.itertuples(index=False):
        customer = str(getattr(row, "customer_id"))
        events_c = events.get(customer)
        profile = _customer_profile(events_c, request_day, float(ages.get(customer, np.nan)), cfg)
        per_item = _per_item_history(events_c, request_day, cfg)
        recent = _recent_articles(events_c, txn, vocab, cfg["c4_recent"])
        recent_rows = [content_rows[a] for a in recent if a in content_rows]
        if events_c is not None and len(events_c["art"]):
            sections, counts = np.unique(item_stats.section[events_c["art"]], return_counts=True)
            section_counts = dict(zip(sections.tolist(), counts.tolist()))
        else:
            section_counts = {}
        sources = candidates.get(customer, {})
        for article in sorted(selected.get(customer, [])):
            code = article_codes.get(article)
            if code is None:
                continue
            stats = item_stats
            per = per_item.get(code, (0, 0, -1.0))
            item_price = float(stats.median_price[code])
            section = int(stats.section[code])
            section_share = section_counts.get(section, 0) / profile.count if profile.count else 0.0
            max_cosine = 0.0
            item_row = content_rows.get(article)
            if item_row is not None and recent_rows:
                sims = normalized[item_row] @ normalized[recent_rows].T
                max_cosine = float(np.max(sims)) if sims.size else 0.0
            values.append([
                float(article in sources.get("C1", ())), float(article in sources.get("C2", ())),
                float(article in sources.get("C3", ())), float(article in sources.get("C4", ())),
                float(article in sources.get("C5", ())),
                float(per[0]), float(per[1]), float(per[2]),
                float(stats.sales_1d[code]), float(stats.sales_7d[code]), float(stats.sales_28d[code]),
                float(stats.sales_7d[code] * 4.0 / (stats.sales_28d[code] + 1.0)),
                item_price, float(section), float(request_day - stats.first_day[code]) if stats.first_day[code] >= 0 else -1.0,
                float(profile.age), float(profile.count), float(profile.count_4w),
                float(profile.days_since_last), float(profile.avg_price),
                float(item_price / profile.avg_price) if profile.avg_price > 0 else 0.0,
                float(section_share), max_cosine,
            ])
            rows.append((customer, article))
    matrix = np.asarray(values, dtype=np.float32) if values else np.empty((0, len(FEATURE_NAMES)), dtype=np.float32)
    return matrix, rows


def _recent_articles(events: dict[str, np.ndarray] | None, txn: Transactions, vocab: Any, limit: int) -> list[str]:
    """최근 산 순서(중복 없이, 어휘 상품만)를 ``limit`` 개까지."""
    if events is None:
        return []
    seen: set[int] = set()
    result: list[str] = []
    for code in events["art"][::-1].tolist():
        code = int(code)
        if code in seen:
            continue
        seen.add(code)
        article = txn.article_string(code)
        if vocab.item(article) is None:
            continue
        result.append(article)
        if len(result) == limit:
            break
    return result


# --------------------------------------------------------------------------- 학습 · 예측


def _truth_by_customer(txn: Transactions, request_day: int, days: int,
                       customer_ids: list[str]) -> dict[str, set[str]]:
    mask = (txn.day >= request_day) & (txn.day < request_day + days)
    cust, art = txn.cust[mask], txn.art[mask]
    wanted = txn.customer_codes
    keep = {wanted[c] for c in customer_ids if c in wanted}
    truth: dict[str, set[str]] = defaultdict(set)
    for code, article in zip(cust.tolist(), art.tolist()):
        if code in keep:
            truth[str(txn.customers[code])].add(txn.article_string(article))
    return truth


def _history_for(events: dict[str, np.ndarray] | None, txn: Transactions, limit: int = 100) -> list[str]:
    if events is None:
        return []
    seen: set[int] = set()
    result: list[str] = []
    for code in events["art"][::-1].tolist():
        code = int(code)
        if code in seen:
            continue
        seen.add(code)
        result.append(txn.article_string(code))
        if len(result) == limit:
            break
    return result


def _sample(values: list[str], count: int, rng: np.random.Generator) -> list[str]:
    if count >= len(values):
        return list(values)
    chosen = rng.choice(len(values), size=count, replace=False)
    return [values[i] for i in sorted(chosen.tolist())]


def build_training_week(txn: Transactions, request: object, *, vocab: Any, content: np.ndarray,
                        content_rows: dict[str, int], ages: dict[str, float], cfg: dict[str, Any],
                        limit: int | None, rng: np.random.Generator
                        ) -> tuple[np.ndarray, np.ndarray, np.ndarray, list[tuple[str, str]]]:
    """한 주의 (특징 행렬, 라벨, 질의 크기, 행 키)를 만든다."""
    request_day = _day_number(request)
    window = (txn.day >= request_day) & (txn.day < request_day + config.TARGET_DAYS)
    buyer_codes = np.unique(txn.cust[window])
    if buyer_codes.size == 0:
        return (np.empty((0, len(FEATURE_NAMES)), dtype=np.float32), np.empty(0, dtype=np.int32),
                np.empty(0, dtype=np.int64), [])
    cap = cfg["max_train_customers"] if limit is None else limit
    if buyer_codes.size > cap:
        buyer_codes = np.sort(rng.choice(buyer_codes, size=cap, replace=False))
    buyers = [str(txn.customers[c]) for c in buyer_codes.tolist()]

    truth = _truth_by_customer(txn, request_day, config.TARGET_DAYS, buyers)
    events = _events_for(txn, request_day, buyers)
    meta = pd.DataFrame({
        "customer_id": buyers,
        "history": [_history_for(events.get(c), txn) for c in buyers],
        "truth": [sorted(truth.get(c, set())) for c in buyers],
    })
    candidates = build_ranker_candidates(meta, txn, request, vocab=vocab, content=content,
                                         content_rows=content_rows, cfg=cfg, events=events)
    item_stats = _item_stats(txn, request_day, vocab)

    article_codes = txn.article_codes
    selected: dict[str, list[str]] = {}
    labels: list[int] = []
    group: list[int] = []
    for customer in buyers:
        # 카탈로그에만 있고 거래에 없는 상품은 특징을 만들 수 없으니 후보에서 뺀다.
        pool = {a for a in candidate_pool({customer: candidates[customer]})[customer] if a in article_codes}
        positives = pool & truth.get(customer, set())
        if not positives:
            continue
        negatives = sorted(pool - truth.get(customer, set()))
        take = min(len(negatives), cfg["negative_ratio"] * len(positives))
        # build_features 가 상품 id 순으로 행을 만들므로 여기서도 같은 순서로 맞춘다.
        chosen = sorted(positives | set(_sample(negatives, take, rng)))
        selected[customer] = chosen
        labels.extend(1 if article in positives else 0 for article in chosen)
        group.append(len(chosen))
    if not group:
        return (np.empty((0, len(FEATURE_NAMES)), dtype=np.float32), np.empty(0, dtype=np.int32),
                np.empty(0, dtype=np.int64), [])
    matrix, rows = build_features(meta, txn, request, selected, candidates, vocab=vocab, content=content,
                                  content_rows=content_rows, ages=ages, cfg=cfg, events=events,
                                  item_stats=item_stats)
    # build_features 가 selected 를 상품 id 순으로 만들므로 라벨 · 질의 크기와 행이 맞는다.
    if len(rows) != len(labels):
        raise RuntimeError("특징 행과 라벨 수가 다릅니다")
    return matrix, np.asarray(labels, dtype=np.int32), np.asarray(group, dtype=np.int64), rows


def _lightgbm_params(cfg: dict[str, Any]) -> dict[str, Any]:
    return {
        "objective": "lambdarank",
        "metric": "map",
        "eval_at": [12],
        "num_leaves": cfg["num_leaves"],
        "learning_rate": cfg["learning_rate"],
        "min_child_samples": cfg["min_child_samples"],
        "feature_fraction": cfg["feature_fraction"],
        "bagging_fraction": cfg["bagging_fraction"],
        "bagging_freq": 1,
        "seed": cfg["seed"],
        "deterministic": True,
        "force_row_wise": True,
        "verbose": -1,
    }


def train_ranker(weeks: list[tuple[np.ndarray, np.ndarray, np.ndarray]], cfg: dict[str, Any]
                 ) -> tuple[Any, int, np.ndarray]:
    """r − 14 · r − 21 로 학습하고 r − 7 로 조기 종료한 뒤 세 주로 재학습한다."""
    import lightgbm as lgb

    train_x = [week[0] for week in weeks[1:] if len(week[1])]
    train_y = [week[1] for week in weeks[1:] if len(week[1])]
    train_g = [week[2] for week in weeks[1:] if len(week[1])]
    if not train_x:
        raise ValueError("학습할 주가 없습니다")
    full_x = np.concatenate(train_x)
    full_y = np.concatenate(train_y)
    full_g = np.concatenate(train_g)
    params = _lightgbm_params(cfg)
    full_set = lgb.Dataset(full_x, label=full_y, group=full_g, feature_name=list(FEATURE_NAMES), free_raw_data=True)

    valid = weeks[0]
    if len(valid[1]):
        valid_set = lgb.Dataset(valid[0], label=valid[1], group=valid[2], feature_name=list(FEATURE_NAMES),
                                reference=full_set, free_raw_data=True)
        model = lgb.train(params, full_set, num_boost_round=cfg["n_estimators"], valid_sets=[valid_set],
                          callbacks=[lgb.early_stopping(cfg["early_stopping"], verbose=False), lgb.log_evaluation(0)])
        iterations = int(model.best_iteration or cfg["n_estimators"])
    else:
        iterations = int(cfg["n_estimators"])
    # 세 주를 합쳐 조기 종료가 고른 반복 수로 다시 학습한다.
    all_x = np.concatenate([week[0] for week in weeks if len(week[1])])
    all_y = np.concatenate([week[1] for week in weeks if len(week[1])])
    all_g = np.concatenate([week[2] for week in weeks if len(week[1])])
    final_set = lgb.Dataset(all_x, label=all_y, group=all_g, feature_name=list(FEATURE_NAMES), free_raw_data=True)
    model = lgb.train(params, final_set, num_boost_round=max(iterations, 1))
    importance = model.feature_importance(importance_type="gain")
    return model, iterations, importance


def rank_customers(model: Any, meta: pd.DataFrame, txn: Transactions, request: object, *, vocab: Any,
                   content: np.ndarray, content_rows: dict[str, int], ages: dict[str, float],
                   cfg: dict[str, Any], top: int = 48) -> tuple[dict[str, list[str]], dict[str, dict[str, set[str]]]]:
    """평가 고객의 후보를 점수순으로 정렬해 상위 ``top`` 개 페이지를 만든다."""
    events = _events_for(txn, _day_number(request), [str(c) for c in meta["customer_id"]])
    candidates = build_ranker_candidates(meta, txn, request, vocab=vocab, content=content,
                                         content_rows=content_rows, cfg=cfg, events=events)
    article_codes = txn.article_codes
    pool = {customer: {a for a in items if a in article_codes}
            for customer, items in candidate_pool(candidates).items()}
    matrix, rows = build_features(meta, txn, request, pool, candidates, vocab=vocab, content=content,
                                  content_rows=content_rows, ages=ages, cfg=cfg, events=events)
    scores = model.predict(matrix) if len(matrix) else np.empty(0, dtype=np.float32)
    per_customer: dict[str, list[tuple[float, str]]] = defaultdict(list)
    for (customer, article), score in zip(rows, scores.tolist()):
        per_customer[customer].append((float(score), article))
    pages: dict[str, list[str]] = {}
    for customer in meta["customer_id"].astype(str):
        ranked = sorted(per_customer.get(customer, []), key=lambda pair: (-pair[0], pair[1]))
        pages[customer] = [article for _, article in ranked[:top]]
    return pages, candidates


# --------------------------------------------------------------------------- 실행


def run_ranker(mode: str, txn: Transactions, meta: pd.DataFrame, *, vocab: Any, content: np.ndarray,
               content_rows: dict[str, int], ages: dict[str, float], cfg: dict[str, Any],
               train_limit: int | None = None, top: int = 48,
               pages_sink: dict[str, list[str]] | None = None) -> dict[str, Any]:
    """학습 세 주와 평가를 한 번에 돌려 보고서 dict 를 만든다(파일 입출력 없음).

    ``pages_sink`` 를 주면 고객별 페이지(상위 ``top`` 개, 순위 순)를 그 dict 에
    채운다(짝 비교용 ``pages.json.gz`` 를 위해 ``run`` 이 넘긴다). 보고서는 건드리지
    않는다.
    """
    started = time.perf_counter()
    request = config.request_of(mode)
    rng = np.random.default_rng(cfg["seed"])
    weeks = []
    for offset in (7, 14, 21):
        week_request = request - pd.Timedelta(days=offset)
        weeks.append(build_training_week(txn, week_request, vocab=vocab, content=content,
                                         content_rows=content_rows, ages=ages, cfg=cfg, limit=train_limit, rng=rng))
    model, iterations, importance = train_ranker(weeks, cfg)
    rank_started = time.perf_counter()
    pages, candidates = rank_customers(model, meta, txn, request, vocab=vocab, content=content,
                                       content_rows=content_rows, ages=ages, cfg=cfg, top=top)
    if pages_sink is not None:
        pages_sink.update(pages)
    ranking_seconds = time.perf_counter() - rank_started
    metrics = evaluate_pages(meta, pages, content=content, content_rows=content_rows,
                             elapsed=ranking_seconds, device="cpu")
    article_codes = txn.article_codes
    pool = {customer: {a for a in items if a in article_codes}
            for customer, items in candidate_pool(candidates).items()}
    truth = [set(_as_articles(value)) for value in meta["truth"]]
    recalls = [len(pool.get(str(c), set()) & t) / len(t) if t else 0.0
               for c, t in zip(meta["customer_id"].astype(str), truth)]
    train_rows = int(sum(len(week[1]) for week in weeks))
    top_features = sorted(zip(FEATURE_NAMES, importance.tolist()), key=lambda pair: -pair[1])[:20]
    return {
        "mode": mode,
        "config": cfg.get("_name", "custom"),
        "request": str(request.date()),
        "customers": int(len(meta)),
        "elapsed_seconds": time.perf_counter() - started,
        "ranking_seconds": ranking_seconds,
        "metrics": {
            "map_at_12": metrics["map_at_12"],
            "page_recall": metrics["page_recall"],
            "new_item_recall": metrics["new_item_recall"],
            "repeat_item_recall": metrics["repeat_item_recall"],
        },
        "candidates_mean": float(np.mean([len(pool.get(str(c), set())) for c in meta["customer_id"]])) if len(meta) else 0.0,
        "candidate_recall": float(np.mean(recalls)) if recalls else 0.0,
        "train_rows": train_rows,
        "iterations": iterations,
        "feature_importance_top20": [[name, float(gain)] for name, gain in top_features],
    }


def write_pages(path: Path, meta: pd.DataFrame, pages: dict[str, list[str]]) -> Path:
    """고객별 페이지(상위 상품, 순위 순)를 ``pages.json.gz`` 로 따로 쓴다.

    결과 JSON 을 키우지 않으려고 gzip 으로 나눠 둔다. 형식은
    ``[{customer_id, items}]`` 이고 순서는 ``meta`` 순서다. 짝 비교
    (:mod:`genpage2.paired_compare`)의 A 입력이 이 파일이다.
    """
    records = [{"customer_id": str(customer), "items": list(pages.get(str(customer), []))}
               for customer in meta["customer_id"]]
    path.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(path, "wt", encoding="utf-8") as handle:
        json.dump(records, handle, ensure_ascii=False)
    return path


def run(args: argparse.Namespace) -> dict[str, Any]:
    began = time.perf_counter()
    base = Path(args.data_dir) if args.data_dir else config.data_dir()
    if args.config not in RANKER_CONFIGS:
        raise ValueError(f"--config 는 {sorted(RANKER_CONFIGS)} 중 하나: {args.config}")
    cfg = dict(RANKER_CONFIGS[args.config])
    cfg["_name"] = args.config
    mode = args.mode
    request = config.request_of(mode)
    mode_dir = base / "hm" / "model" / "genpage2" / mode
    meta = pd.read_parquet(mode_dir / "eval_meta.parquet")
    if args.limit is not None and args.limit < len(meta):
        meta = meta.sample(n=args.limit, random_state=config.SEED).sort_index()
    frame = pd.read_parquet(base / "hm" / "normalized" / "transactions.parquet",
                            columns=["t_dat", "customer_id", "article_id", "price"])
    frame = frame[pd.to_datetime(frame["t_dat"]) < request]
    txn = Transactions.from_frame(frame)
    del frame
    vocab, content, content_rows = load_eval_assets(mode_dir)
    ages = _load_ages(base, txn)
    pages: dict[str, list[str]] = {}
    report = run_ranker(mode, txn, meta, vocab=vocab, content=content, content_rows=content_rows,
                        ages=ages, cfg=cfg, train_limit=args.train_customers, pages_sink=pages)
    report["config"] = args.config
    report["limit"] = args.limit
    report["train_customers"] = args.train_customers
    report["wall_seconds"] = time.perf_counter() - began
    destination = Path(args.out)
    destination.mkdir(parents=True, exist_ok=True)
    path = destination / f"ranker_{args.config}_{mode}.json"
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=float), encoding="utf-8")
    write_pages(destination / "pages.json.gz", meta, pages)
    print(json.dumps(report, ensure_ascii=False, indent=2, default=float))
    return report


def _load_ages(base: Path, txn: Transactions) -> dict[str, float]:
    customers = pd.read_parquet(base / "hm" / "normalized" / "customers.parquet",
                                columns=["customer_id", "age"])
    ages = dict(zip(customers["customer_id"].astype(str), customers["age"].astype(float)))
    return ages


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("validate", "final"), required=True)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--out", required=True, help="결과 JSON 을 쓸 폴더")
    parser.add_argument("--config", default=DEFAULT_CONFIG, choices=sorted(RANKER_CONFIGS))
    parser.add_argument("--data-dir")
    parser.add_argument("--train-customers", type=int, default=None,
                        help="스모크용 학습 고객 상한(기본은 config 값)")
    args = parser.parse_args(argv)
    run(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
