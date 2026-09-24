"""홈 쪽을 넘기는 사이 인기 표와 재고가 바뀔 때(#258 A). 쪽 사이 중복 0 · 품절 노출 0 이 유지되는지 본다.

    uv run --with pymysql python3 tools/home_paging_churn_eval.py OUT BASE [USERS]

사용자마다: 1쪽 → (인기 표 recent_7d 의 순위를 무작위로 섞고, 인기 상위 상품 10개를 품절) → 2쪽 → 3쪽.
끝나면 순위와 재고를 되돌린다.
"""
import json
import pathlib
import random
import sys
import time
import urllib.request


def call(method, url, token=None, body=None):
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=json.dumps(body).encode() if body is not None else None, method=method, headers=h)
    with urllib.request.urlopen(req, timeout=30) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def main(out, base, users=30):
    import pymysql
    out = pathlib.Path(out)
    out.mkdir(parents=True, exist_ok=True)
    conn = pymysql.connect(host="127.0.0.1", port=3306, user="root", password="root", database="becommerce", autocommit=True)
    c = conn.cursor()
    rnd = random.Random(7)
    stamp = int(time.time())
    rows = []
    for u in range(int(users)):
        email = f"page-{stamp}-{u}@load.test"
        call("POST", f"{base}/api/v1/members/signup", body={"email": email, "password": "page-load-only-1234"})
        token = call("POST", f"{base}/api/v1/auth/login", body={"username": email, "password": "page-load-only-1234"})["token"]
        p1 = call("GET", f"{base}/api/v1/personalization/homepage", token)

        c.execute("SELECT id, rank_no FROM product_popularity WHERE window_kind = 'recent_7d'")
        ranks = c.fetchall()
        shuffled = [r for _, r in ranks]
        rnd.shuffle(shuffled)
        c.executemany("UPDATE product_popularity SET rank_no = %s WHERE id = %s", [(r, i) for (i, _), r in zip(ranks, shuffled)])
        c.execute("SELECT pp.product_id, s.quantity FROM product_popularity pp JOIN stock s ON s.product_id = pp.product_id "
                  "WHERE pp.window_kind = 'recent_7d' AND s.quantity > 0 ORDER BY pp.rank_no LIMIT 10")
        soldout = c.fetchall()
        c.executemany("UPDATE stock SET quantity = 0, version = version + 1 WHERE product_id = %s", [(p,) for p, _ in soldout])

        pages = [p1]
        cursor = p1.get("nextCursor")
        while cursor and len(pages) < 3:
            nxt = call("GET", f"{base}/api/v1/personalization/homepage?cursor=" + cursor, token)
            pages.append(nxt)
            cursor = nxt.get("nextCursor")
        ids = [[int(i["itemId"]) for r in p["rows"] for i in r["items"]] for p in pages]
        seen, dup = set(), 0
        for pg in ids:
            dup += sum(1 for i in pg if i in seen)
            seen.update(pg)
        later = [i for pg in ids[1:] for i in pg]
        shown_soldout = 0
        if later:
            c.execute(f"SELECT COUNT(*) FROM stock WHERE quantity = 0 AND product_id IN ({','.join(['%s'] * len(later))})", later)
            shown_soldout = c.fetchone()[0]
        rows.append({"user": u, "pages": len(pages), "items": [len(x) for x in ids], "dup": dup, "shown_soldout": shown_soldout,
                     "soldout_set": len(soldout)})

        c.executemany("UPDATE product_popularity SET rank_no = %s WHERE id = %s", [(r, i) for i, r in ranks])
        c.executemany("UPDATE stock SET quantity = %s, version = version + 1 WHERE product_id = %s", [(q, p) for p, q in soldout])
    conn.close()
    doc = {"users": len(rows), "dup": sum(r["dup"] for r in rows), "shown_soldout": sum(r["shown_soldout"] for r in rows),
           "pages_median": sorted(r["pages"] for r in rows)[len(rows) // 2], "rows": rows}
    (out / "home-churn.json").write_text(json.dumps(doc, indent=1))
    print({k: v for k, v in doc.items() if k != "rows"})


if __name__ == "__main__":
    main(*sys.argv[1:])
