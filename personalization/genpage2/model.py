"""GenPage v2 decoder model and portable checkpoint helpers.

The input vocabulary embedding is deliberately independent from the output
projection.  This matters for the cold-start/fallback training scheme.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Sequence

import numpy as np
import torch
from torch import nn


PRESETS = {
    "small": (128, 2, 4, 512),
    "base": (256, 6, 8, 1024),
    "large": (384, 8, 8, 1536),
}

TOKEN_TYPE_SPECIAL = 0
TOKEN_TYPE_PROFILE = 1
TOKEN_TYPE_REQUEST = 2
TOKEN_TYPE_ACTION = 3
TOKEN_TYPE_TIME = 4
TOKEN_TYPE_ROW = 5
TOKEN_TYPE_ITEM = 6
TOKEN_TYPE_COUNT = 7


@dataclass
class ModelConfig:
    vocab_size: int
    dim: int = 256
    layers: int = 6
    heads: int = 8
    ffn: int = 1024
    dropout: float = 0.1
    maxlen: int = 256
    content_dim: int = 384
    fallback_prob: float = 0.05
    content_output: bool = True

    @classmethod
    def from_preset(cls, name: str, vocab_size: int, **kw: Any) -> "ModelConfig":
        try:
            dim, layers, heads, ffn = PRESETS[name]
        except KeyError as exc:
            raise ValueError(f"unknown preset {name!r}; choose one of {sorted(PRESETS)}") from exc
        return cls(vocab_size=vocab_size, dim=dim, layers=layers, heads=heads, ffn=ffn, **kw)


def token_types(tokens: Sequence[str]) -> np.ndarray:
    """Return the seven coarse token classes, in vocabulary-id order."""
    result = np.full(len(tokens), TOKEN_TYPE_SPECIAL, dtype=np.int64)
    for i, name in enumerate(tokens):
        if name in {"PAD", "BOS", "EOS", "SEP_PROFILE", "SEP_REQUEST", "SEP_HISTORY", "SEP_PAGE", "ITEM_FALLBACK", "ROW_FALLBACK", "UNK"}:
            result[i] = TOKEN_TYPE_SPECIAL
        elif name.startswith(("AGE_", "CLUB_", "NEWS_", "FN_", "ACTIVE_")):
            result[i] = TOKEN_TYPE_PROFILE
        elif name.startswith(("DOW_", "MONTH_")):
            result[i] = TOKEN_TYPE_REQUEST
        elif name.startswith("ACT_"):
            result[i] = TOKEN_TYPE_ACTION
        elif name.startswith("AGO_"):
            result[i] = TOKEN_TYPE_TIME
        elif name.startswith("ROW_"):
            result[i] = TOKEN_TYPE_ROW
        elif name.startswith("ITEM_"):
            result[i] = TOKEN_TYPE_ITEM
    return result


class GenPageV2(nn.Module):
    def __init__(self, cfg: ModelConfig, content: torch.Tensor | None = None,
                 tokens: Sequence[str] | None = None) -> None:
        super().__init__()
        self.cfg = cfg
        self.token_embedding = nn.Embedding(cfg.vocab_size, cfg.dim)
        self.position_embedding = nn.Embedding(cfg.maxlen, cfg.dim)
        self.type_embedding = nn.Embedding(TOKEN_TYPE_COUNT, cfg.dim)
        layer = nn.TransformerEncoderLayer(
            d_model=cfg.dim, nhead=cfg.heads, dim_feedforward=cfg.ffn,
            dropout=cfg.dropout, activation="gelu", batch_first=True,
            norm_first=True,
        )
        self.transformer = nn.TransformerEncoder(layer, num_layers=cfg.layers)
        self.final_norm = nn.LayerNorm(cfg.dim)
        self.content_projection = nn.Linear(cfg.content_dim, cfg.dim)
        self.content_output_projection = nn.Linear(cfg.content_dim, cfg.dim)
        # Do not tie this to token_embedding: GenPage's input/output are separate.
        self.output_projection = nn.Linear(cfg.dim, cfg.vocab_size, bias=False)
        causal = torch.triu(torch.ones(cfg.maxlen, cfg.maxlen, dtype=torch.bool), diagonal=1)
        self.register_buffer("causal_mask", causal, persistent=False)
        ids = token_types(tokens) if tokens is not None else np.zeros(cfg.vocab_size, dtype=np.int64)
        if len(ids) != cfg.vocab_size:
            raise ValueError("token names and vocab_size differ")
        self.register_buffer("token_type", torch.as_tensor(ids, dtype=torch.long))
        value = None if content is None else torch.as_tensor(content).detach()
        if value is not None and (value.ndim != 2 or value.shape[1] != cfg.content_dim):
            raise ValueError("content must have shape [articles, content_dim]")
        # Content is data, not learned state; checkpoints accept a fresh matrix at load time.
        self.register_buffer("content", value, persistent=False)

    @property
    def input_embedding(self) -> nn.Embedding:
        return self.token_embedding

    @property
    def output(self) -> nn.Linear:
        return self.output_projection

    def set_token_types(self, tokens: Sequence[str]) -> None:
        values = token_types(tokens)
        if len(values) != self.cfg.vocab_size:
            raise ValueError("token names and vocab_size differ")
        self.token_type.copy_(torch.as_tensor(values, device=self.token_type.device))

    def input_embeddings(self, tokens: torch.Tensor, content_idx: torch.Tensor | None = None) -> torch.Tensor:
        if tokens.ndim != 2:
            raise ValueError("tokens must be [batch, time]")
        batch, length = tokens.shape
        if length > self.cfg.maxlen:
            raise ValueError(f"sequence length {length} exceeds maxlen {self.cfg.maxlen}")
        positions = torch.arange(length, device=tokens.device).unsqueeze(0)
        kinds = self.token_type[tokens]
        x = self.token_embedding(tokens) + self.position_embedding(positions) + self.type_embedding(kinds)
        if self.content is not None and content_idx is not None:
            if content_idx.shape != tokens.shape:
                raise ValueError("content_idx must have the same shape as tokens")
            valid = content_idx >= 0
            rows = content_idx.clamp(min=0, max=self.content.shape[0] - 1).long()
            features = self.content[rows].to(device=x.device, dtype=torch.float32)
            fusion = self.content_projection(features).to(x.dtype)
            x = x + torch.where(valid.unsqueeze(-1), fusion, torch.zeros_like(fusion))
        return x

    def forward(self, tokens: torch.Tensor, content_idx: torch.Tensor | None = None) -> torch.Tensor:
        x = self.input_embeddings(tokens, content_idx)
        length = tokens.shape[1]
        # True means blocked for TransformerEncoder's boolean attention mask.
        return self.final_norm(self.transformer(x, mask=self.causal_mask[:length, :length]))

    def logits(self, hidden: torch.Tensor) -> torch.Tensor:
        return self.output_projection(hidden)

    def item_content_logits(self, hidden: torch.Tensor, article_rows: torch.Tensor) -> torch.Tensor:
        if not self.cfg.content_output:
            raise RuntimeError("content_output is disabled")
        if self.content is None:
            raise RuntimeError("content matrix is unavailable")
        rows = torch.as_tensor(article_rows, device=self.content.device, dtype=torch.long)
        if rows.numel() and ((rows < 0).any() or (rows >= self.content.shape[0]).any()):
            raise IndexError("article row outside content matrix")
        candidates = self.content_output_projection(self.content[rows].to(dtype=torch.float32)).to(hidden.device, hidden.dtype)
        return hidden @ candidates.transpose(0, 1)


def save_checkpoint(directory: str | Path, model: GenPageV2, cfg: ModelConfig,
                    extra: dict[str, Any] | None = None) -> None:
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    torch.save(model.state_dict(), directory / "model.pt")
    uses_content = model.content is not None
    content_shape = list(model.content.shape) if uses_content else None
    metadata = {"uses_content": uses_content, "content_shape": content_shape}
    with (directory / "config.json").open("w", encoding="utf-8") as handle:
        json.dump({"config": asdict(cfg), **metadata, "extra": extra or {}}, handle,
                  ensure_ascii=False, indent=2, sort_keys=True)


def load_checkpoint(directory: str | Path, content: torch.Tensor | None = None,
                    device: str | torch.device = "cpu") -> tuple[GenPageV2, ModelConfig, dict[str, Any]]:
    directory = Path(directory)
    with (directory / "config.json").open(encoding="utf-8") as handle:
        saved = json.load(handle)
    # Accept the early simple {field: value} layout too, for checkpoint portability.
    cfg = ModelConfig(**saved.get("config", saved))
    uses_content = bool(saved.get("uses_content", False))
    expected_shape = saved.get("content_shape")
    if uses_content and content is None:
        raise ValueError("checkpoint was trained with content embeddings; content is required")
    if uses_content and expected_shape is not None:
        actual_shape = list(torch.as_tensor(content).shape)
        if actual_shape != list(expected_shape):
            raise ValueError(f"content shape mismatch: expected {expected_shape}, got {actual_shape}")
    model = GenPageV2(cfg, content=content)
    try:
        state = torch.load(directory / "model.pt", map_location=device, weights_only=True)
    except TypeError:  # torch versions before weights_only
        state = torch.load(directory / "model.pt", map_location=device)
    model.load_state_dict(state)
    model.to(device)
    return model, cfg, dict(saved.get("extra", {}))
