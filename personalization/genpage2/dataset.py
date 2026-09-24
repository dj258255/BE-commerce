"""Create GenPage v2 training/evaluation examples and their portable files."""

from __future__ import annotations

import argparse
import json
import resource
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import numpy as np
import pandas as pd

from . import config
from .vocab import Vocab, _article_id, content_rows


def age_bucket(age: object) -> str:
    if pd.isna(age):
        return "NA"
    age = float(age)
    if age < 20:
        return "<20"
    if age < 25:
        return "20-24"
    if age < 30:
        return "25-29"
    if age < 40:
        return "30-39"
    if age < 50:
        return "40-49"
    if age < 60:
        return "50-59"
    return "60+"


def ago_bucket(request_date: object, event_date: object) -> str:
    days = (pd.Timestamp(request_date).normalize() - pd.Timestamp(event_date).normalize()).days
    if days < 0:
        raise ValueError("future event cannot be put in context")
    for limit, name in ((3, "AGO_0-3"), (7, "AGO_4-7"), (14, "AGO_8-14"),
                        (30, "AGO_15-30"), (60, "AGO_31-60"), (120, "AGO_61-120"),
                        (365, "AGO_121-365")):
        if days <= limit:
            return name
    return "AGO_366+"


AGO_LIMITS = np.array([3, 7, 14, 30, 60, 120, 365], dtype=np.int64)


def _ago_token_ids(vocab: Vocab, request_day: int, event_days: np.ndarray) -> np.ndarray:
    """Vectorized version of ``ago_bucket`` used by the full dataset build."""
    bins = np.searchsorted(AGO_LIMITS, request_day - event_days, side="left")
    ago_ids = np.asarray([vocab.id(f"AGO_{x}") for x in ("0-3", "4-7", "8-14", "15-30", "31-60", "61-120", "121-365", "366+")], dtype=np.int32)
    return ago_ids[bins]


def _value(row: pd.Series | dict | None, key: str, default: str = "NA") -> str:
    if row is None or key not in row or pd.isna(row[key]):
        return default
    return str(row[key])


def profile_tokens(vocab: Vocab, customer: pd.Series | dict | None) -> list[int]:
    age = age_bucket(None if customer is None else customer.get("age", np.nan))
    club = _value(customer, "club_member_status")
    news = _value(customer, "fashion_news_frequency")
    fn = "1" if _value(customer, "FN", "") == "1.0" or _value(customer, "FN", "") == "1" else "NA"
    active = "1" if _value(customer, "Active", "") == "1.0" or _value(customer, "Active", "") == "1" else "NA"
    return [vocab.id(f"AGE_{age}"), vocab.id(f"CLUB_{club}"), vocab.id(f"NEWS_{news}"),
            vocab.id(f"FN_{fn}"), vocab.id(f"ACTIVE_{active}")]


def _ordered_before(events: pd.DataFrame, request_date: object) -> pd.DataFrame:
    dates = pd.to_datetime(events["t_dat"])
    request = pd.Timestamp(request_date)
    # ``_customer_event_groups`` already supplies this order.  Avoid re-sorting a
    # customer's entire history for every one of the eight request dates.
    if dates.is_monotonic_increasing:
        return events.iloc[:dates.searchsorted(request, side="left")]
    result = events.loc[dates < request].copy()
    # mergesort is stable: source order settles purchases made on the same day.
    return result.sort_values("t_dat", kind="mergesort")


def build_context(vocab: Vocab, events: pd.DataFrame, request_date: object,
                  customer: pd.Series | dict | None, article_content_rows: dict[str, int]) -> tuple[list[int], list[int]]:
    """Return prompt tokens and aligned catalogue-row indexes (or -1)."""
    request = pd.Timestamp(request_date)
    tokens = [vocab.id("BOS"), vocab.id("SEP_PROFILE")] + profile_tokens(vocab, customer)
    tokens += [vocab.id("SEP_REQUEST"), vocab.id(f"DOW_{request.weekday()}"), vocab.id(f"MONTH_{request.month}"), vocab.id("SEP_HISTORY")]
    content = [-1] * len(tokens)
    history = _ordered_before(events, request).tail(config.HISTORY_EVENTS)
    for event in history.itertuples(index=False):
        article = _article_id(getattr(event, "article_id"))
        item = vocab.item(article)
        tokens.extend([item if item is not None else vocab.id("ITEM_FALLBACK"),
                       vocab.id("ACT_STORE") if int(getattr(event, "sales_channel_id")) == 1 else vocab.id("ACT_ONLINE"),
                       vocab.id(ago_bucket(request, getattr(event, "t_dat")))])
        content.extend([article_content_rows[article], -1, -1])
    tokens.append(vocab.id("SEP_PAGE"))
    content.append(-1)
    return tokens, content


def build_page(vocab: Vocab, history: pd.DataFrame, purchases: pd.DataFrame) -> list[int] | None:
    """Make target [row, item ...] tokens, or None when no generatable item exists."""
    prior = {_article_id(x) for x in history["article_id"].tolist()}
    dates = pd.to_datetime(purchases["t_dat"])
    target = purchases if dates.is_monotonic_increasing else purchases.sort_values("t_dat", kind="mergesort")
    rows: dict[int, list[tuple[pd.Timestamp, str, int]]] = {}
    seen: set[str] = set()
    for event in target.itertuples(index=False):
        article = _article_id(getattr(event, "article_id"))
        if article in seen:
            continue
        seen.add(article)
        item = vocab.item(article)
        if item is None:
            continue
        row = vocab.id("ROW_REPEAT") if article in prior else vocab.row_of(article)
        rows.setdefault(row, []).append((pd.Timestamp(getattr(event, "t_dat")), article, item))
    if not rows:
        return None
    # First purchase time breaks equal row-size ties; a row's items retain purchase order.
    ranked = sorted(rows.items(), key=lambda pair: (-len(pair[1]), pair[1][0][0]))[:config.MAX_ROWS]
    page: list[int] = []
    for row, items in ranked:
        page.append(row)
        page.extend(item for _, _, item in items[:config.ITEMS_PER_ROW])
    return page + [vocab.id("EOS")]


@dataclass
class Example:
    customer_id: str
    request_date: pd.Timestamp
    ctx_tokens: np.ndarray | list[int]
    ctx_content: np.ndarray | list[int]
    page_tokens: np.ndarray | list[int]
    truth: list[str]
    history: list[str]
    has_vocab_history: bool | None = None
    page_empty: bool | None = None

    def __post_init__(self) -> None:
        self.ctx_tokens = np.asarray(self.ctx_tokens, dtype=np.int32)
        self.ctx_content = np.asarray(self.ctx_content, dtype=np.int32)
        self.page_tokens = np.asarray(self.page_tokens, dtype=np.int32)


def _page_from_arrays(vocab: Vocab, history_articles: set[str], dates: np.ndarray,
                      articles: np.ndarray, item_tokens: np.ndarray, row_tokens: np.ndarray) -> np.ndarray | None:
    """Fast equivalent of :func:`build_page` for already customer/time-sorted data."""
    rows: dict[int, list[tuple[object, int]]] = {}
    seen: set[str] = set()
    for date, article, item, row in zip(dates, articles, item_tokens, row_tokens, strict=True):
        if article in seen:
            continue
        seen.add(article)
        if int(item) < 0:
            continue
        row = vocab.id("ROW_REPEAT") if article in history_articles else int(row)
        rows.setdefault(row, []).append((date, int(item)))
    if not rows:
        return None
    ranked = sorted(rows.items(), key=lambda pair: (-len(pair[1]), pair[1][0][0]))[:config.MAX_ROWS]
    page: list[int] = []
    for row, values in ranked:
        page.append(row)
        page.extend(item for _, item in values[:config.ITEMS_PER_ROW])
    return np.asarray(page + [vocab.id("EOS")], dtype=np.int32)


def _context_from_arrays(vocab: Vocab, request: pd.Timestamp, profile: list[int], dates: np.ndarray,
                         articles: np.ndarray, item_tokens: np.ndarray, channels: np.ndarray,
                         article_content: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    tokens = [vocab.id("BOS"), vocab.id("SEP_PROFILE"), *profile, vocab.id("SEP_REQUEST"),
              vocab.id(f"DOW_{request.weekday()}"), vocab.id(f"MONTH_{request.month}"), vocab.id("SEP_HISTORY")]
    content = [-1] * len(tokens)
    dates = dates[-config.HISTORY_EVENTS:]
    items = item_tokens[-config.HISTORY_EVENTS:]
    channels = channels[-config.HISTORY_EVENTS:]
    content_rows = article_content[-config.HISTORY_EVENTS:]
    fallback = vocab.id("ITEM_FALLBACK")
    action_store, action_online = vocab.id("ACT_STORE"), vocab.id("ACT_ONLINE")
    actions = np.where(channels == 1, action_store, action_online).astype(np.int32)
    ago = _ago_token_ids(vocab, request.normalize().to_datetime64().astype("datetime64[D]").astype(np.int64), dates)
    for item, action, age, row in zip(items, actions, ago, content_rows, strict=True):
        tokens.extend([int(item) if int(item) != -1 else fallback, int(action), int(age)])
        content.extend([int(row), -1, -1])
    tokens.append(vocab.id("SEP_PAGE"))
    content.append(-1)
    return np.asarray(tokens, dtype=np.int32), np.asarray(content, dtype=np.int32)


def _customer_event_groups(transactions: pd.DataFrame, vocab: Vocab,
                           article_content_rows: dict[str, int]) -> Iterable[tuple[str, dict[str, np.ndarray]]]:
    """Yield sorted NumPy columns, retaining source order for equal dates."""
    tx = transactions
    customer_ids = tx["customer_id"].astype(str).to_numpy()
    article_ids = tx["article_id"].map(_article_id).to_numpy(dtype=str)
    dates = pd.to_datetime(tx["t_dat"]).to_numpy().astype("datetime64[D]").astype(np.int64)
    channels = tx["sales_channel_id"].to_numpy(dtype=np.int8)
    item_tokens = np.fromiter((vocab._item_of.get(a, -1) for a in article_ids), dtype=np.int32, count=len(article_ids))
    row_tokens = np.fromiter((vocab._article_rows.get(a, -1) for a in article_ids), dtype=np.int32, count=len(article_ids))
    contents = np.asarray([article_content_rows.get(a, -1) for a in article_ids], dtype=np.int32)
    codes, customers = pd.factorize(customer_ids, sort=True)
    order = np.lexsort((np.arange(len(tx), dtype=np.int64), dates, codes))
    starts = np.r_[0, np.flatnonzero(np.diff(codes[order])) + 1, len(order)]
    for start, end in zip(starts[:-1], starts[1:], strict=True):
        idx = order[start:end]
        yield str(customers[codes[idx[0]]]), {
            "dates": dates[idx], "articles": article_ids[idx], "item_tokens": item_tokens[idx],
            "row_tokens": row_tokens[idx], "content": contents[idx], "channels": channels[idx],
        }


def generate_examples(vocab: Vocab, transactions: pd.DataFrame, customers: pd.DataFrame,
                      request_date: object, article_content_rows: dict[str, int], *, max_train: int = 2_000_000,
                      sample_customers: int | None = None) -> tuple[list[Example], list[Example]]:
    """Generate examples while keeping the training cap as a bounded reservoir."""
    request = pd.Timestamp(request_date)
    tx = transactions.copy()
    tx["article_id"] = tx["article_id"].map(_article_id)
    if sample_customers is not None:
        ids = np.array(sorted(customers["customer_id"].astype(str).unique()))
        take = min(int(sample_customers), len(ids))
        chosen = set(np.random.default_rng(config.SEED).choice(ids, size=take, replace=False).tolist())
        tx = tx[tx["customer_id"].astype(str).isin(chosen)]
        customers = customers[customers["customer_id"].astype(str).isin(chosen)]
    profiles = {str(row.customer_id): row._asdict() for row in customers.itertuples(index=False)}
    train: list[Example] = []
    evaluation: list[Example] = []
    reservoir_seen = 0
    rng = np.random.default_rng(config.SEED)
    week_end = request + pd.Timedelta(days=config.TARGET_DAYS)
    candidate_dates = [request - pd.Timedelta(days=7 * k) for k in range(1, 9)]
    for customer_id, events in _customer_event_groups(tx, vocab, article_content_rows):
        profile = profiles.get(customer_id)
        profile_ids = profile_tokens(vocab, profile)
        dates = events["dates"]
        articles = events["articles"]
        item_tokens = events["item_tokens"]
        row_tokens = events["row_tokens"]
        channels = events["channels"]
        content = events["content"]
        request_day = request.to_datetime64().astype("datetime64[D]").astype(np.int64)
        # Candidate dates are intentionally newest first and only four valid pages survive.
        made = 0
        for r in candidate_dates:
            r_day = r.to_datetime64().astype("datetime64[D]").astype(np.int64)
            start = int(dates.searchsorted(r_day, side="left"))
            end = int(dates.searchsorted(r_day + config.TARGET_DAYS, side="left"))
            history_articles = set(articles[:start])
            page = _page_from_arrays(vocab, history_articles, dates[start:end], articles[start:end], item_tokens[start:end], row_tokens[start:end])
            if page is None:
                continue
            ctx, ctx_content = _context_from_arrays(vocab, r, profile_ids, dates[:start], articles[:start], item_tokens[:start], channels[:start], content[:start])
            truth = list(dict.fromkeys(articles[start:end].tolist()))
            hist = articles[max(0, start - 100):start][::-1].tolist()
            candidate = Example(customer_id, r, ctx, ctx_content, page, truth, hist)
            reservoir_seen += 1
            if max_train > 0:
                if len(train) < max_train:
                    train.append(candidate)
                else:
                    slot = int(rng.integers(reservoir_seen))
                    if slot < max_train:
                        train[slot] = candidate
            made += 1
            if made == 4:
                break
        start = int(dates.searchsorted(request_day, side="left"))
        end = int(dates.searchsorted(request_day + config.TARGET_DAYS, side="left"))
        if end <= start:
            continue
        history_articles = set(articles[:start])
        has_vocab_history = bool(np.any(item_tokens[:start] >= 0))
        page = _page_from_arrays(vocab, history_articles, dates[start:end], articles[start:end], item_tokens[start:end], row_tokens[start:end])
        page_empty = page is None
        if page_empty:
            page = np.asarray([vocab.id("EOS")], dtype=np.int32)
        ctx, ctx_content = _context_from_arrays(vocab, request, profile_ids, dates[:start], articles[:start], item_tokens[:start], channels[:start], content[:start])
        evaluation.append(Example(customer_id, request, ctx, ctx_content, page,
                                  list(dict.fromkeys(articles[start:end].tolist())),
                                  articles[max(0, start - 100):start][::-1].tolist(),
                                  has_vocab_history=has_vocab_history, page_empty=page_empty))
    return train, evaluation


def _flat(examples: list[Example], field: str, dtype: np.dtype) -> tuple[np.ndarray, np.ndarray]:
    values = [getattr(example, field) for example in examples]
    offsets = np.zeros(len(values) + 1, dtype=np.int64)
    if values:
        offsets[1:] = np.cumsum([len(x) for x in values], dtype=np.int64)
        return np.concatenate(values).astype(dtype, copy=False), offsets
    return np.empty(0, dtype=dtype), offsets


def write_examples(out: str | Path, name: str, examples: list[Example]) -> None:
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    ctx, ctx_offsets = _flat(examples, "ctx_tokens", np.int32)
    content, _ = _flat(examples, "ctx_content", np.int32)
    page, page_offsets = _flat(examples, "page_tokens", np.int32)
    np.savez_compressed(out / f"{name}.npz", ctx_tokens=ctx, ctx_offsets=ctx_offsets,
                        ctx_content=content, page_tokens=page, page_offsets=page_offsets)
    metadata = {"customer_id": [x.customer_id for x in examples],
                "request_date": [x.request_date for x in examples],
                "truth": [x.truth for x in examples], "history": [x.history for x in examples]}
    if name == "eval":
        metadata["has_vocab_history"] = [bool(x.has_vocab_history) for x in examples]
        metadata["page_empty"] = [bool(x.page_empty) for x in examples]
    pd.DataFrame(metadata).to_parquet(out / f"{name}_meta.parquet", index=False)


def load_examples(out: str | Path, name: str) -> tuple[dict[str, np.ndarray], pd.DataFrame]:
    """Small reader used by tests and downstream callers to validate offset round trips."""
    out = Path(out)
    with np.load(out / f"{name}.npz") as data:
        arrays = {key: data[key] for key in data.files}
    return arrays, pd.read_parquet(out / f"{name}_meta.parquet")


def stats(train: list[Example], evaluation: list[Example], vocab: Vocab, elapsed: float) -> dict[str, object]:
    examples = train + evaluation
    page, page_offsets = _flat(examples, "page_tokens", np.int32)
    _, ctx_offsets = _flat(examples, "ctx_tokens", np.int32)

    def segment_lengths(offsets: np.ndarray) -> np.ndarray:
        return np.diff(offsets)

    def segment_counts(values: np.ndarray, offsets: np.ndarray, start: int, stop: int) -> np.ndarray:
        mask = ((values >= start) & (values < stop)).astype(np.int64, copy=False)
        cumulative = np.concatenate((np.zeros(1, dtype=np.int64), np.cumsum(mask, dtype=np.int64)))
        return np.diff(cumulative[offsets])

    rows = segment_counts(page, page_offsets, vocab.row_ids.start, vocab.row_ids.stop)
    items = segment_counts(page, page_offsets, vocab.item_ids.start, vocab.item_ids.stop)
    contexts = segment_lengths(ctx_offsets)

    def percentile(values: np.ndarray, q: float) -> float:
        return float(np.percentile(values, q)) if values.size else 0.0

    def maximum(values: np.ndarray) -> int:
        return int(values.max()) if values.size else 0

    rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    # macOS reports bytes; Linux reports KiB.
    peak_memory_mb = float(rss / (1024 ** 2 if sys.platform == "darwin" else 1024))
    return {"vocab": {"total": len(vocab.tokens), "items": len(vocab.item_ids), "rows": len(vocab.row_ids),
                      "profile": len(vocab.profile_ids), "request": len(vocab.request_ids),
                      "actions": len(vocab.action_ids), "ago": len(vocab.ago_ids)},
            "train_examples": len(train), "eval_examples": len(evaluation), "eval_customers": len(evaluation),
            "eval_has_vocab_history_ratio": float(np.mean([bool(x.has_vocab_history) for x in evaluation])) if evaluation else 0.0,
            "eval_page_empty_ratio": float(np.mean([bool(x.page_empty) for x in evaluation])) if evaluation else 0.0,
            "page_rows": {"p50": percentile(rows, 50), "p90": percentile(rows, 90), "max": maximum(rows)},
            "page_items": {"p50": percentile(items, 50), "p90": percentile(items, 90), "max": maximum(items)},
            "context_length": {"p50": percentile(contexts, 50), "p95": percentile(contexts, 95), "max": maximum(contexts)},
            "over_maxlen": float(np.mean((contexts + segment_lengths(page_offsets)) > config.MAXLEN)) if examples else 0.0,
            "peak_memory_mb": round(peak_memory_mb, 2),
            "elapsed_seconds": round(elapsed, 3)}


def _read_inputs(sample_customers: int | None) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    directory = config.normalized_dir()
    articles = pd.read_parquet(directory / "articles.parquet")
    customers = pd.read_parquet(directory / "customers.parquet")
    # Selecting customer ids before applying the parquet predicate keeps the validation
    # command small.  The full run still uses compact categoricals during sorting.
    if sample_customers is not None:
        ids = np.array(sorted(customers["customer_id"].astype(str).unique()))
        chosen = np.random.default_rng(config.SEED).choice(ids, size=min(sample_customers, len(ids)), replace=False).tolist()
        customers = customers[customers["customer_id"].astype(str).isin(chosen)]
        # A Parquet ``in`` predicate with tens of thousands of UUID-like strings is
        # much slower (and can exhaust Arrow's expression builder).  Scan bounded
        # batches instead; only matching rows remain resident after each batch.
        import pyarrow.parquet as pq

        wanted = set(chosen)
        parts = []
        reader = pq.ParquetFile(directory / "transactions.parquet")
        for batch in reader.iter_batches(columns=["t_dat", "customer_id", "article_id", "sales_channel_id"], batch_size=262_144):
            part = batch.to_pandas()
            part = part[part["customer_id"].astype(str).isin(wanted)]
            if not part.empty:
                parts.append(part)
        tx = pd.concat(parts, ignore_index=True) if parts else pd.DataFrame(
            columns=["t_dat", "customer_id", "article_id", "sales_channel_id"])
    else:
        tx = pd.read_parquet(directory / "transactions.parquet", columns=["t_dat", "customer_id", "article_id", "sales_channel_id"])
    return tx, customers, articles


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", required=True, choices=sorted(config.MODES))
    parser.add_argument("--out", type=Path)
    parser.add_argument("--sample-customers", type=int)
    parser.add_argument("--max-train", type=int, default=2_000_000)
    args = parser.parse_args(argv)
    began = time.monotonic()
    request = config.request_of(args.mode)
    out = args.out or config.out_dir() / args.mode
    tx, customers, articles = _read_inputs(args.sample_customers)
    tx["t_dat"] = pd.to_datetime(tx["t_dat"])
    vocab = Vocab.build(tx[tx["t_dat"] < request], articles)
    rows = content_rows(articles)
    train, evaluation = generate_examples(vocab, tx, customers, request, rows, max_train=args.max_train)
    out.mkdir(parents=True, exist_ok=True)
    vocab.save(out / "vocab.json")
    write_examples(out, "train", train)
    write_examples(out, "eval", evaluation)
    report = stats(train, evaluation, vocab, time.monotonic() - began)
    (out / "stats.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
