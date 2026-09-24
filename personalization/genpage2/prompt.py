"""Turn a serving request into the exact GenPage v2 training prompt."""
from __future__ import annotations

from typing import Any

import numpy as np
import pandas as pd

from . import config
from .dataset import _context_from_arrays, profile_tokens
from .vocab import _article_id


_PURCHASE_ACTIONS = {"STORE": 1, "ONLINE": 2}
_SESSION_ACTIONS = {"CLICK": "ACT_CLICK", "VIEW": "ACT_VIEW"}


def _default_price(vocab: Any) -> float:
    """A deterministic fallback for direct callers without the server price table."""
    edges = [float(x) for x in getattr(vocab, "price_edges", [])]
    return edges[len(edges) // 2] if edges else 0.0


def _as_timestamp(value: Any, fallback: pd.Timestamp) -> pd.Timestamp:
    if value is None:
        return fallback
    parsed = pd.Timestamp(value)
    if pd.isna(parsed):
        return fallback
    return parsed


def build_prompt(vocab: Any, *, now: Any = None, profile: dict[str, Any] | None = None,
                 events: list[dict[str, Any]] | None = None,
                 content_rows: dict[str, int] | None = None) -> tuple[list[int], list[int], dict[str, Any]]:
    """Build a full prompt from oldest-first events.

    Store/online events deliberately go through ``_context_from_arrays``: that
    is the same implementation used while making the training archive.  View
    and click events are appended immediately before ``SEP_PAGE`` because
    those action kinds have no transaction-table representation.
    """
    request = _as_timestamp(now, config.request_of("validate"))
    source = list(events or [])
    rows = content_rows or {}
    missing = {"at": 0, "action": 0, "price": 0,
               "profile": profile is None, "now": now is None}
    purchases: list[tuple[pd.Timestamp, str, int, int, int, float]] = []
    sessions: list[tuple[pd.Timestamp, str, str, int, float]] = []
    fallback_price = _default_price(vocab)

    for event in source:
        if not isinstance(event, dict):
            raise ValueError("events 의 각 원소는 객체여야 합니다")
        if "item" not in event:
            raise ValueError("event.item 이 없습니다")
        article = _article_id(event["item"])
        at_missing = event.get("at") is None
        action_missing = event.get("action") is None
        price_missing = event.get("price") is None
        missing["at"] += int(at_missing)
        missing["action"] += int(action_missing)
        missing["price"] += int(price_missing)
        at = _as_timestamp(event.get("at"), request)
        # A future browser timestamp must not create a negative AGO bucket.
        at = min(at.normalize(), request.normalize())
        action = str(event.get("action") or "ONLINE").upper()
        value = event.get("price")
        if value is None:
            value = event.get("_inferred_price", fallback_price)
        try:
            price = float(value)
        except (TypeError, ValueError) as exc:
            raise ValueError("event.price 는 숫자여야 합니다") from exc
        if not np.isfinite(price):
            raise ValueError("event.price 는 유한한 숫자여야 합니다")
        token = vocab.item(article)
        item = -1 if token is None else int(token)
        content = int(rows.get(article, -1))
        if action in _PURCHASE_ACTIONS:
            purchases.append((at, article, item, _PURCHASE_ACTIONS[action], content, price))
        elif action in _SESSION_ACTIONS:
            sessions.append((at, article, _SESSION_ACTIONS[action], content, price))
        else:
            raise ValueError(f"지원하지 않는 event.action: {action}")

    # Input order is meaningful for same-day events, matching dataset's stable
    # source order.  Dates are passed as day integers exactly as in A1.
    dates = np.asarray([x[0].to_datetime64().astype("datetime64[D]").astype(np.int64) for x in purchases], dtype=np.int64)
    articles = np.asarray([x[1] for x in purchases], dtype=str)
    item_tokens = np.asarray([x[2] for x in purchases], dtype=np.int32)
    channels = np.asarray([x[3] for x in purchases], dtype=np.int8)
    article_content = np.asarray([x[4] for x in purchases], dtype=np.int32)
    prices = np.asarray([x[5] for x in purchases], dtype=float)
    tokens, content = _context_from_arrays(vocab, request, profile_tokens(vocab, profile), dates, articles,
                                           item_tokens, channels, article_content, prices)
    values, content_values = tokens.astype(int).tolist(), content.astype(int).tolist()
    page_at = values.index(vocab.id("SEP_PAGE"))
    for _, article, action, row, price in sessions:
        item = vocab.item(article)
        values[page_at:page_at] = [int(item) if item is not None else vocab.id("ITEM_FALLBACK"),
                                    vocab.id(action), vocab.id("AGO_0-3"),
                                    vocab.price_ids[np.searchsorted(vocab.price_edges, price, side="right")]]
        content_values[page_at:page_at] = [row, -1, -1, -1]
        page_at += 4
    report = {"events": len(source), "missing": missing, "level": "full", "tokens": len(values)}
    return values, content_values, report
