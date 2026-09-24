"""검색 색인의 반영 지연을 실제 API 로 잰다(#246). 판정 기준은 이슈에 측정 전에 고정했다.

    uv run --with pymysql python3 tools/search/freshness_eval.py run    OUT MODE BASE[,BASE...] [PER_TYPE]
    python3 tools/search/freshness_eval.py                        report OUT

변경 셋을 DB 에 직접 쓰고, 커밋 시각부터 **모든 인스턴스의** 검색 결과에 반영될 때까지를 50ms 간격으로 확인한다.

- insert: 새 상품(고유 검색어). `q=<검색어>` 첫 페이지에 나타나면 반영
- price:  가격을 500원으로(카탈로그 최저가 1,000원 아래). `q=<이름>&maxPrice=500` 에 나타나면 반영
- stock:  재고를 0 으로. `q=<이름>&inStock=true` 에서 빠지면 반영

끝나면 바꾼 것을 되돌린다(되돌림도 CDC 로 흐르지만 측정은 끝난 뒤다).
"""
import json
import pathlib
import random
import statistics
import sys
import threading
import time
import urllib.parse
import urllib.request

RATE = 20            # 초당 변경 수
POLL = 0.05          # 확인 간격
TIMEOUT = 30.0       # 이 안에 반영 안 되면 놓친 것
SEED = 7
SIZE = 60
INSERT_BASE = 9_000_000_000


def db():
    import pymysql
    return pymysql.connect(host="127.0.0.1", port=3306, user="root", password="root", database="becommerce",
                           autocommit=False)


def search(base, params):
    url = f"{base}/api/v1/products?" + urllib.parse.urlencode({**params, "size": SIZE})
    with urllib.request.urlopen(url, timeout=10) as r:
        return {i["productId"] for i in json.loads(r.read())["items"]}


def token(rnd):
    return "".join(rnd.choice("bcdfghjklmnpqrstvwxz") for _ in range(10))


def pick_targets(conn, bases, per_type):
    """가격·재고 대상: 이름 검색 첫 페이지에 나오는 상품만 쓴다(안 나오면 빠지는 것을 잴 수 없다). 둘은 겹치지 않는다."""
    rnd = random.Random(SEED)
    with conn.cursor() as c:
        c.execute("SELECT p.product_id, p.name FROM products p JOIN stock s ON s.product_id = p.product_id "
                  "WHERE s.quantity > 0 AND p.price > 1000 AND p.product_id < %s", (INSERT_BASE,))
        rows = list(c.fetchall())
    rnd.shuffle(rows)
    chosen = []
    for pid, name in rows:
        if all(pid in search(b, {"q": name, "inStock": "true"}) for b in bases):
            chosen.append((pid, name))
        if len(chosen) == per_type * 2:
            break
    return chosen[:per_type], chosen[per_type:]


def run(out, mode, bases, per_type):
    out = pathlib.Path(out)
    out.mkdir(parents=True, exist_ok=True)
    conn = db()
    price_targets, stock_targets = pick_targets(conn, bases, per_type)
    rnd = random.Random(SEED + 1)
    changes = ([("insert", INSERT_BASE + i, token(rnd)) for i in range(per_type)]
               + [("price", pid, name) for pid, name in price_targets]
               + [("stock", pid, name) for pid, name in stock_targets])
    random.Random(SEED + 2).shuffle(changes)

    with conn.cursor() as c:
        c.execute("SELECT product_id, price FROM products WHERE product_id IN %s", ([p for t, p, _ in changes if t == "price"],))
        old_price = dict(c.fetchall())
        c.execute("SELECT product_id, quantity FROM stock WHERE product_id IN %s", ([p for t, p, _ in changes if t == "stock"],))
        old_qty = dict(c.fetchall())

    results, lock, threads = [], threading.Lock(), []

    def watch(kind, pid, text, committed):
        params = ({"q": text} if kind == "insert" else
                  {"q": text, "maxPrice": 500} if kind == "price" else {"q": text, "inStock": "true"})
        seen = {}
        while time.time() - committed < TIMEOUT and len(seen) < len(bases):
            for b in bases:
                if b in seen:
                    continue
                try:
                    ids = search(b, params)
                except Exception:
                    continue
                if (pid not in ids) if kind == "stock" else (pid in ids):
                    seen[b] = time.time()
            time.sleep(POLL)
        lag = (max(seen.values()) - committed) * 1000 if len(seen) == len(bases) else None
        with lock:
            results.append({"kind": kind, "id": pid, "lag_ms": lag,
                            "per_instance_ms": {b: (t - committed) * 1000 for b, t in seen.items()}})

    started = time.time()
    try:
        for i, (kind, pid, text) in enumerate(changes):
            time.sleep(max(0.0, started + i / RATE - time.time()))
            with conn.cursor() as c:
                if kind == "insert":
                    c.execute("INSERT INTO products(price, product_id, name, category_code, product_type, colour_code, "
                              "description) VALUES (30000, %s, %s, 'ladieswear', 'Dress', 'black', 'freshness probe')",
                              (pid, text + " dress"))
                elif kind == "price":
                    c.execute("UPDATE products SET price = 500 WHERE product_id = %s", (pid,))
                else:
                    c.execute("UPDATE stock SET quantity = 0, version = version + 1 WHERE product_id = %s", (pid,))
            conn.commit()
            committed = time.time()
            t = threading.Thread(target=watch, args=(kind, pid, text, committed))
            t.start()
            threads.append(t)
        write_seconds = time.time() - started
        for t in threads:
            t.join()
    finally:
        with conn.cursor() as c:     # 되돌린다
            c.execute("DELETE FROM products WHERE product_id >= %s", (INSERT_BASE,))
            for pid, price in old_price.items():
                c.execute("UPDATE products SET price = %s WHERE product_id = %s", (price, pid))
            for pid, qty in old_qty.items():
                c.execute("UPDATE stock SET quantity = %s, version = version + 1 WHERE product_id = %s", (qty, pid))
        conn.commit()
        conn.close()

    doc = {"mode": mode, "instances": bases, "per_type": per_type, "rate": RATE, "poll_ms": POLL * 1000,
           "timeout_s": TIMEOUT, "write_seconds": write_seconds, "results": results}
    (out / f"fresh-{mode}.json").write_text(json.dumps(doc, indent=1))
    print(mode, summary(doc))


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))] if xs else None


def summary(doc):
    s = {}
    for kind in ("insert", "price", "stock", "all"):
        rs = [r for r in doc["results"] if kind == "all" or r["kind"] == kind]
        lags = [r["lag_ms"] for r in rs if r["lag_ms"] is not None]
        s[kind] = {"n": len(rs), "missed": len(rs) - len(lags),
                   "p50": pct(lags, 50), "p95": pct(lags, 95), "max": max(lags) if lags else None}
    return s


def fmt(v):
    return "—" if v is None else f"{v:,.0f}ms"


def report(out):
    out = pathlib.Path(out)
    print("| 모드 | 인스턴스 | 변경 | 놓침(30초) | p50 | **p95** | 최대 | 새 상품 p95 | 가격 p95 | 품절 p95 |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for p in sorted(out.glob("fresh-*.json")):
        doc = json.loads(p.read_text())
        s = summary(doc)
        a = s["all"]
        print(f"| `{doc['mode']}` | {len(doc['instances'])} | {a['n']} | {a['missed']} | {fmt(a['p50'])} | **{fmt(a['p95'])}** "
              f"| {fmt(a['max'])} | {fmt(s['insert']['p95'])} | {fmt(s['price']['p95'])} | {fmt(s['stock']['p95'])} |")
    for p in sorted(out.glob("fresh-*.json")):
        doc = json.loads(p.read_text())
        if len(doc["instances"]) > 1:
            spreads = []
            for r in doc["results"]:
                v = list(r["per_instance_ms"].values())
                if len(v) == len(doc["instances"]):
                    spreads.append(max(v) - min(v))
            if spreads:
                print(f"- `{doc['mode']}` 인스턴스 사이 반영 시각 차이: 중앙값 {statistics.median(spreads):,.0f}ms · "
                      f"p95 {pct(spreads, 95):,.0f}ms")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "run":
        run(sys.argv[2], sys.argv[3], [b.rstrip("/") for b in sys.argv[4].split(",")],
            int(sys.argv[5]) if len(sys.argv) > 5 else 200)
    elif cmd == "report":
        report(sys.argv[2])
