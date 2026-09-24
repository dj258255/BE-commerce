"""ES 의 같은 규모·같은 질의 지연(#244). LuceneScaleBenchmark 와 같은 요청 셋(검색·검색+필터·패싯)을 같은 동시성으로 보낸다.

    python3 tools/search/es_scale_bench.py ES_URL OUT/filter-queries.json OUT/es-scale-N.json

앱을 거치지 않고 엔진에 직접 보낸다 — Lucene 벤치마크도 앱 기동·DB·HTTP 를 뺐다. 본문은 EngineQuery 와 같다.
"""
import concurrent.futures
import json
import sys
import time
import urllib.request

FIELDS = ["name^2", "product_type^2", "description"]
ROUNDS = 3


def text_query(q):
    return {"bool": {"should": [
        {"multi_match": {"query": q, "fields": FIELDS, "type": "best_fields", "boost": 3.0}},
        {"multi_match": {"query": q, "fields": FIELDS, "type": "best_fields", "fuzziness": "AUTO"}}]}}


def clauses(f, skip=None):
    c = []
    if f.get("category"):
        c.append({"term": {"category_code": f["category"]}})
    if f.get("colour") and skip != "colour":
        c.append({"term": {"colour_code": f["colour"]}})
    if f.get("productType") and skip != "productType":
        c.append({"term": {"product_type_kw": f["productType"]}})
    if f.get("minPrice") is not None or f.get("maxPrice") is not None:
        r = {}
        if f.get("minPrice") is not None:
            r["gte"] = f["minPrice"]
        if f.get("maxPrice") is not None:
            r["lte"] = f["maxPrice"]
        c.append({"range": {"price": r}})
    if f.get("inStock"):
        c.append({"term": {"in_stock": True}})
    return c


def bodies(q):
    t, f = q["q"], q["filters"]
    return [
        {"from": 0, "size": 20, "_source": False, "track_total_hits": True, "query": text_query(t)},
        {"from": 0, "size": 20, "_source": False, "track_total_hits": True,
         "query": {"bool": {"must": [text_query(t)], "filter": clauses(f)}}},
        {"size": 0, "track_total_hits": True, "query": text_query(t), "aggs": {
            "colour": {"filter": {"bool": {"filter": clauses(f, "colour")}},
                       "aggs": {"v": {"terms": {"field": "colour_code", "size": 100}}}},
            "type": {"filter": {"bool": {"filter": clauses(f, "productType")}},
                     "aggs": {"v": {"terms": {"field": "product_type_kw", "size": 500}}}}}},
    ]


def post(url, body):
    req = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json"})
    s = time.perf_counter()
    with urllib.request.urlopen(req, timeout=60) as r:
        r.read()
    return (time.perf_counter() - s) * 1000


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))]


def main(es, queries_path, out_path):
    es = es.rstrip("/")
    url = f"{es}/products/_search?request_cache=false"      # 같은 요청의 캐시 적중을 재지 않는다
    work = [b for q in json.load(open(queries_path)) for b in bodies(q)]
    for b in work:
        post(url, b)
    c1 = [post(url, b) for b in work]
    c10 = []
    with concurrent.futures.ThreadPoolExecutor(10) as pool:
        for _ in range(ROUNDS):
            c10 += list(pool.map(lambda b: post(url, b), work))
    out = {"requests_c1": len(c1), "c1_p50_ms": pct(c1, 50), "c1_p95_ms": pct(c1, 95),
           "requests_c10": len(c10), "c10_p50_ms": pct(c10, 50), "c10_p95_ms": pct(c10, 95), "c10_p99_ms": pct(c10, 99)}
    json.dump(out, open(out_path, "w"), indent=1)
    print(out)


if __name__ == "__main__":
    main(*sys.argv[1:4])
