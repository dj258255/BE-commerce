#!/usr/bin/env python3
"""C2(#466) 수평 확장(USL) 실험 하네스 — 모델 서버 N 대에 홀드아웃 500명을 닫힌 루프로 보낸다.

    python3 tools/usl_bench.py --urls http://127.0.0.1:18866 --concurrency 1 4 8 --out OUT
    python3 tools/usl_bench.py --urls http://127.0.0.1:18866 http://127.0.0.1:18867 \
        --concurrency 4 8 --out OUT

요청 본문은 S1 L2 와 같다(compose hybrid · 이벤트 100건 · 고객 시드 7 · 홀드아웃 500명).
워커는 요청을 포트 목록에 번갈아 배분한다(`--urls` 하나면 S1 `closed_loop` 과 같은 동작).
설정(서버 수 × 동시성)마다 처리량 · 왕복/추론 p50 · p95 · 오류를 `--out` 에 JSON + md 로 남긴다.

측정 전 예측은 `docs/40-capacity-model.md` "(i) 수평 확장 예측(측정 전)" 절에 커밋돼 있다.
측정은 `tools/run-c2-usl.sh` 가 서버를 띄우고 이 도구를 부른다.
"""
from __future__ import annotations

import argparse
import json
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Sequence

ROOT = Path(__file__).resolve().parents[1]
PERSONALIZATION = ROOT / "personalization"
for _path in (str(ROOT), str(PERSONALIZATION)):
    if _path not in sys.path:
        sys.path.insert(0, _path)

import tools.s1_serving_bench as s1  # noqa: E402


COMPOSE = "hybrid"


# ---------------------------------------------------------------------------
# 요청 · 서버
# ---------------------------------------------------------------------------

def load_requests(data_dir: Path | None, customers: int, seed: int,
                  history_events: int) -> tuple[Any, dict[str, Any], list[Any]]:
    """S1 L2 와 같은 홀드아웃 요청을 만든다 — `s1.load_context` 의 l2 분기를 그대로 쓴다."""
    context = s1.load_context(SimpleNamespace(data_dir=data_dir, level="l2", customers=int(customers),
                                              seed=int(seed), history_events=int(history_events),
                                              l3_customers=0))
    return context["vocab"], context["sections"], context["requests"]


def build_sources(urls: Sequence[str], vocab: Any, sections: dict[str, Any],
                  timeout: float, history_events: int) -> list[Any]:
    return [s1.HttpSource(url, "v2", vocab, sections, compose=COMPOSE,
                          timeout=float(timeout), history_events=int(history_events)) for url in urls]


# ---------------------------------------------------------------------------
# 닫힌 루프 (N 대)
# ---------------------------------------------------------------------------

def closed_loop(sources: Sequence[Any], requests: Sequence[Any], concurrency: int,
                timeout: float) -> tuple[list[dict[str, Any]], int, float]:
    """요청을 동시성 `concurrency` 의 닫힌 루프로 보내되, 포트 목록에 번갈아 배분한다.

    요청 목록을 워커마다 겹치지 않게 나눈다(동시성 4 면 요청을 네 워커가 나눠 각자 앞
    요청이 끝난 뒤 다음을 보낸다). 워커 안에서도 포트를 번갈아 고르므로 서버 수가 2 이상이면
    두 포트가 비슷하게 요청을 받는다. `--urls` 하나면 포트 선택이 한 곳으로 고정돼 S1
    `closed_loop` 과 같은 동작이 된다(같은 분할 · 같은 본문 · 같은 관측 형식).
    """
    if concurrency < 1:
        raise ValueError("concurrency 는 1 이상")
    if not requests:
        raise ValueError("보낼 요청이 없다")
    if not sources:
        raise ValueError("보낼 서버가 없다")
    endpoints = [source.url.rstrip("/") + "/page" for source in sources]
    builder = sources[0]
    shares = [list(requests[index::concurrency]) for index in range(concurrency)]
    observations: list[dict[str, Any]] = []
    errors = [0]
    lock = threading.Lock()

    def worker(share: list[Any], worker_id: int) -> None:
        local: list[dict[str, Any]] = []
        bad = 0
        for step, request in enumerate(share):
            url = endpoints[(worker_id + step) % len(endpoints)]
            body = json.dumps(builder.request_body(request), ensure_ascii=False,
                              allow_nan=False).encode("utf-8")
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
    threads = [threading.Thread(target=worker, args=(share, index))
               for index, share in enumerate(shares)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return observations, errors[0], time.perf_counter() - began


# ---------------------------------------------------------------------------
# 실행 · 표
# ---------------------------------------------------------------------------

def run(args: argparse.Namespace) -> dict[str, Any]:
    vocab, sections, requests = load_requests(args.data_dir, args.customers, args.seed, args.history_events)
    sources = build_sources(args.urls, vocab, sections, args.timeout, args.history_events)
    runs: list[dict[str, Any]] = []
    for concurrency in args.concurrency:
        observations, errors, elapsed = closed_loop(sources, requests, int(concurrency), args.timeout)
        summary = s1.summarize_observations(observations, errors, elapsed)
        summary["concurrency"] = int(concurrency)
        summary["servers"] = len(sources)
        runs.append(summary)
    report = {"level": "c2-usl", "urls": list(args.urls), "servers": len(sources), "compose": COMPOSE,
              "customers": len(requests), "history_events": int(args.history_events),
              "concurrency": [int(value) for value in args.concurrency], "runs": runs}
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    stem = f"usl-{len(sources)}srv"
    (out / f"{stem}.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2, default=float) + "\n", encoding="utf-8")
    table = table_usl(report)
    (out / f"{stem}.md").write_text(table, encoding="utf-8")
    print(table, end="")
    return report


def _num(value: Any) -> str:
    return "-" if value is None else f"{float(value):.2f}"


def table_usl(report: dict[str, Any]) -> str:
    lines = ["# C2 USL — 수평 확장(닫힌 루프)", "",
             f"- 서버 {report['servers']}대 `{report['urls']}` · compose `{report['compose']}`",
             f"- 홀드아웃 고객 {report['customers']:,}명(seed 7)을 설정마다 한 번씩 · 이벤트 {report['history_events']}건", "",
             "| 서버 | 동시성 | 응답 | 오류 | 처리량/s | 왕복 p50 | 왕복 p95 | 추론 p50 | 추론 p95 | composition | fallback |",
             "|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|"]
    for value in report["runs"]:
        wall, inference = value["wall"], value["inference_ms"]
        lines.append(
            f"| {value['servers']} | {value['concurrency']} | {value['responses']:,} | {value['errors']} | "
            f"{value['throughput']:.2f} | {_num(wall['p50_ms'])} | {_num(wall['p95_ms'])} | "
            f"{_num(inference['p50_ms'])} | {_num(inference['p95_ms'])} | "
            f"`{value['composition']}` | `{value['fallback']}` |")
    lines.append("")
    return "\n".join(lines) + "\n"


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--urls", nargs="+", required=True, help="모델 서버 URL 목록(서버 수만큼)")
    parser.add_argument("--concurrency", type=int, nargs="+", default=[1, 4, 8],
                        help="닫힌 루프 동시성 목록(차례로 잰다)")
    parser.add_argument("--customers", type=int, default=500, help="홀드아웃 고객 수(기본 500)")
    parser.add_argument("--seed", type=int, default=7, help="고객 표집 시드(기본 7)")
    parser.add_argument("--history-events", type=int, default=s1.HISTORY_EVENTS,
                        help=f"요청 본문에 실을 최근 구매 이벤트 수(기본 {s1.HISTORY_EVENTS})")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--data-dir", type=Path)
    parser.add_argument("--out", type=Path, required=True, help="결과 디렉터리(usl-<N>srv.json · .md)")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    run(parse_args(argv))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
