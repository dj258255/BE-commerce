"""검색어 고치기(#260) 실측. 판정 기준은 이슈에 측정 전에 고정했다.

    uv run --with pymysql python3 tools/search/rewrite_eval.py queries OUT
    uv run --with pymysql python3 tools/search/rewrite_eval.py run     OUT BASE NAME      # NAME = off | on
    python3 tools/search/rewrite_eval.py                        report  OUT

쿼리셋: F(미국식) · G1(한국어 중분류) · G2(색상 + 중분류, 띄어 씀) · G3(붙여 씀) · 기존 A~E(#236).
API 는 `GET /api/v1/products?q=&size=10`(정렬 없음). nDCG@10 과 0건 비율.
"""
import collections
import json
import pathlib
import random
import statistics
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import search_quality_eval as sq  # noqa: E402

SEED = 7
# 이슈 #260 에 측정 전에 고정한 목록. 정답 = 대응하는 영국식 종류의 상품
F_TRUTH = {"pants": ["Trousers"], "tank top": ["Vest top"], "tank": ["Vest top"], "romper": ["Jumpsuit/Playsuit"],
           "overalls": ["Dungarees"], "headband": ["Hair/alice band"], "pantyhose": ["Leggings/Tights", "Underwear Tights"],
           "bathing suit": ["Swimsuit"], "suspenders": ["Braces"]}


def db():
    import pymysql
    return pymysql.connect(host="127.0.0.1", port=3306, user="root", password="root", database="becommerce", charset="utf8mb4")


def queries(out):
    out = pathlib.Path(out)
    out.mkdir(parents=True, exist_ok=True)
    c = db().cursor()
    qs = []
    for q, types in F_TRUTH.items():
        c.execute(f"SELECT product_id FROM products WHERE product_type IN ({','.join(['%s'] * len(types))})", types)
        qs.append({"family": "F", "q": q, "relevant": [r[0] for r in c.fetchall()]})
    c.execute("SELECT name, code FROM categories WHERE parent_code IS NOT NULL")
    codes_of = collections.defaultdict(list)
    for name, code in c.fetchall():
        codes_of[name].append(code)
    members = {}
    for name, codes in sorted(codes_of.items()):
        c.execute(f"SELECT product_id, colour_name FROM products WHERE subcategory_code IN ({','.join(['%s'] * len(codes))})", codes)
        rows = c.fetchall()
        members[name] = rows
        qs.append({"family": "G1", "q": name, "relevant": [r[0] for r in rows]})
    pairs = []
    for name, rows in sorted(members.items()):
        by_colour = collections.defaultdict(list)
        for pid, colour in rows:
            if colour:
                by_colour[colour].append(pid)
        for colour, pids in sorted(by_colour.items()):
            if len(pids) >= 20:
                pairs.append((colour, name, pids))
    for colour, name, pids in random.Random(SEED).sample(pairs, min(60, len(pairs))):
        qs.append({"family": "G2", "q": f"{colour} {name}", "relevant": pids})
        qs.append({"family": "G3", "q": f"{colour}{name}".replace(" ", ""), "relevant": pids})
    if not (out / "queries.json").exists():
        sq.build_queries(out / "queries.json")
    base = json.loads((out / "queries.json").read_text())
    (out / "rewrite-queries.json").write_text(json.dumps(qs + base, ensure_ascii=False))
    print(dict(collections.Counter(q["family"] for q in qs + base)), "· 색상·중분류 조합", len(pairs))


def run(out, base, name):
    out = pathlib.Path(out)
    qs = json.loads((out / "rewrite-queries.json").read_text())
    res = []
    for q in qs:
        ids, _ = sq.fetch(base, q["q"])
        res.append({"family": q["family"], "q": q["q"], "ndcg": sq.ndcg(ids, q["relevant"]), "zero": len(ids) == 0})
    (out / f"rewrite-{name}.json").write_text(json.dumps(res, ensure_ascii=False))
    print(name, summary(res))


def summary(res):
    by = collections.defaultdict(list)
    for r in res:
        by[r["family"]].append(r)
    return {f: {"n": len(v), "ndcg": round(statistics.mean(x["ndcg"] for x in v), 4),
                "zero": round(sum(x["zero"] for x in v) / len(v), 4)} for f, v in sorted(by.items())}


def report(out):
    out = pathlib.Path(out)
    off = summary(json.loads((out / "rewrite-off.json").read_text()))
    on = summary(json.loads((out / "rewrite-on.json").read_text()))
    print("| 묶음 | 쿼리 | nDCG@10 끔 | nDCG@10 켬 | 차이 | 0건 끔 | 0건 켬 |")
    print("|---|---:|---:|---:|---:|---:|---:|")
    for f in ["A", "B", "C", "D", "E", "F", "G1", "G2", "G3"]:
        if f in off:
            a, b = off[f], on[f]
            print(f"| {f} | {a['n']} | {a['ndcg']:.3f} | {b['ndcg']:.3f} | {b['ndcg'] - a['ndcg']:+.3f} | {a['zero']:.0%} | {b['zero']:.0%} |")


if __name__ == "__main__":
    cmd, a = sys.argv[1], sys.argv[2:]
    {"queries": queries, "run": run, "report": report}[cmd](*a)
