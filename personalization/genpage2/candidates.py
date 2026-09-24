"""평가 요청 시점에 안전한 GenPage 후보 상품 집합을 만든다."""
from __future__ import annotations

from collections import Counter, defaultdict
from typing import Any

import pandas as pd


def _article_id(value: Any) -> str:
    """원본의 10자리 상품 id 표현을 보존한다."""
    value = str(value)
    return value.zfill(10) if value.isdigit() else value


def _as_articles(value: Any) -> list[str]:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return []
    if isinstance(value, str):
        return [_article_id(value)]
    return [_article_id(article) for article in value]


def _ranked_items(counts: Counter[str], limit: int) -> list[str]:
    """판매 수 내림차순, 같은 수면 article_id 오름차순으로 고른다."""
    return [article for article, _ in sorted(counts.items(), key=lambda pair: (-pair[1], pair[0]))[:limit]]


def build_candidates(meta: pd.DataFrame, transactions: pd.DataFrame, request: object, *,
                     top_n: int, per_section_m: int, vocab: Any) -> dict[str, set[str]]:
    """고객별 운영 후보 집합을 만든다.

    후보 판매량은 요청 시각 직전 7일만 사용한다. 특히 그 뒤 거래가 들어 있는
    전체 parquet을 받더라도 절대로 후보에 섞이지 않게 여기에서 시간 조건을 둔다.
    """
    if top_n < 0 or per_section_m < 0:
        raise ValueError("top_n 과 per_section_m 은 0 이상이어야 합니다")
    required = {"t_dat", "article_id"}
    missing = required.difference(transactions.columns)
    if missing:
        raise ValueError(f"transactions 에 필요한 열이 없습니다: {sorted(missing)}")
    if "customer_id" not in meta or "history" not in meta:
        raise ValueError("eval_meta 에 customer_id 와 history 열이 필요합니다")

    request_at = pd.Timestamp(request)
    dates = pd.to_datetime(transactions["t_dat"])
    recent = transactions[(dates < request_at) & (dates >= request_at - pd.Timedelta(days=7))]
    popular: Counter[str] = Counter()
    by_section: dict[int, Counter[str]] = defaultdict(Counter)
    for article in recent["article_id"]:
        article_id = _article_id(article)
        if vocab.item(article_id) is None:
            continue
        popular[article_id] += 1
        try:
            by_section[vocab.row_of(article_id)][article_id] += 1
        except KeyError:
            # 어휘에는 있지만 현재 카탈로그 메타데이터에 없는 상품은 행별
            # 후보에는 넣지 못한다. 전체 인기 후보에는 그대로 남긴다.
            continue

    overall = _ranked_items(popular, top_n)
    section_popular = {
        section: _ranked_items(counts, per_section_m)
        for section, counts in by_section.items()
    }
    result: dict[str, set[str]] = {}
    for row in meta.itertuples(index=False):
        customer_id = str(getattr(row, "customer_id"))
        history = _as_articles(getattr(row, "history"))
        candidates = {article for article in history if vocab.item(article) is not None}
        candidates.update(overall)
        sections: set[int] = set()
        for article in history:
            try:
                sections.add(vocab.row_of(article))
            except KeyError:
                continue
        for section in sections:
            candidates.update(section_popular.get(section, ()))
        result[customer_id] = candidates
    return result
