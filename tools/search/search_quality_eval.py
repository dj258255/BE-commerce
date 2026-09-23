"""검색 품질을 실제 API 로 잰다(#236). 쿼리셋과 판정 기준은 이슈에 측정 전에 고정했다.

    python3 tools/search/search_quality_eval.py queries  OUT/queries.json        # 쿼리셋 생성(seed 7)
    python3 tools/search/search_quality_eval.py run      OUT/queries.json BASE ENGINE OUT/ENGINE.json
    python3 tools/search/search_quality_eval.py report   OUT                     # 표 출력

API 는 `GET /api/v1/products?q=&size=10` 이다 — 정렬을 주지 않아 검색 구현의 순서가 그대로 나온다.
재는 것과 나가는 것이 같아야 해서 검색 구현을 직접 부르지 않는다.
"""
import collections
import concurrent.futures
import json
import math
import pathlib
import random
import statistics
import sys
import time
import urllib.parse
import urllib.request

K = 10
SEED = 7
FAMILIES = {"A": "종류 그대로", "B": "종류 어형", "C": "종류 오타", "D": "이름 그대로", "E": "이름 오타"}
QUALITY_AXIS = ("B", "C", "E")          # 판정 기준 1 이 보는 축: 오타·어형


# ---------- 쿼리셋 ----------

def inflect(word: str) -> str:
    """단수↔복수. 마지막 단어만 바꾼다. 규칙은 이슈에 적은 그대로다."""
    if word.endswith("s") and not word.endswith("ss"):
        return word[:-1]
    if word.endswith(("s", "x", "ch", "sh")):
        return word + "es"
    return word + "s"


def typo(text: str):
    """가장 긴 단어(5자 이상)의 가운데 인접 두 글자를 바꾼다. 없으면 None."""
    words = text.split(" ")
    i = max(range(len(words)), key=lambda j: len(words[j]))
    w = words[i]
    if len(w) < 5:
        return None
    m = len(w) // 2
    swapped = w[:m - 1] + w[m] + w[m - 1] + w[m + 1:]
    if swapped == w:
        return None
    words[i] = swapped
    return " ".join(words)


def build_queries(out_path):
    sys.path.insert(0, str(pathlib.Path(__file__).parent))
    from catalog_dump import load_products
    rows = load_products()

    by_type = collections.defaultdict(list)
    by_name = collections.defaultdict(list)
    for r in rows:
        by_type[r["product_type"]].append(r["product_id"])
        by_name[r["name"].lower()].append(r["product_id"])

    # 종류 → 쿼리. 서로 다른 종류가 같은 쿼리가 되면(hat/beanie, hat/brim → hat) 정답을 합친다
    type_queries = collections.defaultdict(set)
    for t, ids in by_type.items():
        if len(ids) < 100 or t.lower().startswith("other"):
            continue
        type_queries[t.split("/")[0].strip().lower()].update(ids)

    queries = []
    for q, rel in sorted(type_queries.items()):
        rel = sorted(rel)
        queries.append({"family": "A", "q": q, "relevant": rel})
        words = q.split(" ")
        words[-1] = inflect(words[-1])
        queries.append({"family": "B", "q": " ".join(words), "relevant": rel})
        t = typo(q)
        if t:
            queries.append({"family": "C", "q": t, "relevant": rel})

    names = sorted(n for n, ids in by_name.items() if len(ids) >= 2)
    for n in random.Random(SEED).sample(names, 200):
        rel = sorted(by_name[n])
        queries.append({"family": "D", "q": n, "relevant": rel})
        t = typo(n)
        if t:
            queries.append({"family": "E", "q": t, "relevant": rel})

    pathlib.Path(out_path).write_text(json.dumps(queries, ensure_ascii=False))
    count = collections.Counter(q["family"] for q in queries)
    print("쿼리셋", dict(sorted(count.items())), "총", len(queries))


# ---------- 실행 ----------

def fetch(base, q):
    url = f"{base}/api/v1/products?" + urllib.parse.urlencode({"q": q, "size": K})
    started = time.perf_counter()
    with urllib.request.urlopen(url, timeout=30) as r:
        body = json.loads(r.read())
    return [item["productId"] for item in body["items"]], (time.perf_counter() - started) * 1000


def ndcg(ids, relevant):
    rel = set(relevant)
    dcg = sum(1 / math.log2(i + 2) for i, pid in enumerate(ids[:K]) if pid in rel)
    ideal = sum(1 / math.log2(i + 2) for i in range(min(len(rel), K)))
    return dcg / ideal if ideal else 0.0


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))]


def run(queries_path, base, engine, out_path):
    queries = json.loads(pathlib.Path(queries_path).read_text())
    for q in queries[:5]:                     # 워밍업 — JIT 와 커넥션 풀
        fetch(base, q["q"])
    results, lat1 = [], []
    for q in queries:                         # 동시성 1: 품질과 지연을 함께 잰다
        ids, ms = fetch(base, q["q"])
        lat1.append(ms)
        results.append({"family": q["family"], "q": q["q"], "ids": ids, "ndcg": ndcg(ids, q["relevant"])})
    lat10 = []
    with concurrent.futures.ThreadPoolExecutor(10) as pool:   # 동시성 10: 같은 쿼리를 한 번 더
        for _, ms in pool.map(lambda q: fetch(base, q["q"]), queries):
            lat10.append(ms)
    out = {"engine": engine, "results": results,
           "latency": {"c1_p50": pct(lat1, 50), "c1_p95": pct(lat1, 95), "c10_p95": pct(lat10, 95)}}
    pathlib.Path(out_path).write_text(json.dumps(out, ensure_ascii=False))
    print(engine, summarize(out))


def summarize(run_json):
    by = collections.defaultdict(list)
    zero = collections.defaultdict(int)
    for r in run_json["results"]:
        by[r["family"]].append(r["ndcg"])
        zero[r["family"]] += (len(r["ids"]) == 0)
    fam = {f: {"ndcg": statistics.mean(v), "zero": zero[f] / len(v), "n": len(v)} for f, v in sorted(by.items())}
    axis = statistics.mean(fam[f]["ndcg"] for f in QUALITY_AXIS if f in fam)
    return {"families": fam, "quality_axis": axis, "latency": run_json["latency"]}


# ---------- 표 ----------

ORDER = ["like", "like-fields", "fulltext", "lucene", "elasticsearch", "opensearch"]


def overlap(a, b):
    """같은 쿼리의 상위 10개 겹침 평균(둘 다 빈 결과면 1)."""
    vals = []
    for x, y in zip(a["results"], b["results"]):
        sx, sy = set(x["ids"]), set(y["ids"])
        vals.append(1.0 if not sx and not sy else len(sx & sy) / max(len(sx | sy), 1))
    return statistics.mean(vals)


def report(out_dir):
    runs = {}
    for e in ORDER:
        p = pathlib.Path(out_dir) / f"{e}.json"
        if p.exists():
            runs[e] = json.loads(p.read_text())
    print("| 엔진 | " + " | ".join(f"{f} {FAMILIES[f]}" for f in FAMILIES) + " | **B·C·E 평균** | 0건 비율(B·C·E) | p95 c1 | p95 c10 |")
    print("|---|" + "---:|" * (len(FAMILIES) + 4))
    for e, r in runs.items():
        s = summarize(r)
        fam = s["families"]
        zero = statistics.mean(fam[f]["zero"] for f in QUALITY_AXIS)
        cells = " | ".join(f"{fam[f]['ndcg']:.3f}" for f in FAMILIES)
        print(f"| `{e}` | {cells} | **{s['quality_axis']:.3f}** | {zero:.1%} | "
              f"{s['latency']['c1_p95']:.0f}ms | {s['latency']['c10_p95']:.0f}ms |")
    n = {f: summarize(next(iter(runs.values())))["families"][f]["n"] for f in FAMILIES} if runs else {}
    print("\n쿼리 수:", n)
    engines = [e for e in ("lucene", "elasticsearch", "opensearch") if e in runs]
    if len(engines) > 1:
        print("\n상위 10개 겹침(Jaccard 평균):")
        for i, a in enumerate(engines):
            for b in engines[i + 1:]:
                print(f"- {a} ↔ {b}: {overlap(runs[a], runs[b]):.3f}")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "queries":
        build_queries(sys.argv[2])
    elif cmd == "run":
        run(sys.argv[2], sys.argv[3].rstrip("/"), sys.argv[4], sys.argv[5])
    elif cmd == "report":
        report(sys.argv[2])
