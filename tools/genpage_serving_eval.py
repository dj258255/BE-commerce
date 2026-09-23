"""GenPage 서빙 실측(#238). 두 층에서 잰다.

    python3 tools/genpage_serving_eval.py server MODEL_URL OUT.json      # 모델 서버 직접: 디코딩 방식별 p95
    python3 tools/genpage_serving_eval.py home   APP_URL USERS OUT.json  # 앱을 거쳐 홈 2쪽: p95 · 규칙 위반

이력은 합성이다 — 대분류마다 상품을 골라 사용자마다 5개를 섞는다(seed 7). 실제 세션이 아니다.
"""
import concurrent.futures
import json
import random
import statistics
import subprocess
import sys
import time
import urllib.request

SEED = 7


def products_by_category():
    out = subprocess.run(["docker", "exec", "pay-mysql-1", "mysql", "-N", "-ubecommerce", "-pbecommerce", "becommerce", "-e",
                          "select category_code, product_id from product_popularity pp join products p using(product_id) "
                          "where category_code is not null union select category_code, product_id from products "
                          "where category_code is not null and product_id % 997 = 0"],
                         capture_output=True, text=True, check=True).stdout
    by = {}
    for line in out.strip().split("\n"):
        c, p = line.split("\t")
        by.setdefault(c, []).append(int(p))
    return by


def histories(n):
    by = products_by_category()
    rng = random.Random(SEED)
    cats = sorted(by)
    return [[rng.choice(by[rng.choice(cats)]) for _ in range(5)] for _ in range(n)]


def post(url, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    t = time.perf_counter()
    with urllib.request.urlopen(req, timeout=30) as r:
        data = json.loads(r.read())
    return data, (time.perf_counter() - t) * 1000


def p95(xs):
    xs = sorted(xs)
    return xs[int(0.95 * (len(xs) - 1))]


def server(base, out):
    hs = histories(200)
    result = {}
    for prefix in (0, 2, 8):
        for conc in (1, 4):
            def one(h):
                d, ms = post(base + "/page", {"history": h, "exclude": [], "rows": 3, "items_per_row": 8, "prefix": prefix})
                return ms, d["forward_passes"], d["violations"]
            with concurrent.futures.ThreadPoolExecutor(conc) as pool:
                rs = list(pool.map(one, hs))
            key = f"prefix{prefix}-c{conc}"
            result[key] = {"p50": statistics.median(r[0] for r in rs), "p95": p95([r[0] for r in rs]),
                           "passes": statistics.mean(r[1] for r in rs), "violations": sum(r[2] for r in rs)}
            print(key, result[key], flush=True)
    rec = [post(base + "/recommend", {"history": h, "k": 12})[1] for h in hs]
    result["recommend-c1"] = {"p50": statistics.median(rec), "p95": p95(rec)}
    print("recommend-c1", result["recommend-c1"])
    json.dump(result, open(out, "w"), indent=1)


def call(method, url, token=None, body=None):
    req = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {})})
    t = time.perf_counter()
    with urllib.request.urlopen(req, timeout=30) as r:
        raw = r.read()
    return (json.loads(raw) if raw else None), (time.perf_counter() - t) * 1000


def category_of(ids):
    if not ids:
        return {}
    out = subprocess.run(["docker", "exec", "pay-mysql-1", "mysql", "-N", "-ubecommerce", "-pbecommerce", "becommerce", "-e",
                          f"select product_id, category_code from products where product_id in ({','.join(map(str, ids))})"],
                         capture_output=True, text=True, check=True).stdout
    return {int(a): b for a, b in (line.split("\t") for line in out.strip().split("\n") if line)}


def home(base, users, out):
    hs = histories(users)
    stamp = int(time.time())
    lat2, strategies, dup, cat_mismatch, items = [], {}, 0, 0, 0
    for u, h in enumerate(hs):
        email = f"genpage-{stamp}-{u}@load.test"
        call("POST", base + "/api/v1/members/signup", body={"email": email, "password": "genpage-load-only-1234"})
        token = call("POST", base + "/api/v1/auth/login", body={"username": email, "password": "genpage-load-only-1234"})[0]["token"]
        for seq, item in enumerate(reversed(h), start=1):
            call("POST", base + "/api/v1/personalization/activity", token, {"itemId": item, "type": "VIEW", "seq": seq})
        time.sleep(0.05)
        p1, _ = call("GET", base + "/api/v1/personalization/homepage", token)
        p2, ms = call("GET", base + "/api/v1/personalization/homepage?cursor=" + p1["nextCursor"], token)
        lat2.append(ms)
        shown1 = {int(i["itemId"]) for r in p1["rows"] for i in r["items"]}
        ids2 = [int(i["itemId"]) for r in p2["rows"] for i in r["items"]]
        dup += len(shown1 & set(ids2)) + (len(ids2) - len(set(ids2)))
        cats = category_of(ids2)
        for r in p2["rows"]:
            strategies[r["strategy"]] = strategies.get(r["strategy"], 0) + 1
            for i in r["items"]:
                items += 1
                cat_mismatch += cats.get(int(i["itemId"])) != r["id"].removeprefix("cat:")
    summary = {"users": users, "page2_p50": statistics.median(lat2), "page2_p95": p95(lat2),
               "strategies": strategies, "duplicates": dup, "category_mismatch": cat_mismatch, "items": items}
    print(json.dumps(summary, ensure_ascii=False))
    json.dump(summary, open(out, "w"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    if sys.argv[1] == "server":
        server(sys.argv[2].rstrip("/"), sys.argv[3])
    else:
        home(sys.argv[2].rstrip("/"), int(sys.argv[3]), sys.argv[4])
