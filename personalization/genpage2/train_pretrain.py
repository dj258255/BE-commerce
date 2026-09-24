"""Page-token-only pretraining for GenPage v2.

This module intentionally keeps NPZ examples flat until a DataLoader asks for
one.  Production training sets are large enough that materialising examples as
Python lists is prohibitively expensive.
"""

from __future__ import annotations

import argparse
import json
import math
import time
from pathlib import Path
from typing import Any, Sequence

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F
from torch.utils.data import DataLoader, Dataset

from . import config
from .context import LEVELS, truncate as truncate_context, view as context_view
from .model import GenPageV2, ModelConfig, save_checkpoint

SEED = config.SEED


class NpzExamples(Dataset):
    """Lazy view over the flattened arrays written by the A1 dataset builder."""
    def __init__(self, source: str | Path | Any) -> None:
        self.data = np.load(source, mmap_mode="r") if isinstance(source, (str, Path)) else source
        self.ctx_tokens = self.data["ctx_tokens"]
        self.ctx_offsets = self.data["ctx_offsets"]
        self.ctx_content = self.data["ctx_content"]
        self.page_tokens = self.data["page_tokens"]
        self.page_offsets = self.data["page_offsets"]
        if len(self.ctx_offsets) != len(self.page_offsets):
            raise ValueError("context and page offsets have different example counts")

    def __len__(self) -> int:
        return len(self.ctx_offsets) - 1

    def __getitem__(self, index: int) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        a, b = int(self.ctx_offsets[index]), int(self.ctx_offsets[index + 1])
        c, d = int(self.page_offsets[index]), int(self.page_offsets[index + 1])
        return (np.asarray(self.ctx_tokens[a:b], dtype=np.int64),
                np.asarray(self.ctx_content[a:b], dtype=np.int64),
                np.asarray(self.page_tokens[c:d], dtype=np.int64))


def _vocab_id(vocab: Any, name: str) -> int:
    if hasattr(vocab, "id"):
        return int(vocab.id(name))
    return int(vocab.tokens.index(name))


def _item_bounds(vocab: Any) -> tuple[int, int]:
    """Return the contiguous [start, end) item-token range once per run."""
    ids = getattr(vocab, "item_ids", None)
    if isinstance(ids, range):
        return int(ids.start), int(ids.stop)
    if ids is not None:
        values = list(ids)
        if not values:
            return 0, 0
        return min(values), max(values) + 1
    values = [i for i, token in enumerate(vocab.tokens) if token.startswith("ITEM_")]
    return (min(values), max(values) + 1) if values else (0, 0)


def _row_bounds(vocab: Any) -> range | tuple[int, int] | None:
    ids = getattr(vocab, "row_ids", None)
    if ids is not None:
        values = list(ids)
    else:
        values = [i for i, token in enumerate(vocab.tokens)
                  if token.startswith("ROW_") and token != "ROW_FALLBACK"]
    return range(min(values), max(values) + 1) if values else None


def _optional_vocab_id(vocab: Any, name: str) -> int | None:
    try:
        return _vocab_id(vocab, name)
    except (KeyError, ValueError):
        return None


def history_only_context(ctx_tokens: Sequence[int], ctx_content: Sequence[int], vocab: Any) -> tuple[np.ndarray, np.ndarray]:
    """Compatibility spelling for the renamed ``items`` projection."""
    tokens, content = context_view(ctx_tokens, ctx_content, vocab, "items")
    return np.asarray(tokens, dtype=np.int64), np.asarray(content, dtype=np.int64)


def truncate_context_page(ctx_tokens: Sequence[int], ctx_content: Sequence[int], page_tokens: Sequence[int], *,
                          maxlen: int, sep_history: int, sep_page: int,
                          event_width: int = 3) -> tuple[np.ndarray, np.ndarray, np.ndarray, int]:
    """Legacy wrapper retained for callers from the pre-context-module API.

    Returns the number of removed history tokens as the final element.
    """
    ctx = np.asarray(ctx_tokens, dtype=np.int64).copy()
    content = np.asarray(ctx_content, dtype=np.int64).copy()
    page = np.asarray(page_tokens, dtype=np.int64).copy()
    if len(ctx) != len(content):
        raise ValueError("context token/content lengths differ")
    if event_width <= 0:
        raise ValueError("event_width must be positive")
    try:
        history_at = int(np.where(ctx == sep_history)[0][-1])
        page_at = int(np.where(ctx == sep_page)[0][-1])
    except IndexError as exc:
        raise ValueError("context must contain SEP_HISTORY and SEP_PAGE") from exc
    if history_at >= page_at:
        raise ValueError("SEP_HISTORY must precede SEP_PAGE")
    removed = 0
    # Full history events are [item, action, ago]; history-only events are [item].
    while len(ctx) + len(page) > maxlen and page_at - history_at - 1 >= event_width:
        cut = history_at + 1
        ctx = np.concatenate((ctx[:cut], ctx[cut + event_width:]))
        content = np.concatenate((content[:cut], content[cut + event_width:]))
        page_at -= event_width
        removed += event_width
    if len(ctx) + len(page) > maxlen:
        page = page[:max(0, maxlen - len(ctx))]
    return ctx, content, page, removed


def page_loss_mask(length: int, page_start: int) -> np.ndarray:
    """Mask logits positions which predict a page token (including EOS)."""
    mask = np.zeros(max(0, length - 1), dtype=bool)
    # Sequence[page_start - 1] predicts the first page token.
    mask[max(0, page_start - 1):] = True
    return mask


def _item_range_bounds(item_range: range | tuple[int, int]) -> tuple[int, int]:
    if isinstance(item_range, range):
        return int(item_range.start), int(item_range.stop)
    if isinstance(item_range, tuple) and len(item_range) == 2:
        return int(item_range[0]), int(item_range[1])
    raise TypeError("item_range must be a range or (start, stop) tuple")


def replace_item_inputs(tokens: torch.Tensor, item_range: range | tuple[int, int], fallback_id: int,
                        probability: float, generator: torch.Generator | None = None) -> torch.Tensor:
    """Replace input items only; callers keep their target tensor unchanged."""
    if probability <= 0:
        return tokens.clone()
    item_start, item_end = _item_range_bounds(item_range)
    is_item = (tokens >= item_start) & (tokens < item_end)
    if probability >= 1:
        selected = is_item
    else:
        selected = is_item & (torch.rand(tokens.shape, device=tokens.device, generator=generator) < probability)
    replaced = tokens.clone()
    replaced[selected] = int(fallback_id)
    return replaced


def replace_known_inputs(tokens: torch.Tensor, *, item_range: range | tuple[int, int], item_fallback_id: int,
                         row_range: range | tuple[int, int] | None = None, row_fallback_id: int | None = None,
                         probability: float, generator: torch.Generator | None = None) -> torch.Tensor:
    """Replace known item and row inputs independently, leaving targets untouched."""
    replaced = replace_item_inputs(tokens, item_range, item_fallback_id, probability, generator)
    if row_range is None or row_fallback_id is None or probability <= 0:
        return replaced
    row_start, row_end = _item_range_bounds(row_range)
    is_row = (tokens >= row_start) & (tokens < row_end)
    if probability >= 1:
        selected = is_row
    else:
        selected = is_row & (torch.rand(tokens.shape, device=tokens.device, generator=generator) < probability)
    replaced[selected] = int(row_fallback_id)
    return replaced


def make_batch(examples: Sequence[tuple[np.ndarray, np.ndarray, np.ndarray]], *, vocab: Any, maxlen: int | None = None,
               context: str = "full", pad_id: int | None = None) -> dict[str, torch.Tensor]:
    """Join context/page examples and right-pad a batch without global expansion."""
    # ``history`` appeared in early A3 commands.  Keep it as an input alias,
    # but checkpoints and new commands always record the documented ``items``.
    if context == "history":
        context = "items"
    if context not in LEVELS:
        raise ValueError(f"context must be one of {LEVELS}")
    maxlen = int(getattr(config, "MAXLEN", 320) if maxlen is None else maxlen)
    pad = _vocab_id(vocab, "PAD") if pad_id is None else pad_id
    sep_history, sep_page = _vocab_id(vocab, "SEP_HISTORY"), _vocab_id(vocab, "SEP_PAGE")
    prepared: list[tuple[np.ndarray, np.ndarray, int]] = []
    for ctx, content, page in examples:
        ctx, content = context_view(ctx, content, vocab, context)
        # Only history events may be discarded.  Page targets are a response,
        # so a pathological overlong target is clipped only after prompt trim.
        ctx, content = truncate_context(ctx, content, maxlen - len(page), vocab=vocab, level=context)
        ctx, content = np.asarray(ctx, dtype=np.int64), np.asarray(content, dtype=np.int64)
        page = np.asarray(page, dtype=np.int64)[:max(0, maxlen - len(ctx))]
        joined = np.concatenate((ctx, page))
        joined_content = np.concatenate((content, np.full(len(page), -1, dtype=np.int64)))
        if not page_loss_mask(len(joined), len(ctx)).any():
            raise ValueError("example has no page-token target after truncation")
        prepared.append((joined, joined_content, len(ctx)))
    width = max((len(tokens) for tokens, _, _ in prepared), default=0)
    token_batch = np.full((len(prepared), width), pad, dtype=np.int64)
    content_batch = np.full((len(prepared), width), -1, dtype=np.int64)
    loss_batch = np.zeros((len(prepared), max(0, width - 1)), dtype=bool)
    for row, (tokens, content_ids, page_start) in enumerate(prepared):
        token_batch[row, :len(tokens)] = tokens
        content_batch[row, :len(tokens)] = content_ids
        loss_batch[row, :len(tokens) - 1] = page_loss_mask(len(tokens), page_start)
    return {"tokens": torch.from_numpy(token_batch), "content_idx": torch.from_numpy(content_batch),
            "loss_mask": torch.from_numpy(loss_batch)}


def article_token_rows(vocab: Any, article_rows: dict[str, int]) -> torch.Tensor:
    """Build token->content-row lookup once; non-item and unknown entries are -1."""
    result = torch.full((len(vocab.tokens),), -1, dtype=torch.long)
    for token, article in vocab.article_of.items():
        if article in article_rows:
            result[int(token)] = int(article_rows[article])
    return result


def add_page_content(batch: dict[str, torch.Tensor], token_rows: torch.Tensor) -> None:
    """Annotate page item positions; context annotations were persisted by A1."""
    ids, rows = batch["tokens"], batch["content_idx"]
    lookup = token_rows.to(device=ids.device) if token_rows.device != ids.device else token_rows
    mapped = lookup[ids]
    fill = (rows < 0) & (mapped >= 0)
    rows[fill] = mapped[fill]


def batch_loss(model: GenPageV2, batch: dict[str, torch.Tensor], *, item_range: range | tuple[int, int],
               fallback_id: int, fallback_prob: float, row_range: range | tuple[int, int] | None = None,
               row_fallback_id: int | None = None,
               generator: torch.Generator | None = None) -> tuple[torch.Tensor, torch.Tensor]:
    targets = batch["tokens"][:, 1:]
    inputs = replace_known_inputs(batch["tokens"], item_range=item_range, item_fallback_id=fallback_id,
                                  row_range=row_range, row_fallback_id=row_fallback_id,
                                  probability=fallback_prob, generator=generator)
    mask = batch["loss_mask"]
    hidden = model(inputs, batch["content_idx"])
    selected_hidden = hidden[:, :-1][mask]
    logits = model.logits(selected_hidden)
    loss = F.cross_entropy(logits, targets[mask])
    return loss, logits


def _load_content(data_dir: Path) -> tuple[np.ndarray, dict[str, int]]:
    from genpage2.content import load_content

    return load_content(data_dir.parent / "content")


def _device(value: str) -> torch.device:
    if value == "auto":
        return torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    if value == "mps" and not torch.backends.mps.is_available():
        raise RuntimeError("MPS was requested but is unavailable")
    return torch.device(value)


def evaluate(model: GenPageV2, dataset: Dataset, *, vocab: Any, token_rows: torch.Tensor, batch_size: int,
             maxlen: int, context: str, limit: int, device: torch.device) -> dict[str, float]:
    model.eval()
    loader = DataLoader(dataset, batch_size=batch_size, shuffle=False,
                        collate_fn=lambda x: make_batch(x, vocab=vocab, maxlen=maxlen, context=context))
    total_loss = total_tokens = first_correct = first_total = 0
    item_start, item_end = _item_bounds(vocab)
    item_range = range(item_start, item_end)
    fallback = _vocab_id(vocab, "ITEM_FALLBACK")
    row_range = _row_bounds(vocab)
    row_fallback = _optional_vocab_id(vocab, "ROW_FALLBACK")
    with torch.no_grad():
        seen = 0
        for batch in loader:
            if seen >= limit:
                break
            take = min(len(batch["tokens"]), limit - seen)
            mask_cpu = batch["loss_mask"][:take]
            count = int(mask_cpu.sum())
            batch = {k: v[:take].to(device) for k, v in batch.items()}
            add_page_content(batch, token_rows)
            loss, logits = batch_loss(model, batch, item_range=item_range,
                                      fallback_id=fallback, fallback_prob=0,
                                      row_range=row_range, row_fallback_id=row_fallback)
            total_loss += float(loss) * count
            total_tokens += count
            row_mask = batch["loss_mask"]
            counts = row_mask.sum(dim=1)
            valid_rows = counts > 0
            if valid_rows.any():
                starts = counts.cumsum(dim=0) - counts
                first_logits = logits[starts[valid_rows]]
                first_targets = batch["tokens"][:, 1:][row_mask][starts[valid_rows]]
                first_correct += int((first_logits.argmax(dim=1) == first_targets).sum())
                first_total += int(valid_rows.sum())
            seen += take
    return {"page_loss": total_loss / max(1, total_tokens),
            "first_row_accuracy": first_correct / max(1, first_total), "examples": seen}


def train(args: argparse.Namespace, vocab_loader: Any | None = None) -> dict[str, Any]:
    # A1 is intentionally imported only for an actual training run.
    if vocab_loader is None:
        from genpage2.vocab import Vocab

        vocab_loader = Vocab.load

    if args.context == "history":  # old programmatic callers; CLI no longer advertises it
        args.context = "items"
    if args.context not in LEVELS:
        raise ValueError(f"context must be one of {LEVELS}")
    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    device = _device(args.device)
    data_dir = Path(args.data_dir) if args.data_dir else config.out_dir() / args.mode
    output = Path(args.out) if args.out else data_dir / "ckpt" / args.name
    vocab = vocab_loader(data_dir / "vocab.json")
    content, article_rows = _load_content(data_dir)
    train_data = NpzExamples(data_dir / "train.npz")
    eval_path = data_dir / "eval.npz"
    eval_data = NpzExamples(eval_path) if eval_path.exists() else None
    cfg = ModelConfig.from_preset(args.preset, len(vocab.tokens), maxlen=args.maxlen,
                                  fallback_prob=args.fallback_prob)
    content_tensor = torch.as_tensor(content)
    token_rows = article_token_rows(vocab, article_rows)
    valid_rows = token_rows[token_rows >= 0]
    if valid_rows.numel() and int(valid_rows.max()) >= content_tensor.shape[0]:
        raise ValueError("vocab article content row exceeds content matrix")
    token_rows = token_rows.to(device)
    model = GenPageV2(cfg, content_tensor, tokens=vocab.tokens).to(device)
    item_start, item_end = _item_bounds(vocab)
    item_range = range(item_start, item_end)
    fallback_id = _vocab_id(vocab, "ITEM_FALLBACK")
    row_range = _row_bounds(vocab)
    row_fallback_id = _optional_vocab_id(vocab, "ROW_FALLBACK")
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr)
    planned_steps = args.max_steps or max(1, math.ceil(len(train_data) / args.batch) * args.epochs)
    def factor(step: int) -> float:
        if step < args.warmup:
            return (step + 1) / max(1, args.warmup)
        progress = min(1.0, (step - args.warmup) / max(1, planned_steps - args.warmup))
        return 0.5 * (1 + math.cos(math.pi * progress))
    scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, factor)
    loader = DataLoader(train_data, batch_size=args.batch, shuffle=True,
                        collate_fn=lambda x: make_batch(x, vocab=vocab, maxlen=args.maxlen, context=args.context))
    output.mkdir(parents=True, exist_ok=True)
    log_path = output / "train_log.jsonl"
    step = 0
    started = time.monotonic()
    last_log_time = started
    last_loss_tensor: torch.Tensor | None = None
    pad_id = _vocab_id(vocab, "PAD")
    pending_page_tokens = 0
    pending_input_tokens = 0
    generator = torch.Generator(device=device).manual_seed(args.seed)
    with log_path.open("w", encoding="utf-8") as log:
        for _ in range(args.epochs):
            for batch in loader:
                if args.max_steps and step >= args.max_steps:
                    break
                model.train()
                page_count_cpu = int(batch["loss_mask"].sum())
                input_count_cpu = int((batch["tokens"] != pad_id).sum())
                batch = {k: v.to(device) for k, v in batch.items()}
                add_page_content(batch, token_rows)
                optimizer.zero_grad(set_to_none=True)
                loss, _ = batch_loss(model, batch, item_range=item_range,
                                     fallback_id=fallback_id, fallback_prob=cfg.fallback_prob,
                                     row_range=row_range, row_fallback_id=row_fallback_id, generator=generator)
                loss.backward()
                nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                optimizer.step()
                scheduler.step()
                step += 1
                last_loss_tensor = loss.detach()
                # These reductions happened on CPU copies, avoiding a device sync.
                pending_page_tokens += page_count_cpu
                pending_input_tokens += input_count_cpu
                log_now = (step % args.log_every == 0 or
                           (eval_data is not None and step % args.eval_every == 0) or
                           step >= planned_steps or
                           (args.max_steps is not None and step == args.max_steps))
                if log_now:
                    now = time.monotonic()
                    elapsed = now - started
                    interval = max(now - last_log_time, 1e-9)
                    record: dict[str, Any] = {"step": step, "loss": float(last_loss_tensor),
                                               "lr": scheduler.get_last_lr()[0],
                                               "tokens_per_second": pending_page_tokens / interval,
                                               "input_tokens_per_second": pending_input_tokens / interval,
                                               "elapsed_seconds": elapsed}
                    last_log_time = now
                    pending_page_tokens = pending_input_tokens = 0
                    log.write(json.dumps(record) + "\n")
                    log.flush()
                if eval_data is not None and step % args.eval_every == 0:
                    metrics = evaluate(model, eval_data, vocab=vocab, token_rows=token_rows, batch_size=args.batch,
                                       maxlen=args.maxlen, context=args.context, limit=args.eval_examples, device=device)
                    log.write(json.dumps({"step": step, "eval": metrics}) + "\n")
                    log.flush()
                    last_log_time = time.monotonic()
            if args.max_steps and step >= args.max_steps:
                break
    elapsed = time.monotonic() - started
    last_loss = float(last_loss_tensor) if last_loss_tensor is not None else float("nan")
    extra = {"steps": step, "last_loss": last_loss, "parameter_count": sum(p.numel() for p in model.parameters()),
             "elapsed_seconds": elapsed, "preset": args.preset, "context": args.context}
    save_checkpoint(output, model, cfg, extra)
    return extra


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("validate", "final"), required=True)
    parser.add_argument("--preset", choices=("small", "base", "large"), default="small")
    parser.add_argument("--context", choices=LEVELS, default="full")
    parser.add_argument("--epochs", type=int, default=1)
    parser.add_argument("--max-steps", type=int)
    parser.add_argument("--batch", type=int, default=64)
    parser.add_argument("--lr", type=float, default=3e-4)
    parser.add_argument("--warmup", type=int, default=1000)
    parser.add_argument("--name", default="pretrain")
    parser.add_argument("--data-dir")
    parser.add_argument("--out")
    parser.add_argument("--device", choices=("auto", "mps", "cpu"), default="auto")
    parser.add_argument("--eval-every", type=int, default=1000)
    parser.add_argument("--eval-examples", type=int, default=5000)
    parser.add_argument("--log-every", type=int, default=50)
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--maxlen", type=int, default=getattr(config, "MAXLEN", 320))
    parser.add_argument("--fallback-prob", type=float, default=0.05)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> None:
    result = train(parse_args(argv))
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
