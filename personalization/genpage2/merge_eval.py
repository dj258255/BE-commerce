"""조각으로 나눠 만든 평가 JSON 을 합쳐 전체 지표를 한 번에 낸다.

``evaluate --shard K/N`` 는 고객별 원자료만 남기고 지표를 내지 않는다.  MAP 처럼
분모가 전체 고객이어야 하는 값은 여기서 조각을 모두 합친 뒤 기존 ``evaluate_pages``
로 한 번만 계산한다.  기준선도 조각마다 다시 돌리지 않고 여기서 한 번 계산한다.
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any, Iterable

import pandas as pd

from .config import data_dir, request_of
from .decode import GeneratedRow
from .evaluate import (_load_examples, evaluate_pages, load_eval_assets, popular_last_week,
                       repeat_last_pages, v1_engine_pages)


def _shard_info(shard: dict[str, Any]) -> tuple[int, int]:
    info = shard.get("shard")
    if not isinstance(info, dict) or "index" not in info or "total" not in info:
        raise ValueError("조각 JSON 에 shard.index 와 shard.total 이 필요합니다")
    return int(info["index"]), int(info["total"])


def _collect_pages(shards: list[dict[str, Any]], meta: pd.DataFrame) -> dict[str, list[GeneratedRow]]:
    """Merge shard raw output into one ``customer_id -> page`` mapping."""
    pages: dict[str, list[GeneratedRow]] = {}
    for shard in shards:
        for record in shard.get("pages", []):
            customer = str(record["customer_id"])
            if customer in pages:
                raise ValueError(f"고객 {customer} 이(가) 여러 조각에 있습니다")
            pages[customer] = [GeneratedRow(int(row["row_token"]), [str(a) for a in row["items"]])
                               for row in record["rows"]]
    expected = {str(customer) for customer in meta["customer_id"]}
    if set(pages) != expected:
        raise ValueError(
            "조각의 고객 집합이 평가 대상과 다릅니다 "
            f"(빠짐 {len(expected - set(pages))}, 여분 {len(set(pages) - expected)})"
        )
    return pages


def _collect_violations(shards: list[dict[str, Any]]) -> dict[str, int]:
    violations: dict[str, int] = {}
    for shard in shards:
        for record in shard.get("pages", []):
            violations[str(record["customer_id"])] = int(record.get("violations", 0))
    return violations


def merge_reports(shards: list[dict[str, Any]], *, meta: pd.DataFrame, vocab: Any, content: Any,
                  content_rows: dict[str, int], tx: pd.DataFrame, base: Path | None = None,
                  elapsed_seconds: float | None = None) -> dict[str, Any]:
    """Validate the shard set and compute whole-market metrics exactly once."""
    if not shards:
        raise ValueError("합칠 조각이 없습니다")
    mode = shards[0].get("mode")
    if mode not in ("validate", "final"):
        raise ValueError("조각 JSON 에 올바른 mode 가 없습니다")
    total = _shard_info(shards[0])[1]
    by_index: dict[int, dict[str, Any]] = {}
    devices: set[Any] = set()
    for shard in shards:
        if shard.get("mode") != mode:
            raise ValueError("조각들의 mode 가 다릅니다")
        index, shard_total = _shard_info(shard)
        if shard_total != total:
            raise ValueError("조각들의 전체 개수 N 이 다릅니다")
        if index in by_index:
            raise ValueError(f"조각 {index}/{total} 이(가) 중복됐습니다")
        by_index[index] = shard
        devices.add(shard.get("device"))
    missing = sorted(set(range(1, total + 1)) - set(by_index))
    if missing:
        raise ValueError(f"빠진 조각이 있습니다: {missing}")
    if len(devices) > 1:
        raise ValueError("조각들의 device 가 다릅니다")

    ordered = [by_index[index] for index in range(1, total + 1)]
    for field in ("args", "options", "ckpt"):
        values = [shard.get(field) for shard in ordered]
        if any(value != values[0] for value in values):
            raise ValueError(f"조각들의 {field} 가 다릅니다")

    pages = _collect_pages(ordered, meta)
    violations = _collect_violations(ordered)

    results: dict[str, Any] = {}
    clock = time.perf_counter()
    results["repeat_last"] = evaluate_pages(meta, repeat_last_pages(meta), elapsed=time.perf_counter() - clock)
    results["repeat_last"]["ms_per_page"] = None
    popular = popular_last_week(tx, request_of(mode))
    results["popular_last_week"] = evaluate_pages(
        meta, {str(c): popular for c in meta["customer_id"]}, elapsed=time.perf_counter() - clock
    )
    results["popular_last_week"]["ms_per_page"] = None
    if mode == "final":
        results["v1_engine"] = evaluate_pages(meta, v1_engine_pages(meta, base),
                                              elapsed=time.perf_counter() - clock)
        results["v1_engine"]["ms_per_page"] = None
    generation = elapsed_seconds if elapsed_seconds is not None else float(
        sum(shard.get("generation_seconds", 0.0) for shard in ordered)
    )
    results["model"] = evaluate_pages(meta, pages, vocab=vocab, content=content, content_rows=content_rows,
                                      violations=violations, elapsed=generation,
                                      device=(next(iter(devices)) if devices else "cpu"))

    report: dict[str, Any] = {
        "mode": mode,
        "ckpt": ordered[0].get("ckpt"),
        "args": ordered[0].get("args", {}),
        "elapsed_seconds": float(sum(shard.get("elapsed_seconds", 0.0) for shard in ordered)),
        "results": results,
    }
    if ordered[0].get("options") is not None:
        report["options"] = ordered[0]["options"]
    if any("candidates_mean" in shard for shard in ordered):
        counts = [int(shard["shard"]["customers"]) for shard in ordered]
        means = [float(shard.get("candidates_mean", 0.0)) for shard in ordered]
        report["candidates_mean"] = (sum(count * mean for count, mean in zip(counts, means)) / sum(counts)
                                     if sum(counts) else 0.0)
    return report


def merge(inputs: Iterable[str | Path], *, base: str | Path | None = None, limit: int | None = None,
          out: str | Path | None = None) -> dict[str, Any]:
    """Load shard JSON files, merge them, and optionally write the combined report."""
    shards = [json.loads(Path(path).read_text(encoding="utf-8")) for path in inputs]
    if not shards:
        raise ValueError("합칠 조각이 없습니다")
    mode = shards[0].get("mode")
    if mode not in ("validate", "final"):
        raise ValueError("조각 JSON 에 올바른 mode 가 없습니다")
    root = Path(base) if base else data_dir()
    meta, _archive = _load_examples(root, mode, limit)
    vocab, content, content_rows = load_eval_assets(root / "hm" / "model" / "genpage2" / mode)
    tx = pd.read_parquet(root / "hm" / "normalized" / "transactions.parquet", columns=["t_dat", "article_id"])
    report = merge_reports(shards, meta=meta, vocab=vocab, content=content, content_rows=content_rows,
                           tx=tx, base=root)
    if out is not None:
        destination = Path(out)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=float), encoding="utf-8")
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs", nargs="+", required=True, metavar="JSON")
    parser.add_argument("--out", required=True)
    parser.add_argument("--data-dir")
    parser.add_argument("--limit", type=int)
    args = parser.parse_args(argv)
    report = merge(args.inputs, base=args.data_dir, limit=args.limit, out=args.out)
    print(json.dumps(report, ensure_ascii=False, indent=2, default=float))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
