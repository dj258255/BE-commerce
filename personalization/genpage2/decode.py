"""제약을 지키며 GenPage v2 페이지를 생성한다.

이 모듈은 학습 코드와 분리돼 있다. 따라서 ``model`` 은 DESIGN.md §4의
``forward``/``logits`` 계약만, ``vocab`` 은 §1의 계약만 필요로 한다.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Iterable

import torch

from . import config
from .context import LEVELS, truncate as truncate_context_view, view as context_view


@dataclass
class GeneratedRow:
    row_token: int
    items: list[str]


def truncate_context(
    tokens: list[int], content: list[int], keep_tail: int, *,
    sep_history: int, sep_page: int,
) -> tuple[list[int], list[int]]:
    """Trim oldest history events while preserving prompt/page sentinels.

    A3 removes complete ``[item, action, ago]`` events immediately after
    ``SEP_HISTORY``.  The profile/request prefix and ``SEP_PAGE`` therefore
    remain byte-for-byte intact; this is deliberately not a generic suffix
    truncation.
    """
    if len(tokens) != len(content):
        raise ValueError("tokens 와 content 길이는 같아야 합니다")
    try:
        history_at = tokens.index(sep_history)
    except ValueError as exc:
        raise ValueError("문맥에 SEP_HISTORY가 없습니다") from exc
    try:
        page_at = tokens.index(sep_page, history_at + 1)
    except ValueError as exc:
        raise ValueError("문맥에 SEP_PAGE가 없습니다") from exc
    if page_at < history_at + 1:
        raise ValueError("SEP_HISTORY/SEP_PAGE 순서가 잘못됐습니다")
    event_count = page_at - history_at - 1
    if event_count % 3:
        raise ValueError("SEP_HISTORY와 SEP_PAGE 사이 이벤트가 3토큰 단위가 아닙니다")
    if keep_tail < 1:
        keep_tail = 1
    excess = len(tokens) - keep_tail
    if excess <= 0:
        return list(tokens), list(content)
    drop_events = min(event_count, (excess + 2) // 3)
    cut = history_at + 1 + drop_events * 3
    return (tokens[:history_at + 1] + tokens[cut:],
            content[:history_at + 1] + content[cut:])


class PageDecoder:
    """Masked row/item decoder with hybrid row decoding."""

    def __init__(self, model: Any, vocab: Any, content_rows: dict[str, int], device: Any, level: str = "full"):
        if level not in LEVELS:
            raise ValueError(f"level must be one of {LEVELS}")
        self.model = model
        self.vocab = vocab
        self.content_rows = content_rows
        self.device = torch.device(device)
        self.level = level
        self.maxlen = int(getattr(getattr(model, "cfg", None), "maxlen", getattr(config, "MAXLEN", 320)))
        self._row_repeat = vocab.id("ROW_REPEAT")
        self._sep_history = vocab.id("SEP_HISTORY")
        self._sep_page = vocab.id("SEP_PAGE")
        self._item_ids = list(vocab.item_ids)
        self._row_ids = list(vocab.row_ids)
        self._article_of = vocab.article_of
        self._item_of_article = {
            article: token for token, article in self._article_of.items()
        }
        vocab_size = len(vocab.tokens)
        self._item_row = torch.full((vocab_size,), -1, dtype=torch.long, device=self.device)
        self._row_size: dict[int, int] = {row: 0 for row in self._row_ids}
        row_items: dict[int, list[int]] = {row: [] for row in self._row_ids}
        for token in self._item_ids:
            article = self._article_of.get(token)
            if article is None:
                continue
            row = vocab.row_of(article)
            if row in self._row_size:
                self._item_row[token] = row
                self._row_size[row] += 1
                row_items[row].append(token)
        self._row_item_ids = {
            row: torch.tensor(items, dtype=torch.long, device=self.device)
            for row, items in row_items.items()
        }

    def _project_and_trim(self, tokens: list[int], content: list[int], keep: int) -> tuple[list[int], list[int]]:
        """Apply the checkpoint's training context before reserving decode room."""
        tokens, content = context_view(tokens, content, self.vocab, self.level)
        tokens, content = truncate_context_view(tokens, content, keep, vocab=self.vocab, level=self.level)
        return list(tokens), list(content)

    def _content_for(self, token: int) -> int:
        article = self._article_of.get(token)
        return self.content_rows.get(article, -1) if article is not None else -1

    @staticmethod
    def _split_cache(cache: dict[str, Any], index: int) -> dict[str, Any]:
        return {"layers": [(key[index:index + 1], value[index:index + 1])
                            for key, value in cache["layers"]],
                "lengths": cache["lengths"][index:index + 1]}

    def _merge_caches(self, caches: list[dict[str, Any]]) -> dict[str, Any]:
        if not caches:
            raise ValueError("cannot merge an empty cache list")
        layers = []
        for layer in range(len(caches[0]["layers"])):
            keys = [cache["layers"][layer][0] for cache in caches]
            values = [cache["layers"][layer][1] for cache in caches]
            width = max(key.shape[2] for key in keys)
            shape = (len(keys), keys[0].shape[1], width, keys[0].shape[3])
            merged_key = torch.zeros(shape, dtype=keys[0].dtype, device=self.device)
            merged_value = torch.zeros_like(merged_key)
            for row, (key, value) in enumerate(zip(keys, values, strict=True)):
                merged_key[row, :, :key.shape[2]] = key[0]
                merged_value[row, :, :value.shape[2]] = value[0]
            layers.append((merged_key, merged_value))
        return {"layers": layers,
                "lengths": torch.cat([cache["lengths"] for cache in caches]).to(self.device)}

    def _next_logits(self, tokens: list[int], content: list[int], cache: dict[str, Any] | None = None,
                     *, use_cache: bool = True) -> tuple[torch.Tensor, dict[str, Any] | None]:
        if use_cache:
            if cache is None:
                tokens, content = truncate_context_view(tokens, content, self.maxlen,
                                                        vocab=self.vocab, level=self.level)
            x = torch.tensor([tokens], dtype=torch.long, device=self.device)
            ci = torch.tensor([content], dtype=torch.long, device=self.device)
            with torch.no_grad():
                hidden, new_cache = self.model.forward_cached(x, ci, cache)
                last = int((x != 0).sum(dim=1)[0]) - 1
                logits = self.model.logits(hidden[:, last:last + 1, :])[0, 0]
            return logits.detach(), new_cache
        tokens, content = truncate_context_view(tokens, content, self.maxlen,
                                                vocab=self.vocab, level=self.level)
        x = torch.tensor([tokens], dtype=torch.long, device=self.device)
        ci = torch.tensor([content], dtype=torch.long, device=self.device)
        with torch.no_grad():
            hidden = self.model(x, ci)
            logits = self.model.logits(hidden[:, -1:, :])[0, 0]
        return logits.detach(), None

    def _next_logits_batch(
        self, sequences: list[tuple[list[int], list[int]]],
        caches: list[dict[str, Any] | None] | None = None,
        *, use_cache: bool = True,
    ) -> tuple[list[torch.Tensor], list[dict[str, Any] | None]]:
        """Right-pad a batch and return only each example's final logits."""
        if use_cache:
            initial = caches is None
            if initial:
                prepared = [truncate_context_view(tokens, content, self.maxlen,
                                                  vocab=self.vocab, level=self.level)
                             for tokens, content in sequences]
                next_caches = None
            else:
                prepared = sequences
                next_caches = self._merge_caches([cache for cache in caches if cache is not None])
            width = max(len(tokens) for tokens, _ in prepared)
            x = torch.zeros((len(prepared), width), dtype=torch.long, device=self.device)
            ci = torch.full((len(prepared), width), -1, dtype=torch.long, device=self.device)
            for index, (tokens, content) in enumerate(prepared):
                x[index, :len(tokens)] = torch.tensor(tokens, dtype=torch.long, device=self.device)
                ci[index, :len(content)] = torch.tensor(content, dtype=torch.long, device=self.device)
            with torch.no_grad():
                hidden, batch_cache = self.model.forward_cached(x, ci, next_caches)
                lengths = (x != 0).sum(dim=1) - 1
                batch_index = torch.arange(len(prepared), device=self.device)
                last_hidden = hidden[batch_index, lengths].unsqueeze(1)
                logits = self.model.logits(last_hidden)
            split = [self._split_cache(batch_cache, index) for index in range(len(prepared))]
            return [logits[index, 0].detach() for index in range(len(prepared))], split

        trimmed = [truncate_context_view(tokens, content, self.maxlen,
                                        vocab=self.vocab, level=self.level)
                   for tokens, content in sequences]
        width = max(len(tokens) for tokens, _ in trimmed)
        x = torch.zeros((len(trimmed), width), dtype=torch.long, device=self.device)
        ci = torch.full((len(trimmed), width), -1, dtype=torch.long, device=self.device)
        lengths = []
        for index, (tokens, content) in enumerate(trimmed):
            length = len(tokens)
            x[index, :length] = torch.tensor(tokens, dtype=torch.long, device=self.device)
            ci[index, :length] = torch.tensor(content, dtype=torch.long, device=self.device)
            lengths.append(length - 1)
        with torch.no_grad():
            hidden = self.model(x, ci)
            batch_index = torch.arange(len(lengths), device=self.device)
            last_hidden = hidden[batch_index, torch.tensor(lengths, device=self.device)].unsqueeze(1)
            logits = self.model.logits(last_hidden)
        return [logits[index, 0].detach() for index in range(len(lengths))], [None] * len(lengths)

    @staticmethod
    def _choose(logits: torch.Tensor, allowed: Iterable[int] | torch.Tensor, temperature: float,
                generator: torch.Generator | None, count: int = 1) -> list[int]:
        if isinstance(allowed, torch.Tensor):
            ids_t = (torch.nonzero(allowed, as_tuple=False).flatten()
                     if allowed.dtype == torch.bool else allowed.to(device=logits.device, dtype=torch.long))
            ids = ids_t.tolist()
        else:
            ids = list(allowed)
            ids_t = torch.tensor(ids, dtype=torch.long, device=logits.device) if ids else ids
        if not ids or count <= 0:
            return []
        scores = logits.index_select(0, ids_t)
        n = min(count, len(ids))
        if temperature <= 0:
            local = torch.topk(scores, n).indices.tolist()
        else:
            probs = torch.softmax(scores / temperature, dim=0)
            local = torch.multinomial(probs, n, replacement=False, generator=generator).tolist()
        return [ids[i] for i in local]

    def _history_ids(self, history_articles: list[str]) -> torch.Tensor:
        return torch.tensor(list(dict.fromkeys(self._item_of_article[a] for a in history_articles
                                               if a in self._item_of_article)),
                            dtype=torch.long, device=self.device)

    def _row_candidates(self, history_articles: list[str], used: torch.Tensor,
                        used_per_row: dict[int, int], used_rows: set[int],
                        excluded_rows: set[int], min_items: int) -> list[int]:
        history_ids = self._history_ids(history_articles)
        out: list[int] = []
        for row in self._row_ids:
            if row in used_rows or row in excluded_rows:
                continue
            if row == self._row_repeat:
                available = history_ids[~used[history_ids]] if history_ids.numel() else history_ids
            else:
                if self._row_size.get(row, 0) - used_per_row.get(row, 0) < min_items:
                    continue
                available = None
            if row == self._row_repeat and available.numel() < min_items:
                continue
            out.append(row)
        return out

    def _items_for_row(self, row: int, history_articles: list[str], used: torch.Tensor) -> torch.Tensor:
        if row == self._row_repeat:
            history_ids = self._history_ids(history_articles)
            return history_ids[~used[history_ids]] if history_ids.numel() else history_ids
        row_items = self._row_item_ids.get(row, torch.empty(0, dtype=torch.long, device=self.device))
        return row_items[~used[row_items]]

    def _mark_used(self, used: torch.Tensor, used_per_row: dict[int, int], token: int) -> None:
        if not bool(used[token]):
            used[token] = True
            row = int(self._item_row[token])
            if row >= 0:
                used_per_row[row] = used_per_row.get(row, 0) + 1

    def _violations(self, rows: list[GeneratedRow], *, history_articles: list[str],
                    prev_page: list[int] | None, exclude_items: set[str],
                    exclude_rows: set[int]) -> int:
        previous_tokens = set(prev_page or [])
        previous_articles = {self._article_of[t] for t in previous_tokens if t in self._article_of}
        previous_rows = previous_tokens & set(self._row_ids)
        seen_rows, seen_items, bad = set(), set(), 0
        history = set(history_articles)
        for generated in rows:
            bad += int(generated.row_token in seen_rows)
            bad += int(generated.row_token in previous_rows)
            bad += int(generated.row_token in exclude_rows)
            seen_rows.add(generated.row_token)
            for article in generated.items:
                token = self._item_of_article.get(article)
                # History items may legally be shown in their natural section
                # *or* in ROW_REPEAT; the latter is an additional row, not a
                # different catalogue membership.
                valid_rows = ({self.vocab.row_of(article)} if token is not None else set())
                if article in history:
                    valid_rows.add(self._row_repeat)
                bad += int(article in seen_items)
                bad += int(article in previous_articles)
                bad += int(article in exclude_items)
                bad += int(token is None or generated.row_token not in valid_rows)
                seen_items.add(article)
        return bad

    def generate(self, ctx_tokens: list[int], ctx_content: list[int], *,
                 history_articles: list[str], prev_page: list[int] | None = None,
                 exclude_items: set[str] = frozenset(), exclude_rows: set[int] = frozenset(),
                 pinned: dict[int, int] | None = None, n_rows: int = 3,
                 items_per_row: int = 8, prefix: int = 2, temperature: float = 0.0,
                 generator: torch.Generator | None = None, use_cache: bool = True) -> tuple[list[GeneratedRow], int]:
        """Generate up to ``n_rows`` rows and return them with violation count."""
        if len(ctx_tokens) != len(ctx_content):
            raise ValueError("ctx_tokens 와 ctx_content 길이는 같아야 합니다")
        if items_per_row < 1 or n_rows < 1:
            return [], 0
        use_cache = bool(use_cache and hasattr(self.model, "forward_cached"))
        tokens, content = self._project_and_trim(list(ctx_tokens), list(ctx_content), self.maxlen)
        if self._sep_page not in tokens:
            tokens.append(self._sep_page)
            content.append(-1)
        # Reserve room for the requested page before generation.  Otherwise a
        # very long prompt would evict already-generated early-row tokens.
        reserve = n_rows * (items_per_row + 1) + len(prev_page or []) + 1
        tokens, content = truncate_context_view(tokens, content, max(1, self.maxlen - reserve),
                                                vocab=self.vocab, level=self.level)
        for token in prev_page or []:
            tokens.append(token)
            content.append(self._content_for(token))

        excluded_items = set(exclude_items)
        excluded_rows = set(exclude_rows)
        used_rows = set(excluded_rows) | {t for t in (prev_page or []) if t in self._row_ids}
        used = torch.zeros(len(self.vocab.tokens), dtype=torch.bool, device=self.device)
        used_per_row: dict[int, int] = {}
        previous_items = [t for t in (prev_page or []) if t in self._article_of]
        excluded_ids = [self._item_of_article[a] for a in excluded_items if a in self._item_of_article]
        for token in previous_items + excluded_ids:
            self._mark_used(used, used_per_row, token)
        rows: list[GeneratedRow] = []
        min_items = 3
        pinned = pinned or {}
        next_logits, cache = self._next_logits(tokens, content, use_cache=use_cache)

        for row_pos in range(n_rows):
            candidates = self._row_candidates(history_articles, used, used_per_row, used_rows, excluded_rows, min_items)
            if row_pos in pinned:
                wanted = pinned[row_pos]
                candidates = [wanted] if wanted in candidates else []
            if not candidates:
                break
            row_token = self._choose(next_logits, candidates, temperature, generator)[0]
            tokens.append(row_token)
            content.append(-1)
            used_rows.add(row_token)
            if use_cache:
                next_logits, cache = self._next_logits([row_token], [-1], cache, use_cache=True)
            else:
                next_logits, cache = self._next_logits(tokens, content, use_cache=False)
            allowed = self._items_for_row(row_token, history_articles, used).clone()
            chosen: list[int] = []

            # Prefix tokens each affect the distribution for their successor.
            for _ in range(min(prefix, items_per_row, len(allowed))):
                token = self._choose(next_logits, allowed, temperature, generator)[0]
                chosen.append(token)
                allowed = allowed[allowed != token]
                self._mark_used(used, used_per_row, token)
                tokens.append(token)
                content.append(self._content_for(token))
                if use_cache:
                    next_logits, cache = self._next_logits([token], [self._content_for(token)], cache, use_cache=True)
                else:
                    next_logits, cache = self._next_logits(tokens, content, use_cache=False)

            # The remaining tokens all come from this *last* distribution.
            rest = min(items_per_row - len(chosen), int(allowed.numel()))
            bulk = self._choose(next_logits, allowed, temperature, generator, rest)
            for token in bulk:
                chosen.append(token)
                self._mark_used(used, used_per_row, token)
                tokens.append(token)
                content.append(self._content_for(token))
            if use_cache and bulk:
                next_logits, cache = self._next_logits(bulk,
                                                       [self._content_for(token) for token in bulk],
                                                       cache, use_cache=True)
            elif not use_cache:
                next_logits, cache = self._next_logits(tokens, content, use_cache=False)
            rows.append(GeneratedRow(row_token, [self._article_of[t] for t in chosen]))

        return rows, self._violations(rows, history_articles=history_articles, prev_page=prev_page,
                                      exclude_items=excluded_items, exclude_rows=excluded_rows)

    def generate_batch(self, examples: list[dict[str, Any]], **same_kwargs: Any) -> list[tuple[list[GeneratedRow], int]]:
        """Generate a ragged right-padded batch with single-page-equivalent rules."""
        states = []
        for example in examples:
            args = dict(same_kwargs)
            args.update(example)
            tokens, content = self._project_and_trim(list(args["ctx_tokens"]), list(args["ctx_content"]), self.maxlen)
            if len(tokens) != len(content):
                raise ValueError("ctx_tokens 와 ctx_content 길이는 같아야 합니다")
            if self._sep_page not in tokens:
                tokens.append(self._sep_page)
                content.append(-1)
            reserve = int(args.get("n_rows", 3)) * (int(args.get("items_per_row", 8)) + 1) + len(args.get("prev_page") or []) + 1
            tokens, content = truncate_context_view(tokens, content, max(1, self.maxlen - reserve),
                                                    vocab=self.vocab, level=self.level)
            previous = list(args.get("prev_page") or [])
            tokens.extend(previous)
            content.extend(self._content_for(t) for t in previous)
            excluded_items = set(args.get("exclude_items", frozenset()))
            excluded_rows = set(args.get("exclude_rows", frozenset()))
            used_rows = excluded_rows | {t for t in previous if t in self._row_ids}
            used = torch.zeros(len(self.vocab.tokens), dtype=torch.bool, device=self.device)
            used_per_row: dict[int, int] = {}
            previous_items = [t for t in previous if t in self._article_of]
            excluded_ids = [self._item_of_article[a] for a in excluded_items if a in self._item_of_article]
            for token in previous_items + excluded_ids:
                self._mark_used(used, used_per_row, token)
            states.append({"args": args, "tokens": tokens, "content": content, "previous": previous,
                           "excluded_items": excluded_items, "excluded_rows": excluded_rows,
                           "used_rows": used_rows, "used": used, "used_per_row": used_per_row,
                           "rows": [], "done": False,
                           "use_cache": bool(args.get("use_cache", True) and hasattr(self.model, "forward_cached"))})

        if states:
            use_cache = states[0]["use_cache"]
            if any(state["use_cache"] != use_cache for state in states):
                raise ValueError("generate_batch examples must agree on use_cache")
            initial_sequences = [(state["tokens"], state["content"]) for state in states]
            initial_logits, initial_caches = self._next_logits_batch(initial_sequences, use_cache=use_cache)
            for state, logits, cache in zip(states, initial_logits, initial_caches, strict=True):
                state["logits"], state["cache"] = logits, cache

        max_rows = max((int(s["args"].get("n_rows", 3)) for s in states), default=0)
        for row_pos in range(max_rows):
            active, domains = [], []
            for state in states:
                args = state["args"]
                if state["done"] or row_pos >= int(args.get("n_rows", 3)) or int(args.get("items_per_row", 8)) < 1:
                    continue
                candidates = self._row_candidates(args["history_articles"], state["used"], state["used_per_row"],
                                                  state["used_rows"],
                                                  state["excluded_rows"], 3)
                pinned = args.get("pinned") or {}
                if row_pos in pinned:
                    wanted = pinned[row_pos]
                    candidates = [wanted] if wanted in candidates else []
                if not candidates:
                    state["done"] = True
                    continue
                active.append(state)
                domains.append(candidates)
            if not active:
                continue
            row_logits = [state["logits"] for state in active]
            for state, candidates, logits in zip(active, domains, row_logits, strict=True):
                args = state["args"]
                row = self._choose(logits, candidates, args.get("temperature", 0.0), args.get("generator"))[0]
                state["row"] = row
                state["allowed"] = self._items_for_row(row, args["history_articles"], state["used"]).clone()
                state["chosen"] = []
                state["tokens"].append(row)
                state["content"].append(-1)
                state["used_rows"].add(row)
            step_sequences = [([state["row"]], [-1]) if state["use_cache"]
                             else (state["tokens"], state["content"]) for state in active]
            step_caches = [state["cache"] for state in active] if active[0]["use_cache"] else None
            step_logits, step_new_caches = self._next_logits_batch(step_sequences, step_caches,
                                                                    use_cache=active[0]["use_cache"])
            for state, logits, cache in zip(active, step_logits, step_new_caches, strict=True):
                state["logits"], state["cache"] = logits, cache

            max_prefix = max(min(int(s["args"].get("prefix", 2)), int(s["args"].get("items_per_row", 8))) for s in active)
            for _ in range(max_prefix):
                prefix_active = [s for s in active if s["allowed"].numel() and len(s["chosen"]) < min(
                    int(s["args"].get("prefix", 2)), int(s["args"].get("items_per_row", 8)))]
                if not prefix_active:
                    break
                for state in prefix_active:
                    args = state["args"]
                    token = self._choose(state["logits"], state["allowed"], args.get("temperature", 0.0), args.get("generator"))[0]
                    state["allowed"] = state["allowed"][state["allowed"] != token]
                    state["chosen"].append(token)
                    self._mark_used(state["used"], state["used_per_row"], token)
                    state["tokens"].append(token)
                    state["content"].append(self._content_for(token))
                if prefix_active[0]["use_cache"]:
                    append_sequences = [([state["tokens"][-1]], [state["content"][-1]]) for state in prefix_active]
                    append_logits, append_caches = self._next_logits_batch(
                        append_sequences, [state["cache"] for state in prefix_active], use_cache=True)
                    for state, logits, cache in zip(prefix_active, append_logits, append_caches, strict=True):
                        state["logits"], state["cache"] = logits, cache
                else:
                    for state in prefix_active:
                        state["logits"], state["cache"] = self._next_logits(
                            state["tokens"], state["content"], use_cache=False)

            bulk_active = [s for s in active if s["allowed"].numel() and len(s["chosen"]) < int(s["args"].get("items_per_row", 8))]
            if bulk_active:
                for state in bulk_active:
                    args = state["args"]
                    count = min(int(args.get("items_per_row", 8)) - len(state["chosen"]), int(state["allowed"].numel()))
                    bulk = self._choose(state["logits"], state["allowed"], args.get("temperature", 0.0), args.get("generator"), count)
                    state["chosen"].extend(bulk)
                    for token in bulk:
                        self._mark_used(state["used"], state["used_per_row"], token)
                    state["tokens"].extend(bulk)
                    state["content"].extend(self._content_for(t) for t in bulk)
                if bulk_active[0]["use_cache"]:
                    append_sequences = [([*state["chosen"][-(len(state["chosen"]) - min(
                        int(state["args"].get("prefix", 2)), int(state["args"].get("items_per_row", 8)))):]],
                                         [self._content_for(t) for t in state["chosen"][-(len(state["chosen"]) - min(
                                             int(state["args"].get("prefix", 2)), int(state["args"].get("items_per_row", 8)))):]])
                                       for state in bulk_active]
                    # The slice above is exactly the newly sampled bulk suffix.
                    append_logits, append_caches = self._next_logits_batch(
                        append_sequences, [state["cache"] for state in bulk_active], use_cache=True)
                    for state, logits, cache in zip(bulk_active, append_logits, append_caches, strict=True):
                        state["logits"], state["cache"] = logits, cache
                else:
                    for state in bulk_active:
                        state["logits"], state["cache"] = self._next_logits(
                            state["tokens"], state["content"], use_cache=False)
            for state in active:
                state["rows"].append(GeneratedRow(state["row"], [self._article_of[t] for t in state["chosen"]]))

        return [(s["rows"], self._violations(s["rows"], history_articles=s["args"]["history_articles"],
                                                prev_page=s["previous"], exclude_items=s["excluded_items"],
                                                exclude_rows=s["excluded_rows"])) for s in states]
