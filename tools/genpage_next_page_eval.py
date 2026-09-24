"""홈 다음 쪽의 GenPage 입력 세 변형을 잰다(#270). 판정 기준은 이슈에 측정 전에 고정했다.

    PY=genpage-venv/bin/python   GENPAGE_DATA=personalization/data
    $PY tools/genpage_next_page_eval.py prepare OUT              # #254 와 같은 표본(seed 7)의 앞 1,000명
    $PY tools/genpage_next_page_eval.py signup  OUT APP_URL      # 회원 가입 → 회원 id
    $PY tools/genpage_next_page_eval.py seed    OUT              # 과거 구매 최근 100건을 그 회원의 결제 완료 주문으로 심는다
    $PY tools/genpage_next_page_eval.py run     OUT NAME APP_URL # 1쪽 → 2쪽 → VIEW → 2쪽
    $PY tools/genpage_next_page_eval.py offline OUT NAME         # 조회 전 2쪽을 엔진 직접 호출과 대조(배선)
    $PY tools/genpage_next_page_eval.py report  OUT
    $PY tools/genpage_next_page_eval.py cleanup

변형은 앱 설정으로 고른다(run-genpage-next-page.sh). 세 변형이 같은 회원을 쓰므로 변형마다 세션(Redis ctx: 와 user_activities)을 지우고 시작한다.
"""
import concurrent.futures
import json
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import genpage_purchase_eval as gpe  # noqa: E402  같은 표본·같은 DB 접속을 쓴다

N = 1000
HISTORY_LIMIT = gpe.HISTORY_LIMIT
ID_BASE = 9_200_000_000          # 심는 주문·항목 id 의 시작(#254 의 9,100,000,000 대와 겹치지 않게)
PASSWORD = "next-page-only-1234"
ROWS, ITEMS, PREFIX = 3, 8, 2    # 앱 기본값(app.home.page-rows · item-cap · genpage-prefix)


def call(base, method, path, token=None, body=None):
    req = urllib.request.Request(base + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json",
                                          **({"Authorization": f"Bearer {token}"} if token else {})})
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None), (time.perf_counter() - started) * 1000
    except urllib.error.HTTPError as e:
        return e.code, None, (time.perf_counter() - started) * 1000


def load(out, name):
    return json.loads((pathlib.Path(out) / name).read_text())


def save(out, name, doc):
    (pathlib.Path(out) / name).write_text(json.dumps(doc))


def prepare(out):
    out = pathlib.Path(out)
    out.mkdir(parents=True, exist_ok=True)
    if not (out / "sample.json").exists():
        gpe.prepare(str(out))
    sample = load(out, "sample.json")[:N]
    save(out, "sample-1000.json", sample)
    print(f"표본 {len(sample):,}명 · 과거 구매 중앙값 {sorted(len(s['history']) for s in sample)[N // 2]}건")


def signup(out, base):
    sample = load(out, "sample-1000.json")
    stamp = int(time.time())

    def one(c):
        email = f"np-{stamp}-{c['i']}@load.test"
        status, body, _ = call(base, "POST", "/api/v1/members/signup", body={"email": email, "password": PASSWORD})
        if status != 201:
            raise RuntimeError(f"가입 실패 {status} {email}")
        return c["i"], {"email": email, "member": body["id"]}

    with concurrent.futures.ThreadPoolExecutor(4) as pool:
        members = dict(pool.map(one, sample))
    save(out, "members.json", {str(k): v for k, v in members.items()})
    print(f"가입 {len(members):,}명")


def seed(out):
    sample = load(out, "sample-1000.json")
    members = load(out, "members.json")
    cleanup()
    orders, items = [], []
    oid, iid = ID_BASE, ID_BASE
    for c in sample:
        hist, days = c["history"][-HISTORY_LIMIT:], c["days"][-HISTORY_LIMIT:]
        user = members[str(c["i"])]["member"]
        prev = None
        for a, d in zip(hist, days):
            if d != prev:
                oid += 1
                orders.append((oid, user, f"np-{user}-{oid}", f"{d} 12:00:00"))
                prev = d
            iid += 1
            items.append((iid, oid, a))
    conn = gpe.db()
    with conn.cursor() as cur:
        for i in range(0, len(orders), 5000):
            cur.executemany("INSERT INTO orders(id, user_id, order_no, status, currency, total_amount, created_at, updated_at, "
                            "expires_at, version) VALUES (%s, %s, %s, 'PAID', 'KRW', 0, %s, NOW(6), NOW(6), 0)",
                            orders[i:i + 5000])
        for i in range(0, len(items), 5000):
            cur.executemany("INSERT INTO order_items(id, order_id, product_id, quantity, unit_price, product_name) "
                            "VALUES (%s, %s, %s, 1, 0, 'np')", items[i:i + 5000])
    conn.commit()
    conn.close()
    print(f"주문 {len(orders):,} · 항목 {len(items):,}")


def cleanup(_=None):
    conn = gpe.db()
    with conn.cursor() as c:
        c.execute("DELETE FROM order_items WHERE order_id >= %s", (ID_BASE,))
        c.execute("DELETE FROM orders WHERE id >= %s", (ID_BASE,))
    conn.commit()
    conn.close()


def view_targets():
    """대분류마다 조회할 상품 하나: 모델 어휘 안 · 재고 있음 · 가장 작은 id. 모델이 모르는 상품을 보면 세션을 못 읽는다."""
    vocab = {int(a) for a in json.loads((gpe.DATA / "hm" / "model" / "genpage" / "vocab.json").read_text())["items"]}
    conn = gpe.db()
    with conn.cursor() as c:
        c.execute("SELECT p.category_code, p.product_id FROM products p JOIN stock s ON s.product_id = p.product_id "
                  "WHERE s.quantity > 0 AND p.category_code IS NOT NULL ORDER BY p.product_id")
        rows = c.fetchall()
    conn.close()
    out = {}
    for cat, pid in rows:
        if cat not in out and pid in vocab:
            out[cat] = pid
    return out


def clear_sessions(member_ids):
    """세션을 지운다. 두 군데를 지워야 한다.

    - user_activities: 남아 있으면 다음 변형의 조회(seq 1)가 (user_id, seq) 중복으로 409 가 된다(첫 실행)
    - 앱이 붙는 Redis 의 ctx:{id}:v2: 같은 seq 가 이미 있으면 병합이 새 조회를 무시한다(두 번째 실행).
      앱은 localhost:6379 에 붙는데 이 머신에서는 그 포트를 도커가 아닌 로컬 redis-server 가 먼저 잡는다.
      그래서 도커 컨테이너가 아니라 앱과 같은 주소(127.0.0.1)로 지운다
    """
    conn = gpe.db()
    with conn.cursor() as c:
        for i in range(0, len(member_ids), 500):
            chunk = member_ids[i:i + 500]
            c.execute(f"DELETE FROM user_activities WHERE user_id IN ({','.join(['%s'] * len(chunk))})", chunk)
    conn.commit()
    conn.close()
    keys = [f"ctx:{m}:v2" for m in member_ids]      # ContextStore.key
    for i in range(0, len(keys), 500):
        subprocess.run(["redis-cli", "-h", "127.0.0.1", "-p", "6379", "DEL", *keys[i:i + 500]], check=True,
                       capture_output=True)


def item_ids(page):
    return [int(i["itemId"]) for r in page["rows"] for i in r["items"]]


def run(out, name, base):
    sample = load(out, "sample-1000.json")
    members = load(out, "members.json")
    targets = view_targets()
    cats = sorted(targets)
    clear_sessions([m["member"] for m in members.values()])

    def one(c):
        m = members[str(c["i"])]
        _, login, _ = call(base, "POST", "/api/v1/auth/login", body={"username": m["email"], "password": PASSWORD})
        token = login["token"]
        _, ctx, _ = call(base, "GET", "/api/v1/personalization/context", token)
        if ctx["items"]:
            raise RuntimeError(f"{name}: 세션이 비지 않았다 i={c['i']} — 앞 실행의 조회가 남았다")
        _, p1, _ = call(base, "GET", "/api/v1/personalization/homepage", token)
        _, pre, ms_pre = call(base, "GET", "/api/v1/personalization/homepage?cursor=" + p1["nextCursor"], token)
        first = pre["rows"][0]["id"] if pre["rows"] else None
        others = [x for x in cats if f"cat:{x}" != first]
        target = others[c["i"] % len(others)]
        status, _, _ = call(base, "POST", "/api/v1/personalization/activity", token,
                            {"itemId": targets[target], "type": "VIEW", "seq": 1})
        _, post, ms_post = call(base, "GET", "/api/v1/personalization/homepage?cursor=" + p1["nextCursor"], token)
        return {"i": c["i"], "p1": item_ids(p1), "pre_rows": [(r["id"], r["strategy"], [int(x["itemId"]) for x in r["items"]])
                                                                for r in pre["rows"]],
                "post_rows": [(r["id"], r["strategy"], [int(x["itemId"]) for x in r["items"]]) for r in post["rows"]],
                "target": target, "view_status": status, "ms_pre": ms_pre, "ms_post": ms_post}

    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        runs = list(pool.map(one, sample))
    save(out, f"run-{name}.json", runs)
    failed = sum(r["view_status"] != 201 for r in runs)
    if failed:
        # 조회가 기록되지 않으면 세션 반영을 잴 수 없다 — 숫자를 내지 않고 멈춘다
        raise RuntimeError(f"{name}: 조회 {failed}건이 기록되지 않았다")
    print(name, summarize(out, name, runs))


def summarize(out, name, runs=None):
    runs = runs or load(out, f"run-{name}.json")
    truth = {c["i"]: set(c["truth"]) for c in load(out, "sample-1000.json")}
    recall, hit, reflected, genpage, ms = [], 0, 0, 0, []
    for r in runs:
        items = [x for _, _, row in r["post_rows"] for x in row]
        t = truth[r["i"]]
        got = len(t & set(items))
        recall.append(got / len(t))
        hit += got > 0
        reflected += bool(r["post_rows"]) and r["post_rows"][0][0] == f"cat:{r['target']}"
        genpage += all(s == "GENPAGE" for _, s, _ in r["post_rows"]) and bool(r["post_rows"])
        ms.append(r["ms_post"])
    ms.sort()
    n = len(runs)
    return {"users": n, "recall": sum(recall) / n, "hit_users": hit / n, "session_reflected": reflected / n,
            "all_genpage": genpage / n, "p95_ms": ms[int(0.95 * (n - 1))], "p50_ms": ms[n // 2],
            "view_ok": sum(r["view_status"] in (200, 201, 202, 204) for r in runs) / n}


def offline(out, name):
    """조회 전 2쪽을 엔진 직접 호출과 대조한다. 입력: 구매 최근 100건(최근 것부터) · 1쪽이 보여 준 상품 제외."""
    from genpage_server import Engine
    engine = Engine()
    sample = {c["i"]: c for c in load(out, "sample-1000.json")}
    runs = load(out, f"run-{name}.json")
    conn = gpe.db()
    with conn.cursor() as c:
        c.execute("SELECT product_id FROM stock WHERE quantity > 0")
        in_stock = {r[0] for r in c.fetchall()}
    conn.close()
    order_same, items_same = 0, 0
    for r in runs:
        history = list(reversed(sample[r["i"]]["history"]))[:HISTORY_LIMIT]
        rows, _, violations = engine.page(history, r["p1"], [], ROWS, ITEMS, PREFIX)
        app = r["pre_rows"]
        order_same += [f"cat:{x['category']}" for x in rows] == [x[0] for x in app]
        items_same += len(rows) == len(app) and all([i for i in e["items"] if i in in_stock] == a[2]
                                                      for e, a in zip(rows, app))
    doc = {"users": len(runs), "category_order_same": order_same / len(runs), "items_same": items_same / len(runs)}
    save(out, f"offline-{name}.json", doc)
    print(name, doc)


def report(out):
    out = pathlib.Path(out)
    print("| 변형 | recall@2쪽 | 적중 사용자 | 세션 반영 | 모델 행 | 2쪽 p50 | 2쪽 p95 |")
    print("|---|---:|---:|---:|---:|---:|---:|")
    for name, label in (("v0", "V0 세션(지금)"), ("v1", "V1 구매"), ("v2", "V2 세션 + 구매")):
        if not (out / f"run-{name}.json").exists():
            continue
        s = summarize(out, name)
        (out / f"summary-{name}.json").write_text(json.dumps(s, indent=1))
        print(f"| {label} | {s['recall']:.4f} | {s['hit_users']:.1%} | {s['session_reflected']:.1%} | {s['all_genpage']:.1%} "
              f"| {s['p50_ms']:.0f}ms | {s['p95_ms']:.0f}ms |")
    for p in sorted(out.glob("offline-*.json")):
        print(f"- 배선({p.stem.split('-', 1)[1]}): {json.loads(p.read_text())}")


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    {"prepare": prepare, "signup": signup, "seed": seed, "run": run, "offline": offline, "report": report,
     "cleanup": cleanup}[cmd](*args)
