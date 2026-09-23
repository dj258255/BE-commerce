"""GenPage 최소형 — 이력을 프롬프트로, 홈 행을 생성한다(#238, ADR-053).

GenPage(Netflix) 에서 가져온 것:
  - 사용자 이력을 상품 토큰 시퀀스로 둔다(프롬프트)
  - 사전학습 = 다음 토큰(상품) 예측. 작은 causal transformer
  - 생성: 행(대분류)을 고르고 그 안의 상품을 한 토큰씩 만든다. 규칙은 매 단계 마스크로 강제한다
  - 하이브리드 행 디코딩: 행마다 앞 k 개만 한 칸씩, 나머지는 마지막 분포에서 한 번에 고른다

뺀 것(데이터가 없어서): 후학습(가중 이진분류·강화학습 — 노출·보상 로그가 없다), 행 토큰 학습,
콜드스타트 콘텐츠 임베딩, 증분 학습, 온라인 A/B.

채점은 ALS(ADR-048)와 같은 하네스다 — features_hm 의 split·map_at_k 를 import 한다. 넘어야 할 선은
측정 전에 이슈 #238 에 적은 0.023354(마지막 구매 재추천).

    python train_genpage.py            # 학습 + 채점 + 모델 저장
"""
from __future__ import annotations

import json
import os
import random
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd
import torch
import torch.nn as nn
import torch.nn.functional as F

PIPELINE = Path(__file__).resolve().parent
sys.path.insert(0, str(PIPELINE))
from features_hm import load_transactions, map_at_k, split  # noqa: E402

# 데이터(31GB)는 저장소 밖에 둘 수 있다 — 워크트리에서 돌릴 때 원본 체크아웃의 data 를 가리킨다
DATA = Path(os.environ.get("GENPAGE_DATA", Path(__file__).resolve().parents[1] / "data"))
OUT = DATA / "hm" / "model" / "genpage"
K = 12
HOLDOUT_DAYS = 7
LINE = 0.023354             # 이슈 #238 에 측정 전에 적은 선(repeat_last)
SEED = 7

MAXLEN = 50                 # 프롬프트 길이(최근 구매 50개)
MIN_COUNT = 10              # 어휘: train 에서 10번 이상 팔린 상품
MAX_USERS = 400_000         # 학습 사용자 표본
DIM, LAYERS, HEADS = 64, 2, 2
EPOCHS, BATCH, LAST_K, LR = 2, 128, 10, 1e-3
# 임베딩 초기 표준편차. PyTorch 기본(1.0)이면 가중치 공유 출력의 로짓이 커서 첫 손실이 균등 분포(ln 어휘 ≈ 11.3)보다
# 높게 시작했다(16.8). 트랜스포머에서 흔히 쓰는 0.02 로 둔다 — 1차(1.0)와 2차(0.02) 결과를 둘 다 남긴다
INIT_STD = float(os.environ.get("GENPAGE_INIT_STD", "0.02"))

_START = time.time()


def log(msg: str) -> None:
    print(f"[genpage +{time.time() - _START:6.1f}s] {msg}", flush=True)


class GenPageMini(nn.Module):
    """상품 토큰만 있는 작은 causal transformer. 출력 어휘 = 입력 어휘(가중치 공유)."""

    def __init__(self, vocab: int, dim=DIM, layers=LAYERS, heads=HEADS, maxlen=MAXLEN):
        super().__init__()
        self.item = nn.Embedding(vocab, dim, padding_idx=0)
        self.pos = nn.Embedding(maxlen, dim)
        layer = nn.TransformerEncoderLayer(dim, heads, dim * 4, dropout=0.2, batch_first=True, norm_first=True)
        self.encoder = nn.TransformerEncoder(layer, layers, enable_nested_tensor=False)
        self.norm = nn.LayerNorm(dim)
        nn.init.normal_(self.item.weight, std=INIT_STD)
        nn.init.normal_(self.pos.weight, std=INIT_STD)
        with torch.no_grad():
            self.item.weight[0].zero_()

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """x: B×T, **오른쪽 패딩**(0). causal 마스크만 쓴다 — 오른쪽 패딩이라 실제 토큰은 패드를 보지 않는다."""
        t = x.size(1)
        h = self.item(x) + self.pos(torch.arange(t, device=x.device))[None]
        causal = torch.triu(torch.ones(t, t, dtype=torch.bool, device=x.device), 1)
        return self.norm(self.encoder(h, mask=causal, is_causal=True))

    def next_logits(self, x: torch.Tensor, lengths: torch.Tensor) -> torch.Tensor:
        """각 행의 마지막 실제 토큰 위치에서 다음 상품 분포(로짓)."""
        h = self.forward(x)
        last = h[torch.arange(x.size(0), device=x.device), lengths - 1]
        logits = last @ self.item.weight.T
        logits[:, 0] = float("-inf")          # 패드 토큰은 생성하지 않는다
        return logits


def build_sequences(train: pd.DataFrame, vocab: dict[str, int]):
    """고객별 시간순 상품 토큰 시퀀스(어휘 밖 상품은 뺀다)."""
    tr = train[["customer_id", "article_id", "t_dat"]].copy()
    tr["tok"] = tr["article_id"].astype(str).map(vocab)
    tr = tr.dropna(subset=["tok"]).sort_values(["customer_id", "t_dat"], kind="stable")
    tr["tok"] = tr["tok"].astype(np.int64)
    return tr.groupby("customer_id", observed=True)["tok"].apply(list)


def pad_right(seqs: list[list[int]], maxlen: int):
    x = np.zeros((len(seqs), maxlen), dtype=np.int64)
    lengths = np.zeros(len(seqs), dtype=np.int64)
    for i, s in enumerate(seqs):
        s = s[-maxlen:]
        x[i, :len(s)] = s
        lengths[i] = len(s)
    return torch.from_numpy(x), torch.from_numpy(lengths)


def train_model(seqs: list[list[int]], vocab_size: int, device: str) -> GenPageMini:
    model = GenPageMini(vocab_size).to(device)
    opt = torch.optim.AdamW(model.parameters(), lr=LR, weight_decay=0.01)
    rng = random.Random(SEED)
    usable = [s[-(MAXLEN + 1):] for s in seqs if len(s) >= 2]
    log(f"학습 시퀀스 {len(usable):,}개 · 어휘 {vocab_size:,} · 파라미터 {sum(p.numel() for p in model.parameters()):,}")
    for epoch in range(EPOCHS):
        rng.shuffle(usable)
        model.train()
        total, steps, t0 = 0.0, 0, time.time()
        for start in range(0, len(usable), BATCH):
            chunk = usable[start:start + BATCH]
            inp, lengths = pad_right([s[:-1] for s in chunk], MAXLEN)
            tgt, _ = pad_right([s[1:] for s in chunk], MAXLEN)
            inp, tgt, lengths = inp.to(device), tgt.to(device), lengths.to(device)
            h = model(inp)
            # 행마다 마지막 LAST_K 개 위치만 손실에 넣는다 — 전 위치 × 어휘 로짓은 메모리가 크다
            pos = lengths[:, None] - 1 - torch.arange(LAST_K, device=device)[None]
            valid = pos >= 0
            pos = pos.clamp(min=0)
            rows = torch.arange(inp.size(0), device=device)[:, None].expand_as(pos)
            hs = h[rows[valid], pos[valid]]
            logits = hs @ model.item.weight.T
            loss = F.cross_entropy(logits, tgt[rows[valid], pos[valid]])
            opt.zero_grad()
            loss.backward()
            opt.step()
            total += loss.item()
            steps += 1
            if steps % 500 == 0:
                log(f"  epoch {epoch + 1} step {steps:,} loss {total / steps:.4f} · {time.time() - t0:.0f}s")
        log(f"epoch {epoch + 1} 끝 — 평균 loss {total / max(steps, 1):.4f} · {time.time() - t0:.0f}s")
    return model


@torch.no_grad()
def recommend(model, seqs: list[list[int]], device: str, exclude_seen: bool, batch=1024) -> np.ndarray:
    model.eval()
    tops = []
    for start in range(0, len(seqs), batch):
        chunk = seqs[start:start + batch]
        x, lengths = pad_right(chunk, MAXLEN)
        logits = model.next_logits(x.to(device), lengths.to(device))
        if exclude_seen:
            for i, s in enumerate(chunk):
                logits[i, torch.tensor(sorted(set(s)), device=device)] = float("-inf")
        tops.append(torch.topk(logits, K, dim=1).indices.cpu().numpy())
    return np.concatenate(tops)


def main() -> int:
    torch.manual_seed(SEED)
    device = "mps" if torch.backends.mps.is_available() else "cpu"
    log(f"장치 {device} · 거래 로드")
    tx = load_transactions(DATA)
    train, holdout, cutoff, _ = split(tx, HOLDOUT_DAYS)
    log(f"cutoff {cutoff.date()} · train {len(train):,} · holdout {len(holdout):,}")

    counts = train["article_id"].astype(str).value_counts()
    items = counts[counts >= MIN_COUNT].index.tolist()
    vocab = {a: i + 1 for i, a in enumerate(items)}          # 0 = 패드
    id_of = np.array(["<pad>"] + items, dtype=object)
    log(f"어휘 {len(items):,}개(train {MIN_COUNT}회 이상) · 전체 상품의 {len(items) / train['article_id'].nunique():.1%}")

    by_customer = build_sequences(train, vocab)
    customers = list(by_customer.index)
    rng = random.Random(SEED)
    sample = rng.sample(customers, min(MAX_USERS, len(customers)))
    t0 = time.time()
    model = train_model([by_customer[c] for c in sample], len(items) + 1, device)
    train_seconds = time.time() - t0

    # 채점 — 홀드아웃 고객 중 train 이력이 있는 사람. 이력이 없으면(콜드스타트) 예측을 내지 않는다(기준선과 같다)
    hold_customers = holdout["customer_id"].astype(str).unique()
    scored = [c for c in hold_customers if c in by_customer.index]
    seqs = [by_customer[c] for c in scored]
    report = {"line": LINE, "train_seconds": round(train_seconds, 1), "vocab": len(items),
              "train_users": len(sample), "scored_customers": len(scored), "device": device,
              "config": {"maxlen": MAXLEN, "dim": DIM, "layers": LAYERS, "heads": HEADS, "epochs": EPOCHS,
                         "batch": BATCH, "last_k": LAST_K, "lr": LR, "min_count": MIN_COUNT,
                         "init_std": INIT_STD}}
    for exclude_seen in (False, True):
        top = recommend(model, seqs, device, exclude_seen)
        pred = pd.DataFrame({"customer_id": np.repeat(np.array(scored, dtype=object), K),
                             "article_id": id_of[top.ravel()],
                             "rank": np.tile(np.arange(1, K + 1), len(scored))})
        pred["customer_id"] = pd.Categorical(pred["customer_id"], categories=tx["customer_id"].cat.categories)
        pred["article_id"] = pd.Categorical(pred["article_id"], categories=tx["article_id"].cat.categories)
        score, n_eval = map_at_k(pred, holdout, K)
        key = "map_at_k_exclude_seen" if exclude_seen else "map_at_k"
        report[key] = round(float(score), 6)
        report["evaluated_customers"] = int(n_eval)
        log(f"MAP@{K}{' (산 것 제외)' if exclude_seen else ''} = {score:.6f} (평가 고객 {n_eval:,}) · 선 {LINE}")
    report["passes_line"] = report["map_at_k"] >= LINE

    OUT.mkdir(parents=True, exist_ok=True)
    torch.save(model.state_dict(), OUT / "model.pt")
    (OUT / "vocab.json").write_text(json.dumps({"items": items, "config": report["config"]}))
    (OUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=1))
    log(f"저장 {OUT} · 선 통과={report['passes_line']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
