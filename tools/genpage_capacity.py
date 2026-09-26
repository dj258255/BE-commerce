"""GenPage 모델 서버의 용량을 직접 잰다(#271 · #316). 닫힌 루프 — 스레드마다 응답을 받으면 바로 다음을 보낸다.

    python3 tools/genpage_capacity.py ITEMS.json OUT.json [SECONDS] [--url URL] [--threads "1 2 4"]

ITEMS.json 은 모델 어휘 안의 상품 id 목록이다(앱 부하와 같은 이력을 쓰려고 run 스크립트가 만든다).
/recommend 는 이력 20개 · k 12, /page 는 이력 20개 · 제외 24개 · 3행 × 8개 · prefix 2 로 부른다(앱 기본값).
주소는 `--url` > `$MODEL_URL` > 기본값 순서다 — v2 서버(8766)는 --url 로 준다.
"""
import argparse
import json
import os
import random
import sys
import threading
import time
import urllib.request
from pathlib import Path

DEFAULT_URL = "http://127.0.0.1:8765"
DEFAULT_THREADS = (1, 2, 4, 8)


def resolve_url(value=None, env=None):
    """--url > MODEL_URL > 기본값. 뒷슬래시는 떼어 경로를 붙일 때 `//` 가 되지 않게 한다."""
    environment = os.environ if env is None else env
    return (value or environment.get("MODEL_URL") or DEFAULT_URL).rstrip("/")


def parse_threads(value):
    try:
        threads = tuple(int(x) for x in str(value).replace(",", " ").split())
    except ValueError:
        threads = ()
    if not threads or any(thread <= 0 for thread in threads):
        raise argparse.ArgumentTypeError(f"동시성은 1 이상의 정수 목록이어야 한다: {value}")
    return threads


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("items", type=Path)
    parser.add_argument("out", type=Path)
    parser.add_argument("seconds", nargs="?", type=float, default=20.0, help="동시성마다 재는 시간(기본 20초)")
    parser.add_argument("--url", help=f"모델 서버 주소(기본: $MODEL_URL 또는 {DEFAULT_URL})")
    parser.add_argument("--threads", type=parse_threads, default=DEFAULT_THREADS,
                        help="닫힌 루프 동시성 목록(기본: 1 2 4 8)")
    return parser.parse_args(argv)


def body(path, rnd, items):
    history = rnd.sample(items, min(20, len(items)))
    if path == "/recommend":
        return {"history": history, "k": 12}
    return {"history": history, "exclude": rnd.sample(items, min(24, len(items))), "exclude_categories": [],
            "rows": 3, "items_per_row": 8, "prefix": 2}


def run(path, threads, url, items, seconds):
    lat, errors, stop = [], [0], time.perf_counter() + seconds
    lock = threading.Lock()

    def worker(seed):
        rnd = random.Random(seed)
        while time.perf_counter() < stop:
            data = json.dumps(body(path, rnd, items)).encode()
            req = urllib.request.Request(url + path, data, {"Content-Type": "application/json"})
            t = time.perf_counter()
            try:
                urllib.request.urlopen(req, timeout=10).read()
                with lock:
                    lat.append((time.perf_counter() - t) * 1000)
            except Exception:  # noqa: BLE001  실패는 세기만 한다
                with lock:
                    errors[0] += 1

    started = time.perf_counter()
    ts = [threading.Thread(target=worker, args=(i,)) for i in range(threads)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    elapsed = time.perf_counter() - started
    lat.sort()
    n = len(lat)
    return {"path": path, "threads": threads, "throughput": n / elapsed, "p50_ms": lat[n // 2],
            "p95_ms": lat[int(0.95 * (n - 1))], "p99_ms": lat[int(0.99 * (n - 1))], "n": n, "errors": errors[0]}


def warm(url, items):
    """서버를 데운다 — 첫 호출의 초기화가 p95 에 들어가지 않게."""
    for path in ("/recommend", "/page"):
        for _ in range(20):
            data = json.dumps(body(path, random.Random(0), items)).encode()
            urllib.request.urlopen(urllib.request.Request(url + path, data,
                                                          {"Content-Type": "application/json"})).read()


def main(argv=None):
    args = parse_args(argv)
    items = json.loads(args.items.read_text(encoding="utf-8"))
    url = resolve_url(args.url)
    warm(url, items)
    results = []
    for path in ("/recommend", "/page"):
        for threads in args.threads:
            measured = run(path, threads, url, items, args.seconds)
            results.append(measured)
            print(json.dumps(measured))
    recommend = [r for r in results if r["path"] == "/recommend"]
    page = [r for r in results if r["path"] == "/page"]
    best = max(recommend, key=lambda r: r["throughput"])
    doc = {"url": url, "seconds": args.seconds, "threads": list(args.threads), "results": results,
           "C": best["throughput"], "Lm": best["p95_ms"],
           "Cp": max(r["throughput"] for r in page)}
    args.out.write_text(json.dumps(doc, indent=1), encoding="utf-8")
    print("C", round(doc["C"], 1), "Lm", round(doc["Lm"], 1), "Cp", round(doc["Cp"], 1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
