#!/usr/bin/env python3
"""Measure the gap between GenPage v2 training and serving contexts (#315).

This program deliberately does not start a server.  It reconstructs the five
request shapes locally with :func:`genpage2.prompt.build_prompt`, so a run is
repeatable from a checkpoint and the immutable evaluation archive.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Any, Iterable

import numpy as np
import pandas as pd


ROOT = Path(__file__).resolve().parents[1]
PERSONALIZATION = ROOT / "personalization"
if str(PERSONALIZATION) not in sys.path:
    sys.path.insert(0, str(PERSONALIZATION))

from genpage2 import config  # noqa: E402
from genpage2.prompt import build_prompt  # noqa: E402
from genpage2.vocab import _article_id  # noqa: E402


LEVELS = ("a", "b", "c", "d", "e")
LEVEL_LABELS = {
    "a": "학습 문맥", "b": "상품 id", "c": "상품 id + 구매 시각",
    "d": "상품 id + 구매 시각 + 가격·채널", "e": "전체 서빙 문맥",
}


def compact_json_bytes(value: dict[str, Any]) -> int:
    """Return exactly the UTF-8 byte count used by the HTTP JSON body."""
    return len(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))


def mismatch_rate(expected: Iterable[int], actual: Iterable[int]) -> tuple[float, int]:
    """Position-wise differences plus unmatched suffix, divided by max length."""
    left, right = list(expected), list(actual)
    different = sum(a != b for a, b in zip(left, right)) + abs(len(left) - len(right))
    return (different / max(len(left), len(right), 1), different)


def top12_jaccard(expected: Iterable[str], actual: Iterable[str]) -> float:
    left, right = set(list(expected)[:12]), set(list(actual)[:12])
    return len(left & right) / len(left | right) if left or right else 1.0


def percentile(values: Iterable[float], q: float) -> float:
    values = sorted(float(value) for value in values)
    if not values:
        return 0.0
    return float(np.percentile(values, q, method="linear"))


def _history(value: Any, limit: int = 100) -> list[str]:
    if value is None or (isinstance(value, float) and np.isnan(value)):
        return []
    return [_article_id(item) for item in list(value)[:limit]]


def _event(row: Any, level: str) -> dict[str, Any]:
    event: dict[str, Any] = {"item": _article_id(row.article_id)}
    if level in ("c", "d", "e"):
        event["at"] = pd.Timestamp(row.t_dat).date().isoformat()
    if level in ("d", "e"):
        event["price"] = float(row.price)
        event["action"] = "STORE" if int(row.sales_channel_id) == 1 else "ONLINE"
    return event


def make_level_request(level: str, history_recent_first: Iterable[Any], transactions: pd.DataFrame,
                       profile: dict[str, Any] | None, request_at: Any) -> tuple[dict[str, Any], list[dict[str, Any]], dict[str, Any] | None, Any]:
    """Make one wire request and its equivalent direct ``build_prompt`` inputs.

    ``history`` is always recent-first, exactly as the current application
    sends it.  Rich levels use ``events`` in oldest-first order because that is
    the server adapter's documented input order.  Level e uses the final 60
    pre-request transactions, the same ordering as the training archive.
    """
    if level not in LEVELS:
        raise ValueError(f"unknown X1 level: {level}")
    if level == "a":
        raise ValueError("a 는 요청으로 재구성하지 않고 eval.npz 문맥을 그대로 사용합니다")
    history = [_article_id(item) for item in list(history_recent_first)[:100]]
    if level == "b":
        events = [{"item": item} for item in reversed(history)]
        return {"history": history}, events, None, None

    ordered = transactions.sort_values("t_dat", kind="mergesort")
    if level == "e":
        ordered = ordered.tail(config.HISTORY_EVENTS)
    else:
        # c/d enrich the app's list.  Match repeated item ids to the newest
        # matching transactions, then restore oldest-first transaction order.
        wanted = list(reversed(history))
        pools: dict[str, list[int]] = {}
        for index, row in ordered.iterrows():
            pools.setdefault(_article_id(row.article_id), []).append(index)
        taken: list[int] = []
        for item in wanted:
            values = pools.get(item, [])
            if values:
                taken.append(values.pop())
        taken_set = set(taken)
        ordered = ordered.loc[[index for index in ordered.index if index in taken_set]]
    events = [_event(row, level) for row in ordered.itertuples(index=False)]
    request: dict[str, Any] = {"history": history, "events": events}
    use_profile = profile if level == "e" else None
    use_now = pd.Timestamp(request_at).isoformat() if level == "e" else None
    if level == "e":
        request["profile"] = use_profile
        request["now"] = use_now
    return request, events, use_profile, use_now


def first_token_differences(customer_id: str, expected: list[int], actual: list[int], vocab: Any,
                            limit: int = 5) -> list[dict[str, Any]]:
    differences = []
    sentinel = "<missing>"
    for position in range(max(len(expected), len(actual))):
        before = expected[position] if position < len(expected) else None
        after = actual[position] if position < len(actual) else None
        if before == after:
            continue
        differences.append({"customer_id": customer_id, "position": position,
                            "expected": vocab.tokens[before] if before is not None else sentinel,
                            "actual": vocab.tokens[after] if after is not None else sentinel})
        if len(differences) == limit:
            break
    return differences


def _profile_by_customer(path: Path) -> dict[str, dict[str, Any]]:
    customers = pd.read_parquet(path)
    def json_profile(row: Any) -> dict[str, Any]:
        values = row._asdict()
        values.pop("customer_id", None)
        return {str(key): (None if pd.isna(value) else value.item() if isinstance(value, np.generic) else value)
                for key, value in values.items()}
    return {str(row.customer_id): json_profile(row) for row in customers.itertuples(index=False)}


def _transactions_by_customer(path: Path, request_at: pd.Timestamp,
                              customer_ids: Iterable[str] | None = None) -> dict[str, pd.DataFrame]:
    columns = ["t_dat", "customer_id", "article_id", "sales_channel_id", "price"]
    wanted = None if customer_ids is None else sorted({str(value) for value in customer_ids})
    if wanted == []:
        return {}
    read_kwargs: dict[str, Any] = {"columns": columns}
    if wanted is not None:
        # With the normalized Parquet layout pyarrow applies this predicate at
        # row-group read time, avoiding a full 30M-row customer table scan.
        read_kwargs["filters"] = [("customer_id", "in", wanted)]
    tx = pd.read_parquet(path, **read_kwargs)
    tx["t_dat"] = pd.to_datetime(tx["t_dat"])
    tx = tx[tx["t_dat"] < request_at].copy()
    return {str(customer): group.drop(columns="customer_id")
            for customer, group in tx.groupby(tx["customer_id"].astype(str), sort=False)}


def _page_items(rows: Any) -> list[str]:
    return [item for row in rows for item in row.items][:12]


def _generate_batches(decoder: Any, examples: list[dict[str, Any]], *, batch: int = 256) -> list[tuple[Any, int]]:
    """Generate examples in bounded batches while preserving input order."""
    result: list[tuple[Any, int]] = []
    for start in range(0, len(examples), batch):
        result.extend(decoder.generate_batch(
            examples[start:start + batch], n_rows=config.MAX_ROWS,
            items_per_row=config.ITEMS_PER_ROW, prefix=2,
        ))
    return result


def _table(levels: dict[str, dict[str, Any]]) -> str:
    lines = ["수준  토큰 불일치  콘텐츠 불일치  Jaccard@12  MAP@12  본문 bytes  prompt ms"]
    for level in LEVELS:
        report = levels[level]
        lines.append(f"{level}     {report['token_mismatch_rate']:.4f}      {report['content_mismatch_rate']:.4f}"
                     f"       {report['jaccard_at_12']:.4f}    {report['map_at_12']:.4f}"
                     f"    {report['request_bytes_mean']:.1f}      {report['build_prompt_ms_mean']:.3f}")
    return "\n".join(lines)


def run(args: argparse.Namespace) -> dict[str, Any]:
    # Keep the pure request/mismatch helpers importable in the lightweight test
    # interpreter; decoder loading is only needed by an actual measurement.
    from genpage2.evaluate import _context_at, _load_decoder, map_at_12

    total_started = time.perf_counter()
    base = Path(args.data_dir) if args.data_dir else config.data_dir()
    mode_dir = base / "hm" / "model" / "genpage2" / args.mode
    meta = pd.read_parquet(mode_dir / "eval_meta.parquet").reset_index(drop=True)
    archive = np.load(mode_dir / "eval.npz")
    take = min(max(0, int(args.customers)), len(meta))
    selected = meta.sample(n=take, random_state=config.SEED).sort_index() if take else meta.iloc[:0]
    decoder, vocab, _, content_rows, device = _load_decoder(mode_dir, Path(args.ckpt), args.device)
    request_at = config.request_of(args.mode)
    profiles = _profile_by_customer(base / "hm" / "normalized" / "customers.parquet")
    transactions = _transactions_by_customer(
        base / "hm" / "normalized" / "transactions.parquet", request_at,
        selected["customer_id"].astype(str).tolist(),
    )
    records = {level: {"token_mismatch": [], "content_mismatch": [], "jaccard": [], "bytes": [], "prompt_ms": [],
                       "pages": {}, "violations": 0, "generation_ms": 0.0, "level_ms": 0.0} for level in LEVELS}
    consistency_differences: list[dict[str, Any]] = []
    customer_rows: list[tuple[str, list[int], list[int], list[str], list[str]]] = []
    examples_by_level: dict[str, list[dict[str, Any]]] = {level: [] for level in LEVELS}
    for index, row in selected.iterrows():
        customer = str(row.customer_id)
        expected_tokens, expected_content = _context_at(archive, int(index))
        customer_tx = transactions.get(customer, pd.DataFrame(columns=["t_dat", "article_id", "sales_channel_id", "price"]))
        history = _history(row.history)
        customer_rows.append((customer, expected_tokens, expected_content, history, []))
        for level in LEVELS:
            level_started = time.perf_counter()
            if level == "a":
                # (a) is the archive's immutable training input.  Its body
                # size is represented by the complete request needed to
                # reproduce it, but neither tokens nor build time are rebuilt.
                request, _, _, _ = make_level_request("e", _history(row.history), customer_tx,
                                                       profiles.get(customer), request_at)
                tokens, content = expected_tokens, expected_content
                records[level]["prompt_ms"].append(0.0)
            else:
                request, events, profile, now = make_level_request(level, _history(row.history), customer_tx,
                                                                   profiles.get(customer), request_at)
                began = time.perf_counter()
                tokens, content, _ = build_prompt(vocab, events=events, profile=profile, now=now, content_rows=content_rows)
                records[level]["prompt_ms"].append((time.perf_counter() - began) * 1000)
            token_rate, token_count = mismatch_rate(expected_tokens, tokens)
            content_rate, content_count = mismatch_rate(expected_content, content)
            examples_by_level[level].append({"ctx_tokens": tokens, "ctx_content": content,
                                             "history_articles": history})
            records[level]["token_mismatch"].append(token_rate)
            records[level]["content_mismatch"].append(content_rate)
            records[level]["bytes"].append(compact_json_bytes(request))
            if level == "e" and token_count and len(consistency_differences) < 5:
                consistency_differences.extend(first_token_differences(customer, expected_tokens, tokens, vocab,
                                                                        5 - len(consistency_differences)))
            records[level]["level_ms"] += (time.perf_counter() - level_started) * 1000

    prompt_total_ms = float(sum(sum(values["prompt_ms"]) for values in records.values()))
    # Generate the immutable training level first.  The resulting page is the
    # reference for Jaccard; batch decoding has the same per-example semantics
    # as the single-example decoder (covered by test_x_tools.py).
    archive_examples = [{"ctx_tokens": row[1], "ctx_content": row[2], "history_articles": row[3]}
                        for row in customer_rows]
    expected_started = time.perf_counter()
    expected_results = _generate_batches(decoder, archive_examples)
    expected_generation_ms = (time.perf_counter() - expected_started) * 1000
    expected_pages: dict[str, list[str]] = {}
    expected_bad = 0
    for (customer, _, _, _, _), (rows, bad) in zip(customer_rows, expected_results, strict=True):
        expected_pages[customer] = _page_items(rows)
        expected_bad += int(bad)
    records["a"]["generation_ms"] = expected_generation_ms
    records["a"]["violations"] = expected_bad
    records["a"]["pages"] = expected_pages
    for level in LEVELS[1:]:
        generation_started = time.perf_counter()
        generated_results = _generate_batches(decoder, examples_by_level[level])
        records[level]["generation_ms"] = (time.perf_counter() - generation_started) * 1000
        records[level]["pages"] = {}
        for (customer, expected_tokens, expected_content, _, _), (rows, bad) in zip(customer_rows, generated_results, strict=True):
            page = _page_items(rows)
            records[level]["pages"][customer] = page
            records[level]["jaccard"].append(top12_jaccard(expected_pages[customer], page))
            records[level]["violations"] += int(bad)
    records["a"]["jaccard"] = [1.0] * take

    levels: dict[str, dict[str, Any]] = {}
    for level, values in records.items():
        score, scored = map_at_12(selected, values["pages"])
        levels[level] = {
            "label": LEVEL_LABELS[level], "token_mismatch_rate": float(np.mean(values["token_mismatch"])) if take else 0.0,
            "content_mismatch_rate": float(np.mean(values["content_mismatch"])) if take else 0.0,
            "jaccard_at_12": float(np.mean(values["jaccard"])) if take else 0.0,
            "map_at_12": score, "map_customers": scored, "request_bytes_mean": float(np.mean(values["bytes"])) if take else 0.0,
            "build_prompt_ms_mean": float(np.mean(values["prompt_ms"])) if take else 0.0,
            "build_prompt_ms_p95": percentile(values["prompt_ms"], 95), "violations": values["violations"],
            "prompt_total_ms": float(np.sum(values["prompt_ms"])),
            "generation_total_ms": values["generation_ms"],
            "level_total_ms": float(np.sum(values["prompt_ms"])) + values["generation_ms"],
        }
    report = {"experiment": "X1", "mode": args.mode, "checkpoint": str(args.ckpt), "device": device,
              "seed": config.SEED, "customers": take, "batch_size": 256,
              "timings_ms": {"total": (time.perf_counter() - total_started) * 1000,
                             "prompt_build": prompt_total_ms,
                             "generation": sum(float(values["generation_ms"]) for values in records.values())},
              "levels": levels,
              "consistency_violation": bool(consistency_differences), "consistency_differences": consistency_differences}
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "x1.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(_table(levels))
    if report["consistency_violation"]:
        print("WARNING: e 문맥이 학습 문맥과 다릅니다.")
    return report


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("validate", "final"), required=True)
    parser.add_argument("--ckpt", required=True, type=Path)
    parser.add_argument("--customers", type=int, default=5000)
    parser.add_argument("--device", choices=("cpu", "mps", "auto"), default="cpu")
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--data-dir", type=Path, help="GENPAGE_DATA 대신 사용할 데이터 루트")
    return parser.parse_args(argv)


if __name__ == "__main__":
    run(parse_args())
