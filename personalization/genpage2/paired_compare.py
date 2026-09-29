"""R(순위 모델) 대 G(GenPage) 짝 비교 — 같은 고객에서 (B − A) 부트스트랩.

STATUS.md 의 "D2" 판정 3(같은 고객 짝 부트스트랩)을 한다. A 는 순위 모델이 쓴
``pages.json.gz``, B 는 GenPage 조각 JSON(``.json`` 또는 ``.json.gz``)이다. 두 쪽의
평가 표본은 :mod:`genpage2.evaluate` · :mod:`genpage2.ranker` 와 **같은 추출 ·
같은 순서**(``eval_meta`` 에서 ``SEED`` 로 표본)를 쓴다.

GenPage 조각은 ``pages[].rows[].items`` 를 행 순서대로 펴서 페이지로 본다 —
:func:`genpage2.merge_eval._collect_pages` 와 :func:`genpage2.evaluate._page_items`
를 그대로 재사용한다(``evaluate.py`` 가 채점할 때와 같다).

고객별 지표(AP@12 · 페이지 적중 · 새 상품 적중 · 페이지 nDCG · 행 적중)는
:mod:`genpage2.evaluate` 와 같은 정의로 계산하고, 그 평균이 ``evaluate_pages`` 가
낸 값과 같은지 스스로 확인해 출력에 남긴다. 부트스트랩은 고객을 복원 추출해
(B − A) 평균 차이의 2.5 · 97.5 백분위를 지표마다 구한다.

A 는 평평한 ``pages.json.gz`` 뿐 아니라 GenPage 조각 JSON(``pages[].rows[].items``)
도 받는다(D3 는 B 규칙 페이지와 G 페이지를 짝 비교한다). B 는 GenPage 조각 JSON
이다.
"""
from __future__ import annotations

import argparse
import gzip
import json
from pathlib import Path
from typing import Any, Iterable

import numpy as np
import pandas as pd

from .config import SEED, data_dir
from .evaluate import (_as_articles, _load_examples, _page_items, _page_rows, evaluate_pages, page_ndcg,
                       row_hit)
from .merge_eval import _collect_pages

# 지표 이름 -> (고객별 벡터, evaluate_pages 가 내는 키).
METRICS = {
    "map_at_12": "map_at_12",
    "page_hit": "page_recall",
    "new_item_hit": "new_item_recall",
    "page_ndcg": "page_ndcg",
    "row_hit": "row_hit",
}


def _read_json(path: Path) -> Any:
    if str(path).endswith(".gz"):
        with gzip.open(path, "rt", encoding="utf-8") as handle:
            return json.load(handle)
    return json.loads(path.read_text(encoding="utf-8"))


def _require_customers(pages: dict[str, Any], meta: pd.DataFrame, label: str) -> None:
    expected = {str(customer) for customer in meta["customer_id"]}
    missing = expected - set(pages)
    extra = set(pages) - expected
    if missing or extra:
        raise ValueError(
            f"{label} 의 고객 집합이 평가 표본과 다릅니다 (빠짐 {len(missing)}, 여분 {len(extra)})"
        )


def load_ranker_pages(path: str | Path, meta: pd.DataFrame) -> dict[str, list[str]]:
    """순위 모델의 ``pages.json.gz``(``[{customer_id, items}]``)를 읽는다."""
    records = _read_json(Path(path))
    pages = {str(record["customer_id"]): [str(article) for article in record["items"]]
             for record in records}
    _require_customers(pages, meta, "A(순위 모델)")
    return pages


def load_genpage_pages(paths: Iterable[str | Path], meta: pd.DataFrame) -> dict[str, list[str]]:
    """GenPage 조각 JSON 들을 합치고 ``pages[].rows[].items`` 를 행 순서대로 편다."""
    objects = load_genpage_objects(paths, meta)  # 고객 집합 검증 + GeneratedRow 구성(재사용)
    return {customer: _page_items(page) for customer, page in objects.items()}


def load_genpage_objects(paths: Iterable[str | Path], meta: pd.DataFrame) -> dict[str, list[Any]]:
    """GenPage 조각 JSON 들을 합쳐 고객별 ``GeneratedRow`` 목록으로 돌려준다.

    행 구조를 살려 두어야 ``page_ndcg`` · ``row_hit`` 을 셀 수 있다.
    """
    shards = [_read_json(Path(path)) for path in paths]
    return _collect_pages(shards, meta)


def load_any_pages(sources: Iterable[str | Path], meta: pd.DataFrame) -> dict[str, Any]:
    """A 입력을 읽는다. 평평한 ``pages.json.gz`` 이거나 GenPage 조각 JSON(들)이다.

    조각 JSON 은 첫 파일이 ``pages`` 키를 가진 dict 인지로 알아본다.
    """
    paths = [Path(source) for source in sources]
    if not paths:
        raise ValueError("A 입력이 비었습니다")
    payload = _read_json(paths[0])
    if isinstance(payload, dict) and "pages" in payload:
        return load_genpage_objects(paths, meta)
    if len(paths) != 1:
        raise ValueError("평평한 pages.json.gz 입력은 하나여야 합니다")
    return load_ranker_pages(paths[0], meta)


def average_precision_at_12(items: list[str], truth: set[str], k: int = 12) -> float:
    """v1 ``features_hm.map_at_k`` 와 같은 고객별 AP@k(분모 = min(|정답|, k))."""
    if not truth:
        return 0.0
    hits = 0
    total = 0.0
    for rank, article in enumerate(items[:k], start=1):
        if article in truth:
            hits += 1
            total += hits / rank
    return total / min(len(truth), k)


def customer_metrics(meta: pd.DataFrame, pages: dict[str, Any]) -> dict[str, np.ndarray]:
    """고객별 AP@12 · 페이지 적중 · 새 상품 적중 · 페이지 nDCG · 행 적중을 ``meta`` 순서 벡터로.

    페이지 적중 · 새 상품 적중은 :func:`genpage2.evaluate.page_metrics` 와 같은
    식을 쓴다(정답이 빈 고객도 분모에 포함해 0.0). 페이지 nDCG · 행 적중은 행
    구조가 필요하므로 :func:`genpage2.evaluate._page_rows` 로 행을 얻는다(행이
    없으면 전체를 한 행으로 본다).
    """
    flat = {str(customer): _page_items(page) for customer, page in pages.items()}
    rows = {str(customer): _page_rows(page) for customer, page in pages.items()}
    aps: list[float] = []
    page_hits: list[float] = []
    new_hits: list[float] = []
    ndcgs: list[float] = []
    row_hits: list[float] = []
    for row in meta.itertuples(index=False):
        customer = str(getattr(row, "customer_id"))
        truth = list(dict.fromkeys(_as_articles(getattr(row, "truth"))))
        history = set(_as_articles(getattr(row, "history")))
        items = flat.get(customer, [])
        page_rows = rows.get(customer, [])
        item_set = set(items)
        aps.append(average_precision_at_12(items, set(truth)))
        page_hits.append(len(item_set & set(truth)) / len(truth) if truth else 0.0)
        new_truth = [article for article in truth if article not in history]
        new_hits.append(len(item_set & set(new_truth)) / len(new_truth) if new_truth else 0.0)
        ndcgs.append(page_ndcg(page_rows, truth))
        row_hits.append(row_hit(page_rows, truth))
    return {
        "map_at_12": np.asarray(aps, dtype=np.float64),
        "page_hit": np.asarray(page_hits, dtype=np.float64),
        "new_item_hit": np.asarray(new_hits, dtype=np.float64),
        "page_ndcg": np.asarray(ndcgs, dtype=np.float64),
        "row_hit": np.asarray(row_hits, dtype=np.float64),
    }


def _self_check(meta: pd.DataFrame, pages: dict[str, Any],
                vectors: dict[str, np.ndarray]) -> dict[str, dict[str, Any]]:
    """고객별 벡터의 평균이 ``evaluate_pages``(같은 정의)와 같은지 확인한다."""
    expected = evaluate_pages(meta, {customer: list(page) for customer, page in pages.items()})
    checks: dict[str, dict[str, Any]] = {}
    for name, key in METRICS.items():
        computed = float(vectors[name].mean()) if len(vectors[name]) else 0.0
        reference = float(expected[key])
        checks[name] = {"computed": computed, "evaluate": reference,
                        "match": bool(abs(computed - reference) < 1e-9)}
    return checks


def verdict(low: float, high: float) -> str:
    """(B − A) 구간이 0 을 어디에 두는지로 판정 문자열을 고른다."""
    if low > 0:
        return "B 가 이겼다"
    if high < 0:
        return "B 가 졌다"
    return "비겼다"


def paired_bootstrap(a_vectors: dict[str, np.ndarray], b_vectors: dict[str, np.ndarray], *,
                     bootstrap: int = 2000, seed: int = SEED) -> dict[str, dict[str, Any]]:
    """고객을 복원 추출해 (B − A) 평균 차이의 2.5 · 97.5 백분위를 구한다.

    같은 추출을 지표 셋에 함께 적용해 짝을 유지한다.
    """
    count = len(next(iter(a_vectors.values())))
    rng = np.random.default_rng(seed)
    difference = {name: b_vectors[name] - a_vectors[name] for name in a_vectors}
    draws = {name: np.empty(bootstrap, dtype=np.float64) for name in a_vectors}
    for index in range(bootstrap):
        picked = rng.integers(0, count, size=count)
        for name, vector in difference.items():
            draws[name][index] = vector[picked].mean()
    intervals: dict[str, dict[str, Any]] = {}
    for name, values in draws.items():
        low, high = np.percentile(values, [2.5, 97.5])
        intervals[name] = {"low": float(low), "high": float(high), "verdict": verdict(low, high)}
    return intervals


def run(a: str | Path | Iterable[str | Path], b: Iterable[str | Path], *, mode: str,
        limit: int | None = None,
        bootstrap: int = 2000, seed: int = SEED, out: str | Path | None = None,
        data_dir_path: str | Path | None = None) -> dict[str, Any]:
    """A · B 페이지를 읽어 고객별 지표 · 평균 · 짝 부트스트랩 구간을 낸다.

    ``a`` 는 평평한 ``pages.json.gz`` 하나이거나 GenPage 조각 JSON(들)이다.
    ``b`` 는 GenPage 조각 JSON(들)이다.
    """
    base = Path(data_dir_path) if data_dir_path else data_dir()
    meta, _archive = _load_examples(base, mode, limit)
    a_paths = [a] if isinstance(a, (str, Path)) else list(a)
    b_paths = [str(path) for path in b]
    a_pages = load_any_pages(a_paths, meta)
    b_pages = load_genpage_objects(b_paths, meta)
    a_vectors = customer_metrics(meta, a_pages)
    b_vectors = customer_metrics(meta, b_pages)
    intervals = paired_bootstrap(a_vectors, b_vectors, bootstrap=bootstrap, seed=seed)
    report: dict[str, Any] = {
        "mode": mode,
        "limit": limit,
        "customers": int(len(meta)),
        "bootstrap": int(bootstrap),
        "seed": int(seed),
        "a": str(a_paths[0]) if len(a_paths) == 1 else [str(path) for path in a_paths],
        "b": b_paths,
        "means": {
            "a": {name: float(values.mean()) for name, values in a_vectors.items()},
            "b": {name: float(values.mean()) for name, values in b_vectors.items()},
        },
        "difference": {name: float(b_vectors[name].mean() - a_vectors[name].mean())
                       for name in METRICS},
        "checks": {
            "a": _self_check(meta, a_pages, a_vectors),
            "b": _self_check(meta, b_pages, b_vectors),
        },
        "intervals": intervals,
        "judgment": {name: interval["verdict"] for name, interval in intervals.items()},
    }
    if out is not None:
        destination = Path(out)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=float),
                               encoding="utf-8")
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("validate", "final"), required=True)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--a", nargs="+", required=True, metavar="JSON",
                        help="A 페이지: 평평한 pages.json.gz 하나 또는 GenPage 조각 JSON(들)")
    parser.add_argument("--b", nargs="+", required=True, metavar="JSON",
                        help="GenPage 조각 JSON(.json 또는 .json.gz)")
    parser.add_argument("--bootstrap", type=int, default=2000)
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--out", required=True, help="결과 JSON 경로")
    parser.add_argument("--data-dir")
    args = parser.parse_args(argv)
    report = run(args.a, args.b, mode=args.mode, limit=args.limit, bootstrap=args.bootstrap,
                 seed=args.seed, out=args.out, data_dir_path=args.data_dir)
    print(json.dumps(report, ensure_ascii=False, indent=2, default=float))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
