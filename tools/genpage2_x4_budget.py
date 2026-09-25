#!/usr/bin/env python3
"""Measure GenPage v2 decoder cost and optional /page closed-loop latency (#318)."""
from __future__ import annotations

import argparse
import concurrent.futures
import inspect
import json
import sys
import threading
import time
import urllib.request
from pathlib import Path
from typing import Any, Iterable

import numpy as np
import pandas as pd


ROOT = Path(__file__).resolve().parents[1]
PERSONALIZATION = ROOT / "personalization"
if str(PERSONALIZATION) not in sys.path:
    sys.path.insert(0, str(PERSONALIZATION))

from genpage2 import config  # noqa: E402
from genpage2.vocab import _article_id  # noqa: E402


def build_grid() -> list[dict[str, Any]]:
    """The fixed X4 matrix: decoding method × cache × requested row count."""
    return [{"prefix": prefix, "use_cache": use_cache, "rows": rows}
            for prefix in (0, 2, 8) for use_cache in (True, False) for rows in (3, 6)]


def percentile(values: Iterable[float], q: float) -> float:
    values = sorted(float(value) for value in values)
    if not values:
        return 0.0
    return float(np.percentile(values, q, method="linear"))


def latency_summary(values: Iterable[float]) -> dict[str, float | int]:
    values = [float(value) for value in values]
    return {"n": len(values), "mean_ms": float(np.mean(values)) if values else 0.0,
            "p50_ms": percentile(values, 50), "p95_ms": percentile(values, 95),
            "p99_ms": percentile(values, 99)}


def summarize_server_responses(responses: Iterable[dict[str, Any]], elapsed_seconds: float,
                               errors: int = 0) -> dict[str, Any]:
    """Aggregate fakeable client observations from one closed-loop server run."""
    values = list(responses)
    latency = [float(value["latency_ms"]) for value in values]
    inference = [float(value["body"].get("ms", 0.0)) for value in values]
    queue = [float(value["body"].get("queue_ms", 0.0)) for value in values]
    return {"throughput": len(values) / elapsed_seconds if elapsed_seconds > 0 else 0.0,
            "errors": int(errors), "wall": latency_summary(latency),
            "inference_ms": latency_summary(inference), "queue_ms": latency_summary(queue)}


def _history(value: Any) -> list[str]:
    if value is None or (isinstance(value, float) and np.isnan(value)):
        return []
    return [_article_id(item) for item in list(value)[:100]]


def _items(rows: Any) -> list[str]:
    return [item for row in rows for item in row.items][:12]


def _generate_batches(decoder: Any, examples: list[dict[str, Any]], *, batch: int = 256,
                      n_rows: int = config.MAX_ROWS, prefix: int = 2,
                      use_cache: bool = True) -> list[tuple[Any, int]]:
    result: list[tuple[Any, int]] = []
    for start in range(0, len(examples), batch):
        result.extend(decoder.generate_batch(
            examples[start:start + batch], n_rows=n_rows,
            items_per_row=config.ITEMS_PER_ROW, prefix=prefix, use_cache=use_cache,
        ))
    return result


def _generate_counted(decoder: Any, tokens: list[int], content: list[int], history: list[str], config_row: dict[str, Any]) -> tuple[Any, int, float]:
    """Run one decoder call while counting its private forward-step boundary."""
    if "use_cache" not in inspect.signature(decoder.generate).parameters:
        raise RuntimeError("PageDecoder.generate 가 use_cache 인자를 받지 않습니다; X4 캐시 비교를 실행할 수 없습니다")
    calls = 0
    original = decoder._next_logits

    def counted(*args: Any, **kwargs: Any) -> Any:
        nonlocal calls
        calls += 1
        return original(*args, **kwargs)

    decoder._next_logits = counted
    began = time.perf_counter()
    try:
        rows, violations = decoder.generate(tokens, content, history_articles=history,
                                            n_rows=config_row["rows"], items_per_row=config.ITEMS_PER_ROW,
                                            prefix=config_row["prefix"], use_cache=config_row["use_cache"])
    finally:
        decoder._next_logits = original
    return (rows, violations), calls, (time.perf_counter() - began) * 1000


def _direct(mode_dir: Path, args: argparse.Namespace) -> tuple[list[dict[str, Any]], str, dict[str, bool]]:
    # Importing evaluate loads torch.  The grid/statistics helpers above are
    # intentionally usable without a model runtime for unit tests.
    from genpage2.evaluate import _context_at, _load_decoder, map_at_12

    meta = pd.read_parquet(mode_dir / "eval_meta.parquet").reset_index(drop=True)
    take = min(max(0, int(args.customers)), len(meta))
    selected = meta.sample(n=take, random_state=config.SEED).sort_index() if take else meta.iloc[:0]
    archive = np.load(mode_dir / "eval.npz")
    decoder, _, _, _, device = _load_decoder(mode_dir, Path(args.ckpt), args.device)
    parameters = inspect.signature(decoder.generate).parameters
    support = {"prefix_parameter": "prefix" in parameters, "use_cache_parameter": "use_cache" in parameters,
               "forward_cached": hasattr(decoder.model, "forward_cached")}
    if not support["prefix_parameter"] or not support["use_cache_parameter"]:
        raise RuntimeError(f"PageDecoder.generate 가 X4 설정을 지원하지 않습니다: {support}")
    contexts = []
    for index, row in selected.iterrows():
        tokens, content = _context_at(archive, int(index))
        contexts.append({"ctx_tokens": tokens, "ctx_content": content, "history_articles": _history(row.history)})
    results: list[dict[str, Any]] = []
    for config_row in build_grid():
        latency: list[float] = []
        forwards: list[int] = []
        pages: dict[str, list[str]] = {}
        violations = 0
        for index, row in selected.iterrows():
            tokens, content = _context_at(archive, int(index))
            (rows, bad), count, elapsed = _generate_counted(decoder, tokens, content, _history(row.history), config_row)
            latency.append(elapsed)
            forwards.append(count)
            violations += int(bad)
            if config_row["rows"] == 6:
                pages[str(row.customer_id)] = _items(rows)
        report = {**config_row, "latency_ms": latency_summary(latency),
                  "forward_passes": {"mean": float(np.mean(forwards)) if forwards else 0.0,
                                     "p50": percentile(forwards, 50), "p95": percentile(forwards, 95)},
                  "violations": violations}
        if config_row["rows"] == 6:
            # The direct loop above is the latency measurement.  Recreate only
            # the quality page in batches so MAP scoring never adds per-user
            # decoder calls to the measured path.
            quality_results = _generate_batches(
                decoder, contexts, n_rows=config.MAX_ROWS,
                prefix=config_row["prefix"], use_cache=config_row["use_cache"],
            )
            quality_pages = {
                str(row.customer_id): _items(result[0])
                for row, result in zip(selected.itertuples(index=False), quality_results, strict=True)
            }
            score, customers = map_at_12(selected, quality_pages)
            report["map_at_12"] = score
            report["map_customers"] = customers
        else:
            report["map_at_12"] = None
            report["map_customers"] = None
        results.append(report)
    return results, device, support


def _post(url: str, body: dict[str, Any]) -> dict[str, Any]:
    raw = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(url, data=raw, method="POST", headers={"Content-Type": "application/json"})
    began = time.perf_counter()
    with urllib.request.urlopen(request, timeout=60) as response:
        parsed = json.loads(response.read())
    return {"latency_ms": (time.perf_counter() - began) * 1000, "body": parsed}


def _server(base: str, histories: list[list[str]], seconds: float) -> list[dict[str, Any]]:
    if seconds <= 0:
        raise ValueError("--server-seconds 는 0보다 커야 합니다")
    results: list[dict[str, Any]] = []
    for concurrency in (1, 2, 4):
        responses: list[dict[str, Any]] = []
        errors = [0]
        lock = threading.Lock()
        stop = time.perf_counter() + seconds

        def worker(seed: int) -> None:
            index = seed
            while time.perf_counter() < stop:
                body = {"history": histories[index % len(histories)] if histories else [],
                        "rows": 3, "items_per_row": config.ITEMS_PER_ROW, "prefix": 2}
                index += 1
                try:
                    result = _post(base.rstrip("/") + "/page", body)
                    with lock:
                        responses.append(result)
                except Exception:  # noqa: BLE001 - measurements must retain error counts.
                    with lock:
                        errors[0] += 1

        began = time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
            list(pool.map(worker, range(concurrency)))
        summary = summarize_server_responses(responses, time.perf_counter() - began, errors[0])
        results.append({"concurrency": concurrency, **summary})
    return results


def _table(direct: list[dict[str, Any]], server: list[dict[str, Any]] | None) -> str:
    lines = ["prefix  cache  rows  p50 ms  p95 ms  mean ms  forward  MAP@12"]
    for value in direct:
        latency = value["latency_ms"]
        score = "-" if value["map_at_12"] is None else f"{value['map_at_12']:.4f}"
        lines.append(f"{value['prefix']:>6}  {'on' if value['use_cache'] else 'off':>5}  {value['rows']:>4}"
                     f"  {latency['p50_ms']:>6.2f}  {latency['p95_ms']:>6.2f}  {latency['mean_ms']:>7.2f}"
                     f"  {value['forward_passes']['mean']:>7.2f}  {score}")
    if server is not None:
        lines.append("server concurrency  throughput  p50 ms  p95 ms  p99 ms  errors")
        for value in server:
            wall = value["wall"]
            lines.append(f"{value['concurrency']:>18}  {value['throughput']:>10.2f}  {wall['p50_ms']:>6.2f}"
                         f"  {wall['p95_ms']:>6.2f}  {wall['p99_ms']:>6.2f}  {value['errors']}")
    return "\n".join(lines)


def run(args: argparse.Namespace) -> dict[str, Any]:
    base = Path(args.data_dir) if args.data_dir else config.data_dir()
    mode_dir = base / "hm" / "model" / "genpage2" / args.mode
    direct, device, decoder_support = _direct(mode_dir, args)
    server = None
    if args.server:
        meta = pd.read_parquet(mode_dir / "eval_meta.parquet").reset_index(drop=True)
        take = min(max(0, int(args.customers)), len(meta))
        selected = meta.sample(n=take, random_state=config.SEED).sort_index() if take else meta.iloc[:0]
        server = _server(args.server, [_history(row.history) for row in selected.itertuples(index=False)], args.server_seconds)
    report = {"experiment": "X4", "mode": args.mode, "checkpoint": str(args.ckpt), "device": device,
              "seed": config.SEED, "customers": min(max(0, int(args.customers)), len(pd.read_parquet(mode_dir / "eval_meta.parquet"))),
              "decoder_support": decoder_support, "direct": direct, "server": server}
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "x4.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(_table(direct, server))
    return report


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("validate", "final"), required=True)
    parser.add_argument("--ckpt", required=True, type=Path)
    parser.add_argument("--customers", type=int, default=500)
    parser.add_argument("--device", choices=("cpu", "mps", "auto"), default="cpu")
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--server", help="실행 중인 GenPage v2 서버 URL")
    parser.add_argument("--server-seconds", type=float, default=30.0)
    parser.add_argument("--data-dir", type=Path, help="GENPAGE_DATA 대신 사용할 데이터 루트")
    return parser.parse_args(argv)


if __name__ == "__main__":
    run(parse_args())
