"""평가 요청 시점에 안전한 GenPage 후보 상품 집합을 만든다."""
from __future__ import annotations

from collections import Counter, defaultdict
from typing import Any

import numpy as np
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


def _neighbor_matrix(vocab: Any, content: np.ndarray, content_rows: dict[str, int]) -> tuple[list[str], np.ndarray]:
    """어휘 상품만 남긴 L2 정규화 임베딩 행렬과 그 순서를 만든다.

    이웃 검색은 어휘 상품 안에서만 한다(요청 전 판매로 어휘가 만들어졌으므로
    요청 뒤에만 있는 상품은 애초에 들어오지 않는다). article_id 오름차순으로
    정렬해 같은 점수일 때 순서가 재현되게 한다.
    """
    articles = sorted(article for article in content_rows if vocab.item(article) is not None)
    matrix = np.asarray(content[[content_rows[article] for article in articles]], dtype=np.float32)
    matrix /= np.maximum(np.linalg.norm(matrix, axis=1, keepdims=True), 1e-12)
    return articles, matrix


def _neighbors_for(
    queries: list[str], articles: list[str], matrix: np.ndarray, index_of: dict[str, int],
    neighbors: int, *, chunk: int,
) -> dict[str, list[str]]:
    """모든 질의의 최근접 이웃을 묶은 행렬 곱으로 한 번에 구한다.

    질의마다 어휘 전체와 곱하면 사용자 수 × 어휘 크기가 된다. 대신 여기서 필요한
    질의를 모아 ``chunk`` 크기로 묶어 (Q×d) @ (d×V) 행렬 곱 한 번씩 돌린다.
    """
    result: dict[str, list[str]] = {}
    if neighbors <= 0 or not queries:
        return {article: [] for article in queries}
    for start in range(0, len(queries), chunk):
        part = queries[start:start + chunk]
        rows = np.asarray([index_of[article] for article in part], dtype=np.int64)
        scores = matrix[rows] @ matrix.T
        take = min(neighbors + 1, len(articles))
        top = np.argpartition(-scores, take - 1, axis=1)[:, :take]
        for position, article in enumerate(part):
            ranked = sorted(top[position].tolist(), key=lambda index: (-scores[position, index], index))
            result[article] = [articles[index] for index in ranked if articles[index] != article][:neighbors]
    return result


def build_similar_candidates(
    meta: pd.DataFrame, *, vocab: Any, content: np.ndarray, content_rows: dict[str, int],
    recent_k: int, neighbors: int, chunk: int = 512,
) -> dict[str, set[str]]:
    """최근 산 K 개 상품 각각의 e5 코사인 최근접 N 개를 후보로 만든다.

    "최근 산"은 요청 전 이력(메타의 history, 최신 순)에서 어휘에 있는 상품을
    중복 없이 앞에서 K 개 고른 것이다. 각 질의의 이웃은 자기 자신을 뺀다.
    이웃 계산은 이력 상품 단위로 묶어 캐시하므로 같은 상품을 여러 사용자가
    샀어도 전체 검색은 상품당 한 번만 돈다.
    """
    if recent_k < 0 or neighbors < 0:
        raise ValueError("similar 의 K 와 N 은 0 이상이어야 합니다")
    if "customer_id" not in meta or "history" not in meta:
        raise ValueError("eval_meta 에 customer_id 와 history 열이 필요합니다")
    articles, matrix = _neighbor_matrix(vocab, content, content_rows)
    index_of = {article: index for index, article in enumerate(articles)}

    recent_by_customer: dict[str, list[str]] = {}
    needed: list[str] = []
    seen_needed: set[str] = set()
    for row in meta.itertuples(index=False):
        customer = str(getattr(row, "customer_id"))
        recent: list[str] = []
        seen: set[str] = set()
        for article in _as_articles(getattr(row, "history")):
            if article not in index_of or article in seen:
                continue
            seen.add(article)
            recent.append(article)
            if len(recent) == recent_k:
                break
        recent_by_customer[customer] = recent
        for article in recent:
            if article not in seen_needed:
                seen_needed.add(article)
                needed.append(article)

    neighbor_of = _neighbors_for(needed, articles, matrix, index_of, neighbors, chunk=chunk)
    result: dict[str, set[str]] = {}
    for customer, recent in recent_by_customer.items():
        chosen: set[str] = set()
        for article in recent:
            chosen.update(neighbor_of.get(article, ()))
        result[customer] = chosen
    return result
