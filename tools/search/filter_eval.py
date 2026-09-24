"""검색어 + 필터·패싯을 실제 API 로 재고 정답과 맞춘다(#244). 판정 기준은 이슈에 측정 전에 고정했다.

    python3 tools/search/filter_eval.py queries  OUT                       # OUT/filter-queries.json, OUT/catalog.tsv
    python3 tools/search/filter_eval.py run      OUT BASE MODE             # OUT/MODE.json — /products 와 /products/facets
    python3 tools/search/filter_eval.py es-match OUT ES_URL                # OUT/matches-elasticsearch.json
    python3 tools/search/filter_eval.py report   OUT                       # 표 출력

정답: 엔진의 <b>필터 없는</b> 전체 일치 집합(Lucene 은 LuceneScaleBenchmark matches, ES 는 scroll)에 필터를 여기서
직접 적용한 것. 비교 대상인 엔진 필터 경로와 코드를 나누지 않는다.
"""
import collections
import concurrent.futures
import json
import pathlib
import random
import sys
import time
import urllib.parse
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from catalog_dump import load_full, write_tsv  # noqa: E402

SEED = 7
SIZE = 20
COMBOS = ("price", "colour", "category", "stock")
# 모드 → 정답 집합을 가진 엔진. 후보 자르기는 Lucene 상위 500 을 거르므로 정답은 Lucene 것이다
TRUTH_OF = {"lucene": "lucene", "lucene-candidates": "lucene", "elasticsearch": "elasticsearch"}


# ---------- 쿼리셋 ----------

def build(out):
    out = pathlib.Path(out)
    rows = load_full()
    write_tsv(rows, out / "catalog.tsv")
    (out / "catalog.json").write_text(json.dumps(rows, ensure_ascii=False))
    import search_quality_eval
    if not (out / "queries.json").exists():
        search_quality_eval.build_queries(out / "queries.json")
    base = [q for q in json.loads((out / "queries.json").read_text()) if q["family"] in ("A", "B", "C")]

    prices = sorted(r["price"] for r in rows)
    median = prices[len(prices) // 2]
    colours = [c for c, _ in collections.Counter(r["colour_code"] for r in rows if r["colour_code"]).most_common(8)]
    cats = [c for c, _ in collections.Counter(r["category_code"] for r in rows if r["category_code"]).most_common(5)]
    rnd = random.Random(SEED)
    queries = []
    for i, q in enumerate(base):
        combo = COMBOS[i % 4]
        f = {"price": {"maxPrice": median}, "colour": {"colour": rnd.choice(colours)},
             "category": {"category": rnd.choice(cats)}, "stock": {"inStock": True}}[combo]
        queries.append({"family": q["family"], "q": q["q"], "combo": combo, "filters": f})
    (out / "filter-queries.json").write_text(json.dumps(queries, ensure_ascii=False))
    print("쿼리", len(queries), dict(collections.Counter(q["combo"] for q in queries)),
          "중앙값", median, "색상", colours, "대분류", cats)


# ---------- 실행 ----------

def get(url):
    started = time.perf_counter()
    with urllib.request.urlopen(url, timeout=30) as r:
        body = json.loads(r.read())
    return body, (time.perf_counter() - started) * 1000


def params(q):
    p = {"q": q["q"]}
    p.update({k: str(v).lower() if isinstance(v, bool) else v for k, v in q["filters"].items()})
    return p


def products(base, q):
    return get(f"{base}/api/v1/products?" + urllib.parse.urlencode({**params(q), "size": SIZE}))


def facets(base, q):
    return get(f"{base}/api/v1/products/facets?" + urllib.parse.urlencode(params(q)))


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))] if xs else None


def run(out, base, mode):
    out = pathlib.Path(out)
    queries = json.loads((out / "filter-queries.json").read_text())
    for q in queries[:5]:
        products(base, q)
        facets(base, q)
    results, lat_p, lat_f = [], [], []
    for q in queries:
        pb, pm = products(base, q)
        fb, fm = facets(base, q)
        lat_p.append(pm)
        lat_f.append(fm)
        results.append({"q": q["q"], "combo": q["combo"], "total": pb["totalElements"],
                        "ids": [i["productId"] for i in pb["items"]],
                        "colours": {c["code"]: c["count"] for c in fb["colours"]},
                        "types": {c["code"]: c["count"] for c in fb["productTypes"]}})
    c10p, c10f = [], []
    with concurrent.futures.ThreadPoolExecutor(10) as pool:
        for _, ms in pool.map(lambda q: products(base, q), queries):
            c10p.append(ms)
        for _, ms in pool.map(lambda q: facets(base, q), queries):
            c10f.append(ms)
    lat = {"products_c1_p95": pct(lat_p, 95), "products_c10_p95": pct(c10p, 95),
           "facets_c1_p95": pct(lat_f, 95), "facets_c10_p95": pct(c10f, 95)}
    (out / f"{mode}.json").write_text(json.dumps({"mode": mode, "results": results, "latency": lat}, ensure_ascii=False))
    print(mode, {k: round(v, 1) for k, v in lat.items()})


def es_match(out, es):
    """ES 의 필터 없는 전체 일치 집합. 앱과 같은 텍스트 질의(EngineQuery.textQuery)를 scroll 로 끝까지 받는다."""
    out = pathlib.Path(out)
    queries = json.loads((out / "filter-queries.json").read_text())
    fields = ["name^2", "product_type^2", "description"]
    res = {}
    for q in queries:
        if q["q"] in res:
            continue
        body = {"size": 5000, "_source": False, "sort": ["_doc"], "query": {"bool": {"should": [
            {"multi_match": {"query": q["q"], "fields": fields, "type": "best_fields", "boost": 3.0}},
            {"multi_match": {"query": q["q"], "fields": fields, "type": "best_fields", "fuzziness": "AUTO"}}]}}}
        page = post(f"{es}/products/_search?scroll=1m", body)
        ids = []
        while page["hits"]["hits"]:
            ids += [int(h["_id"]) for h in page["hits"]["hits"]]
            page = post(f"{es}/_search/scroll", {"scroll": "1m", "scroll_id": page["_scroll_id"]})
        res[q["q"]] = ids
    (out / "matches-elasticsearch.json").write_text(json.dumps(res))
    print("ES 일치 집합", len(res), "쿼리")


def post(url, body):
    req = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())


# ---------- 정답과 표 ----------

def keep(r, f, skip=None):
    if skip != "category" and f.get("category") and r["category_code"] != f["category"]:
        return False
    if skip != "colour" and f.get("colour") and r["colour_code"] != f["colour"]:
        return False
    if skip != "productType" and f.get("productType") and r["product_type"] != f["productType"]:
        return False
    if f.get("minPrice") is not None and r["price"] < f["minPrice"]:
        return False
    if f.get("maxPrice") is not None and r["price"] > f["maxPrice"]:
        return False
    if f.get("inStock") and not r["in_stock"]:
        return False
    return True


def truth(catalog, matched, f):
    rows = [catalog[i] for i in matched if i in catalog]
    total = sum(keep(r, f) for r in rows)
    colours = collections.Counter(r["colour_code"] for r in rows if r["colour_code"] and keep(r, f, "colour"))
    types = collections.Counter(r["product_type"] for r in rows if r["product_type"] and keep(r, f, "productType"))
    return total, dict(colours), dict(types)


def report(out):
    out = pathlib.Path(out)
    catalog = {r["product_id"]: r for r in json.loads((out / "catalog.json").read_text())}
    queries = json.loads((out / "filter-queries.json").read_text())
    print("| 모드 | 쿼리 | 결과 수 일치 | 색상 패싯 일치 | 종류 패싯 일치 | 결과 수 합(응답/정답) | 잃은 비율 "
          "| 0건인데 정답 있음 | /products p95 c1·c10 | /facets p95 c1·c10 |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    detail = {}
    for mode, eng in TRUTH_OF.items():
        p, m = out / f"{mode}.json", out / f"matches-{eng}.json"
        if not p.exists() or not m.exists():
            continue
        run_ = json.loads(p.read_text())
        matches = json.loads(m.read_text())
        ok_t = ok_c = ok_y = 0
        got = want = empty_but = 0
        by_combo = collections.defaultdict(lambda: [0, 0])
        mism = []
        for q, r in zip(queries, run_["results"]):
            t, c, y = truth(catalog, matches[q["q"]], q["filters"])
            ok_t += r["total"] == t
            ok_c += r["colours"] == c
            ok_y += r["types"] == y
            got += r["total"]
            want += t
            empty_but += (r["total"] == 0 and t > 0)
            by_combo[q["combo"]][0] += r["total"]
            by_combo[q["combo"]][1] += t
            if r["total"] != t or r["colours"] != c or r["types"] != y:
                mism.append({"q": q["q"], "combo": q["combo"], "total": [r["total"], t],
                             "colour_sum": [sum(r["colours"].values()), sum(c.values())],
                             "type_sum": [sum(r["types"].values()), sum(y.values())]})
        n = len(queries)
        lat = run_["latency"]
        loss = 1 - got / want if want else 0
        print(f"| `{mode}` | {n} | {ok_t}/{n} | {ok_c}/{n} | {ok_y}/{n} | {got:,}/{want:,} | {loss:.1%} | {empty_but} "
              f"| {lat['products_c1_p95']:.0f}·{lat['products_c10_p95']:.0f}ms "
              f"| {lat['facets_c1_p95']:.0f}·{lat['facets_c10_p95']:.0f}ms |")
        detail[mode] = {"by_combo": {k: {"got": v[0], "want": v[1], "loss": 1 - v[0] / v[1] if v[1] else 0}
                                     for k, v in by_combo.items()}, "mismatches": mism[:20], "mismatch_count": len(mism)}
    print()
    for mode, d in detail.items():
        if mode == "first_page":
            continue
        print(f"- `{mode}` 조합별 잃은 비율:",
              ", ".join(f"{k} {v['loss']:.1%}({v['got']:,}/{v['want']:,})" for k, v in sorted(d["by_combo"].items())),
              f"· 불일치 쿼리 {d['mismatch_count']}")
    a, b = out / "lucene.json", out / "lucene-candidates.json"
    if a.exists() and b.exists():   # 후보 자르기가 화면에서 어디서 틀리는가 — 첫 페이지인가, 개수인가
        ra, rb = json.loads(a.read_text())["results"], json.loads(b.read_text())["results"]
        same = sum(x["ids"] == y["ids"] for x, y in zip(ra, rb))
        short = sum(len(y["ids"]) < len(x["ids"]) for x, y in zip(ra, rb))
        print(f"- 첫 페이지({SIZE}개): 후보 자르기가 엔진 필터와 같은 쿼리 {same}/{len(ra)}, 덜 찬 쿼리 {short}")
        detail["first_page"] = {"same": same, "short": short, "n": len(ra)}
    (out / "report-detail.json").write_text(json.dumps(detail, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "queries":
        build(sys.argv[2])
    elif cmd == "run":
        run(sys.argv[2], sys.argv[3].rstrip("/"), sys.argv[4])
    elif cmd == "es-match":
        es_match(sys.argv[2], sys.argv[3].rstrip("/"))
    elif cmd == "report":
        report(sys.argv[2])
