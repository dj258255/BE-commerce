"""ES 와 OpenSearch 의 검색 순서가 왜 다른가(#262). 판정 기준은 이슈에 측정 전에 고정했다.

    python3 tools/search/engine_diff.py collect OUT URL NAME [--sort]     # 쿼리마다 상위 50개와 점수
    python3 tools/search/engine_diff.py classify OUT A B                   # 상위 10개가 다른 쿼리를 동점/점수 다름으로 가른다
    python3 tools/search/engine_diff.py explain OUT URL NAME               # 점수가 다른 쿼리의 대표 문서 explain
    python3 tools/search/engine_diff.py components OUT A B                 # explain 트리에서 처음 갈리는 항

엔진은 하나씩 띄운다(둘을 같이 띄우면 도커 VM 메모리가 모자라다). 본문은 EngineQuery.textQuery 와 같다.
"""
import collections
import json
import pathlib
import sys
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import search_quality_eval as sq  # noqa: E402

FIELDS = ["name^2", "product_type^2", "description"]
REL = 1e-5


def text_query(q):
    return {"bool": {"should": [
        {"multi_match": {"query": q, "fields": FIELDS, "type": "best_fields", "boost": 3.0}},
        {"multi_match": {"query": q, "fields": FIELDS, "type": "best_fields", "fuzziness": "AUTO"}}]}}


def post(url, body):
    req = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())


def queries(out):
    out = pathlib.Path(out)
    out.mkdir(parents=True, exist_ok=True)
    if not (out / "queries.json").exists():
        sq.build_queries(out / "queries.json")
    return json.loads((out / "queries.json").read_text())


def collect(out, url, name, sort=None):
    res = {}
    for q in queries(out):
        body = {"size": 50, "_source": False, "track_total_hits": False, "query": text_query(q["q"])}
        if sort:
            body["sort"] = [{"_score": "desc"}, {"product_id": "asc"}]
            body["track_scores"] = True
        hits = post(f"{url}/products/_search", body)["hits"]["hits"]
        res[q["q"]] = [[h["_id"], h["_score"]] for h in hits]
    (pathlib.Path(out) / f"hits-{name}.json").write_text(json.dumps(res))
    print(name, len(res), "쿼리")


def load(out, name):
    return json.loads((pathlib.Path(out) / f"hits-{name}.json").read_text())


def classify(out, a, b):
    ha, hb = load(out, a), load(out, b)
    overlaps, classes, detail = [], collections.Counter(), []
    for q in ha:
        ta, tb = [d for d, _ in ha[q][:10]], [d for d, _ in hb[q][:10]]
        sa, sb = set(ta), set(tb)
        overlaps.append(1.0 if not sa and not sb else len(sa & sb) / max(len(sa | sb), 1))
        if ta == tb:
            classes["same"] += 1
            continue
        score_a, score_b = dict(ha[q]), dict(hb[q])
        union = sa | sb
        missing = [d for d in union if d not in score_a or d not in score_b]
        diffs = [d for d in union if d in score_a and d in score_b
                 and abs(score_a[d] - score_b[d]) > REL * max(abs(score_a[d]), 1e-9)]
        if missing:
            kind = "score" if diffs else "outside50"
        else:
            kind = "score" if diffs else "tie"
        classes[kind] += 1
        detail.append({"q": q, "kind": kind, "diff_docs": diffs[:3], "missing": missing[:3]})
    total_diff = sum(v for k, v in classes.items() if k != "same")
    doc = {"pair": [a, b], "queries": len(ha), "overlap_mean": sum(overlaps) / len(overlaps), "classes": dict(classes),
           "differing": total_diff, "detail": detail}
    (pathlib.Path(out) / f"classify-{a}-{b}.json").write_text(json.dumps(doc, indent=1))
    print({k: v for k, v in doc.items() if k != "detail"})


def explain(out, url, name):
    c = json.loads(next(pathlib.Path(out).glob("classify-*.json")).read_text())
    picks = [(d["q"], d["diff_docs"][0]) for d in c["detail"] if d["kind"] == "score" and d["diff_docs"]][:40]
    res = {}
    for q, doc in picks:
        res[f"{q}\t{doc}"] = post(f"{url}/products/_explain/{doc}", {"query": text_query(q)})["explanation"]
    (pathlib.Path(out) / f"explain-{name}.json").write_text(json.dumps(res))
    print(name, len(res), "explain")


def first_divergence(x, y, path="root"):
    """두 explain 트리에서 값이 처음 갈리는 가장 깊은 노드의 설명."""
    if abs(x["value"] - y["value"]) <= REL * max(abs(x["value"]), 1e-9):
        return None
    kids_x, kids_y = x.get("details", []), y.get("details", [])
    if len(kids_x) == len(kids_y):
        for kx, ky in zip(kids_x, kids_y):
            d = first_divergence(kx, ky, path + " > " + kx["description"][:40])
            if d:
                return d
    return {"path": path, "a": {"value": x["value"], "description": x["description"][:140]},
            "b": {"value": y["value"], "description": y["description"][:140]},
            "structure_differs": len(kids_x) != len(kids_y)}


def components(out, a, b):
    ea = json.loads((pathlib.Path(out) / f"explain-{a}.json").read_text())
    eb = json.loads((pathlib.Path(out) / f"explain-{b}.json").read_text())
    counts, samples = collections.Counter(), []
    for key in ea:
        d = first_divergence(ea[key], eb[key])
        if not d:
            counts["no-divergence"] += 1
            continue
        label = d["a"]["description"].split(",")[0].split("(")[0].strip()
        counts[("structure: " if d["structure_differs"] else "") + label] += 1
        samples.append({"key": key, **d})
    doc = {"counts": dict(counts.most_common()), "samples": samples[:10]}
    (pathlib.Path(out) / "components.json").write_text(json.dumps(doc, indent=1, ensure_ascii=False))
    print(json.dumps(doc["counts"], ensure_ascii=False, indent=1))


if __name__ == "__main__":
    cmd, a = sys.argv[1], sys.argv[2:]
    if cmd == "collect":
        collect(a[0], a[1].rstrip("/"), a[2], "--sort" in a)
    elif cmd == "classify":
        classify(*a)
    elif cmd == "explain":
        explain(a[0], a[1].rstrip("/"), a[2])
    else:
        components(*a)
