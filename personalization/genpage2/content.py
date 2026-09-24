"""상품 설명과 속성의 multilingual-e5-small 임베딩을 만든다."""

from __future__ import annotations

import argparse
import json
import os
import re
import time
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from . import config


MODEL_NAME = "intfloat/multilingual-e5-small"
CONTENT_DIM = 384
TEXT_TEMPLATE = (
    "passage: {prod_name}. {product_type_name}, {product_group_name}. "
    "{colour_group_name}, {perceived_colour_value_name}. {section_name}, "
    "{garment_group_name}, {index_name}. {detail_desc}"
)
TEXT_COLUMNS = (
    "prod_name",
    "product_type_name",
    "product_group_name",
    "colour_group_name",
    "perceived_colour_value_name",
    "section_name",
    "garment_group_name",
    "index_name",
    "detail_desc",
)
CHECK_PAIRS = 10_000
_SPACE_RE = re.compile(r"\s+")


def _value(row: Any, name: str) -> str:
    """Return a non-empty, whitespace-normalized value from a row."""
    if isinstance(row, Mapping):
        value = row.get(name)
    else:
        value = getattr(row, name, None)

    if bool(pd.isna(value)):
        return ""
    if not isinstance(value, str):
        value = str(value)
    return _SPACE_RE.sub(" ", value).strip()


def _chunk(values: Sequence[str], suffix: str = "") -> str:
    values = [value for value in values if value]
    if not values:
        return ""
    return ", ".join(values) + suffix


def article_text(row: Any) -> str:
    """Build the design-document text template, omitting missing chunks."""
    values = {name: _value(row, name) for name in TEXT_COLUMNS}
    chunks = [
        _chunk([values["prod_name"]], "."),
        _chunk([values["product_type_name"], values["product_group_name"]], "."),
        _chunk([values["colour_group_name"], values["perceived_colour_value_name"]], "."),
        _chunk(
            [values["section_name"], values["garment_group_name"], values["index_name"]],
            ".",
        ),
        values["detail_desc"],
    ]
    return _SPACE_RE.sub(" ", "passage: " + " ".join(chunk for chunk in chunks if chunk)).strip()


def _resolve_device(device: str | Any) -> str:
    requested = str(device).lower()
    if requested == "auto":
        try:
            import torch

            requested = "mps" if torch.backends.mps.is_available() else "cpu"
        except ImportError:
            requested = "cpu"
    if requested not in {"cpu", "mps"}:
        raise ValueError("device 는 auto, mps, cpu 중 하나여야 합니다: " + str(device))
    if requested == "mps":
        import torch

        if not torch.backends.mps.is_available():
            raise RuntimeError("mps 를 요청했지만 사용할 수 없습니다")
    return requested


def _load_model(device: str):
    # local_files_only is intentional: this command must never fetch a model.
    try:
        from transformers import AutoModel, AutoTokenizer
    except ImportError as exc:
        raise RuntimeError("transformers 가 필요합니다") from exc

    os.environ["HF_HUB_OFFLINE"] = "1"
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME, local_files_only=True)
    model = AutoModel.from_pretrained(MODEL_NAME, local_files_only=True)
    model.to(device)
    model.eval()
    return tokenizer, model


def embed(
    texts: Sequence[str],
    *,
    batch: int,
    device: str,
    max_length: int = 256,
) -> np.ndarray:
    """Embed texts with offline multilingual-e5-small and normalized mean pooling."""
    if batch <= 0:
        raise ValueError("batch 는 양수여야 합니다")
    if max_length <= 0:
        raise ValueError("max_length 는 양수여야 합니다")
    resolved = _resolve_device(device)
    tokenizer, model = _load_model(resolved)

    import torch

    text_list = [str(text) for text in texts]
    hidden_size = int(getattr(model.config, "hidden_size", CONTENT_DIM))
    if not text_list:
        return np.empty((0, hidden_size), dtype=np.float32)

    vectors: list[np.ndarray] = []
    with torch.inference_mode():
        for start in range(0, len(text_list), batch):
            encoded = tokenizer(
                text_list[start : start + batch],
                padding=True,
                truncation=True,
                max_length=max_length,
                return_tensors="pt",
            )
            encoded = {key: value.to(resolved) for key, value in encoded.items()}
            output = model(**encoded)
            token_embeddings = output.last_hidden_state
            mask = encoded["attention_mask"].unsqueeze(-1).to(token_embeddings.dtype)
            pooled = (token_embeddings * mask).sum(dim=1) / mask.sum(dim=1).clamp_min(1e-9)
            normalized = torch.nn.functional.normalize(pooled, p=2, dim=1)
            vectors.append(normalized.cpu().numpy().astype(np.float32, copy=False))
    return np.concatenate(vectors, axis=0)


def _articles_frame():
    import pandas as pd

    path = config.normalized_dir() / "articles.parquet"
    frame = pd.read_parquet(path)
    frame["article_id"] = frame["article_id"].astype("string")
    frame = frame.sort_values("article_id", kind="mergesort").reset_index(drop=True)
    if frame["article_id"].isna().any():
        raise ValueError("article_id 에 결측이 있습니다")
    ids = frame["article_id"].astype(str)
    if not ids.str.fullmatch(r"\d{10}").all():
        raise ValueError("article_id 는 10자리 문자열이어야 합니다")
    if ids.duplicated().any():
        raise ValueError("article_id 가 중복됩니다")
    frame["article_id"] = ids
    return frame


def _write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def save_content(
    out: str | Path,
    vectors: np.ndarray,
    article_ids: Sequence[str],
    *,
    elapsed_seconds: float = 0.0,
    device: str = "cpu",
) -> None:
    """Save content vectors and their stable article-id row mapping."""
    out_path = Path(out)
    out_path.mkdir(parents=True, exist_ok=True)
    matrix = np.asarray(vectors)
    ids = [str(article_id) for article_id in article_ids]
    if matrix.ndim != 2:
        raise ValueError("vectors 는 2차원이어야 합니다")
    if matrix.shape[0] != len(ids):
        raise ValueError("vectors 와 article_ids 의 행 수가 다릅니다")
    if matrix.shape[1] != CONTENT_DIM:
        raise ValueError(f"content 차원은 {CONTENT_DIM}이어야 합니다")
    if len(set(ids)) != len(ids):
        raise ValueError("article_id 가 중복됩니다")

    np.save(out_path / "content_e5.npy", matrix.astype(np.float16, copy=False))
    _write_json(out_path / "articles.json", ids)
    _write_json(
        out_path / "content_meta.json",
        {
            "model": MODEL_NAME,
            "dimension": int(matrix.shape[1]),
            "count": int(matrix.shape[0]),
            "elapsed_seconds": float(elapsed_seconds),
            "device": device,
            "template": TEXT_TEMPLATE,
        },
    )


def load_content(out: str | Path) -> tuple[np.ndarray, dict[str, int]]:
    """Load content vectors and return the article-id to row-number mapping."""
    out_path = Path(out)
    matrix = np.load(out_path / "content_e5.npy", allow_pickle=False)
    ids = json.loads((out_path / "articles.json").read_text(encoding="utf-8"))
    if not isinstance(ids, list):
        raise ValueError("articles.json 은 article_id 목록이어야 합니다")
    if matrix.ndim != 2 or matrix.shape[0] != len(ids):
        raise ValueError("content 행렬과 articles.json 의 크기가 다릅니다")
    return matrix, {str(article_id): index for index, article_id in enumerate(ids)}


def _pair_indices(labels: Sequence[str], rng: np.random.Generator, same: bool, count: int) -> np.ndarray:
    groups: dict[str, list[int]] = {}
    for index, label in enumerate(labels):
        groups.setdefault(str(label), []).append(index)
    eligible = [members for members in groups.values() if len(members) >= 2]
    if same:
        if not eligible:
            return np.empty((0, 2), dtype=np.int64)
        pairs = np.empty((count, 2), dtype=np.int64)
        for row in range(count):
            members = eligible[int(rng.integers(len(eligible)))]
            chosen = rng.choice(members, size=2, replace=False)
            pairs[row] = chosen
        return pairs

    if len(groups) < 2:
        return np.empty((0, 2), dtype=np.int64)
    keys = list(groups)
    pairs = np.empty((count, 2), dtype=np.int64)
    for row in range(count):
        first, second = rng.choice(len(keys), size=2, replace=False)
        pairs[row] = [rng.choice(groups[keys[first]]), rng.choice(groups[keys[second]])]
    return pairs


def _mean_pair_cosine(matrix: np.ndarray, pairs: np.ndarray) -> float | None:
    if len(pairs) == 0:
        return None
    left = matrix[pairs[:, 0]].astype(np.float32, copy=False)
    right = matrix[pairs[:, 1]].astype(np.float32, copy=False)
    left_norm = np.linalg.norm(left, axis=1)
    right_norm = np.linalg.norm(right, axis=1)
    cosine = (left * right).sum(axis=1) / np.maximum(left_norm * right_norm, 1e-12)
    return float(cosine.mean())


def _check_dimension(
    matrix: np.ndarray,
    labels: Sequence[str],
    *,
    seed: int,
    pair_count: int,
) -> dict[str, Any]:
    rng = np.random.default_rng(seed)
    same_pairs = _pair_indices(labels, rng, True, pair_count)
    different_pairs = _pair_indices(labels, rng, False, pair_count)
    same_mean = _mean_pair_cosine(matrix, same_pairs)
    different_mean = _mean_pair_cosine(matrix, different_pairs)
    return {
        "same_mean": same_mean,
        "different_mean": different_mean,
        "same_count": int(len(same_pairs)),
        "different_count": int(len(different_pairs)),
        "passed": bool(
            same_mean is not None
            and different_mean is not None
            and same_mean > different_mean
        ),
    }


def check_content(out: str | Path, frame, *, seed: int = config.SEED) -> dict[str, Any]:
    """Compare same-label and different-label cosine samples and save the result."""
    matrix, article_rows = load_content(out)
    ordered = frame.set_index("article_id").loc[list(article_rows)]
    if len(ordered) != len(matrix):
        raise ValueError("검사할 articles 와 content 행렬의 크기가 다릅니다")
    row_order = [article_rows[str(article_id)] for article_id in ordered.index]
    aligned = matrix[np.asarray(row_order, dtype=np.int64)]
    result = {
        "seed": int(seed),
        "count": int(len(aligned)),
        "pair_count": CHECK_PAIRS,
        "section": _check_dimension(
            aligned,
            ordered["section_name"].fillna("").astype(str).tolist(),
            seed=seed,
            pair_count=CHECK_PAIRS,
        ),
        "product_type": _check_dimension(
            aligned,
            ordered["product_type_name"].fillna("").astype(str).tolist(),
            seed=seed + 1,
            pair_count=CHECK_PAIRS,
        ),
    }
    result["passed"] = bool(result["section"]["passed"] and result["product_type"]["passed"])
    _write_json(Path(out) / "content_check.json", result)
    return result


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=config.out_dir() / "content")
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--batch", type=int, default=256)
    parser.add_argument("--device", choices=("auto", "mps", "cpu"), default="auto")
    parser.add_argument("--check", action="store_true", help="저장 후 콘텐츠 품질 표본검사")
    return parser.parse_args()


def main() -> None:
    args = _parse_args()
    if args.limit is not None and args.limit < 0:
        raise SystemExit("--limit 은 0 이상이어야 합니다")
    existing_content = (args.out / "content_e5.npy").exists() and (args.out / "articles.json").exists()
    if args.check and existing_content:
        result = check_content(args.out, _articles_frame())
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return
    started = time.perf_counter()
    frame = _articles_frame()
    if args.limit is not None:
        frame = frame.iloc[: args.limit].copy()
    article_ids = frame["article_id"].tolist()
    texts = [article_text(row) for row in frame.to_dict(orient="records")]
    resolved = _resolve_device(args.device)
    vectors = embed(texts, batch=args.batch, device=resolved)
    elapsed = time.perf_counter() - started
    save_content(args.out, vectors, article_ids, elapsed_seconds=elapsed, device=resolved)
    print(
        json.dumps(
            {
                "out": str(args.out),
                "count": len(article_ids),
                "dimension": int(vectors.shape[1]),
                "elapsed_seconds": elapsed,
                "device": resolved,
            },
            ensure_ascii=False,
        )
    )
    if args.check:
        result = check_content(args.out, frame)
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
