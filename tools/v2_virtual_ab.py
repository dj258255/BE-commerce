#!/usr/bin/env python3
"""가상 사용자 A/B — 서빙 v1 과 v2 를 HTTP /page 로 겨룬다(#305).

설계 · 지표 · 판정은 `personalization/docs/genpage-v2/BACKEND.md` D 절에 측정 전에
고정했다. 이 도구는 그 절의 하네스다. 홀드아웃 주(요청 2020-09-16)에 실제로 산
고객을 해시로 A · B 에 고정 배정하고, 두 정책의 페이지를 C0 반응 모델로 채점한다.

    python3 tools/v2_virtual_ab.py --v1-url http://127.0.0.1:8765 --v2-url http://127.0.0.1:8766 \
        --mode ab --customers 5000 --seed 7 --bootstrap 2000 --out OUT

`--mode aa` 는 같은 사용자 분할에 두 쪽 모두 v2 를 보여 준다(하네스 점검).

S1(#454) L4 는 한 v2 서버를 compose 로 가른다. A/A 는 `--a-compose hybrid --aa`, A/B 는
`--a-compose rule --b-compose hybrid` 다. 결과에 두 arm 응답의 `composition` · `fallback`
개수도 남긴다(대체 경로가 얼마나 탔는지). compose 로 가르면 두 arm 이 모두 v2 라 모드 ·
정책 표기도 compose 값을 따른다 — A/A 는 "aa", A/B 는 "ab", 정책은 `rule` · `hybrid` 다.

S3(#462) `--paired` 는 사용자를 나누지 않는다. **모든 사용자에게 A · B 두 정책을 차례로**
돌려, 사용자 사이 분산이 (B − A) 에서 빠지는 짝 설계로 잰다. 반응 난수는 `rng_for(customer,
date)` 그대로라 두 정책이 같은 시드에서 시작한다. `--customers 0` 은 홀드아웃 구매 고객
전원이다. 결과는 고객별 값을 따로 parquet 로도 남긴다(`--paired-customers`, 저장소 밖 경로).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from collections import Counter
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
                 "buy_rate": "구매율(구매/본 것)", "purchase_hit": "실제 구매 적중",
                 "purchase_hit_min_items": "실제 구매 적중(3개 미만 행 제거)"}
# S3(#462) 보조 지표가 버리는 행의 최소 항목 수. 앱 `app.home.min-items` 기본값(S2).
MIN_ITEMS = 3
# S3(#462) 진행 로그 단위(사용자 수). 이 수마다 진행 수 · 경과 시간을 찍는다.
PAIRED_CHUNK = 1000


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


def paired_bootstrap_ci(a: Sequence[float], b: Sequence[float], draws: int,
                        rng: np.random.Generator) -> dict[str, Any]:
    """같은 사용자의 B − A 평균 차이와 짝 부트스트랩 95% 구간.

    `paired_compare.paired_bootstrap` 과 같은 관례다 — 사용자 색인을 **한 번만** 복원
    추출해 (B − A) 차이 벡터에 함께 적용한다(짝 유지). A · B 는 사용자별로 짝지어야
    하므로 길이가 같아야 한다. 같은 정책 둘이면 차이 벡터가 0 이라 구간이 [0, 0] 이다.
    """
    left = np.asarray(list(a), dtype=float)
    right = np.asarray(list(b), dtype=float)
    if left.shape != right.shape:
        raise ValueError(f"짝 부트스트랩은 A · B 길이가 같아야 합니다: {left.size} != {right.size}")
    if left.size == 0:
        return {"a": None, "b": None, "diff": None, "ci95": [None, None], "draws": int(draws)}
    difference = right - left
    index = rng.integers(0, left.size, size=(int(draws), left.size))
    sampled = difference[index].mean(axis=1)
    return {"a": float(left.mean()), "b": float(right.mean()), "diff": float(difference.mean()),
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


def min_items_hit(pages: Sequence[Any], wanted: Iterable[str], min_items: int = MIN_ITEMS) -> float:
    """상품 ``min_items`` 개 미만 행을 버린 뒤에도 원하는 상품이 남아 있는가.

    앱 홈의 `app.home.min-items`(기본 3, S2) 규칙을 두 정책 페이지에 똑같이 적용한
    실제 구매 적중이다. 행 크기는 페이지 안 `row_rank` 로 센다(행은 페이지마다 0부터).
    """
    purchased = set(wanted)
    for page in pages:
        sizes = Counter(impression.row_rank for impression in page.impressions)
        for impression in page.impressions:
            if sizes[impression.row_rank] >= int(min_items) and impression.article_id in purchased:
                return 1.0
    return 0.0


class RecordingSource:
    """PageSource 를 감싸 실패한 요청을 사용자별로 세고 기억한다(S3, #462).

    `HttpSource` 는 한 요청이 실패하면 예외를 올려 배치 전체를 중단시킨다. 짝 설계는
    실패를 조용히 넘기지 않되 **짝을 유지**해야 하므로, 요청을 하나씩 보내고 실패는
    그 사용자 id 에 적어 둔 뒤 빈 페이지를 돌려준다. 나중에 두 정책 모두에서 뺀다.
    """

    def __init__(self, source: Any) -> None:
        self.source = source
        self.failed: set[str] = set()
        self.errors: dict[str, str] = {}

    @property
    def name(self) -> str:
        return getattr(self.source, "name", "source")

    def pages(self, examples: list[Any]) -> list[Any]:
        result: list[Any] = []
        for example in examples:
            customer = str(example.customer_id)
            try:
                result.extend(self.source.pages([example]))
            except Exception as exc:  # noqa: BLE001 - 실패를 세고 계속한다(짝 유지)
                self.failed.add(customer)
                self.errors.setdefault(customer, str(exc))
                result.append([])
        return result


def paired_kept(users: Sequence[Any], source_a: Any, source_b: Any) -> tuple[list[Any], set[str]]:
    """실패한 사용자를 두 정책 모두에서 뺀 목록과 뺀 집합(짝 유지)."""
    failed = set(getattr(source_a, "failed", set())) | set(getattr(source_b, "failed", set()))
    kept = [user for user in users if str(user.customer_id) not in failed]
    return kept, failed


def run_paired_arms(users: Sequence[Any], source_a: Any, source_b: Any, *, vocab: Vocab,
                    attributes: dict[str, tuple], content_map: dict[str, int], prices: Any,
                    chunk: int = PAIRED_CHUNK, log: Any = None) -> tuple[list[Any], list[Any]]:
    """모든 사용자에게 A · B 를 차례로 돌린다. `chunk` 명마다 진행을 `log` 로 알린다.

    사용자 난수는 `simulate` 안에서 `rng_for(customer, date)` 로 정해지므로 두 정책이
    같은 시드에서 시작한다(공통 난수). 청크는 사용자마다 독립이라 한 번에 돌린 것과 같다.
    """
    pages_a: list[Any] = []
    pages_b: list[Any] = []
    total = len(users)
    started = time.monotonic()
    for start in range(0, total, int(chunk)):
        part = users[start:start + int(chunk)]
        pages_a.extend(simulate(part, source_a, vocab=vocab, attributes=attributes,
                                content_rows_map=content_map, prices=prices))
        pages_b.extend(simulate(part, source_b, vocab=vocab, attributes=attributes,
                                content_rows_map=content_map, prices=prices))
        if log is not None:
            log(f"{min(start + int(chunk), total):,}/{total:,}명 · {time.monotonic() - started:.1f}s")
    return pages_a, pages_b


def paired_metrics(users: Sequence[Any], pages_a: Sequence[Any], pages_b: Sequence[Any], *,
                   draws: int, seed: int, min_items: int = MIN_ITEMS) -> tuple[dict[str, Any], pd.DataFrame]:
    """짝 사용자별 지표 · 짝 부트스트랩 · 고객별 값 표를 만든다.

    네 지표와 보조(``purchase_hit_min_items``)를 모두 사용자 단위 짝으로 낸다. 같은
    난수기(시드)를 지표마다 이어 쓴다(비대응 모드와 같다).
    """
    a_pages = _by_customer(pages_a)
    b_pages = _by_customer(pages_b)
    a_values = _metric_values(a_pages, users)
    b_values = _metric_values(b_pages, users)
    rng = np.random.default_rng(int(seed))
    metrics: dict[str, Any] = {
        name: paired_bootstrap_ci(a_values[name], b_values[name], draws, rng) for name in METRICS}
    a_hit = [min_items_hit(a_pages.get(str(user.customer_id), []), set(user.wanted), min_items)
             for user in users]
    b_hit = [min_items_hit(b_pages.get(str(user.customer_id), []), set(user.wanted), min_items)
             for user in users]
    metrics["purchase_hit_min_items"] = paired_bootstrap_ci(a_hit, b_hit, draws, rng)
    frame = pd.DataFrame({"customer_id": [str(user.customer_id) for user in users],
                          **{f"a_{name}": a_values[name] for name in METRICS},
                          **{f"b_{name}": b_values[name] for name in METRICS},
                          "a_purchase_hit_min_items": a_hit, "b_purchase_hit_min_items": b_hit})
    return metrics, frame


def _arms(args: argparse.Namespace) -> dict[str, tuple[str, str, str | None]]:
    """arm → (kind, url, compose).

    compose 옵션(`--a-compose` · `--b-compose` · `--aa`)이 있으면 한 v2 서버를 compose 로
    가른다. `--aa` 는 두 arm 모두 `--a-compose` 다(A/A 점검). 없으면 기존대로 `--mode ab` 는
    v1 대 v2, `--mode aa` 는 둘 다 v2(compose 없음 = 서버 기본 generate)다.
    """
    if args.aa:
        if not args.a_compose:
            raise ValueError("--aa 에는 --a-compose 가 필요합니다")
        return {"A": ("v2", args.v2_url, args.a_compose), "B": ("v2", args.v2_url, args.a_compose)}
    if args.a_compose or args.b_compose:
        return {"A": ("v2", args.v2_url, args.a_compose or "generate"),
                "B": ("v2", args.v2_url, args.b_compose or "generate")}
    if args.mode == "ab":
        if not args.v1_url:
            raise ValueError("--mode ab 에는 --v1-url 이 필요합니다")
        return {"A": ("v1", args.v1_url, None), "B": ("v2", args.v2_url, None)}
    return {"A": ("v2", args.v2_url, None), "B": ("v2", args.v2_url, None)}


def notation(kinds: dict[str, tuple[str, str, str | None]], *, split: bool,
             mode: str) -> tuple[str, dict[str, str]]:
    """결과의 모드 · 정책 표기(S1, #454).

    compose 로 가르면 두 arm 이 모두 v2 라 기존 `aa` · `v2` 표기가 안 맞는다 — 모드를 compose
    값에서 만들고(A/A 는 "aa", A/B 는 "ab"), 정책도 compose 값으로 적는다. compose 를 안 쓰면
    기존대로 `--mode` 와 arm 의 kind(v1 · v2)를 쓴다.
    """
    if not split:
        return mode, {arm: kinds[arm][0] for arm in ("A", "B")}
    return ("aa" if kinds["A"][2] == kinds["B"][2] else "ab",
            {arm: (kinds[arm][2] or "generate") for arm in ("A", "B")})


def run(args: argparse.Namespace) -> dict[str, Any]:
    if getattr(args, "paired", False):
        return run_paired(args)
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
    users, prices = build_eval_users(vocab, transactions, customers, attributes, content_map,
                                     request=request, history_events=int(args.history_events))
    users.sort(key=lambda user: user.customer_id)
    arm_users = {"A": [user for user in users if arm_of(user.customer_id) == "A"],
                 "B": [user for user in users if arm_of(user.customer_id) == "B"]}
    # S1(#454): compose 옵션이 있으면 한 v2 서버를 compose 로 가른다. 없으면 기존 v1 대 v2 · A/A.
    kinds = _arms(args)
    split = bool(args.aa or args.a_compose or args.b_compose)
    mode, policies = notation(kinds, split=split, mode=args.mode)
    arm_pages: dict[str, dict[str, list[Any]]] = {}
    sources: dict[str, HttpSource] = {}
    for arm in ("A", "B"):
        kind, url, compose = kinds[arm]
        source = HttpSource(url, kind, vocab, sections, compose=compose,
                            history_events=int(args.history_events))
        sources[arm] = source
        arm_pages[arm] = _by_customer(simulate(arm_users[arm], source, vocab=vocab, attributes=attributes,
                                               content_rows_map=content_map, prices=prices))
    rng = np.random.default_rng(int(args.seed))
    metrics: dict[str, Any] = {}
    for name in METRICS:
        values = {arm: _metric_values(arm_pages[arm], arm_users[arm])[name] for arm in ("A", "B")}
        metrics[name] = bootstrap_ci(values["A"], values["B"], int(args.bootstrap), rng)
    report = {"mode": mode, "request_date": str(request.date()),
              "customers_requested": int(args.customers), "seed": int(args.seed),
              "bootstrap_draws": int(args.bootstrap), "holdout_buyers": len(buyers),
              "users": {"A": len(arm_users["A"]), "B": len(arm_users["B"])},
              "policies": policies,
              "compose": {arm: kinds[arm][2] for arm in ("A", "B")},
              "urls": {arm: kinds[arm][1] for arm in ("A", "B")},
              # S1: 응답의 실제 composition · fallback 개수(대체 경로가 얼마나 탔는지).
              "response_composition": {arm: dict(sources[arm].composition_counts) for arm in ("A", "B")},
              "response_fallback": {arm: dict(sources[arm].fallback_counts) for arm in ("A", "B")},
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
    for arm in ("A", "B"):
        composition = report.get("response_composition", {}).get(arm) or {}
        fallback = report.get("response_fallback", {}).get(arm) or {}
        lines.append(f"- {arm} `{report.get('compose', {}).get(arm)}` · 응답 composition `{composition}`"
                     f" · fallback `{fallback}`")
    lines.append("")
    return "\n".join(lines) + "\n"


def run_paired(args: argparse.Namespace) -> dict[str, Any]:
    """S3(#462) 짝 설계 — 모든 사용자에게 A · B 를 차례로 돌린다.

    `--customers 0` 은 홀드아웃 구매 고객 전원이다. 반응 난수는 `rng_for(customer,
    date)` 그대로라 두 정책이 같은 시드에서 시작한다. 실패한 사용자는 두 정책 모두에서
    빼고 뺀 수를 결과에 남긴다. 고객별 값은 `--paired-customers` parquet 로도 남긴다.
    """
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
    if int(args.customers) == 0:
        chosen = sorted(str(value) for value in buyers)
    else:
        taken = min(int(args.customers), len(buyers))
        if taken == 0:
            raise ValueError("평가 창에 산 고객이 없습니다")
        chosen = sorted(str(value) for value in np.random.default_rng(int(args.seed)).choice(
            np.asarray(buyers), size=taken, replace=False).tolist())
    if not chosen:
        raise ValueError("평가 창에 산 고객이 없습니다")
    transactions = eval_transactions(normalized / "transactions.parquet", chosen, request)
    customers = pd.read_parquet(normalized / "customers.parquet")
    users, prices = build_eval_users(vocab, transactions, customers, attributes, content_map,
                                     request=request, history_events=int(args.history_events))
    users.sort(key=lambda user: user.customer_id)

    a_compose = args.a_compose or "rule"
    b_compose = args.b_compose or "hybrid"
    source_a = RecordingSource(HttpSource(args.v2_url, "v2", vocab, sections, compose=a_compose,
                                          history_events=int(args.history_events)))
    source_b = RecordingSource(HttpSource(args.v2_url, "v2", vocab, sections, compose=b_compose,
                                          history_events=int(args.history_events)))
    print(f"짝 설계 A/B — 사용자 {len(users):,}명 · A `{a_compose}` · B `{b_compose}`", flush=True)
    pages_a, pages_b = run_paired_arms(users, source_a, source_b, vocab=vocab, attributes=attributes,
                                       content_map=content_map, prices=prices,
                                       chunk=int(args.paired_chunk), log=lambda line: print(f"  {line}", flush=True))
    kept, failed = paired_kept(users, source_a, source_b)
    metrics, frame = paired_metrics(kept, pages_a, pages_b, draws=int(args.bootstrap),
                                    seed=int(args.seed), min_items=int(args.min_items))
    report = {"mode": "paired-ab", "design": "paired", "request_date": str(request.date()),
              "customers_requested": int(args.customers), "seed": int(args.seed),
              "bootstrap_draws": int(args.bootstrap), "holdout_buyers": len(buyers),
              "customers_sampled": len(chosen), "users": len(users), "users_paired": len(kept),
              "min_items": int(args.min_items), "policies": {"A": a_compose, "B": b_compose},
              "compose": {"A": a_compose, "B": b_compose},
              "urls": {"A": str(args.v2_url), "B": str(args.v2_url)},
              # 실패한 요청은 조용히 넘기지 않는다 — 센 수 · 뺀 수 · 표본을 남긴다(짝 유지).
              "failures": {"A": len(source_a.failed), "B": len(source_b.failed),
                           "dropped": len(failed), "sample": sorted(failed)[:20]},
              "response_composition": {"A": dict(source_a.source.composition_counts),
                                       "B": dict(source_b.source.composition_counts)},
              "response_fallback": {"A": dict(source_a.source.fallback_counts),
                                    "B": dict(source_b.source.fallback_counts)},
              "metrics": metrics, "elapsed_seconds": time.monotonic() - started}
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (out / "report-table.md").write_text(_paired_table(report), encoding="utf-8")
    if args.paired_customers:
        path = Path(args.paired_customers)
        path.parent.mkdir(parents=True, exist_ok=True)
        frame.to_parquet(path, index=False)
        print(f"고객별 값 parquet: {path}", flush=True)
    print(_paired_table(report), end="")
    return report


def _paired_table(report: dict[str, Any]) -> str:
    lines = [f"# 가상 사용자 A/B (짝 설계) — {report['mode']}", "",
             f"- 요청 {report['request_date']} · 홀드아웃 구매자 {report['holdout_buyers']:,}명 중 "
             f"{report['customers_sampled']:,}명(seed {report['seed']})",
             f"- 짝 사용자 {report['users_paired']:,}명 / 전체 {report['users']:,}명 · "
             f"실패로 뺀 사용자 {report['failures']['dropped']:,}명(요청 실패 A "
             f"{report['failures']['A']:,} · B {report['failures']['B']:,})",
             f"- 부트스트랩 {report['bootstrap_draws']:,}회(사용자 단위 짝) · 정책 A "
             f"`{report['policies']['A']}` · 정책 B `{report['policies']['B']}` · "
             f"최소 항목 {report['min_items']}", "",
             "| 지표 | A | B | B − A | 95% 구간 |", "|---|---:|---:|---:|---|"]
    for name in list(METRICS) + ["purchase_hit_min_items"]:
        value = report["metrics"][name]
        lines.append(f"| {METRIC_LABELS[name]} | {_num(value['a'])} | {_num(value['b'])} | {_num(value['diff'])}"
                     f" | [{_num(value['ci95'][0])}, {_num(value['ci95'][1])}] |")
    lines.append("")
    for arm in ("A", "B"):
        composition = report.get("response_composition", {}).get(arm) or {}
        fallback = report.get("response_fallback", {}).get(arm) or {}
        lines.append(f"- {arm} `{report.get('compose', {}).get(arm)}` · 응답 composition `{composition}`"
                     f" · fallback `{fallback}`")
    lines.append("")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--v1-url")
    parser.add_argument("--v2-url", required=True)
    parser.add_argument("--customers", type=int, default=5000)
    parser.add_argument("--history-events", type=int, default=config.HISTORY_EVENTS,
                        help="요청 본문에 실을 최근 구매 이벤트 수(기본 config.HISTORY_EVENTS). "
                             "S1 은 dataset 이력 상한과 같은 100 을 준다")
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--bootstrap", type=int, default=2000)
    parser.add_argument("--mode", choices=("ab", "aa"), default="ab")
    parser.add_argument("--a-compose", choices=("generate", "rule", "hybrid", "hybrid-cached"),
                        help="S1: 한 v2 서버에서 A arm 이 쓸 compose")
    parser.add_argument("--b-compose", choices=("generate", "rule", "hybrid", "hybrid-cached"),
                        help="S1: 한 v2 서버에서 B arm 이 쓸 compose")
    parser.add_argument("--aa", action="store_true",
                        help="S1: 두 arm 모두 --a-compose 로 보낸다(A/A 점검)")
    # S3(#462) 짝 설계 — 사용자를 나누지 않고 모든 사용자에게 A · B 를 차례로 돌린다.
    parser.add_argument("--paired", action="store_true",
                        help="S3: 짝 설계. 모든 사용자에게 A · B 를 모두 돌린다(--customers 0 = 전원)")
    parser.add_argument("--paired-customers", type=Path,
                        help="S3: 고객별 A · B 값을 담을 parquet 경로(저장소 밖). 없으면 안 쓴다")
    parser.add_argument("--min-items", type=int, default=MIN_ITEMS,
                        help="S3 보조 지표가 버리는 행의 최소 항목 수(app.home.min-items, 기본 3)")
    parser.add_argument("--paired-chunk", type=int, default=PAIRED_CHUNK,
                        help="S3 진행 로그 단위(사용자 수, 기본 1000)")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    run(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
