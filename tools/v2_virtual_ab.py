#!/usr/bin/env python3
"""가상 사용자 A/B — 서빙 v1 과 v2 를 HTTP /page 로 겨룬다(#305).

설계 · 지표 · 판정은 `personalization/docs/genpage-v2/BACKEND.md` D 절에 측정 전에
고정했다. 이 도구는 그 절의 하네스다. 홀드아웃 주(요청 2020-09-16)에 실제로 산
고객을 해시로 A · B 에 고정 배정하고, 두 정책의 페이지를 C0 반응 모델로 채점한다.

    python3 tools/v2_virtual_ab.py --v1-url http://127.0.0.1:8765 --v2-url http://127.0.0.1:8766 \
        --mode ab --customers 5000 --seed 7 --bootstrap 2000 --out OUT

`--mode aa` 는 같은 사용자 분할에 두 쪽 모두 v2 를 보여 준다(하네스 점검).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from pathlib import Path
from typing import Any, Iterable, Sequence

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
PERSONALIZATION = ROOT / "personalization"
if str(PERSONALIZATION) not in sys.path:
    sys.path.insert(0, str(PERSONALIZATION))

from genpage2 import config  # noqa: E402
from genpage2.simulate import (HttpSource, build_eval_users, eval_buyers, eval_transactions,  # noqa: E402
                               load_attributes, simulate)
from genpage2.vocab import Vocab, _article_id, content_rows  # noqa: E402


METRICS = ("page_reward", "click_rate", "buy_rate", "purchase_hit")
METRIC_LABELS = {"page_reward": "페이지 보상 합", "click_rate": "클릭률(클릭+구매/본 것)",
                 "buy_rate": "구매율(구매/본 것)", "purchase_hit": "실제 구매 적중"}


def arm_of(customer_id: Any) -> str:
    """sha256(customer_id) 의 짝홀로 A · B 를 고정 배정한다(비대응 설계)."""
    digest = hashlib.sha256(str(customer_id).encode("utf-8")).digest()
    return "A" if int.from_bytes(digest[:8], "big") % 2 == 0 else "B"


def user_metrics(pages: Sequence[Any], wanted: Iterable[str]) -> dict[str, float]:
    """한 사용자의 페이지들에서 지표를 센다."""
    impressions = [impression for page in pages for impression in page.impressions]
    seen = sum(impression.feedback != "unseen" for impression in impressions)
    clicks = sum(impression.feedback in ("click", "buy") for impression in impressions)
    buys = sum(impression.feedback == "buy" for impression in impressions)
    purchased = set(wanted)
    hit = any(impression.article_id in purchased for impression in impressions)
    return {"page_reward": float(sum(page.page_reward for page in pages)),
            "click_rate": (clicks / seen if seen else 0.0),
            "buy_rate": (buys / seen if seen else 0.0),
            "purchase_hit": 1.0 if hit else 0.0}


def bootstrap_ci(a: Sequence[float], b: Sequence[float], draws: int,
                 rng: np.random.Generator) -> dict[str, Any]:
    """B − A 의 차이와 부트스트랩 95% 구간(사용자 단위 재표집)."""
    left = np.asarray(list(a), dtype=float)
    right = np.asarray(list(b), dtype=float)
    if left.size == 0 or right.size == 0:
        return {"a": None, "b": None, "diff": None, "ci95": [None, None], "draws": int(draws)}
    diff = float(right.mean() - left.mean())
    a_index = rng.integers(0, left.size, size=(int(draws), left.size))
    b_index = rng.integers(0, right.size, size=(int(draws), right.size))
    sampled = right[b_index].mean(axis=1) - left[a_index].mean(axis=1)
    return {"a": float(left.mean()), "b": float(right.mean()), "diff": diff,
            "ci95": [float(np.percentile(sampled, 2.5)), float(np.percentile(sampled, 97.5))],
            "draws": int(draws)}


def _by_customer(pages: Iterable[Any]) -> dict[str, list[Any]]:
    grouped: dict[str, list[Any]] = {}
    for page in pages:
        grouped.setdefault(str(page.customer_id), []).append(page)
    return grouped


def _metric_values(arm_pages: dict[str, list[Any]], users: Sequence[Any]) -> dict[str, list[float]]:
    values: dict[str, list[float]] = {name: [] for name in METRICS}
    for user in users:
        metrics = user_metrics(arm_pages.get(str(user.customer_id), []), set(user.wanted))
        for name in METRICS:
            values[name].append(metrics[name])
    return values


def run(args: argparse.Namespace) -> dict[str, Any]:
    started = time.monotonic()
    base = config.data_dir()
    normalized = base / "hm" / "normalized"
    request = config.request_of("final")
    vocab = Vocab.load(base / "hm" / "model" / "genpage2" / "final" / "vocab.json")
    articles = pd.read_parquet(normalized / "articles.parquet")
    attributes = load_attributes(articles)
    content_map = content_rows(articles)
    sections = {_article_id(article): section
                for article, section in zip(articles["article_id"].tolist(), articles["section_no"].tolist())}
    buyers = eval_buyers(normalized / "transactions.parquet", request)
    taken = min(int(args.customers), len(buyers))
    if taken == 0:
        raise ValueError("평가 창에 산 고객이 없습니다")
    chosen = sorted(str(value) for value in np.random.default_rng(int(args.seed)).choice(
        np.asarray(buyers), size=taken, replace=False).tolist())
    transactions = eval_transactions(normalized / "transactions.parquet", chosen, request)
    customers = pd.read_parquet(normalized / "customers.parquet")
    users, prices = build_eval_users(vocab, transactions, customers, attributes, content_map, request=request)
    users.sort(key=lambda user: user.customer_id)
    arm_users = {"A": [user for user in users if arm_of(user.customer_id) == "A"],
                 "B": [user for user in users if arm_of(user.customer_id) == "B"]}
    if args.mode == "ab":
        if not args.v1_url:
            raise ValueError("--mode ab 에는 --v1-url 이 필요합니다")
        kinds = {"A": ("v1", args.v1_url), "B": ("v2", args.v2_url)}
    else:
        kinds = {"A": ("v2", args.v2_url), "B": ("v2", args.v2_url)}
    arm_pages: dict[str, dict[str, list[Any]]] = {}
    for arm in ("A", "B"):
        kind, url = kinds[arm]
        source = HttpSource(url, kind, vocab, sections)
        arm_pages[arm] = _by_customer(simulate(arm_users[arm], source, vocab=vocab, attributes=attributes,
                                               content_rows_map=content_map, prices=prices))
    rng = np.random.default_rng(int(args.seed))
    metrics: dict[str, Any] = {}
    for name in METRICS:
        values = {arm: _metric_values(arm_pages[arm], arm_users[arm])[name] for arm in ("A", "B")}
        metrics[name] = bootstrap_ci(values["A"], values["B"], int(args.bootstrap), rng)
    report = {"mode": args.mode, "request_date": str(request.date()),
              "customers_requested": int(args.customers), "seed": int(args.seed),
              "bootstrap_draws": int(args.bootstrap), "holdout_buyers": len(buyers),
              "users": {"A": len(arm_users["A"]), "B": len(arm_users["B"])},
              "policies": {arm: kinds[arm][0] for arm in ("A", "B")},
              "urls": {arm: kinds[arm][1] for arm in ("A", "B")},
              "metrics": metrics, "elapsed_seconds": time.monotonic() - started}
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (out / "report-table.md").write_text(_table(report), encoding="utf-8")
    print(_table(report), end="")
    return report


def _num(value: Any) -> str:
    return "-" if value is None else f"{float(value):.4f}"


def _table(report: dict[str, Any]) -> str:
    lines = [f"# 가상 사용자 A/B — {report['mode']}", "",
             f"- 요청 {report['request_date']} · 홀드아웃 구매자 {report['holdout_buyers']:,}명 중 "
             f"{report['customers_requested']:,}명 표집(seed {report['seed']})",
             f"- 배정 A {report['users']['A']:,}명 · B {report['users']['B']:,}명(sha256 짝홀 고정)",
             f"- 부트스트랩 {report['bootstrap_draws']:,}회 · 정책 A `{report['policies']['A']}` · "
             f"정책 B `{report['policies']['B']}`", "",
             "| 지표 | A | B | B − A | 95% 구간 |", "|---|---:|---:|---:|---|"]
    for name in METRICS:
        value = report["metrics"][name]
        lines.append(f"| {METRIC_LABELS[name]} | {_num(value['a'])} | {_num(value['b'])} | {_num(value['diff'])}"
                     f" | [{_num(value['ci95'][0])}, {_num(value['ci95'][1])}] |")
    lines.append("")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--v1-url")
    parser.add_argument("--v2-url", required=True)
    parser.add_argument("--customers", type=int, default=5000)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--bootstrap", type=int, default=2000)
    parser.add_argument("--mode", choices=("ab", "aa"), default="ab")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    run(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
