"""GenPage 모델 서버의 용량을 직접 잰다(#271). 닫힌 루프 — 스레드마다 응답을 받으면 바로 다음을 보낸다.

    python3 tools/genpage_capacity.py ITEMS.json OUT.json [SECONDS]

ITEMS.json 은 모델 어휘 안의 상품 id 목록이다(앱 부하와 같은 이력을 쓰려고 run 스크립트가 만든다).
/recommend 는 이력 20개 · k 12, /page 는 이력 20개 · 제외 24개 · 3행 × 8개 · prefix 2 로 부른다(앱 기본값).
"""
import json
import random
import sys
import threading
import time
import urllib.request

URL = "http://127.0.0.1:8765"
ITEMS = json.loads(open(sys.argv[1]).read())
OUT = sys.argv[2]
SECONDS = float(sys.argv[3]) if len(sys.argv) > 3 else 20.0


def body(path, rnd):
    history = rnd.sample(ITEMS, 20)
    if path == "/recommend":
        return {"history": history, "k": 12}
    return {"history": history, "exclude": rnd.sample(ITEMS, 24), "exclude_categories": [], "rows": 3,
            "items_per_row": 8, "prefix": 2}


def run(path, threads):
    lat, errors, stop = [], [0], time.perf_counter() + SECONDS
    lock = threading.Lock()

    def worker(seed):
        rnd = random.Random(seed)
        while time.perf_counter() < stop:
            data = json.dumps(body(path, rnd)).encode()
            req = urllib.request.Request(URL + path, data, {"Content-Type": "application/json"})
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


def main():
    for path in ("/recommend", "/page"):          # 서버를 데운다(첫 호출의 torch 초기화가 p95 에 들어가지 않게)
        for _ in range(20):
            data = json.dumps(body(path, random.Random(0))).encode()
            urllib.request.urlopen(urllib.request.Request(URL + path, data, {"Content-Type": "application/json"})).read()
    results = []
    for path in ("/recommend", "/page"):
        for threads in (1, 2, 4, 8):
            r = run(path, threads)
            results.append(r)
            print(json.dumps(r))
    rec = [r for r in results if r["path"] == "/recommend"]
    page = [r for r in results if r["path"] == "/page"]
    doc = {"results": results,
           "C": max(r["throughput"] for r in rec),
           "Lm": next(r["p95_ms"] for r in rec if r["threads"] == 4),
           "Cp": max(r["throughput"] for r in page)}
    open(OUT, "w").write(json.dumps(doc, indent=1))
    print("C", round(doc["C"], 1), "Lm", round(doc["Lm"], 1), "Cp", round(doc["Cp"], 1))


if __name__ == "__main__":
    main()
