"""WBC 후학습: 노출 페이지 토큰의 로짓에 가중 이진 교차 엔트로피를 건다.

사전학습 체크포인트로 만든 페이지를 "운영 정책의 노출"로 두고, 노출된 토큰마다
다음 7일 실제 구매 여부를 라벨(부호)로, ``--w-pos`` / ``--w-neg`` 를 가중치(크기)로
쓴다. 행 토큰의 보상은 그 행에서 산 상품 수만큼 키운다. 생성은 기본 탐욕이며 문맥
수준은 ``--init`` 체크포인트의 값을 그대로 따른다(DESIGN.md §8).

1차 WBC(#301)의 실패 원인을 가르는 지렛대 셋을 인자로 낸다(넷 다 기본값이면 종전과 같다).

- ``--pretrain-weight λ``: WBC 배치마다 학습 기간 예제에서 같은 크기의 사전학습
  배치를 뽑아 ``train_pretrain`` 의 페이지 토큰 손실을 계산하고 ``loss = wbc + λ · pretrain``
  로 더한다. 두 손실은 로그 · 곡선에 따로 남는다.
- ``--neg-samples K`` · ``--neg-weight``: 노출된 상품 위치마다 노출되지 않은 상품 토큰
  K 개를 균등하게 뽑아 라벨 0 으로 더한다(음성 하나의 가중치 = ``neg-weight`` / K).
  음성은 입력 시퀀스에 넣지 않고, 노출 상품을 예측한 **바로 그 자리의 은닉 상태**에서
  점수를 매긴다(생성 때 그 자리에서 노출 상품과 경쟁하기 때문이다). 그 예제의 실제
  구매 상품과 노출된 상품은 뽑지 않고, 행 토큰에는 걸지 않는다. 손실은 노출 항과
  음성 항의 합을 **노출 자리 수**로 나눠 K 가 커져도 노출 항의 무게가 줄지 않는다.
- ``--gen-temperature T``: 노출 생성을 온도 T 로 표본 추출한다(0 이면 종전의 탐욕).
"""
from __future__ import annotations

import argparse
import json
import math
import time
from dataclasses import dataclass, field
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
from .train_pretrain import (
    NpzExamples,
    _item_bounds,
    _optional_vocab_id,
    _row_bounds,
    _vocab_id,
    add_page_content,
    article_token_rows,
    batch_loss,
    make_batch,
)

SEED = config.SEED


@dataclass
class Exposure:
    """한 예시의 노출: 문맥과 생성 페이지 토큰, 그리고 그 토큰의 라벨 · 가중치.

    ``negatives`` 는 페이지 토큰과 같은 길이의 표로, 각 위치에서 뽑은 음성
    상품 토큰 목록이다(행 토큰 자리는 빈 목록). 음성은 입력 시퀀스에 붙이지
    않고, 배치에서 ``neg_target`` · ``neg_mask`` 로만 실려 그 자리 은닉으로
    점수를 매긴다. ``negative_weight`` 는 음성 하나에 걸 가중치
    (= ``--neg-weight`` / ``--neg-samples``)다.
    """

    ctx_tokens: list[int]
    ctx_content: list[int]
    page_tokens: list[int]
    labels: list[float]
    weights: list[float]
    negatives: list[list[int]] = field(default_factory=list)
    negative_weight: float = 0.0


def _negative_table(vocab: Any, positions: Sequence[int | None], exposed: Sequence[int],
                    bought: Sequence[str], count: int,
                    generator: np.random.Generator | None) -> list[list[int]]:
    """노출된 상품 위치마다 노출되지 않은 상품 토큰을 균등하게 ``count`` 개 뽑는다.

    그 예제의 실제 구매 상품과 페이지에 노출된 상품은 후보에서 뺀다. 행 토큰 자리
    (``positions`` 가 ``None``)는 빈 목록으로 남긴다.
    """
    table: list[list[int]] = [[] for _ in positions]
    if count <= 0:
        return table
    item_start, item_end = _item_bounds(vocab)
    excluded = {int(token) for token in exposed}
    for article in bought:
        token = vocab.item(article)
        if token is not None:
            excluded.add(int(token))
    allowed = np.asarray([token for token in range(item_start, item_end) if token not in excluded],
                         dtype=np.int64)
    if allowed.size == 0:
        return table
    rng = generator if generator is not None else np.random.default_rng(SEED)
    size = min(int(count), int(allowed.size))
    for index, token in enumerate(positions):
        if token is None:
            continue
        picks = rng.choice(allowed, size=size, replace=False)
        table[index] = [int(value) for value in np.atleast_1d(picks)]
    return table


def exposure_from_rows(rows: Sequence[GeneratedRow], truth: Sequence[str], vocab: Any,
                       ctx_tokens: Sequence[int], ctx_content: Sequence[int], *,
                       w_pos: float = 1.0, w_neg: float = 1.0, neg_samples: int = 0,
                       neg_weight: float = 1.0,
                       neg_generator: np.random.Generator | None = None) -> Exposure:
    """생성 페이지와 실제 구매(truth)로 토큰별 라벨 · 가중치를 만든다.

    상품 토큰: truth 에 있으면 1(``w_pos``), 없으면 0(``w_neg``).
    행 토큰: 그 행에서 산 상품이 있으면 1(산 상품 수 × ``w_pos``), 없으면 0(``w_neg``).

    ``neg_samples`` > 0 이면 노출된 상품 위치마다 노출되지 않은 상품 토큰을
    ``neg_samples`` 개 뽑아 라벨 0 으로 더한다(음성 가중치 = ``neg_weight`` / ``neg_samples``).
    """
    bought = set(truth)
    page_tokens: list[int] = []
    labels: list[float] = []
    weights: list[float] = []
    positions: list[int | None] = []
    for row in rows:
        hit = sum(1 for article in row.items if article in bought)
        page_tokens.append(int(row.row_token))
        labels.append(1.0 if hit else 0.0)
        weights.append(hit * w_pos if hit else w_neg)
        positions.append(None)
        for article in row.items:
            token = vocab.item(article)
            if token is None:
                continue
            page_tokens.append(int(token))
            positive = article in bought
            labels.append(1.0 if positive else 0.0)
            weights.append(w_pos if positive else w_neg)
            positions.append(int(token))
    item_start, item_end = _item_bounds(vocab)
    exposed = [token for token in page_tokens if item_start <= token < item_end]
    count = int(neg_samples)
    negatives = _negative_table(vocab, positions, exposed, truth, count, neg_generator)
    negative_weight = float(neg_weight) / count if count > 0 else 0.0
    return Exposure([int(t) for t in ctx_tokens], [int(c) for c in ctx_content],
                    page_tokens, labels, weights, negatives, negative_weight)


def _content_lookup(vocab: Any, content_of_token: Sequence[int] | None) -> list[int]:
    if content_of_token is None:
        return [-1] * len(vocab.tokens)
    return list(content_of_token)


def make_wbc_batch(exposures: Sequence[Exposure], *, vocab: Any, level: str, maxlen: int,
                   device: Any, content_of_token: Sequence[int] | None = None) -> dict[str, torch.Tensor]:
    """문맥(체크포인트 수준) + 생성 페이지를 오른쪽 패딩 배치로 만든다.

    페이지의 각 토큰 위치에는 그 토큰을 예측하는 자리(바로 앞 위치) 하나만 표시한다.
    음성은 입력 시퀀스에 넣지 않고 ``neg_target`` · ``neg_mask`` 로만 싣는다. 음성은
    노출 상품 위치(``is_item``)에만 둔다. 그래서 ``tokens`` · ``content`` · ``mask`` ·
    ``target`` · ``label`` · ``weight`` 는 K 와 무관하게 K=0 과 완전히 같다.
    """
    lookup = _content_lookup(vocab, content_of_token)
    item_start, item_end = _item_bounds(vocab)
    pad = vocab.id("PAD")
    prepared = []
    for exposure in exposures:
        page = [int(t) for t in exposure.page_tokens]
        labels = [float(x) for x in exposure.labels]
        weights = [float(x) for x in exposure.weights]
        table = exposure.negatives if len(exposure.negatives) == len(page) else [[] for _ in page]
        ctx, content = context_view(exposure.ctx_tokens, exposure.ctx_content, vocab, level)
        ctx, content = truncate_context(ctx, content, maxlen - len(page), vocab=vocab, level=level)
        ctx = [int(t) for t in ctx]
        content = [int(c) for c in content]
        overflow = len(ctx) + len(page) - maxlen
        if overflow > 0:
            keep = len(page) - overflow
            page = page[:keep]
            labels = labels[:keep]
            weights = weights[:keep]
            table = table[:keep]
        if not page:
            continue
        prepared.append((ctx + page, content + [lookup[t] for t in page], len(ctx), page,
                         labels, weights, table, float(exposure.negative_weight)))
    if not prepared:
        raise ValueError("노출에 페이지 토큰이 없습니다")

    width = max(len(row[0]) for row in prepared)
    size = len(prepared)
    neg_k = max((max((len(negatives) for negatives in row[6]), default=0) for row in prepared), default=0)
    tokens = np.full((size, width), pad, dtype=np.int64)
    content_ids = np.full((size, width), -1, dtype=np.int64)
    mask = np.zeros((size, width - 1), dtype=bool)
    target = np.zeros((size, width - 1), dtype=np.int64)
    label = np.zeros((size, width - 1), dtype=np.float32)
    weight = np.zeros((size, width - 1), dtype=np.float32)
    is_item = np.zeros((size, width - 1), dtype=bool)
    neg_target = np.full((size, width - 1, neg_k), pad, dtype=np.int64)
    neg_mask = np.zeros((size, width - 1, neg_k), dtype=bool)
    neg_weight = np.zeros(size, dtype=np.float32)
    for index, (joined, rows, page_start, page, labels, weights, table, neg_w) in enumerate(prepared):
        length = len(joined)
        tokens[index, :length] = joined
        content_ids[index, :length] = rows
        neg_weight[index] = neg_w
        # SEP_PAGE(마지막 문맥 토큰)가 첫 페이지 토큰을 예측한다.
        for offset, token in enumerate(page):
            position = page_start - 1 + offset
            mask[index, position] = True
            target[index, position] = token
            label[index, position] = labels[offset]
            weight[index, position] = weights[offset]
            item = item_start <= token < item_end
            is_item[index, position] = item
            if item:
                for slot, negative in enumerate(table[offset][:neg_k]):
                    neg_target[index, position, slot] = negative
                    neg_mask[index, position, slot] = True

    def to_tensor(values: np.ndarray, dtype: torch.dtype) -> torch.Tensor:
        return torch.as_tensor(values, dtype=dtype, device=device)

    return {"tokens": to_tensor(tokens, torch.long), "content": to_tensor(content_ids, torch.long),
            "mask": to_tensor(mask, torch.bool), "target": to_tensor(target, torch.long),
            "label": to_tensor(label, torch.float32), "weight": to_tensor(weight, torch.float32),
            "is_item": to_tensor(is_item, torch.bool),
            "neg_target": to_tensor(neg_target, torch.long), "neg_mask": to_tensor(neg_mask, torch.bool),
            "neg_weight": to_tensor(neg_weight, torch.float32)}


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


def _weighted_bce(logits: torch.Tensor, labels: torch.Tensor, weights: torch.Tensor) -> torch.Tensor:
    """가중 BCE 의 원소 평균(원소 수로 나눔). 노출 토큰만 볼 때의 종전 정의다."""
    return F.binary_cross_entropy_with_logits(logits, labels, weight=weights)


def _weighted_sum(logits: torch.Tensor, labels: torch.Tensor, weights: torch.Tensor) -> torch.Tensor:
    """가중 BCE 의 합 Σ w·ℓ. 빈 항은 0 을 낸다."""
    if logits.numel() == 0:
        return logits.new_zeros(())
    return F.binary_cross_entropy_with_logits(logits, labels, weight=weights, reduction="sum")


def _wbc_terms(model: Any, batch: dict[str, torch.Tensor]) -> tuple[
        torch.Tensor, torch.Tensor, torch.Tensor,
        torch.Tensor, torch.Tensor, torch.Tensor]:
    """은닉 상태를 한 번만 계산해 노출 항과 음성 항의 (로짓, 라벨, 가중치) 를 낸다.

    노출 토큰은 그 자리 은닉과 실제 토큰의 로짓을 ``chosen_logits`` 로 구하고, 음성은
    **같은 자리 은닉**과 ``neg_target`` 의 내적(+편향)으로 구한다. 어휘 전체 로짓은
    만들지 않는다. 음성 라벨은 0, 가중치는 예제별 ``negative_weight`` 다.
    """
    hidden = model(batch["tokens"], batch["content"])
    positioned = hidden[:, :-1]
    selected = positioned[batch["mask"]]
    token_ids = batch["target"][batch["mask"]]
    logits = chosen_logits(model, selected, token_ids)
    labels = batch["label"][batch["mask"]]
    weights = batch["weight"][batch["mask"]]
    neg_mask = batch.get("neg_mask")
    if neg_mask is not None and bool(neg_mask.any()):
        active = neg_mask.any(dim=-1)
        hidden_at = positioned[active]
        ids = batch["neg_target"][active]
        keep = neg_mask[active]
        expanded = hidden_at.unsqueeze(1).expand(-1, ids.shape[1], -1)
        neg_logits = chosen_logits(model, expanded[keep], ids[keep])
        neg_labels = torch.zeros_like(neg_logits)
        per_step = batch["neg_weight"].to(neg_logits.dtype).view(-1, 1, 1).expand_as(neg_mask)
        neg_weights = per_step[active][keep].to(neg_logits.dtype)
    else:
        neg_logits = logits.new_zeros(0)
        neg_labels = logits.new_zeros(0)
        neg_weights = logits.new_zeros(0)
    return logits, labels, weights, neg_logits, neg_labels, neg_weights


def _wbc_objective(model: Any, batch: dict[str, torch.Tensor]) -> tuple[
        torch.Tensor, torch.Tensor, torch.Tensor | None,
        torch.Tensor, torch.Tensor, torch.Tensor]:
    """은닉을 한 번만 계산해 최적화 손실과 그 구성 항을 낸다.

    최적화 손실 = (Σ 노출 w·ℓ + Σ 음성 w·ℓ) / **노출 자리 수**. 분모를 노출 자리
    수로 고정하므로 음성 수 K 가 커져도 노출 항의 무게 · 학습률이 K=0 과 같다.
    노출 항 · 음성 항도 같은 분모를 써서 ``노출 항 + 음성 항`` 이 최적화 손실이
    된다(음성이 없으면 음성 항은 ``None``). 반환하는 로짓 · 라벨 · 가중치는
    **노출 토큰만** 담는다.
    """
    logits, labels, weights, neg_logits, neg_labels, neg_weights = _wbc_terms(model, batch)
    positions = int(batch["mask"].sum())
    exposure = _weighted_sum(logits, labels, weights) / positions
    if neg_logits.numel():
        negative: torch.Tensor | None = _weighted_sum(neg_logits, neg_labels, neg_weights) / positions
        loss = exposure + negative
    else:
        negative = None
        loss = exposure
    return loss, exposure, negative, logits, labels, weights


def wbc_loss(model: Any, batch: dict[str, torch.Tensor]) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
    """가중 BCE. 노출 항과 음성 항의 합을 **노출 자리 수**로 나눈다.

    분모가 노출 자리 수로 고정되므로 음성 수 K 가 커져도 노출 항의 기여 · 학습률이
    줄지 않는다(K=0 이면 종전 정의(노출 원소 평균)와 수치가 같다). 반환: 최적화
    손실, 노출 위치의 로짓, 라벨, 가중치. 로짓 · 라벨 · 가중치는 평가 지표가 K 와
    무관하도록 **노출 토큰만** 담는다(음성 항은 손실에만 들어간다).
    """
    loss, _, _, logits, labels, weights = _wbc_objective(model, batch)
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
            logits, labels, weights, _, _, _ = _wbc_terms(model, current)
            positions = int(current["mask"].sum())
            # 검증 손실은 노출 토큰만: 음성 항은 평가에서 제외한다.
            total_loss += float(_weighted_bce(logits, labels, weights)) * positions
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
          warmup: int = 100, log_every: int = 50, eval_every: int = 1000, seed: int = SEED,
          pretrain_weight: float = 0.0, pretrain_data: Any | None = None,
          token_rows: torch.Tensor | None = None,
          pretrain_generator: torch.Generator | None = None) -> dict[str, Any]:
    """``--init`` 모델을 그 자리에서 WBC 로 학습하고 로그 · 손실 곡선을 남긴다.

    ``pretrain_weight`` > 0 이면 WBC 배치마다 학습 기간 예제에서 같은 크기의
    사전학습 배치를 뽑아 ``train_pretrain`` 의 페이지 토큰 손실을 계산하고
    ``loss = wbc + λ · pretrain`` 로 더한다. 두 손실은 로그 · 곡선에 따로 남는다.
    """
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
    weight = float(pretrain_weight)
    use_pretrain = weight != 0.0 and pretrain_data is not None and len(pretrain_data) > 0
    if use_pretrain:
        if pretrain_generator is None:
            pretrain_generator = torch.Generator(device=device).manual_seed(seed)
        pretrain_sampler = torch.Generator().manual_seed(seed)
        pretrain_item_range = range(*_item_bounds(vocab))
        pretrain_fallback = _vocab_id(vocab, "ITEM_FALLBACK")
        pretrain_row_range = _row_bounds(vocab)
        pretrain_row_fallback = _optional_vocab_id(vocab, "ROW_FALLBACK")
        pretrain_fallback_prob = float(getattr(getattr(model, "cfg", None), "fallback_prob", 0.0))
    handle = None
    if output is not None:
        output = Path(output)
        output.mkdir(parents=True, exist_ok=True)
        handle = (output / "train_log.jsonl").open("w", encoding="utf-8")
    step = 0
    curve: list[dict[str, Any]] = []
    last_loss: float | None = None
    last_pretrain: float | None = None
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
                loss, wbc_term, neg_term, logits, labels, weights = _wbc_objective(model, current)
                wbc_value = float(wbc_term.detach())
                neg_value = float(neg_term.detach()) if neg_term is not None else None
                pretrain_loss: torch.Tensor | None = None
                if use_pretrain:
                    picked = torch.randint(0, len(pretrain_data), (len(part),),
                                           generator=pretrain_sampler).tolist()
                    examples = [pretrain_data[int(index)] for index in picked]
                    pretrain_batch = make_batch(examples, vocab=vocab, maxlen=maxlen, context=level)
                    pretrain_batch = {key: value.to(device) for key, value in pretrain_batch.items()}
                    if token_rows is not None:
                        add_page_content(pretrain_batch, token_rows)
                    pretrain_loss, _ = batch_loss(
                        model, pretrain_batch, item_range=pretrain_item_range,
                        fallback_id=pretrain_fallback, fallback_prob=pretrain_fallback_prob,
                        row_range=pretrain_row_range, row_fallback_id=pretrain_row_fallback,
                        generator=pretrain_generator)
                total = loss if pretrain_loss is None else loss + weight * pretrain_loss
                total.backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                optimizer.step()
                scheduler.step()
                step += 1
                last_loss = float(total.detach())
                last_pretrain = float(pretrain_loss.detach()) if pretrain_loss is not None else None
                positive_ratio = float((labels > 0.5).to(torch.float32).mean()) if labels.numel() else 0.0
                if handle is not None and (step % log_every == 0 or step == planned):
                    record = {"step": step, "wbc_loss": wbc_value, "positive_ratio": positive_ratio,
                              "lr": scheduler.get_last_lr()[0], "elapsed_seconds": time.monotonic() - started}
                    curve_record = {"step": step, "wbc_loss": wbc_value, "positive_ratio": positive_ratio}
                    if neg_value is not None:
                        record["neg_loss"] = neg_value
                        curve_record["neg_loss"] = neg_value
                    if last_pretrain is not None:
                        record["pretrain_loss"] = last_pretrain
                        record["total_loss"] = last_loss
                        curve_record["pretrain_loss"] = last_pretrain
                        curve_record["total_loss"] = last_loss
                    handle.write(json.dumps(record) + "\n")
                    handle.flush()
                    curve.append(curve_record)
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
    return {"steps": step, "last_loss": last_loss, "last_pretrain_loss": last_pretrain,
            "train_seconds": time.monotonic() - started, "loss_curve": curve, "valid": valid}


def _generate_exposures(decoder: Any, data: NpzExamples, indices: Sequence[int],
                        histories: Sequence[list[str]], truths: Sequence[list[str]], *, vocab: Any,
                        pin_repeat: bool, gen_batch: int, w_pos: float, w_neg: float,
                        temperature: float = 0.0, generator: torch.Generator | None = None,
                        neg_samples: int = 0, neg_weight: float = 1.0,
                        neg_generator: np.random.Generator | None = None) -> tuple[list[Exposure], int]:
    """디코더로 노출 페이지를 만들고 실제 구매로 라벨을 붙인다.

    ``temperature`` > 0 이면 ``generator`` 로 표본 추출한다(0 이면 탐욕).
    """
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
                                         items_per_row=config.ITEMS_PER_ROW, prefix=2,
                                         temperature=temperature, generator=generator)
        for offset, (rows, bad) in enumerate(decoded):
            violations += int(bad)
            example = examples[offset]
            exposure = exposure_from_rows(rows, truths[begin + offset], vocab,
                                          example["ctx_tokens"], example["ctx_content"],
                                          w_pos=w_pos, w_neg=w_neg, neg_samples=neg_samples,
                                          neg_weight=neg_weight, neg_generator=neg_generator)
            if exposure.page_tokens:
                exposures.append(exposure)
    return exposures, violations


def run(args: argparse.Namespace) -> dict[str, Any]:
    base = Path(args.data_dir) if args.data_dir else config.data_dir()
    mode_dir = base / "hm" / "model" / "genpage2" / args.mode
    decoder, vocab, _, content_rows, device = _load_decoder(mode_dir, Path(args.init), args.device)
    model, level = decoder.model, decoder.level
    maxlen = int(model.cfg.maxlen)
    token_rows = article_token_rows(vocab, content_rows)
    content_of_token = token_rows.tolist()

    seed = int(args.seed)
    neg_samples = int(getattr(args, "neg_samples", 0))
    neg_weight = float(getattr(args, "neg_weight", 1.0))
    temperature = float(getattr(args, "gen_temperature", 0.0))
    pretrain_weight = float(getattr(args, "pretrain_weight", 0.0))
    neg_generator = np.random.default_rng(seed)
    gen_generator = torch.Generator(device=device).manual_seed(seed)

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
        w_pos=float(args.w_pos), w_neg=float(args.w_neg), temperature=temperature,
        generator=gen_generator, neg_samples=neg_samples, neg_weight=neg_weight,
        neg_generator=neg_generator)
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
            w_pos=float(args.w_pos), w_neg=float(args.w_neg), temperature=temperature,
            generator=gen_generator, neg_samples=neg_samples, neg_weight=neg_weight,
            neg_generator=neg_generator)
        violations += valid_violations
        valid_generation_seconds = time.monotonic() - valid_started

    output = Path(args.out) if args.out else mode_dir / "ckpt" / args.name
    result = train(model, train_exposures, vocab=vocab, level=level, maxlen=maxlen, device=device,
                   content_of_token=content_of_token, valid_exposures=valid_exposures or None,
                   output=output, epochs=int(args.epochs), batch=int(args.batch), lr=float(args.lr),
                   warmup=int(args.warmup), log_every=int(args.log_every), eval_every=int(args.eval_every),
                   seed=int(args.seed), pretrain_weight=pretrain_weight, pretrain_data=train_data,
                   token_rows=token_rows)

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
        "last_pretrain_loss": result["last_pretrain_loss"],
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
        "pretrain_weight": pretrain_weight,
        "neg_samples": neg_samples,
        "neg_weight": neg_weight,
        "gen_temperature": temperature,
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
    parser.add_argument("--pretrain-weight", type=float, default=0.0,
                        help="WBC 손실에 더할 사전학습 페이지 토큰 손실의 가중치 λ")
    parser.add_argument("--neg-samples", type=int, default=0,
                        help="노출된 상품 위치마다 뽑을 음성 상품 수 K")
    parser.add_argument("--neg-weight", type=float, default=1.0, help="음성 가중치 합(음성 하나 = neg-weight / K)")
    parser.add_argument("--gen-temperature", type=float, default=0.0,
                        help="노출 생성 온도(0 이면 탐욕)")
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
