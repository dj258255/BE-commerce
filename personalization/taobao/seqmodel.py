"""G: 노출 순차 모델.

문서의 "G: 노출 순차 모델" 절을 구현한다.

- 고객마다 노출을 시각 순서로 한 번 읽고 모든 노출 위치의 클릭을 한꺼번에 예측한다.
- 토큰 = 광고 · 카테고리 · 브랜드 · 캠페인 · 가격 구간 임베딩의 합 + 클릭 여부
  임베딩 + 직전 노출과의 시간 간격 구간 임베딩.
- 채점할 노출은 클릭 여부를 뺀 같은 토큰을 질의로 쓴다. 이력 토큰끼리는 시각
  순서 인과 주의, 질의는 **시각이 더 이른** 이력만 본다. 고객 속성 · 맥락은
  질의에 더한다.
- 이력은 마지막 256개 노출까지.
- 학습: AdamW lr 1e-3, 배치 256명, 최대 3 에폭, 에폭마다 검증 GAUC 로 가장 좋은
  에폭을 남긴다. 시드 7.
- G-click: 고른 G 설정 그대로, 이력에서 안 누른 노출을 지운다(질의 · 라벨은 그대로).
"""
from __future__ import annotations

import argparse
import copy
import json
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np

from . import config, data as data_module, metrics

USER_ATTRIBUTES = ("cms_segid", "cms_group_id", "final_gender_code", "age_level",
                   "pvalue_level", "shopping_level", "occupation", "new_user_class_level")


def gap_bucket(seconds: np.ndarray) -> np.ndarray:
    """시간 간격(초)을 구간 번호로. 0 초과 ~ 마지막 임계 초과까지."""
    return np.searchsorted(np.asarray(config.GAP_THRESHOLDS), seconds, side="right").astype(np.int64)


def select_device(device: str = "auto") -> str:
    if device != "auto":
        return device
    import torch

    if torch.backends.mps.is_available():
        return "mps"
    if torch.cuda.is_available():
        return "cuda"
    return "cpu"


# --------------------------------------------------------------------------- 순서 만들기


@dataclass
class UserSequence:
    """한 고객의 이력 창(마지막 256개)과 그 안의 채점 위치.

    노출 배열은 창 안의 노출마다 하나다(0 번이 창의 첫 노출). `q_pos` 는 창 안에서
    채점할 위치이고, `q_ctx` 는 그 위치의 맥락(pid · 시 · 주말), `q_label` 은 클릭,
    `q_row` 는 분할 채점 행의 전역 번호다(-1 이면 행 번호를 쓰지 않는다).
    """

    item: np.ndarray
    cate: np.ndarray
    brand: np.ndarray
    campaign: np.ndarray
    price: np.ndarray
    ts: np.ndarray
    click: np.ndarray
    q_pos: np.ndarray
    q_ctx: np.ndarray
    q_label: np.ndarray
    q_row: np.ndarray
    user: int


def _rows_by_user(split_data: data_module.SplitData) -> dict[int, dict[str, np.ndarray]]:
    order = np.lexsort((split_data.row_pos, split_data.row_user))
    user = split_data.row_user[order]
    cuts = np.flatnonzero(np.diff(user)) + 1
    starts = np.concatenate([[0], cuts])
    ends = np.concatenate([cuts, [len(user)]])
    result: dict[int, dict[str, np.ndarray]] = {}
    for start, end in zip(starts, ends):
        index = order[start:end]
        result[int(user[start])] = {
            "pos": split_data.row_pos[index],
            "pid": split_data.row_pid[index],
            "hour": split_data.row_hour[index],
            "weekend": split_data.row_weekend[index],
            "label": split_data.row_label[index],
            "row": index,
        }
    return result


def build_sequences(split_data: data_module.SplitData, *,
                    max_history: int = config.MAX_HISTORY) -> list[UserSequence]:
    """고객마다 이력 창과 채점 위치를 만든다.

    창은 그 고객의 마지막 채점 행까지에서 뒤로 `max_history` 개 노출이다. 학습은
    학습 분할의 마지막 채점 행까지, 평가는 그 분할의 마지막 채점 행까지 본다.
    그래서 분할 뒤(미래) 노출은 창에 들어오지 않는다. 창 밖으로 밀린 채점 행은
    버린다(문서의 "이력은 마지막 256개").
    """
    rows = _rows_by_user(split_data)
    sequences: list[UserSequence] = []
    for user in range(split_data.n_users):
        record = rows.get(user)
        if record is None:
            continue
        positions = record["pos"]
        end = int(positions.max()) + 1
        start = max(end - max_history, 0)
        in_window = positions >= start
        index = np.flatnonzero(in_window)
        if len(index) == 0:
            continue
        window = np.arange(start, end)
        order = np.argsort(positions[index], kind="stable")
        index = index[order]
        q_pos = (positions[index] - start).astype(np.int64)
        q_ctx = np.stack([record["pid"][index], record["hour"][index], record["weekend"][index]],
                         axis=1).astype(np.int64)
        sequences.append(UserSequence(
            item=split_data.item[window], cate=split_data.cate[window], brand=split_data.brand[window],
            campaign=split_data.campaign[window], price=split_data.price[window], ts=split_data.ts[window],
            click=split_data.click[window], q_pos=q_pos, q_ctx=q_ctx,
            q_label=record["label"][index].astype(np.int64), q_row=record["row"][index].astype(np.int64),
            user=user,
        ))
    return sequences


# --------------------------------------------------------------------------- 모델


def _torch():
    import torch
    return torch


class _Attention:
    """여러 헤드 어텐션. 마스크는 True 가 금지다. 모두 금지된 질의는 0 을 낸다."""

    @staticmethod
    def apply(torch, query, key, value, mask, key_pad, dropout, heads):
        batch, length_q, dim = query.shape
        head_dim = dim // heads

        def split(tensor):
            return tensor.reshape(batch, -1, heads, head_dim).transpose(1, 2)

        q = split(query)
        k = split(key)
        v = split(value)
        scores = torch.matmul(q, k.transpose(-1, -2)) * (head_dim ** -0.5)
        if mask is not None:
            if mask.dim() == 2:
                mask = mask[None, None]
            else:
                mask = mask[:, None]
            scores = scores.masked_fill(mask, float("-inf"))
        if key_pad is not None:
            scores = scores.masked_fill(key_pad[:, None, None, :], float("-inf"))
        weights = torch.softmax(scores, dim=-1)
        weights = torch.nan_to_num(weights, nan=0.0)
        weights = dropout(weights)
        out = torch.matmul(weights, v)
        return out.transpose(1, 2).reshape(batch, length_q, dim)


def build_model(dim: int, layers: int, *, heads: int = 4, dropout: float = 0.1):
    torch = _torch()
    from torch import nn

    class Block(nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.norm_hist = nn.LayerNorm(dim)
            self.norm_query = nn.LayerNorm(dim)
            self.norm_cross = nn.LayerNorm(dim)
            self.self_qkv = nn.Linear(dim, 3 * dim)
            self.cross_q = nn.Linear(dim, dim)
            self.cross_kv = nn.Linear(dim, 2 * dim)
            self.ff = nn.Sequential(nn.Linear(dim, 4 * dim), nn.GELU(), nn.Linear(4 * dim, dim))
            self.drop = nn.Dropout(dropout)

        def forward(self, history, query, self_mask, cross_mask, key_pad):
            source = self.norm_hist(history)
            q, k, v = self.self_qkv(source).chunk(3, dim=-1)
            attention = _Attention.apply(torch, q, k, v, self_mask, key_pad, self.drop, heads)
            history = history + self.drop(attention)

            source = self.norm_cross(history)
            q = self.cross_q(self.norm_query(query))
            k, v = self.cross_kv(source).chunk(2, dim=-1)
            attention = _Attention.apply(torch, q, k, v, cross_mask, key_pad, self.drop, heads)
            query = query + self.drop(attention)
            query = query + self.ff(self.norm_query(query))
            return history, query

    class SeqRanker(nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.item = nn.Embedding(config.VOCAB["item"] + 1, dim)
            self.cate = nn.Embedding(config.VOCAB["cate"] + 1, dim)
            self.brand = nn.Embedding(config.VOCAB["brand"] + 1, dim)
            self.campaign = nn.Embedding(config.VOCAB["campaign"] + 1, dim)
            self.price = nn.Embedding(config.VOCAB["price_bucket"] + 1, dim)
            self.click = nn.Embedding(2, dim)
            self.gap = nn.Embedding(len(config.GAP_THRESHOLDS) + 1, dim)
            self.attribute = nn.ModuleList([nn.Embedding(config.VOCAB[name] + 1, dim) for name in USER_ATTRIBUTES])
            self.context = nn.ModuleList([
                nn.Embedding(config.VOCAB["pid"] + 1, dim),
                nn.Embedding(config.VOCAB["hour"] + 1, dim),
                nn.Embedding(config.VOCAB["is_weekend"] + 1, dim),
            ])
            self.blocks = nn.ModuleList([Block() for _ in range(layers)])
            self.head = nn.Linear(dim, 1)

        def _token(self, item, cate, brand, campaign, price, gap):
            return (self.item(item) + self.cate(cate) + self.brand(brand)
                    + self.campaign(campaign) + self.price(price) + self.gap(gap))

        def forward(self, batch: dict[str, Any], device: str):
            item = batch["hist_item"].to(device)
            cate = batch["hist_cate"].to(device)
            brand = batch["hist_brand"].to(device)
            campaign = batch["hist_campaign"].to(device)
            price = batch["hist_price"].to(device)
            click = batch["hist_click"].to(device)
            gap = batch["hist_gap"].to(device)
            hist_ts = batch["hist_ts"].to(device)
            hist_pad = batch["hist_pad"].to(device)

            history = self._token(item, cate, brand, campaign, price, gap) + self.click(click)

            q_item = batch["q_item"].to(device)
            q_cate = batch["q_cate"].to(device)
            q_brand = batch["q_brand"].to(device)
            q_campaign = batch["q_campaign"].to(device)
            q_price = batch["q_price"].to(device)
            q_gap = batch["q_gap"].to(device)
            q_ts = batch["q_ts"].to(device)
            q_attr = batch["q_attr"].to(device)
            q_ctx = batch["q_ctx"].to(device)

            query = self._token(q_item, q_cate, q_brand, q_campaign, q_price, q_gap)
            for index, embedding in enumerate(self.attribute):
                query = query + embedding(q_attr[:, :, index])
            for index, embedding in enumerate(self.context):
                query = query + embedding(q_ctx[..., index])

            length = hist_ts.shape[1]
            self_mask = torch.triu(torch.ones(length, length, dtype=torch.bool, device=hist_ts.device), diagonal=1)
            earlier = hist_ts[:, None, :] < q_ts[:, :, None]
            earlier = earlier & ~hist_pad[:, None, :]
            cross_mask = ~earlier

            for block in self.blocks:
                history, query = block(history, query, self_mask, cross_mask, hist_pad)
            return self.head(query).squeeze(-1)

    return SeqRanker()


# --------------------------------------------------------------------------- 배치


def _collate(torch, sequences: list[UserSequence], user_feats: np.ndarray,
             no_unclicked: bool) -> dict[str, Any]:
    batch = len(sequences)
    hist_len = max(len(sequence.item) for sequence in sequences)
    query_len = max(len(sequence.q_pos) for sequence in sequences)

    hist_item = np.zeros((batch, hist_len), dtype=np.int64)
    hist_cate = np.zeros((batch, hist_len), dtype=np.int64)
    hist_brand = np.zeros((batch, hist_len), dtype=np.int64)
    hist_campaign = np.zeros((batch, hist_len), dtype=np.int64)
    hist_price = np.zeros((batch, hist_len), dtype=np.int64)
    hist_click = np.zeros((batch, hist_len), dtype=np.int64)
    hist_gap = np.zeros((batch, hist_len), dtype=np.int64)
    hist_ts = np.zeros((batch, hist_len), dtype=np.int64)
    hist_pad = np.ones((batch, hist_len), dtype=bool)

    q_item = np.zeros((batch, query_len), dtype=np.int64)
    q_cate = np.zeros((batch, query_len), dtype=np.int64)
    q_brand = np.zeros((batch, query_len), dtype=np.int64)
    q_campaign = np.zeros((batch, query_len), dtype=np.int64)
    q_price = np.zeros((batch, query_len), dtype=np.int64)
    q_gap = np.zeros((batch, query_len), dtype=np.int64)
    q_ts = np.zeros((batch, query_len), dtype=np.int64)
    q_attr = np.zeros((batch, query_len, len(USER_ATTRIBUTES)), dtype=np.int64)
    q_ctx = np.zeros((batch, query_len, 3), dtype=np.int64)
    q_label = np.zeros((batch, query_len), dtype=np.float32)
    q_valid = np.zeros((batch, query_len), dtype=bool)

    for row, sequence in enumerate(sequences):
        keep = sequence.click.astype(bool) if no_unclicked else np.ones(len(sequence.item), dtype=bool)
        indices = np.flatnonzero(keep)
        length = len(indices)
        hist_item[row, :length] = sequence.item[indices]
        hist_cate[row, :length] = sequence.cate[indices]
        hist_brand[row, :length] = sequence.brand[indices]
        hist_campaign[row, :length] = sequence.campaign[indices]
        hist_price[row, :length] = sequence.price[indices]
        hist_click[row, :length] = sequence.click[indices]
        hist_ts[row, :length] = sequence.ts[indices]
        gaps = np.zeros(length, dtype=np.int64)
        if length > 1:
            gaps[1:] = sequence.ts[indices][1:] - sequence.ts[indices][:-1]
        hist_gap[row, :length] = gap_bucket(gaps)
        hist_pad[row, :length] = False

        count = len(sequence.q_pos)
        positions = sequence.q_pos
        q_item[row, :count] = sequence.item[positions]
        q_cate[row, :count] = sequence.cate[positions]
        q_brand[row, :count] = sequence.brand[positions]
        q_campaign[row, :count] = sequence.campaign[positions]
        q_price[row, :count] = sequence.price[positions]
        q_ts[row, :count] = sequence.ts[positions]
        query_gaps = np.zeros(count, dtype=np.int64)
        has_previous = positions > 0
        query_gaps[has_previous] = sequence.ts[positions[has_previous]] - sequence.ts[positions[has_previous] - 1]
        q_gap[row, :count] = gap_bucket(query_gaps)
        q_attr[row, :count] = user_feats[sequence.user]
        q_ctx[row, :count] = sequence.q_ctx
        q_label[row, :count] = sequence.q_label
        q_valid[row, :count] = True

    return {
        "hist_item": torch.as_tensor(hist_item), "hist_cate": torch.as_tensor(hist_cate),
        "hist_brand": torch.as_tensor(hist_brand), "hist_campaign": torch.as_tensor(hist_campaign),
        "hist_price": torch.as_tensor(hist_price), "hist_click": torch.as_tensor(hist_click),
        "hist_gap": torch.as_tensor(hist_gap), "hist_ts": torch.as_tensor(hist_ts),
        "hist_pad": torch.as_tensor(hist_pad),
        "q_item": torch.as_tensor(q_item), "q_cate": torch.as_tensor(q_cate),
        "q_brand": torch.as_tensor(q_brand), "q_campaign": torch.as_tensor(q_campaign),
        "q_price": torch.as_tensor(q_price), "q_gap": torch.as_tensor(q_gap),
        "q_ts": torch.as_tensor(q_ts), "q_attr": torch.as_tensor(q_attr), "q_ctx": torch.as_tensor(q_ctx),
        "q_label": torch.as_tensor(q_label), "q_valid": torch.as_tensor(q_valid),
    }


# --------------------------------------------------------------------------- 학습


def _predict(torch, model, sequences: list[UserSequence], user_feats: np.ndarray, *,
             batch_size: int, device: str, no_unclicked: bool) -> np.ndarray:
    model.eval()
    scores = np.zeros(len(sequences), dtype=object)
    with torch.no_grad():
        for start in range(0, len(sequences), batch_size):
            part = sequences[start:start + batch_size]
            batch = _collate(torch, part, user_feats, no_unclicked)
            logits = model(batch, device).cpu()
            valid = batch["q_valid"]
            for row, sequence in enumerate(part):
                count = len(sequence.q_pos)
                scores[start + row] = logits[row, :count].numpy().astype(np.float64)
    return scores


def _flat_scores(scores: np.ndarray, sequences: list[UserSequence]) -> tuple[np.ndarray, np.ndarray]:
    """순서마다의 점수를 (행 번호, 점수) 로 펴서 잇는다."""
    rows: list[np.ndarray] = []
    values: list[np.ndarray] = []
    for sequence, score in zip(sequences, scores):
        rows.append(sequence.q_row)
        values.append(score)
    return np.concatenate(rows), np.concatenate(values)


def train(split: str, config_name: str, *, users: int | None = None, no_unclicked: bool = False,
          epochs: int = 3, batch_size: int = 256, device: str = "auto",
          use_cache: bool = True, learning_rate: float = 1e-3, seed: int = config.SEED,
          max_batches: int | None = None) -> dict[str, Any]:
    """학습 분할로 G 를 학습하고 `split` 에서 지표와 예측을 낸다."""
    import torch

    torch.manual_seed(seed)
    np.random.seed(seed)
    settings = config.SEQ_CONFIGS[config_name]
    resolved = select_device(device)
    started = time.perf_counter()

    train_data = data_module.load_split("train", users=users, use_cache=use_cache)
    eval_data = data_module.load_split(split, users=users, use_cache=use_cache)
    valid_data = eval_data if split == "valid" else data_module.load_split("valid", users=users, use_cache=use_cache)

    train_sequences = build_sequences(train_data)
    valid_sequences = build_sequences(valid_data)
    eval_sequences = build_sequences(eval_data)

    model = build_model(settings["dim"], settings["layers"]).to(resolved)
    optimizer = torch.optim.AdamW(model.parameters(), lr=learning_rate)
    loss_fn = torch.nn.BCEWithLogitsLoss(reduction="none")
    train_attributes = train_data.user_feats.astype(np.int64)
    valid_attributes = valid_data.user_feats.astype(np.int64)
    eval_attributes = eval_data.user_feats.astype(np.int64)

    history: list[dict[str, Any]] = []
    best_gauc = float("-inf")
    best_state = copy.deepcopy(model.state_dict())
    best_epoch = -1
    rng = np.random.default_rng(seed)

    for epoch in range(epochs):
        model.train()
        order = rng.permutation(len(train_sequences))
        total = 0.0
        count = 0
        for step, start in enumerate(range(0, len(order), batch_size)):
            if max_batches is not None and step >= max_batches:
                break
            picked = order[start:start + batch_size]
            part = [train_sequences[index] for index in picked]
            batch = _collate(torch, part, train_attributes, no_unclicked)
            logits = model(batch, resolved)
            valid = batch["q_valid"].to(resolved)
            target = batch["q_label"].to(resolved)
            loss = loss_fn(logits[valid], target[valid]).mean()
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()
            total += float(loss.item()) * int(valid.sum())
            count += int(valid.sum())
        valid_scores = _predict(torch, model, valid_sequences, valid_attributes,
                                batch_size=batch_size, device=resolved, no_unclicked=no_unclicked)
        rows, scores = _flat_scores(valid_scores, valid_sequences)
        valid_user = valid_data.row_user[rows]
        valid_label = valid_data.row_label[rows]
        valid_group = _bundle_ids_from_eval(valid_data, rows)
        bundle = metrics.bundled_auc(valid_user, valid_group, valid_label, scores)["gauc"]
        user = metrics.grouped_auc(valid_user, valid_label, scores)["gauc"]
        history.append({"epoch": epoch, "train_loss": total / max(count, 1),
                        "valid_bundle_gauc": float(bundle), "valid_user_gauc": float(user)})
        if not np.isnan(bundle) and bundle > best_gauc:
            best_gauc = float(bundle)
            best_state = copy.deepcopy(model.state_dict())
            best_epoch = epoch

    model.load_state_dict(best_state)
    eval_scores = _predict(torch, model, eval_sequences, eval_attributes,
                           batch_size=batch_size, device=resolved, no_unclicked=no_unclicked)
    rows, logits = _flat_scores(eval_scores, eval_sequences)
    probability = 1.0 / (1.0 + np.exp(-logits))
    user = eval_data.row_user[rows]
    label = eval_data.row_label[rows]
    report: dict[str, Any] = {
        "model": "seq",
        "split": split,
        "config": config_name,
        "settings": settings,
        "no_unclicked": bool(no_unclicked),
        "device": resolved,
        "best_epoch": best_epoch,
        "best_valid_bundle_gauc": best_gauc,
        "epochs": history,
        "train_rows": int(sum(len(sequence.q_pos) for sequence in train_sequences)),
        "train_customers": len(train_sequences),
        "eval_rows": int(len(rows)),
        "elapsed_seconds": time.perf_counter() - started,
        "metrics": metrics.evaluate(user, label, logits, probability=probability,
                                    group=_bundle_ids_from_eval(eval_data, rows),
                                    history_length=eval_data.pre_day_history()[user]),
        "predictions": _save_predictions(split, config_name, no_unclicked, eval_data, rows,
                                         user, label, logits),
    }
    return report


def _bundle_ids_from_eval(split_data: data_module.SplitData, rows: np.ndarray) -> np.ndarray:
    from .ranker import _bundle_ids

    return _bundle_ids(split_data)[rows]


def _save_predictions(split: str, config_name: str, no_unclicked: bool,
                      eval_data: data_module.SplitData, rows: np.ndarray, user: np.ndarray,
                      label: np.ndarray, score: np.ndarray) -> dict[str, Any]:
    import pandas as pd

    name = f"{split}_seq_{config_name}"
    if no_unclicked:
        name += "_nuc"
    destination = config.cache_dir() / "predictions" / f"{name}.parquet"
    destination.parent.mkdir(parents=True, exist_ok=True)
    frame = pd.DataFrame({
        "user_id": eval_data.user_ids[user].astype(np.int64),
        "row": rows.astype(np.int64),
        "ts": eval_data.ts[eval_data.row_exposure()[rows]].astype(np.int64),
        "score": np.asarray(score, dtype=np.float64),
        "label": np.asarray(label, dtype=np.int8),
    })
    frame.to_parquet(destination, index=False)
    return {"path": str(destination), "rows": int(len(frame))}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--split", choices=("valid", "test"), required=True)
    parser.add_argument("--config", choices=tuple(config.SEQ_CONFIGS), default=config.SEQ_DEFAULT)
    parser.add_argument("--no-unclicked", action="store_true")
    parser.add_argument("--users", type=int)
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--batch-size", type=int, default=256)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--no-cache", action="store_true")
    parser.add_argument("--out")
    args = parser.parse_args(argv)
    report = train(args.split, args.config, users=args.users, no_unclicked=args.no_unclicked,
                   epochs=args.epochs, batch_size=args.batch_size, device=args.device,
                   use_cache=not args.no_cache)
    name = f"seq_{args.split}_{args.config}" + ("_nuc" if args.no_unclicked else "")
    destination = Path(args.out) if args.out else (config.out_dir() / f"{name}.json")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=float), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2, default=float))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
