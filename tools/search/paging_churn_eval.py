"""쪽을 넘기는 사이 색인이 바뀔 때 검색 결과가 중복·누락되는가(#258). 판정 기준은 이슈에 측정 전에 고정했다.

    uv run --with pymysql python3 tools/search/paging_churn_eval.py queries OUT BASE
    uv run --with pymysql python3 tools/search/paging_churn_eval.py run     OUT NAME BASE MODE
        MODE = none(변경 없음) · random(카탈로그 무작위 상품의 가격) · targeted(그 쿼리 결과 상품의 가격) · insert(새 상품)

쿼리마다: 스냅숏(1~3쪽을 연달아) → 1쪽 → 3초 변경 → 2쪽 → 3초 변경 → 3쪽.
- 중복: 앞 쪽에 이미 나온 상품이 뒤 쪽에 다시 나온 수
- 누락: 스냅숏 1~3쪽에 있었는데 넘기는 동안 끝내 안 나온 수
가격 변경은 검색어·필터와 무관하다(필터를 걸지 않는다). 쿼리가 끝나면 가격을 되돌리고 넣은 상품을 지운다.
"""
import json
import pathlib
import random
import sys
import threading
import time
import urllib.parse
import urllib.request

SIZE = 20
PAGES = 3
RATE = 20
PAUSE = 3.0
SEED = 7
N = 50
INSERT_BASE = 9_200_000_000


def page(base, q, p):
    url = f"{base}/api/v1/products?" + urllib.parse.urlencode({"q": q, "page": p, "size": SIZE})
    with urllib.request.urlopen(url, timeout=10) as r:
        body = json.loads(r.read())
    return [i["productId"] for i in body["items"]], body["totalElements"]


def db():
    import pymysql
    return pymysql.connect(host="127.0.0.1", port=3306, user="root", password="root", database="becommerce", autocommit=True)


def queries(out, base):
    sys.path.insert(0, str(pathlib.Path(__file__).parent))
    import search_quality_eval
    out = pathlib.Path(out)
    out.mkdir(parents=True, exist_ok=True)
    if not (out / "all-queries.json").exists():
        search_quality_eval.build_queries(out / "all-queries.json")
    cands = [q["q"] for q in json.loads((out / "all-queries.json").read_text()) if q["family"] == "A"]
    ok = [q for q in cands if page(base, q, 0)[1] >= SIZE * PAGES]
    chosen = random.Random(SEED).sample(ok, min(N, len(ok)))
    (out / "paging-queries.json").write_text(json.dumps(chosen))
    print("쿼리", len(chosen), "(후보", len(cands), "· 3쪽 이상", len(ok), ")")


def run(out, name, base, mode):
    out = pathlib.Path(out)
    qs = json.loads((out / "paging-queries.json").read_text())
    conn = db()
    c = conn.cursor()
    c.execute("SELECT product_id FROM products WHERE product_id < %s", (INSERT_BASE,))
    catalog = [r[0] for r in c.fetchall()]
    rnd = random.Random(f"{SEED}-{name}")
    results = []
    for qi, q in enumerate(qs):
        snap, pages = [], []
        for p in range(PAGES):
            snap += page(base, q, p)[0]
        snap_more = snap + sum((page(base, q, p)[0] for p in range(PAGES, PAGES * 2)), [])
        touched = {}
        inserted = []

        def churn(seconds):
            end = time.time() + seconds
            k = 0
            while time.time() < end:
                time.sleep(1 / RATE)
                if mode == "none":
                    continue
                if mode == "insert":
                    pid = INSERT_BASE + qi * 1000 + len(inserted)
                    c.execute("INSERT INTO products(price, product_id, name, category_code, product_type, colour_code, description) "
                              "VALUES (30000, %s, %s, 'ladieswear', %s, 'black', %s)", (pid, q, q.title(), q))
                    inserted.append(pid)
                    continue
                pool = snap_more if mode == "targeted" else catalog
                pid = rnd.choice(pool)
                if pid not in touched:
                    c.execute("SELECT price FROM products WHERE product_id = %s", (pid,))
                    row = c.fetchone()
                    if not row:
                        continue
                    touched[pid] = row[0]
                k += 1
                c.execute("UPDATE products SET price = %s WHERE product_id = %s", (touched[pid] + k, pid))

        seen = []
        for p in range(PAGES):
            if p > 0:
                churn(PAUSE)
            seen.append(page(base, q, p)[0])
        flat = [i for pg in seen for i in pg]
        dup = sum(1 for idx, pg in enumerate(seen) for i in pg if any(i in prev for prev in seen[:idx]))
        miss = len(set(snap) - set(flat))
        results.append({"q": q, "dup": dup, "miss": miss, "changes": len(touched) if mode != "insert" else len(inserted)})
        for pid, price in touched.items():
            c.execute("UPDATE products SET price = %s WHERE product_id = %s", (price, pid))
        if inserted:
            c.execute(f"DELETE FROM products WHERE product_id IN ({','.join(['%s'] * len(inserted))})", inserted)
        time.sleep(2)   # 되돌림이 색인에 들어간 뒤 다음 쿼리
    conn.close()
    doc = {"name": name, "mode": mode, "queries": len(results), "dup": sum(r["dup"] for r in results),
           "miss": sum(r["miss"] for r in results), "queries_with_issue": sum(1 for r in results if r["dup"] or r["miss"]),
           "results": results}
    (out / f"paging-{name}.json").write_text(json.dumps(doc, indent=1))
    print(name, {k: v for k, v in doc.items() if k != "results"})


def report(out):
    out = pathlib.Path(out)
    print("| 실행 | 변경 | 쿼리 | 중복 | 누락 | 문제 있는 쿼리 |")
    print("|---|---|---:|---:|---:|---:|")
    for p in sorted(out.glob("paging-*.json")):
        if p.name == "paging-queries.json":
            continue
        d = json.loads(p.read_text())
        print(f"| {d['name']} | {d['mode']} | {d['queries']} | {d['dup']} | {d['miss']} | {d['queries_with_issue']} |")


if __name__ == "__main__":
    cmd, a = sys.argv[1], sys.argv[2:]
    {"queries": queries, "run": run, "report": report}[cmd](*a)
