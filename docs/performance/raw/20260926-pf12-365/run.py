"""PF-12(#365): 같은 스냅숏을 refresh 끔 × 2 · 켬 × 2 로, 그리고 지금 DB 로 한 번 색인해 `flip flop` 점수와 explain 의 BM25 통계를 비교한다."""
import json, re, subprocess, sys, time, urllib.request
sys.path.insert(0, "tools/search")
from engine_diff import text_query

URL = "http://localhost:19201"
SNAP, OUT = sys.argv[1], sys.argv[2]
Q = "flip flop"

def post(path, body):
    req = urllib.request.Request(URL + path, json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())

def stats(expl, acc):
    d = expl.get("description", "")
    for key, pat in (("N", r"^N, total number of documents"), ("n", r"^n, number of documents containing term"), ("avgdl", r"^avgdl")):
        if re.search(pat, d):
            acc.setdefault(key, []).append(expl["value"])
    for c in expl.get("details", []):
        stats(c, acc)
    return acc

def es_up():
    subprocess.run(["docker", "rm", "-f", "pf12-es"], capture_output=True)
    subprocess.run(["docker", "run", "-d", "--name", "pf12-es", "-p", "19201:9200", "-e", "discovery.type=single-node",
                    "-e", "xpack.security.enabled=false", "-e", "ES_JAVA_OPTS=-Xms512m -Xmx512m",
                    "docker.elastic.co/elasticsearch/elasticsearch:8.15.5"], capture_output=True, check=True)
    for _ in range(120):
        try:
            urllib.request.urlopen(URL, timeout=2).read(); return
        except Exception:
            time.sleep(2)

variants = [("snap-off-1", ["--from-json", SNAP]), ("snap-off-2", ["--from-json", SNAP]),
            ("snap-live-1", ["--from-json", SNAP, "--live-refresh"]), ("snap-live-2", ["--from-json", SNAP, "--live-refresh"]),
            ("db-now", [])]
res = {}
es_up()
for name, args in variants:
    idx = subprocess.run(["python3", "tools/search/search_index.py", URL, *args], capture_output=True, text=True)
    info = json.loads(idx.stdout.strip().splitlines()[-1]) if idx.stdout.strip() else {"error": idx.stderr[-300:]}
    hits = post("/products/_search", {"size": 10, "_source": False, "query": text_query(Q)})["hits"]["hits"]
    top = hits[0]["_id"] if hits else None
    expl = post(f"/products/_explain/{top}", {"query": text_query(Q)})["explanation"] if top else {}
    st = stats(expl, {})
    seg = post("/products/_segments", {}) if False else None
    res[name] = {"index": info, "top10": [(h["_id"], h["_score"]) for h in hits], "top_score": hits[0]["_score"] if hits else None,
                 "explain_N": sorted(set(st.get("N", []))), "explain_n": sorted(set(st.get("n", []))),
                 "explain_avgdl": sorted(set(round(x, 4) for x in st.get("avgdl", [])))}
    print(name, info.get("docs"), "top", res[name]["top_score"], "N", res[name]["explain_N"], "n", res[name]["explain_n"][:6])
subprocess.run(["docker", "rm", "-f", "pf12-es"], capture_output=True)
json.dump(res, open(OUT, "w"), indent=1)
