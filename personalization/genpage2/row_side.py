"""T3(#463) GenPage 행 선택에 순위 통계를 학습해 붙이는 작은 머리.

STATUS.md 의 "T3" 절(측정 전에 고정)을 그대로 구현한다. D5 에서 이긴 H'(4) 는
GenPage 행 로그 확률에 순위 모델 행 점수 z 를 손으로 고른 λ=4 배로 더했다. T3 는
그 행 통계를 **직접 받아 행을 고르는 법**을 작은 MLP(6 → 16 → 1)로 학습한다.

- 입력 특징 6개는 :func:`genpage2.page_compose.t3_row_features` 가 만든다(허용 행마다).
- 학습 데이터는 요청 시각 r − 7 주에 산 고객과 그 주의 R 점수(누설 없이 r − 14 ·
  r − 21 로만 학습한 모델이 매긴 것 — :func:`genpage2.ranker.run_training_scores`)다.
- 정답은 GenPage 사전학습과 같은 방식으로 다음 주 구매를 행으로 묶은 페이지
  (:func:`genpage2.dataset._page_from_arrays`)의 행 순서를 허용 행으로 제한한 것이다.
- 손실은 행 단계마다 허용 행 위 교차 엔트로피(GenPage 행 log_softmax + 머리 출력)를
  teacher forcing 으로 계산한다. GenPage 는 고정이라 각 단계의 로그 확률은 학습 전에
  한 번만 계산해 둔다.
- AdamW lr 1e-3, 최대 5 에폭, 10% 를 떼어 조기 종료, 시드 7.
"""
from __future__ import annotations

import argparse
import json
import resource
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
import torch
from torch import nn
from torch.nn import functional as F

from . import config
from .config import TARGET_DAYS
from .context import truncate as truncate_context_view, view as context_view
from .dataset import (_context_from_arrays, _customer_event_groups, _page_from_arrays, profile_tokens)
from .page_compose import read_scores, t3_row_features
from .ranker import _day_number
from .vocab import Vocab, content_rows

FEATURE_COUNT = 6
DEFAULT_HIDDEN = 16
DEFAULT_EPOCHS = 5
DEFAULT_LR = 1e-3
VAL_FRACTION = 0.1
TRAIN_DAY_OFFSET = 7


class RowHead(nn.Module):
    """행 특징 6개를 행 로그 확률에 더할 스칼라 하나로 바꾼다."""

    def __init__(self, features: int = FEATURE_COUNT, hidden: int = DEFAULT_HIDDEN) -> None:
        super().__init__()
        self.features = int(features)
        self.hidden = int(hidden)
        self.net = nn.Sequential(nn.Linear(self.features, self.hidden), nn.ReLU(),
                                 nn.Linear(self.hidden, 1))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.net(x).squeeze(-1)


def save_head(path: str | Path, head: RowHead, meta: dict[str, Any] | None = None) -> Path:
    """머리를 파일로 저장한다. ``meta`` 는 JSON 문자열로 넣어 ``weights_only`` 로도 읽힌다."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    torch.save({"state": head.state_dict(), "features": head.features, "hidden": head.hidden,
                "meta_json": json.dumps(meta or {}, ensure_ascii=False)}, path)
    return path


def load_head(path: str | Path, device: str = "cpu") -> RowHead:
    payload = torch.load(Path(path), map_location=device)
    head = RowHead(int(payload.get("features", FEATURE_COUNT)), int(payload.get("hidden", DEFAULT_HIDDEN)))
    head.load_state_dict(payload["state"])
    head.to(device).eval()
    return head


def head_bias(head: RowHead, features: dict[int, list[float]]) -> dict[int, float]:
    """허용 행마다 머리 출력을 계산한다(디코더 ``row_bias`` 자리에 그대로 넣는다)."""
    if not features:
        return {}
    rows = sorted(features)
    device = next(head.parameters()).device
    x = torch.tensor([features[row] for row in rows], dtype=torch.float32, device=device)
    with torch.no_grad():
        values = head(x).tolist()
    return {row: float(value) for row, value in zip(rows, values)}


@dataclass
class HeadExample:
    """한 고객의 머리 학습 예시: 문맥 · 허용 행 특징 · teacher forcing 정답 행 순서."""

    customer: str
    ctx_tokens: list[int]
    ctx_content: list[int]
    allowed_rows: set[int]
    features: dict[int, list[float]]
    target_rows: list[int]


# --------------------------------------------------------------------------- 학습 데이터


def _load_transactions(directory: Path, request: pd.Timestamp, wanted: set[str]) -> pd.DataFrame:
    """요청 전 거래 중 대상 고객 것만 배치로 훑어 모은다(전체를 들고 있지 않는다)."""
    import pyarrow.parquet as pq

    reader = pq.ParquetFile(directory / "transactions.parquet")
    parts: list[pd.DataFrame] = []
    columns = ["t_dat", "customer_id", "article_id", "sales_channel_id", "price"]
    for batch in reader.iter_batches(columns=columns, batch_size=262_144):
        part = batch.to_pandas()
        part = part[part["customer_id"].astype(str).isin(wanted)]
        if part.empty:
            continue
        part = part[pd.to_datetime(part["t_dat"]) < request]
        if not part.empty:
            parts.append(part)
    if not parts:
        return pd.DataFrame(columns=columns)
    return pd.concat(parts, ignore_index=True)


def build_examples(mode: str, base: Path, scores: dict[str, list[tuple[str, float]]], *, vocab: Vocab,
                   limit: int | None = None) -> list[HeadExample]:
    """r − 7 주 구매 고객마다 머리 학습 예시를 만든다.

    정답 페이지는 GenPage 사전학습과 같은 방식(:func:`dataset._page_from_arrays`)으로
    다음 주 구매를 행으로 묶고, 그 행 순서를 허용 행으로 제한한다.
    """
    request = config.request_of(mode)
    request_train = request - pd.Timedelta(days=TRAIN_DAY_OFFSET)
    request_day = _day_number(request_train)
    directory = base / "hm" / "normalized"
    customers = pd.read_parquet(directory / "customers.parquet")
    articles = pd.read_parquet(directory / "articles.parquet")
    content_map = content_rows(articles)
    profiles = {str(row.customer_id): row._asdict() for row in customers.itertuples(index=False)}

    order = list(scores)
    if limit is not None and limit < len(order):
        order = order[:limit]
    wanted = set(order)
    tx = _load_transactions(directory, request, wanted)

    examples: list[HeadExample] = []
    for customer_id, events in _customer_event_groups(tx, vocab, content_map):
        if customer_id not in wanted:
            continue
        profile = profiles.get(customer_id)
        if profile is None:
            continue
        dates, articles_col = events["dates"], events["articles"]
        start = int(dates.searchsorted(request_day, side="left"))
        end = int(dates.searchsorted(request_day + TARGET_DAYS, side="left"))
        if end <= start:
            continue
        target = _page_from_arrays(vocab, set(articles_col[:start].tolist()),
                                   dates[start:end], articles_col[start:end],
                                   events["item_tokens"][start:end], events["row_tokens"][start:end])
        if target is None:
            continue
        ctx, ctx_content = _context_from_arrays(
            vocab, request_train, profile_tokens(vocab, profile),
            dates[:start], articles_col[:start], events["item_tokens"][:start],
            events["channels"][:start], events["content"][:start], events["prices"][:start])
        history = articles_col[max(0, start - 100):start][::-1].tolist()
        _allowed_items, _row_items, allowed_rows, features = t3_row_features(scores[customer_id],
                                                                            history, vocab)
        page_rows = [int(token) for token in target.tolist() if int(token) in vocab.row_ids]
        target_rows = [row for row in page_rows if row in allowed_rows]
        if not target_rows:
            continue
        examples.append(HeadExample(customer_id, ctx.tolist(), ctx_content.tolist(),
                                    allowed_rows, features, target_rows))
    return examples


# --------------------------------------------------------------------------- GenPage 행 로그 확률(고정)


def _forward_last(model: Any, sequences: list[tuple[list[int], list[int]]], maxlen: int,
                  device: Any) -> torch.Tensor:
    """오른쪽 패딩 배치의 각 예시 **마지막** 위치 로짓을 돌려준다."""
    width = max(len(tokens) for tokens, _ in sequences)
    if width > maxlen:
        raise ValueError(f"시퀀스 길이 {width} 가 maxlen {maxlen} 를 넘습니다")
    x = torch.zeros((len(sequences), width), dtype=torch.long, device=device)
    ci = torch.full((len(sequences), width), -1, dtype=torch.long, device=device)
    lengths = []
    for index, (tokens, content) in enumerate(sequences):
        x[index, :len(tokens)] = torch.tensor(tokens, dtype=torch.long, device=device)
        ci[index, :len(content)] = torch.tensor(content, dtype=torch.long, device=device)
        lengths.append(len(tokens) - 1)
    with torch.no_grad():
        hidden = model(x, ci)
        index = torch.arange(len(sequences), device=device)
        last = hidden[index, torch.tensor(lengths, device=device)]
        return model.logits(last)


def _prepared_context(example: HeadExample, *, vocab: Any, maxlen: int, level: str
                      ) -> tuple[list[int], list[int]]:
    """디코더와 같이 문맥을 투영하고 정답 행이 들어갈 자리를 남겨 자른다."""
    tokens, content = context_view(example.ctx_tokens, example.ctx_content, vocab, level)
    keep = max(1, maxlen - (len(example.target_rows) + 1))
    tokens, content = truncate_context_view(tokens, content, keep, vocab=vocab, level=level)
    return list(tokens), list(content)


def precompute_row_logits(model: Any, examples: list[HeadExample], *, vocab: Any, maxlen: int,
                          level: str, device: Any, batch_size: int = 64
                          ) -> list[list[tuple[list[int], np.ndarray, int]]]:
    """행 단계마다 허용 행 위 GenPage 로짓을 teacher forcing 으로 미리 계산한다.

    반환은 예시마다 ``(후보 행 목록, 그 행들의 로짓, 정답 행)`` 의 목록이다.
    GenPage 는 고정이라 학습 내내 같은 값이므로 한 번만 계산한다.
    """
    prepared = [_prepared_context(example, vocab=vocab, maxlen=maxlen, level=level)
                for example in examples]
    steps: list[list[tuple[list[int], np.ndarray, int]]] = [[] for _ in examples]
    max_steps = max((len(example.target_rows) for example in examples), default=0)
    for step in range(max_steps):
        active = [index for index, example in enumerate(examples) if len(example.target_rows) > step]
        for begin in range(0, len(active), batch_size):
            chunk = active[begin:begin + batch_size]
            sequences = []
            for index in chunk:
                tokens, content = prepared[index]
                previous = examples[index].target_rows[:step]
                sequences.append((tokens + previous, content + [-1] * len(previous)))
            logits = _forward_last(model, sequences, maxlen, device)
            for offset, index in enumerate(chunk):
                example = examples[index]
                used = set(example.target_rows[:step])
                candidates = sorted(example.allowed_rows - used)
                ids = torch.tensor(candidates, dtype=torch.long, device=device)
                values = logits[offset].index_select(0, ids).detach().cpu().numpy()
                steps[index].append((candidates, values, example.target_rows[step]))
    return steps


# --------------------------------------------------------------------------- 머리 학습


def _samples(examples: list[HeadExample], steps_by_example: list[list[tuple[list[int], np.ndarray, int]]]
             ) -> list[tuple[int, np.ndarray, np.ndarray, int]]:
    samples = []
    for index, example in enumerate(examples):
        for candidates, values, target in steps_by_example[index]:
            matrix = np.asarray([example.features[row] for row in candidates], dtype=np.float32)
            samples.append((index, matrix, values.astype(np.float32), candidates.index(target)))
    return samples


def _sample_loss(head: RowHead, sample: tuple[int, np.ndarray, np.ndarray, int], device: Any) -> torch.Tensor:
    _index, matrix, values, target_index = sample
    x = torch.tensor(matrix, dtype=torch.float32, device=device)
    gen = torch.tensor(values, dtype=torch.float32, device=device)
    combined = (torch.log_softmax(gen, dim=0) + head(x)).unsqueeze(0)
    return F.cross_entropy(combined, torch.tensor([target_index], device=device))


def _mean_loss(head: RowHead, samples: list[tuple[int, np.ndarray, np.ndarray, int]], device: Any) -> float:
    if not samples:
        return 0.0
    with torch.no_grad():
        total = sum(float(_sample_loss(head, sample, device)) for sample in samples)
    return total / len(samples)


def train_head(examples: list[HeadExample],
               steps_by_example: list[list[tuple[list[int], np.ndarray, int]]], *,
               hidden: int = DEFAULT_HIDDEN, epochs: int = DEFAULT_EPOCHS, lr: float = DEFAULT_LR,
               val_fraction: float = VAL_FRACTION, seed: int = config.SEED, batch_size: int = 256,
               device: Any = "cpu") -> tuple[RowHead, list[dict[str, float]]]:
    """허용 행 위 교차 엔트로피로 머리를 학습하고, 10% 를 떼어 조기 종료한다."""
    torch.manual_seed(seed)
    rng = np.random.default_rng(seed)
    order = rng.permutation(len(examples)).tolist()
    val_count = max(1, int(round(len(examples) * val_fraction))) if val_fraction > 0 else 0
    val_examples = set(order[:val_count])
    samples = _samples(examples, steps_by_example)
    train_samples = [sample for sample in samples if sample[0] not in val_examples]
    val_samples = [sample for sample in samples if sample[0] in val_examples]

    head = RowHead(FEATURE_COUNT, hidden).to(device)
    optimizer = torch.optim.AdamW(head.parameters(), lr=lr)
    best_state = None
    best_val = None
    history: list[dict[str, float]] = []
    for epoch in range(epochs):
        head.train()
        shuffle = np.random.default_rng(seed + epoch).permutation(len(train_samples)).tolist()
        total = 0.0
        for begin in range(0, len(shuffle), batch_size):
            chunk = [train_samples[index] for index in shuffle[begin:begin + batch_size]]
            optimizer.zero_grad()
            loss = torch.stack([_sample_loss(head, sample, device) for sample in chunk]).mean()
            loss.backward()
            optimizer.step()
            total += float(loss.detach()) * len(chunk)
        train_loss = total / len(train_samples) if train_samples else 0.0
        head.eval()
        val_loss = _mean_loss(head, val_samples, device) if val_samples else train_loss
        history.append({"epoch": epoch + 1, "train_loss": train_loss, "val_loss": val_loss})
        if best_val is None or val_loss < best_val:
            best_val = val_loss
            best_state = {key: value.detach().clone() for key, value in head.state_dict().items()}
        else:
            break
    if best_state is not None:
        head.load_state_dict(best_state)
    head.eval()
    return head, history


# --------------------------------------------------------------------------- 실행


def _pick_device(value: str) -> str:
    if value != "auto":
        return value
    if torch.cuda.is_available():
        return "cuda"
    if torch.backends.mps.is_available():
        return "mps"
    return "cpu"


def _peak_memory_mb() -> float:
    rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return round(float(rss / (1024 ** 2 if sys.platform == "darwin" else 1024)), 2)


def _load_frozen_genpage(mode_dir: Path, ckpt: Path, device: str) -> tuple[Any, str]:
    from .content import load_content
    from .model import load_checkpoint

    content, _rows = load_content(mode_dir.parent / "content")
    model, _cfg, extra = load_checkpoint(ckpt, content=torch.tensor(content, dtype=torch.float32),
                                         device=device)
    model.to(device).eval()
    for parameter in model.parameters():
        parameter.requires_grad_(False)
    level = extra.get("context", "full")
    if level == "history":
        level = "items"
    return model, level


def run(args: argparse.Namespace) -> dict[str, Any]:
    began = time.perf_counter()
    base = Path(args.data_dir) if args.data_dir else config.data_dir()
    mode_dir = base / "hm" / "model" / "genpage2" / args.mode
    scores = read_scores(args.scores)
    vocab = Vocab.load(mode_dir / "vocab.json")
    examples = build_examples(args.mode, base, scores, vocab=vocab, limit=args.limit)
    if not examples:
        raise ValueError("머리 학습 예시가 없습니다(점수·데이터를 확인하라)")

    device = _pick_device(args.device)
    model, level = _load_frozen_genpage(mode_dir, Path(args.ckpt), device)
    maxlen = int(getattr(model.cfg, "maxlen", config.MAXLEN))
    steps = precompute_row_logits(model, examples, vocab=vocab, maxlen=maxlen, level=level,
                                  device=device, batch_size=args.batch)
    head, history = train_head(examples, steps, hidden=args.hidden, epochs=args.epochs, lr=args.lr,
                               seed=config.SEED, batch_size=args.batch, device="cpu")
    destination = Path(args.out)
    save_head(destination, head, {"mode": args.mode, "features": FEATURE_COUNT, "hidden": args.hidden,
                                  "epochs": len(history), "seed": config.SEED, "level": level,
                                  "scores": str(args.scores)})
    report: dict[str, Any] = {
        "mode": args.mode,
        "ckpt": str(args.ckpt),
        "scores": str(args.scores),
        "device": device,
        "customers": len(examples),
        "supervised_steps": int(sum(len(example.target_rows) for example in examples)),
        "features": FEATURE_COUNT,
        "hidden": args.hidden,
        "epochs": len(history),
        "history": history,
        "elapsed_seconds": time.perf_counter() - began,
        "peak_memory_mb": _peak_memory_mb(),
        "head": str(destination),
    }
    (destination.parent / f"row_head_{args.mode}.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2, default=float), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2, default=float))
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("validate", "final"), required=True)
    parser.add_argument("--scores", required=True, help="ranker --train-scores-out 의 scores.json.gz")
    parser.add_argument("--ckpt", required=True, help="고정할 GenPage 체크포인트")
    parser.add_argument("--out", required=True, help="머리 파일 경로")
    parser.add_argument("--limit", type=int, help="스모크용 학습 고객 상한")
    parser.add_argument("--epochs", type=int, default=DEFAULT_EPOCHS)
    parser.add_argument("--hidden", type=int, default=DEFAULT_HIDDEN)
    parser.add_argument("--lr", type=float, default=DEFAULT_LR)
    parser.add_argument("--batch", type=int, default=64)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--data-dir")
    args = parser.parse_args(argv)
    run(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
