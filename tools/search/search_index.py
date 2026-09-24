"""ES·OpenSearch 에 카탈로그를 색인하고 걸린 시간과 크기를 적는다(#236).

    python3 tools/search/search_index.py http://localhost:9200 [index]

색인은 앱이 아니라 여기서 만든다 — 카탈로그가 앱 밖 파이프라인에서 적재되므로 색인도 그 옆에서 만든다.
"""
import argparse
import itertools
import json
import pathlib
import time
import urllib.request

from catalog_dump import load_full, replicate

MAPPING = pathlib.Path(__file__).with_name("products-index.json").read_text()
BATCH = 5000


def call(method, url, body=None, ctype="application/json"):
    data = body.encode() if isinstance(body, str) else body
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": ctype})
    try:
        with urllib.request.urlopen(req, timeout=1800) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        if method == "DELETE" and e.code == 404:
            return {}
        raise SystemExit(f"{method} {url} → {e.code} {e.read()[:300]}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base")
    ap.add_argument("index", nargs="?", default="products")
    ap.add_argument("--replicate", type=int, default=1, help="규모 실험: 실제 행을 N 번 복제(#244)")
    ap.add_argument("--live-refresh", action="store_true",
                    help="적재 중에도 refresh 를 켠다(#236 의 절차). 세그먼트가 엔진마다 다르게 생겨 내부 문서 순서가 갈린다(#262)")
    a = ap.parse_args()
    base, index = a.base.rstrip("/"), a.index
    rows = replicate(load_full(), a.replicate)          # 제너레이터 — 300만 행을 메모리에 다 올리지 않는다
    call("DELETE", f"{base}/{index}")
    call("PUT", f"{base}/{index}", MAPPING)
    if not a.live_refresh:
        call("PUT", f"{base}/{index}/_settings", json.dumps({"index": {"refresh_interval": "-1"}}))   # 적재 중에는 끈다
    started = time.time()
    while True:
        batch = list(itertools.islice(rows, BATCH))
        if not batch:
            break
        lines = []
        for r in batch:
            lines.append(json.dumps({"index": {"_index": index, "_id": str(r["product_id"])}}))
            doc = {k: r[k] for k in ("product_id", "name", "product_type", "description", "category_code", "subcategory_code",
                                     "colour_code", "price", "in_stock")}
            doc["product_type_kw"] = r["product_type"]
            lines.append(json.dumps(doc, ensure_ascii=False))
        res = call("POST", f"{base}/_bulk", "\n".join(lines) + "\n", "application/x-ndjson")
        if res.get("errors"):
            raise SystemExit("bulk 오류: " + json.dumps(res)[:300])
    call("PUT", f"{base}/{index}/_settings", json.dumps({"index": {"refresh_interval": "1s"}}))
    call("POST", f"{base}/{index}/_refresh")
    elapsed = time.time() - started
    call("POST", f"{base}/{index}/_forcemerge?max_num_segments=1")
    call("POST", f"{base}/{index}/_refresh")
    stats = call("GET", f"{base}/{index}/_stats/store,docs")
    total = stats["indices"][index]["total"]
    print(json.dumps({"docs": total["docs"]["count"], "index_seconds": round(elapsed, 2),
                      "store_bytes": total["store"]["size_in_bytes"]}))


if __name__ == "__main__":
    main()
