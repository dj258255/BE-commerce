"""GenPage 를 구매 이력으로 서빙할 때 오프라인 점수가 서빙에서도 나오는지 잰다(#254). 판정 기준은 이슈에 측정 전에 고정했다.

    PY=genpage-venv/bin/python   GENPAGE_DATA=personalization/data
    $PY tools/genpage_purchase_eval.py prepare OUT          # 홀드아웃 고객 5,000명(seed 7) · 과거 구매 · 정답
    $PY tools/genpage_purchase_eval.py offline OUT          # 모델 서버 엔진을 프로세스 안에서 직접 부른다
    $PY tools/genpage_purchase_eval.py seed    OUT          # 과거 구매(최근 100건)를 orders·order_items 에 결제 완료로 심는다
    $PY tools/genpage_purchase_eval.py serve   OUT NAME APP_URL   # 앱의 추천 경로(실험 엔드포인트)로 받는다
    $PY tools/genpage_purchase_eval.py cleanup
    $PY tools/genpage_purchase_eval.py report  OUT

오프라인 기준은 서빙과 **같은 모델 서버 엔진**(genpage_server.Engine)이다. 그래서 두 값의 차이는 앱 경로(주문 표 왕복 ·
최근 100건 자르기 · 순서 · 재고 필터)에서만 생긴다.
"""
import concurrent.futures
import json
import os
import pathlib
import random
import sys
import time
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "personalization" / "pipeline"))
sys.path.insert(0, str(ROOT / "personalization" / "serving"))
DATA = pathlib.Path(os.environ.get("GENPAGE_DATA", ROOT / "personalization" / "data"))

SEED = 7
N = 5000
K = 12
HISTORY_LIMIT = 100          # 앱의 app.recommendation.purchase-history-limit 과 같다
USER_BASE = 910_000_000
ID_BASE = 9_100_000_000      # 심는 주문·항목 id 의 시작(기존 행과 겹치지 않게)


def ap_at_k(pred, truth):
    """Kaggle H&M 규약: 맞힌 자리마다의 정밀도 합 ÷ min(|정답|, k). 중복 예측은 한 번만 센다."""
    hits, s, seen = 0, 0.0, set()
    for i, p in enumerate(pred[:K]):
        if p in truth and p not in seen:
            hits += 1
            s += hits / (i + 1)
        seen.add(p)
    return s / min(len(truth), K)


def mean_ap(preds, sample):
    return sum(ap_at_k(preds.get(str(c["i"]), []), set(c["truth"])) for c in sample) / len(sample)


def repeat_first(newest_first, k=K):
    out = []
    for p in newest_first:
        if p not in out:
            out.append(p)
        if len(out) == k:
            break
    return out


def hybrid(newest_first, model, k=K):
    out = repeat_first(newest_first, k)
    for m in model:
        if len(out) == k:
            break
        if m not in out:
            out.append(m)
    return out


def prepare(out):
    from features_hm import load_transactions, split
    tx = load_transactions(DATA)
    train, holdout, cutoff, _ = split(tx, 7)
    vocab = set(json.loads((DATA / "hm" / "model" / "genpage" / "vocab.json").read_text())["items"])
    tr = train[["customer_id", "article_id", "t_dat"]].copy()
    tr["customer_id"] = tr["customer_id"].astype(str)
    tr["article_id"] = tr["article_id"].astype(str)
    tr = tr.sort_values(["customer_id", "t_dat"], kind="stable")       # 학습 시퀀스와 같은 순서(build_sequences)
    with_vocab = set(tr.loc[tr["article_id"].isin(vocab), "customer_id"].unique())
    ho = holdout[["customer_id", "article_id"]].astype(str)
    targets = sorted(set(ho["customer_id"].unique()) & with_vocab)    # 오프라인 채점 대상과 같은 정의
    sample_ids = random.Random(SEED).sample(targets, N)
    chosen = set(sample_ids)
    hist = tr[tr["customer_id"].isin(chosen)].groupby("customer_id")
    truth = ho[ho["customer_id"].isin(chosen)].groupby("customer_id")["article_id"].apply(lambda s: sorted({int(a) for a in s}))
    sample = []
    for i, c in enumerate(sample_ids):
        g = hist.get_group(c)
        sample.append({"i": i, "customer": c, "history": [int(a) for a in g["article_id"]],
                       "days": [str(d.date()) for d in g["t_dat"]], "truth": truth[c]})
    pathlib.Path(out).mkdir(parents=True, exist_ok=True)
    (pathlib.Path(out) / "sample.json").write_text(json.dumps(sample))
    print(f"대상 {len(targets):,} 중 {N:,}명 · 과거 구매 중앙값 {sorted(len(s['history']) for s in sample)[N // 2]}건 · 홀드아웃 {cutoff.date()} 부터")


def offline(out):
    from genpage_server import Engine
    sample = json.loads((pathlib.Path(out) / "sample.json").read_text())
    engine = Engine()
    res = {}
    for variant, cut in (("full", None), ("last100", HISTORY_LIMIT)):
        model, rep, hyb = {}, {}, {}
        for c in sample:
            newest = list(reversed(c["history"]))
            if cut:
                newest = newest[:cut]
            m = engine.recommend(newest, K)
            model[str(c["i"])] = m
            rep[str(c["i"])] = repeat_first(newest)
            hyb[str(c["i"])] = hybrid(newest, m)
        res[variant] = {"model": mean_ap(model, sample), "repeat": mean_ap(rep, sample), "hybrid": mean_ap(hyb, sample)}
        print(variant, res[variant])
    (pathlib.Path(out) / "offline.json").write_text(json.dumps(res, indent=1))


def db():
    import pymysql
    return pymysql.connect(host="127.0.0.1", port=3306, user="root", password="root", database="becommerce", autocommit=False)


def cleanup(_=None):
    conn = db()
    with conn.cursor() as c:
        c.execute("DELETE FROM order_items WHERE order_id >= %s", (ID_BASE,))
        c.execute("DELETE FROM orders WHERE id >= %s", (ID_BASE,))
    conn.commit()
    conn.close()


def seed(out):
    """최근 100건을 결제 완료 주문으로 심는다. 하루 = 주문 하나. id 를 시간순으로 매겨 '최근 것부터'가 원래 순서의 역순이 되게 한다."""
    sample = json.loads((pathlib.Path(out) / "sample.json").read_text())
    cleanup()
    orders, items = [], []
    oid, iid = ID_BASE, ID_BASE
    for c in sample:
        hist, days = c["history"][-HISTORY_LIMIT:], c["days"][-HISTORY_LIMIT:]
        user = USER_BASE + c["i"]
        prev = None
        for a, d in zip(hist, days):
            if d != prev:
                oid += 1
                orders.append((oid, user, f"gpe-{user}-{oid}", f"{d} 12:00:00"))
                prev = d
            iid += 1
            items.append((iid, oid, a))
    conn = db()
    with conn.cursor() as cur:
        for i in range(0, len(orders), 5000):
            cur.executemany("INSERT INTO orders(id, user_id, order_no, status, currency, total_amount, created_at, updated_at, "
                            "expires_at, version) VALUES (%s, %s, %s, 'PAID', 'KRW', 0, %s, NOW(6), NOW(6), 0)",
                            orders[i:i + 5000])
        for i in range(0, len(items), 5000):
            cur.executemany("INSERT INTO order_items(id, order_id, product_id, quantity, unit_price, product_name) "
                            "VALUES (%s, %s, %s, 1, 0, 'gpe')", items[i:i + 5000])
    conn.commit()
    conn.close()
    print(f"주문 {len(orders):,} · 항목 {len(items):,}")


def serve(out, name, base):
    sample = json.loads((pathlib.Path(out) / "sample.json").read_text())
    req = urllib.request.Request(f"{base}/api/v1/auth/login", json.dumps({"username": "1", "password": "user-local-only"}).encode(),
                                 {"Content-Type": "application/json"})
    token = json.loads(urllib.request.urlopen(req).read())["token"]

    def one(c):
        url = f"{base}/api/v1/experiments/recommendations?" + urllib.parse.urlencode({"userId": USER_BASE + c["i"]})
        t = time.perf_counter()
        body = json.loads(urllib.request.urlopen(urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"}),
                                                 timeout=30).read())
        return c["i"], body["items"], body["source"], body.get("contextItems"), (time.perf_counter() - t) * 1000

    preds, sources, ms, ctx = {}, {}, [], []
    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        for i, items, source, n, t in pool.map(one, sample):
            preds[str(i)] = items
            sources[source] = sources.get(source, 0) + 1
            ms.append(t)
            ctx.append(n)
    ms.sort()
    doc = {"name": name, "map": mean_ap(preds, sample), "sources": sources, "p95_ms": ms[int(0.95 * (len(ms) - 1))],
           "context_items_median": sorted(ctx)[len(ctx) // 2], "preds": preds}
    (pathlib.Path(out) / f"served-{name}.json").write_text(json.dumps(doc))
    print(name, {k: v for k, v in doc.items() if k != "preds"})


def report(out):
    out = pathlib.Path(out)
    off = json.loads((out / "offline.json").read_text())
    served = {p.stem.split("-", 1)[1]: json.loads(p.read_text()) for p in out.glob("served-*.json")}
    print("| 경로 | 이력 | 모델 단독 | 재구매 규칙 | 혼합(재구매 우선 + 모델) |")
    print("|---|---|---:|---:|---:|")
    for v, label in (("full", "전체"), ("last100", "최근 100건")):
        o = off[v]
        print(f"| 오프라인(엔진 직접) | {label} | {o['model']:.6f} | {o['repeat']:.6f} | {o['hybrid']:.6f} |")
    m, h = served.get("model"), served.get("hybrid")
    print(f"| **서빙(앱 경로)** | 최근 100건 | **{m['map']:.6f}** | — | **{h['map']:.6f}** |" if m and h else "")
    for s in served.values():
        print(f"- 서빙 `{s['name']}`: 응답 출처 {s['sources']} · p95 {s['p95_ms']:.0f}ms · 이력 길이 중앙값 {s['context_items_median']}")
    if m:
        base = off["last100"]["model"]
        print(f"- 서빙 모델 단독 / 오프라인(최근 100건) = {m['map'] / base:.4f}")
    if h:
        print(f"- 서빙 혼합 / 오프라인 재구매 규칙(최근 100건) = {h['map'] / off['last100']['repeat']:.4f}")


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    {"prepare": prepare, "offline": offline, "seed": seed, "serve": serve, "cleanup": cleanup, "report": report}[cmd](*args)
