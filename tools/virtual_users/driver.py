"""A/B 용 가상 사용자 드라이버(#292). 가상 사용자가 홈에서 **실제로 보인 상품만 보고** 클릭 · 구매한다.

    PY="uv run --with pymysql,pandas,pyarrow python3"   # 표본 준비에 H&M 데이터가 필요하다(GENPAGE_DATA)
    $PY tools/virtual_users/driver.py prepare OUT N              # 페르소나 N 명(H&M 홀드아웃 표본 seed 7)
    $PY tools/virtual_users/driver.py signup  OUT APP_URL        # 페르소나마다 회원 가입
    $PY tools/virtual_users/driver.py seed    OUT                # 과거 구매 최근 100건을 그 회원의 결제 완료 주문으로 심는다
    $PY tools/virtual_users/driver.py run     OUT NAME APP_URL [rule|ollama] [세션 수]
    $PY tools/virtual_users/driver.py summary OUT NAME
    $PY tools/virtual_users/driver.py cleanup                    # 심은 주문을 지운다

세션 하나: 로그인 → 홈 1쪽 → 판단 → 클릭 기록 → (정책이 원하면) 2쪽 → 판단 → 클릭 기록 → 고른 것 구매(주문 + 결제).
기록은 OUT/sessions-NAME.jsonl 에 세션마다 한 줄이다. 보인 상품 전부(행 · 자리) · 클릭 · 구매 · 지나친 것이 들어간다 — Netflix GenPage 의
학습 예시(context · page · feedback)와 같은 모양이라, 나중에 반응으로 후학습(WBC)할 데이터로도 쓸 수 있다.

A/B 로 쓸 때: 앱을 `rec-history` 실험을 켜고(새 솔트) 띄우고, run 뒤에 `tools/ab_analysis.py rec-history <since>` 로 판정한다.
since 는 run 이 출력하고 기록 첫 줄에도 남긴다. 레이트리밋을 끈 앱이어야 한다(가상 사용자가 한 IP 에서 온다).
"""
import concurrent.futures
import datetime as dt
import json
import os
import pathlib
import random
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import personas as P  # noqa: E402
from policies import POLICIES  # noqa: E402

PASSWORD = "vu-load-only-1234"
ID_BASE = 9_300_000_000          # 심는 주문 · 항목 id 의 시작(다른 하네스의 9,100,000,000 · 9,200,000,000 대와 겹치지 않게)


def call(base, method, path, token=None, body=None, headers=None):
    h = {"Content-Type": "application/json", **(headers or {})}
    if token:
        h["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(base + path, data=json.dumps(body).encode() if body is not None else None,
                                 method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        return e.code, None


def prepare(out, n):
    P.build(out, n)


def signup(out, base):
    personas = P.load(out)
    stamp = int(time.time())

    def one(p):
        email = f"vu-{stamp}-{p.i}@load.test"
        status, body = call(base, "POST", "/api/v1/members/signup", body={"email": email, "password": PASSWORD})
        if status != 201:
            raise RuntimeError(f"가입 실패 {status} {email}")
        return str(p.i), {"email": email, "member": body["id"]}

    with concurrent.futures.ThreadPoolExecutor(4) as pool:
        members = dict(pool.map(one, personas))
    (pathlib.Path(out) / "members.json").write_text(json.dumps(members))
    print(f"가입 {len(members)}명")


def seed(out):
    personas = P.load(out)
    members = json.loads((pathlib.Path(out) / "members.json").read_text())
    cleanup()
    orders, items = [], []
    oid, iid = ID_BASE, ID_BASE
    for p in personas:
        user = members[str(p.i)]["member"]
        prev = None
        for a, d in zip(p.history[-P.HISTORY_LIMIT:], p.days[-P.HISTORY_LIMIT:]):
            if d != prev:
                oid += 1
                orders.append((oid, user, f"vu-{user}-{oid}", f"{d} 12:00:00"))
                prev = d
            iid += 1
            items.append((iid, oid, a))
    conn = P.gpe.db()
    with conn.cursor() as cur:
        for k in range(0, len(orders), 5000):
            cur.executemany("INSERT INTO orders(id, user_id, order_no, status, currency, total_amount, created_at, updated_at, "
                            "expires_at, version) VALUES (%s, %s, %s, 'PAID', 'KRW', 0, %s, NOW(6), NOW(6), 0)", orders[k:k + 5000])
        for k in range(0, len(items), 5000):
            cur.executemany("INSERT INTO order_items(id, order_id, product_id, quantity, unit_price, product_name) "
                            "VALUES (%s, %s, %s, 1, 0, 'vu')", items[k:k + 5000])
    conn.commit()
    conn.close()
    print(f"주문 {len(orders):,} · 항목 {len(items):,}")


def cleanup(_=None):
    conn = P.gpe.db()
    with conn.cursor() as c:
        c.execute("DELETE FROM order_items WHERE order_id >= %s AND order_id < %s", (ID_BASE, ID_BASE + 100_000_000))
        c.execute("DELETE FROM orders WHERE id >= %s AND id < %s", (ID_BASE, ID_BASE + 100_000_000))
    conn.commit()
    conn.close()


class Catalog:
    """보인 상품의 속성(종류 · 색)을 한 번씩만 읽는다. 카드에는 이름 · 가격 · 대분류만 있다."""

    def __init__(self):
        self.cache, self.lock = {}, threading.Lock()

    def enrich(self, ids):
        missing = [i for i in ids if i not in self.cache]
        if missing:
            found = P.attributes(missing)
            with self.lock:
                for i in missing:
                    self.cache[i] = found.get(i)
        return self.cache


def page_view(body, catalog):
    """홈 응답 → 정책이 받는 모양. 행 순서(rank)와 행 안 자리(pos)를 붙인다."""
    ids = [int(it["itemId"]) for r in body["rows"] for it in r["items"]]
    attrs = catalog.enrich(ids)
    rows = []
    for rank, r in enumerate(body["rows"]):
        items = []
        for pos, it in enumerate(r["items"]):
            pid = int(it["itemId"])
            a = attrs.get(pid)
            items.append({"id": pid, "name": it["name"], "price": it["price"], "category": it.get("category"),
                          "type": a[1] if a else None, "colour": a[2] if a else None, "pos": pos})
        rows.append({"row": r["id"], "title": r["title"], "strategy": r["strategy"], "rank": rank, "items": items})
    return rows


def buy(base, token, pid):
    status, order = call(base, "POST", "/api/v1/orders", token, {"items": [{"productId": pid, "quantity": 1}]})
    if status not in (200, 201) or not order:
        return f"order-{status}"
    status, _ = call(base, "POST", "/api/v1/payments/confirm", token,
                     {"paymentKey": f"vu-{uuid.uuid4()}", "orderNo": order["orderNo"], "amount": order["totalAmount"]},
                     {"Idempotency-Key": str(uuid.uuid4())})
    return "ok" if status in (200, 201) else f"payment-{status}"


def run(out, name, base, policy="rule", sessions="1"):
    out = pathlib.Path(out)
    personas = P.load(out)
    members = json.loads((out / "members.json").read_text())
    decide = POLICIES[policy]()
    catalog = Catalog()
    since = dt.datetime.now(dt.timezone.utc).replace(tzinfo=None) - dt.timedelta(seconds=1)
    log_path = out / f"sessions-{name}.jsonl"
    lock = threading.Lock()
    with log_path.open("w", encoding="utf-8") as f:
        f.write(json.dumps({"meta": {"name": name, "policy": policy, "since": since.isoformat(), "sessions": int(sessions)}}) + "\n")

    def one(p):
        m = members[str(p.i)]
        rnd = random.Random(f"{name}-{p.i}")
        _, login = call(base, "POST", "/api/v1/auth/login", body={"username": m["email"], "password": PASSWORD})
        token = login["token"]
        seq = int(time.time() * 1000) * 100      # 활동 순번은 사용자별로 단조 증가하면 된다 — 실행마다 겹치지 않게 시각에서 시작
        for s in range(int(sessions)):
            record = {"persona": p.i, "user": m["member"], "session": s, "at": dt.datetime.now(dt.timezone.utc).isoformat(),
                      "pages": [], "clicks": [], "buys": [], "outside": 0, "notes": []}
            status, body = call(base, "GET", "/api/v1/personalization/homepage", token)
            cursor = None
            for page_no in (1, 2):
                if status != 200 or not body:
                    record["notes"].append(f"page{page_no}-{status}")
                    break
                record.setdefault("variant", body.get("variant"))
                view = page_view(body, catalog)
                shown = {it["id"] for r in view for it in r["items"]}
                d = decide.decide(p, view, rnd)
                if d.note:
                    record["notes"].append(d.note)
                clicks = [c for c in d.clicks if c in shown]
                buys = [b for b in d.buys if b in shown]
                record["outside"] += (len(d.clicks) - len(clicks)) + (len(d.buys) - len(buys))
                for c in clicks:
                    seq += 1
                    call(base, "POST", "/api/v1/personalization/activity", token, {"itemId": c, "type": "CLICK", "seq": seq})
                record["pages"].append({"page": page_no, "source": body.get("source"),
                                        "rows": [{"row": r["row"], "strategy": r["strategy"],
                                                  "items": [it["id"] for it in r["items"]]} for r in view],
                                        "clicks": clicks, "buys": buys,
                                        "passed": [i for i in shown if i not in clicks]})
                record["clicks"] += clicks
                record["buys"] += [b for b in buys if b not in record["buys"]]
                cursor = body.get("nextCursor")
                if page_no == 2 or not d.next_page or not cursor:
                    break
                status, body = call(base, "GET", "/api/v1/personalization/homepage?cursor=" + cursor, token)
            record["buy_results"] = [buy(base, token, b) for b in record["buys"]]
            record["bought_wanted"] = sum(1 for b in record["buys"] if b in set(p.wanted))
            with lock, log_path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(record) + "\n")

    workers = int(os.environ.get("VU_WORKERS", "4" if policy == "rule" else "1"))
    started = time.time()
    with concurrent.futures.ThreadPoolExecutor(workers) as pool:
        list(pool.map(one, personas))
    print(f"{name}: 세션 {len(personas) * int(sessions)} · {time.time() - started:.0f}초 · since {since.isoformat()}")
    summary(out, name)


def summary(out, name):
    rows = [json.loads(l) for l in (pathlib.Path(out) / f"sessions-{name}.jsonl").read_text().splitlines()[1:]]
    by = {}
    for r in rows:
        b = by.setdefault(r.get("variant") or "none", {"sessions": 0, "shown": 0, "clicks": 0, "buy_sessions": 0, "buys": 0,
                                                       "bought_wanted": 0, "page2": 0, "outside": 0, "notes": 0, "buy_failed": 0})
        b["sessions"] += 1
        b["shown"] += sum(len(x["items"]) for pg in r["pages"] for x in pg["rows"])
        b["clicks"] += len(r["clicks"])
        b["buys"] += len(r["buys"])
        b["buy_sessions"] += bool(r["buys"])
        b["bought_wanted"] += r["bought_wanted"]
        b["page2"] += len(r["pages"]) > 1
        b["outside"] += r["outside"]
        b["notes"] += len(r["notes"])
        b["buy_failed"] += sum(1 for x in r.get("buy_results", []) if x != "ok")
    print(json.dumps(by, ensure_ascii=False, indent=1))
    return by


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    {"prepare": prepare, "signup": signup, "seed": seed, "run": run, "summary": summary, "cleanup": cleanup}[cmd](*args)
