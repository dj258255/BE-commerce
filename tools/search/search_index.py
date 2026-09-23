"""ES·OpenSearch 에 카탈로그를 색인하고 걸린 시간과 크기를 적는다(#236).

    python3 tools/search/search_index.py http://localhost:9200 [index]

색인은 앱이 아니라 여기서 만든다 — 카탈로그가 앱 밖 파이프라인에서 적재되므로 색인도 그 옆에서 만든다.
"""
import json
import pathlib
import sys
import time
import urllib.request

from catalog_dump import load_products

MAPPING = pathlib.Path(__file__).with_name("products-index.json").read_text()
BATCH = 5000


def call(method, url, body=None, ctype="application/json"):
    data = body.encode() if isinstance(body, str) else body
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": ctype})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        if method == "DELETE" and e.code == 404:
            return {}
        raise SystemExit(f"{method} {url} → {e.code} {e.read()[:300]}")


def main():
    base = sys.argv[1].rstrip("/")
    index = sys.argv[2] if len(sys.argv) > 2 else "products"
    rows = load_products()
    call("DELETE", f"{base}/{index}")
    call("PUT", f"{base}/{index}", MAPPING)
    started = time.time()
    for i in range(0, len(rows), BATCH):
        lines = []
        for r in rows[i:i + BATCH]:
            lines.append(json.dumps({"index": {"_index": index, "_id": str(r["product_id"])}}))
            lines.append(json.dumps({k: r[k] for k in ("name", "product_type", "description")}, ensure_ascii=False))
        res = call("POST", f"{base}/_bulk", "\n".join(lines) + "\n", "application/x-ndjson")
        if res.get("errors"):
            raise SystemExit("bulk 오류: " + json.dumps(res)[:300])
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
