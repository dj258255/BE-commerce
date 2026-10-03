#!/usr/bin/env python3
"""S1(#454) 순위 모델 + GenPage 줄 구성 서빙 측정 — L1 일치 · L2 지연 · L3 대체 경로.

설계 · 판정은 `personalization/docs/genpage-v2/BACKEND.md` "S1" 절에 측정 전에 고정했다.

- L1: 홀드아웃 구매 고객 500명(seed 7)에 대해 서버의 `rule` 첫 페이지를 D3 final
  `B` 조각과, `hybrid` · `hybrid-cached` 를 D5 final `H-thin-4` 조각과 행 토큰 ·
  상품 순서로 비교한다. 불일치가 하나라도 있으면 판정 1 에 따라 L2 ~ L4 를 쓰지 않는다
- L2: 같은 500명을 compose 네 가지(`generate` · `rule` · `hybrid` · `hybrid-cached`) ×
  동시성 1 · 4 로 보낸다. config 마다 고객 전원을 정확히 한 번씩 닫힌 루프로 보내되,
  동시성 4 면 네 워커가 나눠 맡는다. 서버 추론 ms(응답 `ms`)와 왕복 ms 의
  p50 · p95 · p99, 처리량, 오류, composition · fallback 개수를 낸다
- L3: 홀드아웃 밖 고객(검증 주) 100명을 `hybrid` 로 섞어 보낸다. 대체 비율 · 오류 ·
  대체 응답의 지연을 낸다

요청 본문은 `genpage2.simulate.HttpSource` 가 만드는 것과 같다(같은 이벤트 · 프로필 ·
요청 시각). L4(가상 사용자 A/B)는 `tools/v2_virtual_ab.py` 가 한다(`tools/run-s1.sh`).
"""
from __future__ import annotations

import argparse
import glob
import gzip
import json
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path
from typing import Any, Iterable, Sequence

import numpy as np
import pandas as pd


ROOT = Path(__file__).resolve().parents[1]
PERSONALIZATION = ROOT / "personalization"
for _path in (str(ROOT), str(PERSONALIZATION)):
    if _path not in sys.path:
        sys.path.insert(0, _path)

from genpage2 import config  # noqa: E402
from genpage2.simulate import (HttpSource, PageRequest, build_eval_users, eval_buyers,  # noqa: E402
                               eval_transactions, load_attributes)
from genpage2.vocab import Vocab, _article_id, content_rows  # noqa: E402
from tools.genpage2_x4_budget import latency_summary  # noqa: E402


COMPOSES = ("generate", "rule", "hybrid", "hybrid-cached")
L1_COMPOSES = {"rule": "b", "hybrid": "h", "hybrid-cached": "h"}
# S1(#454) L1 ~ L4 가 서버로 보내는 최근 구매 이벤트 수. dataset.py 의 이력 상한
# (`articles[max(0, start - 100):start]`, dataset.py:298 · 324)과 같은 값이다 — 프롬프트는
# dataset 에서 tail(60) 이라 100건을 보내도 같고, 서버가 다시 사기 행을 정하는 이력만
# 오프라인(`eval_meta.history`)과 같아진다.
HISTORY_EVENTS = 100


# ---------------------------------------------------------------------------
# 오프라인 파일(D3 · D5 조각)과 비교
# ---------------------------------------------------------------------------

def expand_paths(patterns: Iterable[str]) -> list[Path]:
    """셸이 안 편 글로브까지 펴서 실제 파일 목록으로 만든다."""
    files: list[Path] = []
    for pattern in patterns:
        text = str(pattern)
        matches = sorted(glob.glob(text)) if any(ch in text for ch in "*?[") else [text]
        if not matches:
            raise FileNotFoundError(f"파일이 없다: {text}")
        for match in matches:
            path = Path(match)
            if not path.is_file():
                raise FileNotFoundError(f"파일이 아니다: {path}")
            files.append(path)
    if not files:
        raise ValueError("오프라인 파일이 하나도 없다")
    return files


def read_json(path: Path) -> Any:
    if path.suffix == ".gz":
        with gzip.open(path, "rt", encoding="utf-8") as handle:
            return json.load(handle)
    return json.loads(path.read_text(encoding="utf-8"))


def load_pages(patterns: Iterable[str]) -> dict[str, list[dict[str, Any]]]:
    """D3 · D5 조각(`pages[].rows[].row_token` · `items`)을 고객별로 합친다."""
    pages: dict[str, list[dict[str, Any]]] = {}
    for path in expand_paths(patterns):
        report = read_json(path)
        for record in report.get("pages", []):
            customer = str(record["customer_id"])
            if customer in pages:
                raise ValueError(f"고객 {customer} 이(가) 여러 조각에 있다")
            pages[customer] = [{"row_token": int(row["row_token"]),
                                "items": [str(article) for article in row["items"]]}
                               for row in record["rows"]]
    return pages


def _server_signature(rows: Sequence[Any]) -> list[tuple[int, list[str]]]:
    return [(int(row.row_token), [str(article) for article in row.items]) for row in rows]


def _offline_signature(rows: Sequence[dict[str, Any]]) -> list[tuple[int, list[str]]]:
    return [(int(row["row_token"]), [str(article) for article in row["items"]]) for row in rows]


def first_difference(left: Sequence[Any], right: Sequence[Any]) -> dict[str, Any] | None:
    """행 토큰 · 상품 순서가 처음 갈리는 자리(없으면 None)."""
    for index in range(max(len(left), len(right))):
        a = list(left[index]) if index < len(left) else None
        b = list(right[index]) if index < len(right) else None
        if a != b:
            return {"index": index, "server": a, "offline": b}
    return None


def compare_against_source(source: HttpSource, requests: Sequence[PageRequest],
                           offline: dict[str, list[dict[str, Any]]], *,
                           max_examples: int = 3) -> dict[str, Any]:
    """요청마다 서버 첫 페이지를 오프라인과 비교한다(오류 · 빠짐도 센다)."""
    matched = mismatched = missing = errors = 0
    examples: list[dict[str, Any]] = []
    for request in requests:
        try:
            rows = source.pages([request])[0]
        except RuntimeError as exc:
            errors += 1
            if len(examples) < max_examples:
                examples.append({"customer_id": str(request.customer_id), "error": str(exc)})
            continue
        expected = offline.get(str(request.customer_id))
        if expected is None:
            missing += 1
            if len(examples) < max_examples:
                examples.append({"customer_id": str(request.customer_id), "error": "오프라인에 없다"})
            continue
        left, right = _server_signature(rows), _offline_signature(expected)
        if left == right:
            matched += 1
        else:
            mismatched += 1
            if len(examples) < max_examples:
                examples.append({"customer_id": str(request.customer_id), "rows": left, "offline": right,
                                 "first_diff": first_difference(left, right)})
    return {"matched": matched, "mismatched": mismatched, "missing": missing, "errors": errors,
            "total": len(requests), "examples": examples}


# ---------------------------------------------------------------------------
# 닫힌 루프 지연 (L2)
# ---------------------------------------------------------------------------

def closed_loop(source: HttpSource, requests: Sequence[PageRequest], concurrency: int,
                timeout: float) -> tuple[list[dict[str, Any]], int, float]:
    """고객 전원을 정확히 한 번씩, 동시성 `concurrency` 의 닫힌 루프로 보낸다.

    요청 목록을 워커마다 겹치지 않게 나눈다(동시성 4 면 500명을 네 워커가 나눠 각자
    앞 요청이 끝난 뒤 다음을 보낸다). 그래서 (compose × 동시성)마다 응답 수가 고객 수와
    같아야 한다.
    """
    if concurrency < 1:
        raise ValueError("concurrency 는 1 이상")
    if not requests:
        raise ValueError("보낼 요청이 없다")
    url = source.url.rstrip("/") + "/page"
    shares = [list(requests[index::concurrency]) for index in range(concurrency)]
    observations: list[dict[str, Any]] = []
    errors = [0]
    lock = threading.Lock()

    def worker(share: list[PageRequest]) -> None:
        local: list[dict[str, Any]] = []
        bad = 0
        for request in share:
            body = json.dumps(source.request_body(request), ensure_ascii=False, allow_nan=False).encode("utf-8")
            post = urllib.request.Request(url, data=body, method="POST",
                                          headers={"Content-Type": "application/json"})
            began = time.perf_counter()
            try:
                with urllib.request.urlopen(post, timeout=timeout) as response:
                    payload = json.loads(response.read().decode("utf-8"))
            except (urllib.error.HTTPError, urllib.error.URLError, OSError, ValueError):
                bad += 1
                continue
            local.append({"latency_ms": (time.perf_counter() - began) * 1000,
                          "inference_ms": float(payload.get("ms", 0.0) or 0.0),
                          "queue_ms": float(payload.get("queue_ms", 0.0) or 0.0),
                          "composition": payload.get("composition"),
                          "fallback": payload.get("fallback"),
                          "violations": int(payload.get("violations", 0))})
        with lock:
            observations.extend(local)
            errors[0] += bad

    began = time.perf_counter()
    threads = [threading.Thread(target=worker, args=(share,)) for share in shares]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return observations, errors[0], time.perf_counter() - began


def summarize_observations(observations: Sequence[dict[str, Any]], errors: int,
                           elapsed: float) -> dict[str, Any]:
    """관측 목록을 지연 · 처리량 · composition · fallback 요약으로 만든다."""
    latency = [float(value["latency_ms"]) for value in observations]
    inference = [float(value.get("inference_ms", 0.0)) for value in observations]
    queue = [float(value.get("queue_ms", 0.0)) for value in observations]
    composition = Counter("null" if value.get("composition") is None else str(value["composition"])
                          for value in observations)
    fallback = Counter("null" if value.get("fallback") is None else str(value["fallback"])
                       for value in observations)
    return {"responses": len(observations), "errors": int(errors),
            "throughput": len(observations) / elapsed if elapsed > 0 else 0.0,
            "wall": latency_summary(latency), "inference_ms": latency_summary(inference),
            "queue_ms": latency_summary(queue), "composition": dict(composition),
            "fallback": dict(fallback),
            "violations": int(sum(int(value.get("violations", 0)) for value in observations))}


def measure_source(source: HttpSource, requests: Sequence[PageRequest]) -> dict[str, Any]:
    """요청을 하나씩 보내며 관측을 모은다(L3). 오류는 세고 계속 간다."""
    began = time.perf_counter()
    errors = 0
    for request in requests:
        try:
            source.pages([request])
        except RuntimeError:
            errors += 1
    summary = summarize_observations(source.observations, errors, time.perf_counter() - began)
    fallback_latency = [float(value["latency_ms"]) for value in source.observations
                        if value.get("fallback") is not None]
    summary["fallback_latency_ms"] = latency_summary(fallback_latency)
    return summary


# ---------------------------------------------------------------------------
# 데이터 · 요청
# ---------------------------------------------------------------------------

def _eval_users(normalized: Path, vocab: Vocab, attributes: dict[str, tuple], content_map: dict[str, int],
                request: Any, customers: int, seed: int, history_events: int) -> list[Any]:
    buyers = eval_buyers(normalized / "transactions.parquet", request)
    taken = min(int(customers), len(buyers))
    if taken == 0:
        raise ValueError(f"평가 창({request})에 산 고객이 없다")
    chosen = sorted(str(value) for value in np.random.default_rng(int(seed)).choice(
        np.asarray(buyers), size=taken, replace=False).tolist())
    transactions = eval_transactions(normalized / "transactions.parquet", chosen, request)
    customers_frame = pd.read_parquet(normalized / "customers.parquet")
    users, _ = build_eval_users(vocab, transactions, customers_frame, attributes, content_map,
                                request=request, history_events=int(history_events))
    users.sort(key=lambda user: user.customer_id)
    return users


def first_requests(users: Sequence[Any]) -> list[PageRequest]:
    """`simulate` 의 1쪽 요청과 같은 모양(prev_page 없음 · truth_items=wanted)."""
    return [PageRequest(user.customer_id, user.request_date, list(user.ctx_tokens),
                        list(user.ctx_content), list(user.history), [], list(user.wanted),
                        list(user.events), dict(user.profile))
            for user in users]


def load_context(args: argparse.Namespace) -> dict[str, Any]:
    base = Path(args.data_dir) if args.data_dir else config.data_dir()
    normalized = base / "hm" / "normalized"
    articles = pd.read_parquet(normalized / "articles.parquet")
    attributes = load_attributes(articles)
    content_map = content_rows(articles)
    sections = {_article_id(article): section
                for article, section in zip(articles["article_id"].tolist(), articles["section_no"].tolist())}
    context: dict[str, Any] = {"sections": sections}
    if args.level in ("l1", "l2"):
        vocab = Vocab.load(base / "hm" / "model" / "genpage2" / "final" / "vocab.json")
        users = _eval_users(normalized, vocab, attributes, content_map,
                            config.request_of("final"), args.customers, args.seed, args.history_events)
        context.update({"vocab": vocab, "requests": first_requests(users)})
    if args.level == "l3":
        validate_vocab = Vocab.load(base / "hm" / "model" / "genpage2" / "validate" / "vocab.json")
        users = _eval_users(normalized, validate_vocab, attributes, content_map,
                            config.request_of("validate"), args.l3_customers, args.seed, args.history_events)
        # 서버는 final 어휘로 응답한다 — 응답 행 이름을 파싱할 어휘도 final 것을 쓴다.
        context.update({"vocab": Vocab.load(base / "hm" / "model" / "genpage2" / "final" / "vocab.json"),
                        "requests": first_requests(users)})
    return context


# ---------------------------------------------------------------------------
# L1 · L2 · L3
# ---------------------------------------------------------------------------

def run_l1(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any]:
    if not args.b_pages or not args.h_pages:
        raise ValueError("L1 에는 --b-pages 와 --h-pages 가 필요하다")
    offline = {"b": load_pages(args.b_pages), "h": load_pages(args.h_pages)}
    requests = context["requests"]
    report: dict[str, Any] = {"level": "l1", "url": args.url, "customers": len(requests), "compose": {}}
    for compose, kind in L1_COMPOSES.items():
        source = HttpSource(args.url, "v2", context["vocab"], context["sections"],
                            compose=compose, timeout=args.timeout, history_events=args.history_events)
        result = compare_against_source(source, requests, offline[kind], max_examples=args.max_examples)
        result["composition"] = dict(source.composition_counts)
        result["fallback"] = dict(source.fallback_counts)
        result["all_matched"] = (result["matched"] == len(requests) and result["mismatched"] == 0
                                 and result["missing"] == 0 and result["errors"] == 0)
        report["compose"][compose] = result
    report["all_matched"] = all(value["all_matched"] for value in report["compose"].values())
    return report


def run_l2(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any]:
    requests = list(context["requests"])
    if int(args.l2_requests) > 0:
        requests = requests[:int(args.l2_requests)]
    report: dict[str, Any] = {"level": "l2", "url": args.url, "customers": len(requests),
                              "concurrency": [int(value) for value in args.concurrency], "configs": {}}
    for compose in COMPOSES:
        source = HttpSource(args.url, "v2", context["vocab"], context["sections"],
                            compose=compose, timeout=args.timeout, history_events=args.history_events)
        report["configs"][compose] = {}
        for concurrency in args.concurrency:
            observations, errors, elapsed = closed_loop(source, requests, int(concurrency), args.timeout)
            report["configs"][compose][str(int(concurrency))] = summarize_observations(
                observations, errors, elapsed)
    return report


def run_l3(args: argparse.Namespace, context: dict[str, Any]) -> dict[str, Any]:
    requests = context["requests"]
    source = HttpSource(args.url, "v2", context["vocab"], context["sections"],
                        compose="hybrid", timeout=args.timeout, history_events=args.history_events)
    summary = measure_source(source, requests)
    responses = summary["responses"]
    fallbacks = {reason: count for reason, count in summary["fallback"].items() if reason != "null"}
    summary.update({"level": "l3", "url": args.url, "customers": len(requests),
                    "fallback_total": sum(fallbacks.values()),
                    "fallback_rate": (sum(fallbacks.values()) / responses if responses else 0.0),
                    "fallback_reasons": dict(fallbacks)})
    return summary


# ---------------------------------------------------------------------------
# 표
# ---------------------------------------------------------------------------

def _num(value: Any) -> str:
    return "-" if value is None else f"{float(value):.2f}"


def table_l1(report: dict[str, Any]) -> str:
    lines = ["# S1 L1 — 서빙 = 오프라인", "",
             f"- 서버 `{report['url']}` · 고객 {report['customers']:,}명(seed 7)",
             "- `rule` ↔ D3 final B · `hybrid` · `hybrid-cached` ↔ D5 final H-thin-4", "",
             "| compose | 일치 | 불일치 | 빠짐 | 오류 | composition | fallback |", "|---|---:|---:|---:|---:|---|---|"]
    for compose, value in report["compose"].items():
        lines.append(f"| {compose} | {value['matched']:,}/{value['total']:,} | {value['mismatched']} | "
                     f"{value['missing']} | {value['errors']} | `{value['composition']}` | `{value['fallback']}` |")
    lines.append("")
    lines.append(f"전체 일치: **{report['all_matched']}**")
    for compose, value in report["compose"].items():
        for example in value["examples"]:
            lines.append(f"- `{compose}` 첫 불일치: `{json.dumps(example, ensure_ascii=False)}`")
    lines.append("")
    return "\n".join(lines) + "\n"


def table_l2(report: dict[str, Any]) -> str:
    lines = ["# S1 L2 — 지연(닫힌 루프)", "",
             f"- 서버 `{report['url']}` · 고객 {report['customers']:,}명을 config 마다 한 번씩 "
             f"(동시성 {' · '.join(str(value) for value in report['concurrency'])})", "",
             "| compose | 동시성 | 응답 | 오류 | 처리량/s | 왕복 p50 | 왕복 p95 | 왕복 p99 | "
             "추론 p50 | 추론 p95 | 추론 p99 | composition | fallback |",
             "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|"]
    for compose, by_concurrency in report["configs"].items():
        for concurrency, value in by_concurrency.items():
            wall, inference = value["wall"], value["inference_ms"]
            lines.append(
                f"| {compose} | {concurrency} | {value['responses']:,} | {value['errors']} | "
                f"{value['throughput']:.2f} | {_num(wall['p50_ms'])} | {_num(wall['p95_ms'])} | "
                f"{_num(wall['p99_ms'])} | {_num(inference['p50_ms'])} | {_num(inference['p95_ms'])} | "
                f"{_num(inference['p99_ms'])} | `{value['composition']}` | `{value['fallback']}` |")
    lines.append("")
    return "\n".join(lines) + "\n"


def table_l3(report: dict[str, Any]) -> str:
    wall, inference = report["wall"], report["inference_ms"]
    lines = ["# S1 L3 — 대체 경로(검증 주 고객)", "",
             f"- 서버 `{report['url']}` · 고객 {report['customers']:,}명 · compose `hybrid`",
             f"- 응답 {report['responses']:,} · 오류 {report['errors']} · "
             f"대체 {report['fallback_total']:,}({report['fallback_rate']:.1%})",
             f"- fallback 사유 `{report['fallback_reasons']}` · composition `{report['composition']}`",
             f"- 왕복 p50 {_num(wall['p50_ms'])} · p95 {_num(wall['p95_ms'])} · p99 {_num(wall['p99_ms'])} ms",
             f"- 추론 p50 {_num(inference['p50_ms'])} · p95 {_num(inference['p95_ms'])} · "
             f"p99 {_num(inference['p99_ms'])} ms"]
    fallback_latency = report["fallback_latency_ms"]
    lines.append(f"- 대체 응답 왕복 p50 {_num(fallback_latency['p50_ms'])} · "
                 f"p95 {_num(fallback_latency['p95_ms'])} · p99 {_num(fallback_latency['p99_ms'])} ms")
    lines.append("")
    return "\n".join(lines) + "\n"


TABLES = {"l1": table_l1, "l2": table_l2, "l3": table_l3}
RUNNERS = {"l1": run_l1, "l2": run_l2, "l3": run_l3}


def run(args: argparse.Namespace) -> dict[str, Any]:
    context = load_context(args)
    report = RUNNERS[args.level](args, context)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / f"{args.level}.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2, default=float) + "\n", encoding="utf-8")
    table = TABLES[args.level](report)
    (out / f"{args.level}.md").write_text(table, encoding="utf-8")
    print(table, end="")
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True, help="실행 중인 GenPage v2 서버 URL")
    parser.add_argument("--level", choices=("l1", "l2", "l3"), required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--b-pages", nargs="+", help="D3 final B 조각(.json 또는 .json.gz)")
    parser.add_argument("--h-pages", nargs="+", help="D5 final H-thin-4 조각(.json 또는 .json.gz)")
    parser.add_argument("--customers", type=int, default=500)
    parser.add_argument("--l3-customers", type=int, default=100)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--l2-requests", type=int, default=0,
                        help="L2 의 config(compose × 동시성)마다 보낼 요청 수. 0(기본)이면 고객 전원")
    parser.add_argument("--history-events", type=int, default=HISTORY_EVENTS,
                        help=f"요청 본문에 실을 최근 구매 이벤트 수(기본 {HISTORY_EVENTS} = dataset 이력 상한)")
    parser.add_argument("--concurrency", type=int, nargs="+", default=[1, 4])
    parser.add_argument("--max-examples", type=int, default=3, help="L1 불일치 예시 최대 개수")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--data-dir", type=Path)
    args = parser.parse_args(argv)
    run(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
