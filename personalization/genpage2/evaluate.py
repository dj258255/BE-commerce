"""GenPage v2 오프라인 평가와 재현 가능한 기준선 CLI."""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Any, Iterable

import numpy as np
import pandas as pd

from .candidates import build_candidates, build_similar_candidates
from .config import ITEMS_PER_ROW, MAX_ROWS, SEED, data_dir, out_dir, request_of
from .context import LEVELS

# ``decode`` 는 torch 를 끌어온다. 랭커는 LightGBM 과 torch 의 OpenMP 가 충돌해
# torch 없이 돌아야 하므로, 페이지가 실제로 ``GeneratedRow`` 일 때만 늦게 부른다.
_TRIVIAL_PAGE_TYPES = (str, bytes, int, np.integer)


def _as_articles(value: Any) -> list[str]:
    """Normalise parquet list cells without ever converting article ids to int."""
    if value is None or (isinstance(value, float) and np.isnan(value)):
        return []
    if isinstance(value, str):
        return [value]
    return [str(v).zfill(10) if str(v).isdigit() else str(v) for v in value]


def parse_candidates(value: str | tuple[int, int] | list[int] | None) -> tuple[int, int] | None:
    """Parse the ``N,M`` CLI form without accepting ambiguous candidate sets."""
    if value is None:
        return None
    if isinstance(value, (tuple, list)) and len(value) == 2:
        top_n, per_section_m = value
    elif isinstance(value, str):
        parts = value.split(",")
        if len(parts) != 2:
            raise ValueError("--candidates 는 N,M 형식이어야 합니다")
        top_n, per_section_m = parts
    else:
        raise ValueError("--candidates 는 N,M 형식이어야 합니다")
    try:
        result = int(top_n), int(per_section_m)
    except (TypeError, ValueError) as exc:
        raise ValueError("--candidates 의 N 과 M 은 정수여야 합니다") from exc
    if min(result) < 0:
        raise ValueError("--candidates 의 N 과 M 은 0 이상이어야 합니다")
    return result


def parse_similar(value: str | tuple[int, int] | list[int] | None) -> tuple[int, int] | None:
    """Parse the ``K,N`` form of ``--similar`` (recent K items, nearest N each)."""
    if value is None:
        return None
    if isinstance(value, (tuple, list)) and len(value) == 2:
        recent_k, neighbors = value
    elif isinstance(value, str):
        parts = value.split(",")
        if len(parts) != 2:
            raise ValueError("--similar 는 K,N 형식이어야 합니다")
        recent_k, neighbors = parts
    else:
        raise ValueError("--similar 는 K,N 형식이어야 합니다")
    try:
        result = int(recent_k), int(neighbors)
    except (TypeError, ValueError) as exc:
        raise ValueError("--similar 의 K 와 N 은 정수여야 합니다") from exc
    if min(result) < 0:
        raise ValueError("--similar 의 K 와 N 은 0 이상이어야 합니다")
    return result


def parse_shard(value: str | tuple[int, int] | list[int] | None) -> tuple[int, int] | None:
    """Parse the ``K/N`` CLI form; K is one-based so ``1/N`` is the first shard."""
    if value is None:
        return None
    if isinstance(value, (tuple, list)) and len(value) == 2:
        index, total = value
    elif isinstance(value, str):
        parts = value.split("/")
        if len(parts) != 2:
            raise ValueError("--shard 는 K/N 형식이어야 합니다")
        index, total = parts
    else:
        raise ValueError("--shard 는 K/N 형식이어야 합니다")
    try:
        parsed = int(index), int(total)
    except (TypeError, ValueError) as exc:
        raise ValueError("--shard 의 K 와 N 은 정수여야 합니다") from exc
    shard_index, shard_total = parsed
    if shard_total < 1:
        raise ValueError("--shard 의 N 은 1 이상이어야 합니다")
    if not 1 <= shard_index <= shard_total:
        raise ValueError("--shard 의 K 는 1..N 범위여야 합니다")
    return parsed


def shard_bounds(count: int, index: int, total: int) -> tuple[int, int]:
    """Return the half-open customer-position range of shard ``index`` (one-based).

    The split mirrors ``numpy.array_split``: contiguous, near-equal chunks with the
    remainder handed to the lowest shard numbers.
    """
    if total < 1 or not 1 <= index <= total:
        raise ValueError("조각 번호는 1..N 범위여야 합니다")
    base, extra = divmod(count, total)
    start = (base + 1) * min(index - 1, extra) + base * max(index - 1 - extra, 0)
    size = base + 1 if index - 1 < extra else base
    return start, start + size


def set_torch_threads(value: int | None) -> None:
    """Limit this process to ``value`` torch threads so shards can run side by side."""
    if value is None:
        return
    if int(value) < 1:
        raise ValueError("--threads 는 1 이상이어야 합니다")
    import torch

    torch.set_num_threads(int(value))


def _repeat_pin(history: list[str], vocab: Any, *, gate: bool = False) -> dict[int, int] | None:
    """어휘 이력이 세 개 이상일 때만 다시 사기 행을 고정한다.

    ``gate`` 를 켜면 이력 안에 같은 상품을 두 번 이상 산 기록이 있는 사용자는
    첫 행(0)에, 없는 사용자는 모델 행 두 개 뒤인 셋째 행(2)에 둔다. 이 판단은
    요청 전 이력만 쓴다. 기본값(``gate=False``)은 지금까지와 같이 첫 행에 둔다.
    """
    items = {vocab.item(article) for article in history if vocab.item(article) is not None}
    if len(items) < 3:
        return None
    if gate:
        seen: set[str] = set()
        repeated = False
        for article in history:
            if article in seen:
                repeated = True
                break
            seen.add(article)
        return {0: vocab.id("ROW_REPEAT")} if repeated else {2: vocab.id("ROW_REPEAT")}
    return {0: vocab.id("ROW_REPEAT")}


def _recent_repeat_items(history: list[str], vocab: Any, limit: int) -> list[str]:
    """이력의 최근 산 순서(중복 없이, 어휘 상품만)를 ``limit`` 개까지 돌려준다."""
    seen: set[str] = set()
    items: list[str] = []
    for article in history:
        if article in seen or vocab.item(article) is None:
            continue
        seen.add(article)
        items.append(article)
        if len(items) == limit:
            break
    return items


def repeat_gate_first_row_ratio(meta: pd.DataFrame, vocab: Any) -> float:
    """게이트가 다시 사기 행을 첫 행에 둔 사용자 비율."""
    if not len(meta):
        return 0.0
    first = 0
    for row in meta.itertuples(index=False):
        pin = _repeat_pin(_as_articles(getattr(row, "history")), vocab, gate=True)
        if pin is not None and pin.get(0) == vocab.id("ROW_REPEAT"):
            first += 1
    return first / len(meta)


def repeat_last_pages(meta: pd.DataFrame, k: int = 12) -> dict[str, list[str]]:
    result: dict[str, list[str]] = {}
    for row in meta.itertuples(index=False):
        seen: set[str] = set()
        items: list[str] = []
        for article in _as_articles(getattr(row, "history")):
            if article not in seen:
                seen.add(article)
                items.append(article)
            if len(items) == k:
                break
        result[str(getattr(row, "customer_id"))] = items
    return result


def popular_last_week(tx: pd.DataFrame, request: pd.Timestamp, k: int = 12) -> list[str]:
    dates = pd.to_datetime(tx["t_dat"])
    window = tx[(dates < request) & (dates >= request - pd.Timedelta(days=7))]
    # Stable article-id tie breaking makes this baseline reproducible.
    counts = window.groupby("article_id", observed=True).size().rename("count").reset_index()
    counts["article_id"] = counts["article_id"].astype(str).str.zfill(10)
    return counts.sort_values(["count", "article_id"], ascending=[False, True]).head(k)["article_id"].tolist()


def _truth_frame(meta: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for row in meta.itertuples(index=False):
        customer = str(getattr(row, "customer_id"))
        rows.extend((customer, article) for article in dict.fromkeys(_as_articles(getattr(row, "truth"))))
    return pd.DataFrame(rows, columns=["customer_id", "article_id"])


def map_at_12(meta: pd.DataFrame, pages: dict[str, list[str]]) -> tuple[float, int]:
    """Use v1's exact scorer, including its all-truth-customers denominator."""
    root = Path(__file__).resolve().parents[1]
    pipeline = str(root / "pipeline")
    if pipeline not in sys.path:
        sys.path.insert(0, pipeline)
    from features_hm import map_at_k  # imported here so CLI does not need A1/A3

    pred_rows = [(customer, article, rank + 1)
                 for customer, items in pages.items()
                 for rank, article in enumerate(items[:12])]
    pred = pd.DataFrame(pred_rows, columns=["customer_id", "article_id", "rank"])
    return map_at_k(pred, _truth_frame(meta), 12)


def _page_is_generated(page: Any) -> bool:
    """페이지가 ``GeneratedRow`` 목록인지 본다. 문자열 목록이면 torch 를 부르지 않는다."""
    if not page:
        return False
    if isinstance(page[0], _TRIVIAL_PAGE_TYPES):
        return False
    from .decode import GeneratedRow

    return isinstance(page[0], GeneratedRow)


def _page_items(page: Any) -> list[str]:
    if _page_is_generated(page):
        return [article for row in page for article in row.items]
    return list(page or [])


def page_metrics(meta: pd.DataFrame, pages: dict[str, Any], *, vocab: Any | None = None,
                 content: np.ndarray | None = None, content_rows: dict[str, int] | None = None,
                 violations: dict[str, int] | None = None, elapsed: float = 0.0,
                 device: str = "cpu") -> dict[str, Any]:
    """Calculate all §7 page metrics for pages keyed by customer id."""
    flat = {str(c): _page_items(page) for c, page in pages.items()}
    score, customers = map_at_12(meta, flat)
    recalls: list[float] = []
    new_recalls: list[float] = []
    repeat_recalls: list[float] = []
    row_recalls: list[float] = []
    diversities: list[float] = []
    total_rows = 0
    total_items = 0
    for row in meta.itertuples(index=False):
        customer = str(getattr(row, "customer_id"))
        truth = list(dict.fromkeys(_as_articles(getattr(row, "truth"))))
        history = set(_as_articles(getattr(row, "history")))
        items = flat.get(customer, [])
        item_set = set(items)
        # 정답이 빈 고객은 page_recall 과 같은 규칙으로 0.0 을 넣고 분모에 포함한다.
        # (new/repeat 로 나눠도 각 분모는 "정답이 빈 고객도 센 전체 고객 수"다.)
        recalls.append(len(item_set & set(truth)) / len(truth) if truth else 0.0)
        new_truth = [article for article in truth if article not in history]
        repeat_truth = [article for article in truth if article in history]
        new_recalls.append(len(item_set & set(new_truth)) / len(new_truth) if new_truth else 0.0)
        repeat_recalls.append(len(item_set & set(repeat_truth)) / len(repeat_truth) if repeat_truth else 0.0)
        page = pages.get(customer, [])
        page_rows = {r.row_token for r in page} if _page_is_generated(page) else set()
        if vocab is not None and truth:
            hit_rows = [vocab.row_of(article) in page_rows or (
                article in history and vocab.id("ROW_REPEAT") in page_rows
            ) for article in truth]
            row_recalls.append(sum(hit_rows) / len(hit_rows))
        elif truth:
            row_recalls.append(0.0)
        if content is not None and content_rows is not None:
            indices = [content_rows[a] for a in dict.fromkeys(items) if a in content_rows]
            if len(indices) >= 2:
                vectors = np.asarray(content[indices], dtype=np.float32)
                # e5 vectors are normalised, but normalise again for fixtures.
                vectors /= np.maximum(np.linalg.norm(vectors, axis=1, keepdims=True), 1e-12)
                pairs = vectors @ vectors.T
                diversities.append(float((1 - pairs[np.triu_indices(len(indices), 1)]).mean()))
            else:
                diversities.append(0.0)
        total_rows += len(page) if _page_is_generated(page) else 0
        total_items += len(items)
    return {
        "map_at_12": score,
        "map_at_12_has_vocab_history": None,  # filled below when its column exists
        "page_recall": float(np.mean(recalls)) if recalls else 0.0,
        "new_item_recall": float(np.mean(new_recalls)) if new_recalls else 0.0,
        "repeat_item_recall": float(np.mean(repeat_recalls)) if repeat_recalls else 0.0,
        "row_recall": float(np.mean(row_recalls)) if row_recalls else 0.0,
        "diversity": float(np.mean(diversities)) if diversities else 0.0,
        "violations": int(sum((violations or {}).values())),
        "rows_mean": total_rows / customers if customers else 0.0,
        "items_mean": total_items / customers if customers else 0.0,
        "ms_per_page": elapsed * 1000 / customers if customers else 0.0,
        "customers": customers,
        "device": device,
    }


def evaluate_pages(meta: pd.DataFrame, pages: dict[str, Any], **kwargs: Any) -> dict[str, Any]:
    metrics = page_metrics(meta, pages, **kwargs)
    if "has_vocab_history" in meta.columns:
        subset = meta[meta["has_vocab_history"].fillna(False).astype(bool)]
        metrics["map_at_12_has_vocab_history"] = map_at_12(
            subset, {str(c): _page_items(pages.get(str(c), [])) for c in subset["customer_id"]}
        )[0] if len(subset) else 0.0
    else:
        metrics["map_at_12_has_vocab_history"] = 0.0
    return metrics


def _load_examples(base: Path, mode: str, limit: int | None) -> tuple[pd.DataFrame, Any]:
    mode_dir = base / "hm" / "model" / "genpage2" / mode
    meta = pd.read_parquet(mode_dir / "eval_meta.parquet")
    archive = np.load(mode_dir / "eval.npz")
    if limit is not None and limit < len(meta):
        meta = meta.sample(n=limit, random_state=SEED).sort_index()
    return meta, archive


def _context_at(archive: Any, index: int) -> tuple[list[int], list[int]]:
    start, end = archive["ctx_offsets"][index:index + 2]
    return archive["ctx_tokens"][start:end].astype(int).tolist(), archive["ctx_content"][start:end].astype(int).tolist()


def _load_decoder(mode_dir: Path, ckpt: Path, device_arg: str) -> tuple[PageDecoder, Any, np.ndarray, dict[str, int], str]:
    # Deliberately runtime imports: A1/A3 are separate concurrent work.
    import torch
    from .content import load_content
    from .decode import PageDecoder
    from .model import load_checkpoint
    from .vocab import Vocab

    device = "cuda" if device_arg == "auto" and torch.cuda.is_available() else (
        "mps" if device_arg == "auto" and torch.backends.mps.is_available() else device_arg)
    if device == "auto":
        device = "cpu"
    vocab = Vocab.load(mode_dir / "vocab.json")
    content, content_rows = load_content(mode_dir.parent / "content")
    loaded = load_checkpoint(ckpt, content=torch.tensor(content, dtype=torch.float32), device=device)
    model, _, extra = loaded
    model.to(device).eval()
    level = extra.get("context", "full")
    if level == "history":  # checkpoints written before the six-level rename
        level = "items"
    if level not in LEVELS:
        raise ValueError(f"checkpoint has invalid extra.context {level!r}")
    return PageDecoder(model, vocab, content_rows, device, level=level), vocab, content, content_rows, device


def load_eval_assets(mode_dir: Path) -> tuple[Any, np.ndarray, dict[str, int]]:
    """Load the vocabulary and content rows that page metrics need."""
    from .content import load_content
    from .vocab import Vocab

    vocab = Vocab.load(mode_dir / "vocab.json")
    content, content_rows = load_content(mode_dir.parent / "content")
    return vocab, content, content_rows


def v1_engine_pages(meta: pd.DataFrame, base: Path) -> dict[str, list[str]]:
    """Score the serving v1 engine for every customer, without regenerating it."""
    root = Path(__file__).resolve().parents[1]
    serving = str(root / "serving")
    if serving not in sys.path:
        sys.path.insert(0, serving)
    os.environ["GENPAGE_DATA"] = str(base)
    from genpage_server import Engine

    engine = Engine()
    pages: dict[str, list[str]] = {}
    for row in meta.itertuples(index=False):
        history = [int(a) for a in _as_articles(getattr(row, "history"))]
        pages[str(getattr(row, "customer_id"))] = (
            [str(a).zfill(10) for a in engine.recommend(history, 12)] if history else []
        )
    return pages


def generate_pages(meta: pd.DataFrame, archive: Any, decoder: PageDecoder, vocab: Any,
                   candidates_by_customer: dict[str, set[str]] | None, batch: int,
                   pin_repeat: bool, *, repeat_order: str = "model",
                   repeat_gate: bool = False) -> tuple[dict[str, list[GeneratedRow]], dict[str, int]]:
    """Generate one page per customer in ``meta`` order, in batches."""
    pages: dict[str, list[GeneratedRow]] = {}
    violations: dict[str, int] = {}
    queued = list(meta.iterrows())
    for begin in range(0, len(queued), batch):
        part = queued[begin:begin + batch]
        examples = []
        for index, row in part:
            ctx_tokens, ctx_content = _context_at(archive, int(index))
            history = _as_articles(row.history)
            pin = _repeat_pin(history, vocab, gate=repeat_gate) if pin_repeat else None
            pinned_items = None
            if pin is not None and repeat_order == "recency":
                recent = _recent_repeat_items(history, vocab, ITEMS_PER_ROW)
                pinned_items = {position: recent for position in pin}
            examples.append({"ctx_tokens": ctx_tokens, "ctx_content": ctx_content,
                             "history_articles": history, "pinned": pin,
                             "pinned_items": pinned_items,
                             "allowed_items": (candidates_by_customer[str(row.customer_id)]
                                               if candidates_by_customer is not None else None)})
        decoded = decoder.generate_batch(examples, n_rows=MAX_ROWS, items_per_row=ITEMS_PER_ROW, prefix=2)
        for (_, row), (page, bad) in zip(part, decoded):
            customer = str(row.customer_id)
            pages[customer], violations[customer] = page, bad
    return pages, violations


def _options(pin_repeat: bool, candidate_config: tuple[int, int] | None,
             similar_config: tuple[int, int] | None, repeat_gate: bool,
             repeat_order: str, ranker_candidates: bool = False) -> dict[str, Any] | None:
    """Build the report ``options`` block, or ``None`` when every option is default."""
    if not (pin_repeat or candidate_config is not None or similar_config is not None
            or repeat_gate or repeat_order != "model" or ranker_candidates):
        return None
    options: dict[str, Any] = {
        "pin_repeat": pin_repeat,
        "candidates": (list(candidate_config) if candidate_config is not None else None),
    }
    if repeat_order != "model":
        options["repeat_order"] = repeat_order
    if similar_config is not None:
        options["similar"] = list(similar_config)
    if repeat_gate:
        options["repeat_gate"] = True
    if ranker_candidates:
        options["ranker_candidates"] = True
    return options


def _report_args(args: argparse.Namespace, pin_repeat: bool,
                 candidate_config: tuple[int, int] | None,
                 similar_config: tuple[int, int] | None = None) -> dict[str, Any]:
    """Reconstruct the option set a single-process run would report."""
    report_args = dict(vars(args))
    # 옵션을 끄면 종전 결과 JSON의 모양까지 유지한다.
    if not pin_repeat:
        report_args.pop("pin_repeat", None)
    if candidate_config is None:
        report_args.pop("candidates", None)
    if similar_config is None:
        report_args.pop("similar", None)
    if not getattr(args, "ranker_candidates", False):
        report_args.pop("ranker_candidates", None)
    if getattr(args, "repeat_order", "model") == "model":
        report_args.pop("repeat_order", None)
    if not getattr(args, "repeat_gate", False):
        report_args.pop("repeat_gate", None)
    # 조각은 합친 결과의 args 에 남기지 않는다(단일 실행과 같은 모양).
    report_args.pop("shard", None)
    if getattr(args, "threads", None) is None:
        report_args.pop("threads", None)
    return report_args


def _candidates_for(meta: pd.DataFrame, tx: pd.DataFrame, request: Any, *,
                    vocab: Any, content: np.ndarray, content_rows: dict[str, int],
                    candidate_config: tuple[int, int] | None,
                    similar_config: tuple[int, int] | None,
                    ranker_candidates: bool = False) -> dict[str, set[str]] | None:
    """Union the operational candidates with the e5 nearest-neighbour candidates.

    ``ranker_candidates`` 를 켜면 랭커의 C1 ~ C5 합집합을 후보 풀로 쓴다(공정한
    비교를 위해 ``build_ranker_candidates`` 를 그대로 부른다).
    """
    result: dict[str, set[str]] | None = None
    if ranker_candidates:
        from .ranker import DEFAULT_CONFIG, RANKER_CONFIGS, Transactions, build_ranker_candidates, candidate_pool

        if "customer_id" not in tx.columns:
            raise ValueError("--ranker-candidates 에는 customer_id 열이 있는 거래가 필요합니다")
        per_source = build_ranker_candidates(meta, Transactions.from_frame(tx), request, vocab=vocab,
                                             content=content, content_rows=content_rows,
                                             cfg=dict(RANKER_CONFIGS[DEFAULT_CONFIG]))
        result = candidate_pool(per_source)
    if candidate_config is not None:
        top_n, per_section_m = candidate_config
        operational = build_candidates(meta, tx, request, top_n=top_n, per_section_m=per_section_m, vocab=vocab)
        if result is None:
            result = operational
        else:
            for customer, items in operational.items():
                result.setdefault(customer, set()).update(items)
    if similar_config is not None:
        recent_k, neighbors = similar_config
        similar = build_similar_candidates(meta, vocab=vocab, content=content, content_rows=content_rows,
                                           recent_k=recent_k, neighbors=neighbors)
        if result is None:
            result = similar
        else:
            for customer, items in similar.items():
                result.setdefault(customer, set()).update(items)
    return result


def _shard_report(args: argparse.Namespace, base: Path, meta: pd.DataFrame, archive: Any, *,
                  shard_config: tuple[int, int], started: float, pin_repeat: bool,
                  candidate_config: tuple[int, int] | None, report_args: dict[str, Any],
                  similar_config: tuple[int, int] | None = None, repeat_order: str = "model",
                  repeat_gate: bool = False, ranker_candidates: bool = False) -> dict[str, Any]:
    """Generate only the requested shard and keep per-customer raw output."""
    if not args.ckpt:
        raise ValueError("모델 평가에는 --ckpt 가 필요합니다")
    index, total = shard_config
    start, stop = shard_bounds(len(meta), index, total)
    shard_meta = meta.iloc[start:stop]
    columns = ["t_dat", "article_id"] + (["customer_id"] if ranker_candidates else [])
    tx = pd.read_parquet(base / "hm" / "normalized" / "transactions.parquet", columns=columns)
    request = request_of(args.mode)
    mode_dir = base / "hm" / "model" / "genpage2" / args.mode
    decoder, vocab, content, content_rows, device = _load_decoder(mode_dir, Path(args.ckpt), args.device)
    candidates_by_customer = _candidates_for(shard_meta, tx, request, vocab=vocab, content=content,
                                             content_rows=content_rows, candidate_config=candidate_config,
                                             similar_config=similar_config, ranker_candidates=ranker_candidates)
    generated_at = time.perf_counter()
    pages, violations = generate_pages(shard_meta, archive, decoder, vocab, candidates_by_customer,
                                       args.batch, pin_repeat, repeat_order=repeat_order,
                                       repeat_gate=repeat_gate)
    generation_seconds = time.perf_counter() - generated_at
    report: dict[str, Any] = {
        "mode": args.mode,
        "ckpt": str(args.ckpt),
        "args": report_args,
        "device": device,
        "shard": {"index": index, "total": total, "customers": int(len(shard_meta))},
        "elapsed_seconds": time.perf_counter() - started,
        "generation_seconds": generation_seconds,
        "pages": [
            {"customer_id": str(row.customer_id),
             "rows": [{"row_token": int(generated.row_token), "items": [str(a) for a in generated.items]}
                      for generated in pages.get(str(row.customer_id), [])],
             "violations": int(violations.get(str(row.customer_id), 0))}
            for row in shard_meta.itertuples(index=False)
        ],
    }
    options = _options(pin_repeat, candidate_config, similar_config, repeat_gate, repeat_order, ranker_candidates)
    if options is not None:
        report["options"] = options
    if pin_repeat and repeat_gate:
        report["repeat_gate_first_row_ratio"] = repeat_gate_first_row_ratio(shard_meta, vocab)
    if candidates_by_customer is not None:
        report["candidates_mean"] = (sum(len(items) for items in candidates_by_customer.values()) / len(shard_meta)
                                     if len(shard_meta) else 0.0)
    return report


def run(args: argparse.Namespace) -> dict[str, Any]:
    base = Path(args.data_dir) if args.data_dir else data_dir()
    meta, archive = _load_examples(base, args.mode, args.limit)
    request = request_of(args.mode)
    repeat_gate = bool(getattr(args, "repeat_gate", False))
    if repeat_gate and not getattr(args, "pin_repeat", False):
        # 게이트는 다시 사기 행을 어느 자리에 둘지 정하는 옵션이므로, 홀로 줘도
        # 고정을 함께 켠다(기본값인 꺼짐 상태는 건드리지 않는다).
        setattr(args, "pin_repeat", True)
    pin_repeat = bool(getattr(args, "pin_repeat", False))
    candidate_config = parse_candidates(getattr(args, "candidates", None))
    similar_config = parse_similar(getattr(args, "similar", None))
    repeat_order = getattr(args, "repeat_order", "model")
    ranker_candidates = bool(getattr(args, "ranker_candidates", False))
    shard_config = parse_shard(getattr(args, "shard", None))
    set_torch_threads(getattr(args, "threads", None))
    started = time.perf_counter()
    report_args = _report_args(args, pin_repeat, candidate_config, similar_config)

    if shard_config is not None:
        return _shard_report(args, base, meta, archive, shard_config=shard_config, started=started,
                             pin_repeat=pin_repeat, candidate_config=candidate_config,
                             report_args=report_args, similar_config=similar_config,
                             repeat_order=repeat_order, repeat_gate=repeat_gate,
                             ranker_candidates=ranker_candidates)

    candidates_by_customer: dict[str, set[str]] | None = None
    results: dict[str, Any] = {}

    repeat = repeat_last_pages(meta)
    results["repeat_last"] = evaluate_pages(meta, repeat, elapsed=time.perf_counter() - started)
    results["repeat_last"]["ms_per_page"] = None
    tx_columns = ["t_dat", "article_id"] + (["customer_id"] if ranker_candidates else [])
    tx = pd.read_parquet(base / "hm" / "normalized" / "transactions.parquet", columns=tx_columns)
    popular = popular_last_week(tx, request)
    results["popular_last_week"] = evaluate_pages(
        meta, {str(c): popular for c in meta["customer_id"]}, elapsed=time.perf_counter() - started
    )
    results["popular_last_week"]["ms_per_page"] = None

    if args.mode == "final":
        results["v1_engine"] = evaluate_pages(meta, v1_engine_pages(meta, base),
                                              elapsed=time.perf_counter() - started)
        results["v1_engine"]["ms_per_page"] = None

    vocab: Any | None = None
    if not args.baselines_only:
        if not args.ckpt:
            raise ValueError("모델 평가에는 --ckpt 가 필요합니다")
        mode_dir = base / "hm" / "model" / "genpage2" / args.mode
        decoder, vocab, content, content_rows, device = _load_decoder(mode_dir, Path(args.ckpt), args.device)
        candidates_by_customer = _candidates_for(meta, tx, request, vocab=vocab, content=content,
                                                 content_rows=content_rows, candidate_config=candidate_config,
                                                 similar_config=similar_config, ranker_candidates=ranker_candidates)
        generated_at = time.perf_counter()
        pages, violations = generate_pages(meta, archive, decoder, vocab, candidates_by_customer,
                                           args.batch, pin_repeat, repeat_order=repeat_order,
                                           repeat_gate=repeat_gate)
        results["model"] = evaluate_pages(meta, pages, vocab=vocab, content=content, content_rows=content_rows,
                                          violations=violations, elapsed=time.perf_counter() - generated_at,
                                          device=device)

    elapsed = time.perf_counter() - started
    report = {"mode": args.mode, "ckpt": str(args.ckpt) if args.ckpt else None,
              "args": report_args, "elapsed_seconds": elapsed, "results": results}
    options = _options(pin_repeat, candidate_config, similar_config, repeat_gate, repeat_order, ranker_candidates)
    if options is not None:
        report["options"] = options
    if pin_repeat and repeat_gate and vocab is not None:
        report["repeat_gate_first_row_ratio"] = repeat_gate_first_row_ratio(meta, vocab)
    if candidates_by_customer is not None:
        report["candidates_mean"] = (sum(len(items) for items in candidates_by_customer.values()) / len(meta)
                                     if len(meta) else 0.0)
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("validate", "final"), required=True)
    parser.add_argument("--ckpt")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--data-dir")
    parser.add_argument("--out")
    parser.add_argument("--batch", type=int, default=256)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--baselines-only", action="store_true")
    parser.add_argument("--pin-repeat", action="store_true")
    parser.add_argument("--repeat-order", choices=("model", "recency"), default="model",
                        help="고정한 다시 사기 행의 상품을 모델(model) 또는 최근 산 순서(recency)로 채운다")
    parser.add_argument("--repeat-gate", action="store_true",
                        help="재구매 기록이 있는 사용자만 다시 사기 행을 첫 행에 둔다")
    parser.add_argument("--candidates", metavar="N,M")
    parser.add_argument("--similar", metavar="K,N",
                        help="최근 산 K 개 상품 각각의 e5 코사인 최근접 N 개를 후보에 더한다")
    parser.add_argument("--ranker-candidates", action="store_true",
                        help="허용 후보를 랭커의 C1 ~ C5 합집합으로 둔다(GenPage 대 R 공정 비교)")
    parser.add_argument("--shard", metavar="K/N")
    parser.add_argument("--threads", type=int)
    args = parser.parse_args(argv)
    report = run(args)
    base = Path(args.data_dir) if args.data_dir else data_dir()
    name = Path(args.ckpt).name if args.ckpt else "baselines"
    candidate_config = parse_candidates(args.candidates)
    similar_config = parse_similar(args.similar)
    shard_config = parse_shard(args.shard)
    if args.pin_repeat:
        name += "__pin"
    if candidate_config is not None:
        name += f"__cand{candidate_config[0]}-{candidate_config[1]}"
    if similar_config is not None:
        name += f"__sim{similar_config[0]}-{similar_config[1]}"
    if args.repeat_order != "model":
        name += f"__{args.repeat_order}"
    if args.repeat_gate:
        name += "__gate"
    if shard_config is not None:
        name += f"__shard{shard_config[0]}-{shard_config[1]}"
    destination = Path(args.out) if args.out else out_dir() / args.mode / "eval" / f"{name}.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=float), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2, default=float))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
