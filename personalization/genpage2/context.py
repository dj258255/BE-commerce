"""Shared context projections for GenPage v2.

The dataset persists the richest prompt.  Training, decoding, and evaluation
must derive every ablation from that exact prompt rather than each maintaining
its own (and subtly different) history parser.
"""
from __future__ import annotations

from typing import Any, Sequence

import numpy as np


LEVELS = ("items", "+action", "+time", "+price", "+profile", "full")
_WIDTHS = {"items": 1, "+action": 2, "+time": 3, "+price": 4,
           "+profile": 4, "full": 4}


def event_width(level: str) -> int:
    """Number of event fields retained by a context level."""
    try:
        return _WIDTHS[level]
    except KeyError as exc:
        raise ValueError(f"unknown context level {level!r}; expected one of {LEVELS}") from exc


def _id(vocab: Any, name: str) -> int:
    return int(vocab.id(name)) if hasattr(vocab, "id") else int(vocab.tokens.index(name))


def _as_lists(tokens: Sequence[int], content: Sequence[int]) -> tuple[list[int], list[int], bool]:
    if len(tokens) != len(content):
        raise ValueError("context token/content lengths differ")
    return [int(x) for x in tokens], [int(x) for x in content], isinstance(tokens, np.ndarray)


def _restore(tokens: list[int], content: list[int], want_array: bool) -> tuple[Any, Any]:
    if want_array:
        return np.asarray(tokens, dtype=np.int64), np.asarray(content, dtype=np.int64)
    return tokens, content


def _name(vocab: Any, token: int) -> str:
    try:
        return str(vocab.tokens[int(token)])
    except (IndexError, TypeError) as exc:
        raise ValueError(f"token id outside vocabulary: {token}") from exc


def _is_item(name: str) -> bool:
    return name.startswith("ITEM_")


def _fields(tokens: list[int], content: list[int], vocab: Any, history_at: int, page_at: int) -> list[list[tuple[int, int]]]:
    """Parse events by their item marker, not a global config width.

    This intentionally remains readable while old 3-field artifacts coexist
    with 4-field artifacts.  Each resulting group is still an event boundary,
    so truncation can never split an event.
    """
    events: list[list[tuple[int, int]]] = []
    current: list[tuple[int, int]] | None = None
    for index in range(history_at + 1, page_at):
        pair = (tokens[index], content[index])
        if _is_item(_name(vocab, pair[0])):
            if current is not None:
                events.append(current)
            current = [pair]
        elif current is not None:
            current.append(pair)
        else:
            raise ValueError("history event must start with an ITEM_ token")
    if current is not None:
        events.append(current)
    return events


def _event_projection(event: list[tuple[int, int]], vocab: Any, width: int) -> list[tuple[int, int]]:
    selected: list[tuple[int, int]] = []
    prefixes = ("ITEM_", "ACT_", "AGO_", "PRICE_")
    for prefix in prefixes[:width]:
        match = next((pair for pair in event if _name(vocab, pair[0]).startswith(prefix)), None)
        # A persisted old-format prompt has no price.  Do not invent a token:
        # it can still be used safely while data/vocab migration is in flight.
        if match is not None:
            selected.append(match)
    if not selected or not _is_item(_name(vocab, selected[0][0])):
        raise ValueError("history event has no item token")
    return selected


def view(ctx_tokens: Sequence[int], ctx_content: Sequence[int], vocab: Any, level: str) -> tuple[Any, Any]:
    """Project a persisted full context to ``level`` with aligned content rows."""
    event_width(level)  # validate even for full
    tokens, content, want_array = _as_lists(ctx_tokens, ctx_content)
    if level == "full":
        return _restore(tokens, content, want_array)
    profile, request, history, page = (_id(vocab, name) for name in
                                       ("SEP_PROFILE", "SEP_REQUEST", "SEP_HISTORY", "SEP_PAGE"))
    try:
        bos_at = next(i for i, token in enumerate(tokens) if _name(vocab, token) == "BOS")
        profile_at = tokens.index(profile, bos_at + 1)
        request_at = tokens.index(request, profile_at + 1)
        history_at = tokens.index(history, request_at + 1)
        page_at = tokens.index(page, history_at + 1)
    except ValueError as exc:
        raise ValueError("full context must contain BOS/profile/request/history/page sentinels") from exc
    if not (bos_at < profile_at < request_at < history_at < page_at):
        raise ValueError("context sentinels are out of order")

    events = _fields(tokens, content, vocab, history_at, page_at)
    if level == "+profile":
        prefix_tokens = [tokens[bos_at], profile]
        prefix_content = [content[bos_at], -1]
        prefix_tokens.extend(tokens[profile_at + 1:request_at])
        prefix_content.extend(content[profile_at + 1:request_at])
    else:
        prefix_tokens = [tokens[bos_at]]
        prefix_content = [content[bos_at]]
    prefix_tokens.append(history)
    prefix_content.append(-1)
    width = event_width(level)
    for event in events:
        for token, row in _event_projection(event, vocab, width):
            prefix_tokens.append(token)
            prefix_content.append(row)
    prefix_tokens.append(page)
    prefix_content.append(-1)
    return _restore(prefix_tokens, prefix_content, want_array)


def truncate(tokens: Sequence[int], content: Sequence[int], keep: int, *, vocab: Any, level: str) -> tuple[Any, Any]:
    """Drop oldest *complete* history events until at most ``keep`` tokens remain.

    Prefix metadata and ``SEP_PAGE`` are invariant.  If those protected tokens
    alone exceed ``keep``, they are returned intact rather than silently losing
    profile/request information.
    """
    event_width(level)
    values, rows, want_array = _as_lists(tokens, content)
    history, page = (_id(vocab, name) for name in ("SEP_HISTORY", "SEP_PAGE"))
    try:
        history_at = values.index(history)
        page_at = values.index(page, history_at + 1)
    except ValueError as exc:
        raise ValueError("context must contain SEP_HISTORY and SEP_PAGE") from exc
    if history_at >= page_at:
        raise ValueError("SEP_HISTORY must precede SEP_PAGE")
    if len(values) <= max(0, int(keep)):
        return _restore(values, rows, want_array)
    events = _fields(values, rows, vocab, history_at, page_at)
    cut = 0
    for event in events:
        if len(values) - cut <= max(0, int(keep)):
            break
        cut += len(event)
    if cut:
        values = values[:history_at + 1] + values[history_at + 1 + cut:]
        rows = rows[:history_at + 1] + rows[history_at + 1 + cut:]
    return _restore(values, rows, want_array)
