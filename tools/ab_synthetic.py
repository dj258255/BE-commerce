"""A/B 기반 검증용 합성 사용자(#256). 사용자 행동은 여기서 정한 확률이다 — 추천 품질에 대한 주장이 아니다.

    uv run --with pymysql python3 tools/ab_synthetic.py users OUT N BASE
    uv run --with pymysql python3 tools/ab_synthetic.py run   OUT NAME BASE CLICK_C CLICK_T BUY_C BUY_T NOISE_CLICK NOISE_BUY

run: 사용자마다 홈 1쪽을 세 번 연다(고정 배정 확인). 변형에 따라 정한 확률로
  - 노출된 상품 하나를 클릭한다(CLICK_C · CLICK_T)
  - 노출된 상품 하나를 산다(BUY_C · BUY_T, 주문 + 결제 승인)
  - 음성 대조: 변형과 무관하게 노출되지 않은 상품을 클릭(NOISE_CLICK)·구매(NOISE_BUY)한다. 분석은 이것을 세면 안 된다
사용자가 실제로 한 일은 OUT/truth-NAME.json 에 남긴다(분석과 맞출 정답).
"""
import concurrent.futures
import datetime as dt
import json
import pathlib
import random
import sys
import time
import urllib.request
import uuid


def call(method, url, token=None, body=None, headers=None):
    h = {"Content-Type": "application/json", **(headers or {})}
    if token:
        h["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=json.dumps(body).encode() if body is not None else None, method=method, headers=h)
    with urllib.request.urlopen(req, timeout=30) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def users(out, n, base):
    out = pathlib.Path(out)
    out.mkdir(parents=True, exist_ok=True)
    stamp = int(time.time())

    def one(i):
        email = f"ab-{stamp}-{i}@load.test"
        call("POST", f"{base}/api/v1/members/signup", body={"email": email, "password": "ab-load-only-1234"})
        token = call("POST", f"{base}/api/v1/auth/login", body={"username": email, "password": "ab-load-only-1234"})["token"]
        return {"email": email, "token": token}
    with concurrent.futures.ThreadPoolExecutor(8) as pool:
        us = list(pool.map(one, range(int(n))))
    (out / "users.json").write_text(json.dumps(us))
    print("사용자", len(us))


def noise_pool():
    import pymysql
    conn = pymysql.connect(host="127.0.0.1", port=3306, user="root", password="root", database="becommerce")
    c = conn.cursor()
    c.execute("SELECT p.product_id FROM products p JOIN stock s ON s.product_id = p.product_id WHERE s.quantity > 20 "
              "ORDER BY p.product_id LIMIT 3000")
    ids = [r[0] for r in c.fetchall()]
    conn.close()
    return ids


def buy(base, token, pid):
    order = call("POST", f"{base}/api/v1/orders", token, {"items": [{"productId": pid, "quantity": 1}]})
    call("POST", f"{base}/api/v1/payments/confirm", token,
         {"paymentKey": f"ab-{uuid.uuid4()}", "orderNo": order["orderNo"], "amount": order["totalAmount"]},
         {"Idempotency-Key": str(uuid.uuid4())})


def run(out, name, base, click_c, click_t, buy_c, buy_t, noise_click, noise_buy):
    out = pathlib.Path(out)
    us = json.loads((out / "users.json").read_text())
    noise = noise_pool()
    since = dt.datetime.now(dt.timezone.utc).replace(tzinfo=None) - dt.timedelta(seconds=1)
    p_click = {"control": float(click_c), "treatment": float(click_t)}
    p_buy = {"control": float(buy_c), "treatment": float(buy_t)}

    def one(i):
        u = us[i]
        rnd = random.Random(f"{name}-{i}")
        visits = [call("GET", f"{base}/api/v1/personalization/homepage", u["token"]) for _ in range(3)]
        variants = [v.get("variant") for v in visits]
        v = variants[0]
        shown = [int(it["itemId"]) for r in visits[0]["rows"] for it in r["items"]]
        t = {"user_id": int(visits[0]["userId"]), "variants": variants, "shown_click": 0, "shown_buy": 0,
             "noise_click": 0, "noise_buy": 0}
        seq = int(time.time() * 1000) * 10
        if v in p_click and shown and rnd.random() < p_click[v]:
            call("POST", f"{base}/api/v1/personalization/activity", u["token"],
                 {"itemId": rnd.choice(shown), "type": "CLICK", "seq": seq + 1})
            t["shown_click"] = 1
        if v in p_buy and shown and rnd.random() < p_buy[v]:
            buy(base, u["token"], rnd.choice(shown))
            t["shown_buy"] = 1
        if rnd.random() < float(noise_click):
            candidates = [p for p in noise if p not in set(shown)]
            call("POST", f"{base}/api/v1/personalization/activity", u["token"],
                 {"itemId": rnd.choice(candidates), "type": "CLICK", "seq": seq + 2})
            t["noise_click"] = 1
        if rnd.random() < float(noise_buy):
            candidates = [p for p in noise if p not in set(shown)]
            buy(base, u["token"], rnd.choice(candidates))
            t["noise_buy"] = 1
        return t

    started = time.time()
    with concurrent.futures.ThreadPoolExecutor(8) as pool:
        truth = list(pool.map(one, range(len(us))))
    doc = {"name": name, "since": since.isoformat(), "seconds": round(time.time() - started, 1),
           "params": {"click": p_click, "buy": p_buy, "noise_click": float(noise_click), "noise_buy": float(noise_buy)},
           "users": truth}
    (out / f"truth-{name}.json").write_text(json.dumps(doc))
    by = {}
    for t in truth:
        b = by.setdefault(t["variants"][0], {"users": 0, "shown_click": 0, "shown_buy": 0, "noise_click": 0, "noise_buy": 0})
        b["users"] += 1
        for k in ("shown_click", "shown_buy", "noise_click", "noise_buy"):
            b[k] += t[k]
    print(name, "since", since.isoformat(), json.dumps(by))


if __name__ == "__main__":
    cmd, a = sys.argv[1], sys.argv[2:]
    if cmd == "users":
        users(*a)
    else:
        run(*a)
