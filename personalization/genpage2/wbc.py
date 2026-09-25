"""WBC 후학습: 노출 페이지 토큰의 로짓에 가중 이진 교차 엔트로피를 건다.

사전학습 체크포인트로 만든 페이지를 "운영 정책의 노출"로 두고, 노출된 토큰마다
다음 7일 실제 구매 여부를 라벨(부호)로, ``--w-pos`` / ``--w-neg`` 를 가중치(크기)로
쓴다. 행 토큰의 보상은 그 행에서 산 상품 수만큼 키운다. 생성은 탐욕이며 문맥
수준은 ``--init`` 체크포인트의 값을 그대로 따른다(DESIGN.md §8).
"""
from __future__ import annotations

import argparse
import json
import math
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Sequence

import numpy as np
import pandas as pd
import torch
import torch.nn.functional as F

from . import config
from .context import truncate as truncate_context
from .context import view as context_view
from .decode import GeneratedRow
from .evaluate import _as_articles, _load_decoder, _repeat_pin
from .model import save_checkpoint
from .train_pretrain import NpzExamples, _item_bounds, article_token_rows

SEED = config.SEED


@dataclass
class Exposure:
    """한 예시의 노출: 문맥과 생성 페이지 토큰, 그리고 그 토큰의 라벨 · 가중치."""

    ctx_tokens: list[int]
    ctx_content: list[int]
    page_tokens: list[int]
    labels: list[float]
    weights: list[float]


def exposure_from_rows(rows: Sequence[GeneratedRow], truth: Sequence[str], vocab: Any,
                       ctx_tokens: Sequence[int], ctx_content: Sequence[int], *,
                       w_pos: float = 1.0, w_neg: float = 1.0) -> Exposure:
    """생성 페이지와 실제 구매(truth)로 토큰별 라벨 · 가중치를 만든다.

    상품 토큰: truth 에 있으면 1(``w_pos``), 없으면 0(``w_neg``).
    행 토큰: 그 행에서 산 상품이 있으면 1(산 상품 수 × ``w_pos``), 없으면 0(``w_neg``).
    """
    bought = set(truth)
    page_tokens: list[int] = []
    labels: list[float] = []
    weights: list[float] = []
    for row in rows:
        hit = sum(1 for article in row.items if article in bought)
        page_tokens.append(int(row.row_token))
        labels.append(1.0 if hit else 0.0)
        weights.append(hit * w_pos if hit else w_neg)
        for article in row.items:
            token = vocab.item(article)
            if token is None:
                continue
            page_tokens.append(int(token))
            positive = article in bought
            labels.append(1.0 if positive else 0.0)
            weights.append(w_pos if positive else w_neg)
    return Exposure([int(t) for t in ctx_tokens], [int(c) for c in ctx_content],
                    page_tokens, labels, weights)


def _content_lookup(vocab: Any, content_of_token: Sequence[int] | None) -> list[int]:
    if content_of_token is None:
        return [-1] * len(vocab.tokens)
    return list(content_of_token)


def make_wbc_batch(exposures: Sequence[Exposure], *, vocab: Any, level: str, maxlen: int,
                   device: Any, content_of_token: Sequence[int] | None = None) -> dict[str, torch.Tensor]:
    """문맥(체크포인트 수준) + 생성 페이지를 오른쪽 패딩 배치로 만든다.

    페이지의 각 토큰 위치에는 그 토큰을 예측하는 자리(바로 앞 위치) 하나만 표시한다.
    """
    lookup = _content_lookup(vocab, content_of_token)
    item_start, item_end = _item_bounds(vocab)
    pad = vocab.id("PAD")
    prepared = []
    for exposure in exposures:
        page = [int(t) for t in exposure.page_tokens]
        labels = [float(x) for x in exposure.labels]
        weights = [float(x) for x in exposure.weights]
        ctx, content = context_view(exposure.ctx_tokens, exposure.ctx_content, vocab, level)
        ctx, content = truncate_context(ctx, content, maxlen - len(page), vocab=vocab, level=level)
        ctx = [int(t) for t in ctx]
        content = [int(c) for c in content]
        overflow = len(ctx) + len(page) - maxlen
        if overflow > 0:
            page = page[:len(page) - overflow]
            labels = labels[:len(page)]
            weights = weights[:len(page)]
        if not page:
            continue
        prepared.append((ctx + page, content + [lookup[t] for t in page], len(ctx), page, labels, weights))
    if not prepared:
        raise ValueError("노출에 페이지 토큰이 없습니다")

    width = max(len(row[0]) for row in prepared)
    size = len(prepared)
    tokens = np.full((size, width), pad, dtype=np.int64)
    content_ids = np.full((size, width), -1, dtype=np.int64)
    mask = np.zeros((size, width - 1), dtype=bool)
    target = np.zeros((size, width - 1), dtype=np.int64)
    label = np.zeros((size, width - 1), dtype=np.float32)
    weight = np.zeros((size, width - 1), dtype=np.float32)
    is_item = np.zeros((size, width - 1), dtype=bool)
    for index, (joined, rows, page_start, page, labels, weights) in enumerate(prepared):
        length = len(joined)
        tokens[index, :length] = joined
        content_ids[index, :length] = rows
        # SEP_PAGE(마지막 문맥 토큰)가 첫 페이지 토큰을 예측한다.
        for offset, token in enumerate(page):
            position = page_start - 1 + offset
            mask[index, position] = True
            target[index, position] = token
            label[index, position] = labels[offset]
            weight[index, position] = weights[offset]
            is_item[index, position] = item_start <= token < item_end

    def to_tensor(values: np.ndarray, dtype: torch.dtype) -> torch.Tensor:
        return torch.as_tensor(values, dtype=dtype, device=device)

    return {"tokens": to_tensor(tokens, torch.long), "content": to_tensor(content_ids, torch.long),
            "mask": to_tensor(mask, torch.bool), "target": to_tensor(target, torch.long),
            "label": to_tensor(label, torch.float32), "weight": to_tensor(weight, torch.float32),
            "is_item": to_tensor(is_item, torch.bool)}


def chosen_logits(model: Any, hidden: torch.Tensor, token_ids: torch.Tensor) -> torch.Tensor:
    """선택한 위치에서 지정한 토큰 하나의 로짓만 계산한다(어휘 전체를 만들지 않는다)."""
    projection = getattr(model, "output_projection", None)
    if projection is None:
        projection = getattr(model, "output", None)
    weight = getattr(projection, "weight", None) if projection is not None else None
    if weight is None:
        return model.logits(hidden).gather(1, token_ids.unsqueeze(1)).squeeze(1)
    logits = (hidden * weight[token_ids]).sum(dim=-1)
    bias = getattr(projection, "bias", None)
    return logits if bias is None else logits + bias[token_ids]


def wbc_loss(model: Any, batch: dict[str, torch.Tensor]) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
    """가중 BCE. 반환: 손실, 선택 위치의 로짓, 라벨, 가중치."""
    hidden = model(batch["tokens"], batch["content"])
    selected = hidden[:, :-1][batch["mask"]]
    token_ids = batch["target"][batch["mask"]]
    logits = chosen_logits(model, selected, token_ids)
    labels = batch["label"][batch["mask"]]
    weights = batch["weight"][batch["mask"]]
    loss = F.binary_cross_entropy_with_logits(logits, labels, weight=weights)
    return loss, logits, labels, weights


def weighted_auc(scores: Sequence[float], labels: Sequence[float],
                 weights: Sequence[float]) -> float | None:
    """표본 가중 ROC-AUC. 한쪽 클래스의 가중치 합이 0이면 None."""
    scores = np.asarray(scores, dtype=np.float64)
    labels = np.asarray(labels, dtype=np.float64)
    weights = np.asarray(weights, dtype=np.float64)
    positive = labels > 0.5
    total_positive = float(weights[positive].sum())
    total_negative = float(weights[~positive].sum())
    if total_positive <= 0 or total_negative <= 0:
        return None
    order = np.argsort(scores, kind="mergesort")
    ranked_scores = scores[order]
    ranked_weights = weights[order]
    ranked_positive = positive[order]
    numerator = 0.0
    lower_negative = 0.0
    start = 0
    while start < len(ranked_scores):
        end = start
        while end < len(ranked_scores) and ranked_scores[end] == ranked_scores[start]:
            end += 1
        group = slice(start, end)
        group_positive = float(ranked_weights[group][ranked_positive[group]].sum())
        group_negative = float(ranked_weights[group][~ranked_positive[group]].sum())
        numerator += group_positive * (lower_negative + 0.5 * group_negative)
        lower_negative += group_negative
        start = end
    return numerator / (total_positive * total_negative)


def evaluate_wbc(model: Any, exposures: Sequence[Exposure], *, vocab: Any, level: str, maxlen: int,
                 device: Any, content_of_token: Sequence[int] | None = None,
                 batch: int = 128) -> dict[str, Any]:
    """검증 노출의 WBC 손실과 상품 토큰 AUC."""
    if not exposures:
        return {"wbc_loss": None, "item_auc": None, "positive_ratio": None, "examples": 0}
    model.eval()
    total_loss = 0.0
    total_positions = 0
    item_scores: list[np.ndarray] = []
    item_labels: list[np.ndarray] = []
    item_weights: list[np.ndarray] = []
    all_labels: list[np.ndarray] = []
    with torch.no_grad():
        for begin in range(0, len(exposures), batch):
            part = exposures[begin:begin + batch]
            current = make_wbc_batch(part, vocab=vocab, level=level, maxlen=maxlen, device=device,
                                     content_of_token=content_of_token)
            loss, logits, labels, weights = wbc_loss(model, current)
            positions = int(current["mask"].sum())
            total_loss += float(loss) * positions
            total_positions += positions
            all_labels.append(labels.cpu().numpy())
            item_mask = current["is_item"][current["mask"]]
            if bool(item_mask.any()):
                item_scores.append(logits[item_mask].cpu().numpy())
                item_labels.append(labels[item_mask].cpu().numpy())
                item_weights.append(weights[item_mask].cpu().numpy())
    scores = np.concatenate(item_scores) if item_scores else np.empty(0)
    labels = np.concatenate(item_labels) if item_labels else np.empty(0)
    weights = np.concatenate(item_weights) if item_weights else np.empty(0)
    every = np.concatenate(all_labels) if all_labels else np.empty(0)
    return {"wbc_loss": total_loss / total_positions if total_positions else None,
            "item_auc": weighted_auc(scores, labels, weights),
            "positive_ratio": float((every > 0.5).mean()) if every.size else None,
            "examples": len(exposures)}


def train(model: Any, exposures: Sequence[Exposure], *, vocab: Any, level: str, maxlen: int, device: Any,
          content_of_token: Sequence[int] | None = None, valid_exposures: Sequence[Exposure] | None = None,
          output: str | Path | None = None, epochs: int = 1, batch: int = 128, lr: float = 1e-4,
          warmup: int = 100, log_every: int = 50, eval_every: int = 1000, seed: int = SEED) -> dict[str, Any]:
    """``--init`` 모델을 그 자리에서 WBC 로 학습하고 로그 · 손실 곡선을 남긴다."""
    torch.manual_seed(seed)
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr)
    steps_per_epoch = max(1, math.ceil(len(exposures) / batch))
    planned = max(1, steps_per_epoch * epochs)

    def factor(step: int) -> float:
        if step < warmup:
            return (step + 1) / max(1, warmup)
        progress = min(1.0, (step - warmup) / max(1, planned - warmup))
        return 0.5 * (1 + math.cos(math.pi * progress))

    scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, factor)
    generator = torch.Generator().manual_seed(seed)
    handle = None
    if output is not None:
        output = Path(output)
        output.mkdir(parents=True, exist_ok=True)
        handle = (output / "train_log.jsonl").open("w", encoding="utf-8")
    step = 0
    curve: list[dict[str, Any]] = []
    last_loss: float | None = None
    valid: dict[str, Any] | None = None
    started = time.monotonic()
    try:
        for _ in range(epochs):
            order = torch.randperm(len(exposures), generator=generator).tolist()
            for begin in range(0, len(order), batch):
                part = [exposures[index] for index in order[begin:begin + batch]]
                current = make_wbc_batch(part, vocab=vocab, level=level, maxlen=maxlen, device=device,
                                         content_of_token=content_of_token)
                model.train()
                optimizer.zero_grad(set_to_none=True)
                loss, _, labels, _ = wbc_loss(model, current)
                loss.backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                optimizer.step()
                scheduler.step()
                step += 1
                last_loss = float(loss.detach())
                positive_ratio = float((labels > 0.5).to(torch.float32).mean()) if labels.numel() else 0.0
                if handle is not None and (step % log_every == 0 or step == planned):
                    record = {"step": step, "wbc_loss": last_loss, "positive_ratio": positive_ratio,
                              "lr": scheduler.get_last_lr()[0], "elapsed_seconds": time.monotonic() - started}
                    handle.write(json.dumps(record) + "\n")
                    handle.flush()
                    curve.append({"step": step, "wbc_loss": last_loss, "positive_ratio": positive_ratio})
                if valid_exposures and eval_every and step % eval_every == 0:
                    valid = evaluate_wbc(model, valid_exposures, vocab=vocab, level=level, maxlen=maxlen,
                                         device=device, content_of_token=content_of_token, batch=batch)
                    if handle is not None:
                        handle.write(json.dumps({"step": step, "eval": valid}) + "\n")
                        handle.flush()
                    curve.append({"step": step, "eval": valid})
    finally:
        if handle is not None:
            handle.close()
    return {"steps": step, "last_loss": last_loss, "train_seconds": time.monotonic() - started,
            "loss_curve": curve, "valid": valid}


def _generate_exposures(decoder: Any, data: NpzExamples, indices: Sequence[int],
                        histories: Sequence[list[str]], truths: Sequence[list[str]], *, vocab: Any,
                        pin_repeat: bool, gen_batch: int, w_pos: float,
                        w_neg: float) -> tuple[list[Exposure], int]:
    """디코더로 노출 페이지를 만들고 실제 구매로 라벨을 붙인다."""
    exposures: list[Exposure] = []
    violations = 0
    for begin in range(0, len(indices), gen_batch):
        stop = min(begin + gen_batch, len(indices))
        examples = []
        for offset in range(begin, stop):
            ctx, content, _ = data[int(indices[offset])]
            history = list(histories[offset])
            examples.append({"ctx_tokens": np.asarray(ctx).astype(int).tolist(),
                             "ctx_content": np.asarray(content).astype(int).tolist(),
                             "history_articles": history,
                             "pinned": _repeat_pin(history, vocab) if pin_repeat else None})
        decoded = decoder.generate_batch(examples, n_rows=config.MAX_ROWS,
                                         items_per_row=config.ITEMS_PER_ROW, prefix=2)
        for offset, (rows, bad) in enumerate(decoded):
            violations += int(bad)
            example = examples[offset]
            exposure = exposure_from_rows(rows, truths[begin + offset], vocab,
                                          example["ctx_tokens"], example["ctx_content"],
                                          w_pos=w_pos, w_neg=w_neg)
            if exposure.page_tokens:
                exposures.append(exposure)
    return exposures, violations


def run(args: argparse.Namespace) -> dict[str, Any]:
    base = Path(args.data_dir) if args.data_dir else config.data_dir()
    mode_dir = base / "hm" / "model" / "genpage2" / args.mode
    decoder, vocab, _, content_rows, device = _load_decoder(mode_dir, Path(args.init), args.device)
    model, level = decoder.model, decoder.level
    maxlen = int(model.cfg.maxlen)
    content_of_token = article_token_rows(vocab, content_rows).tolist()

    torch.manual_seed(args.seed)
    np.random.seed(args.seed)

    train_data = NpzExamples(mode_dir / "train.npz")
    train_meta = pd.read_parquet(mode_dir / "train_meta.parquet")
    total = len(train_data)
    take = total if args.examples is None else min(int(args.examples), total)
    rng = np.random.default_rng(args.seed)
    indices = np.sort(rng.choice(total, size=take, replace=False)) if take < total else np.arange(total)
    sampled = train_meta.iloc[indices]
    histories = [_as_articles(value) for value in sampled["history"].tolist()]
    truths = [_as_articles(value) for value in sampled["truth"].tolist()]

    started = time.monotonic()
    train_exposures, violations = _generate_exposures(
        decoder, train_data, indices, histories, truths, vocab=vocab,
        pin_repeat=bool(args.pin_repeat), gen_batch=int(args.gen_batch),
        w_pos=float(args.w_pos), w_neg=float(args.w_neg))
    generation_seconds = time.monotonic() - started
    if not train_exposures:
        raise ValueError("학습 노출이 하나도 만들어지지 않았습니다")

    valid_exposures: list[Exposure] = []
    valid_generation_seconds = 0.0
    eval_path = mode_dir / "eval.npz"
    if args.eval_examples and eval_path.exists():
        valid_started = time.monotonic()
        eval_data = NpzExamples(eval_path)
        eval_meta = pd.read_parquet(mode_dir / "eval_meta.parquet")
        count = min(int(args.eval_examples), len(eval_data))
        valid_indices = np.arange(count)
        valid_sampled = eval_meta.iloc[valid_indices]
        valid_histories = [_as_articles(value) for value in valid_sampled["history"].tolist()]
        valid_truths = [_as_articles(value) for value in valid_sampled["truth"].tolist()]
        valid_exposures, valid_violations = _generate_exposures(
            decoder, eval_data, valid_indices, valid_histories, valid_truths, vocab=vocab,
            pin_repeat=bool(args.pin_repeat), gen_batch=int(args.gen_batch),
            w_pos=float(args.w_pos), w_neg=float(args.w_neg))
        violations += valid_violations
        valid_generation_seconds = time.monotonic() - valid_started

    output = Path(args.out) if args.out else mode_dir / "ckpt" / args.name
    result = train(model, train_exposures, vocab=vocab, level=level, maxlen=maxlen, device=device,
                   content_of_token=content_of_token, valid_exposures=valid_exposures or None,
                   output=output, epochs=int(args.epochs), batch=int(args.batch), lr=float(args.lr),
                   warmup=int(args.warmup), log_every=int(args.log_every), eval_every=int(args.eval_every),
                   seed=int(args.seed))

    label_count = sum(len(exposure.labels) for exposure in train_exposures)
    positive = sum(sum(1 for value in exposure.labels if value > 0.5) for exposure in train_exposures)
    extra = {
        "init": str(args.init),
        "examples": len(train_exposures),
        "pin_repeat": bool(args.pin_repeat),
        "context": level,
        "positive_ratio": positive / label_count if label_count else 0.0,
        "steps": result["steps"],
        "last_loss": result["last_loss"],
        "loss_curve": result["loss_curve"],
        "valid": result["valid"],
        "valid_examples": len(valid_exposures),
        "violations": int(violations),
        "generation_seconds": generation_seconds,
        "valid_generation_seconds": valid_generation_seconds,
        "train_seconds": result["train_seconds"],
        "parameter_count": sum(parameter.numel() for parameter in model.parameters()),
        "w_pos": float(args.w_pos),
        "w_neg": float(args.w_neg),
        "lr": float(args.lr),
        "batch": int(args.batch),
        "epochs": int(args.epochs),
        "seed": int(args.seed),
    }
    save_checkpoint(output, model, model.cfg, extra)
    return extra


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("validate", "final"), required=True)
    parser.add_argument("--init", required=True, help="사전학습 체크포인트 디렉터리")
    parser.add_argument("--examples", type=int, default=100_000)
    parser.add_argument("--pin-repeat", action="store_true")
    parser.add_argument("--epochs", type=int, default=1)
    parser.add_argument("--batch", type=int, default=128)
    parser.add_argument("--lr", type=float, default=1e-4)
    parser.add_argument("--warmup", type=int, default=100)
    parser.add_argument("--w-pos", type=float, default=1.0)
    parser.add_argument("--w-neg", type=float, default=1.0)
    parser.add_argument("--name", default="wbc")
    parser.add_argument("--device", choices=("auto", "mps", "cpu"), default="auto")
    parser.add_argument("--gen-batch", type=int, default=256)
    parser.add_argument("--data-dir")
    parser.add_argument("--out")
    parser.add_argument("--eval-every", type=int, default=1000)
    parser.add_argument("--eval-examples", type=int, default=2000)
    parser.add_argument("--log-every", type=int, default=50)
    parser.add_argument("--seed", type=int, default=SEED)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> None:
    result = run(parse_args(argv))
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
