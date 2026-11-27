"""원본 MySQL에 BE-commerce와 같은 모양의 주문·결제 흐름을 쓴다.

실험의 원천 데이터를 만드는 도구다. BE-commerce 앱 전체를 띄우지 않고, 앱이 남기는 행의 모양만 흉내 낸다.
상태 값과 전이 순서는 원본을 따르지만 업무 규칙(재고, 쿠폰, 수수료 계산 등)은 넣지 않았다.

한 주문이 남기는 행:
  orders 1, order_items 1~3, payments 1, payment_history 2~3, internal_records 1(승인 seq 0), settlement_items 1
나중에 일어나는 일:
  부분 취소  payments.balance_amount 감소, internal_records seq 1..N(음수), settlement_items 금액 조정
  UNKNOWN    승인 응답을 모르는 결제. 몇 초 뒤 복구 배치가 DONE 또는 ABORTED로 확정한다
  구매 확정  settlement_items PENDING_CONFIRMATION -> CONFIRMED

예:
  python loadgen.py --rate 50 --duration 60
  python loadgen.py --rate 200 --sellers 100 --hot-seller-share 0.5   # 판매자 한 명에 절반이 몰린다
"""

import argparse
import random
import signal
import time
import uuid
from datetime import datetime, timedelta, timezone

import pymysql

KST = timezone(timedelta(hours=9))


def now_utc():
    # DATETIME(6)에는 시간대가 없다. UTC 벽시계 값을 그대로 넣는다.
    return datetime.now(timezone.utc).replace(tzinfo=None)


def kst_date(ts_utc):
    return ts_utc.replace(tzinfo=timezone.utc).astimezone(KST).date()


def connect(args):
    return pymysql.connect(host=args.host, port=args.port, user=args.user, password=args.password,
                           database="becommerce", autocommit=False)


def seed_catalog(cur, rng, sellers, products):
    cur.execute("SELECT COUNT(*) FROM sellers")
    have = cur.fetchone()[0]
    ts = now_utc()
    for i in range(have + 1, sellers + 2):  # id 1은 플랫폼 직판
        cur.execute("INSERT INTO sellers (id, business_number, legal_name, country_code, status, created_at, updated_at)"
                    " VALUES (%s, %s, %s, 'KR', 'ACTIVE', %s, %s)",
                    (i, f"BN{i:08d}", f"판매자 {i}", ts, ts))
    cur.execute("SELECT COUNT(*) FROM products")
    if cur.fetchone()[0] == 0:
        for pid in range(1, products + 1):
            cur.execute("INSERT INTO products (product_id, name, price, category_code, created_at) VALUES (%s, %s, %s, %s, %s)",
                        (pid, f"상품 {pid}", rng.randrange(10, 500) * 100, f"C{pid % 20:02d}", ts))


def pick_seller(rng, sellers, hot_share):
    if rng.random() < hot_share:
        return 2  # 인기 판매자
    return rng.randrange(1, sellers + 2)


def history(cur, payment_id, frm, to, by, ts, reason=None):
    cur.execute("INSERT INTO payment_history (payment_id, from_status, to_status, triggered_by, reason, created_at)"
                " VALUES (%s, %s, %s, %s, %s, %s)", (payment_id, frm, to, by, reason, ts))


def place_order(cur, rng, args, products):
    ts = now_utc()
    order_no = uuid.uuid4().hex[:20]
    items = [(rng.randrange(1, products + 1), rng.randrange(1, 4)) for _ in range(rng.randrange(1, 4))]
    cur.execute("SELECT product_id, name, price FROM products WHERE product_id IN %s", ([p for p, _ in items],))
    catalog = {r[0]: r for r in cur.fetchall()}
    total = sum(catalog[p][2] * q for p, q in items)
    cur.execute("INSERT INTO orders (order_no, user_id, total_amount, currency, status, version, created_at, updated_at, expires_at)"
                " VALUES (%s, %s, %s, 'KRW', 'PAYMENT_IN_PROGRESS', 0, %s, %s, %s)",
                (order_no, rng.randrange(1, args.users + 1), total, ts, ts, ts + timedelta(minutes=30)))
    order_id = cur.lastrowid
    for p, q in items:
        cur.execute("INSERT INTO order_items (order_id, product_id, product_name, unit_price, quantity) VALUES (%s, %s, %s, %s, %s)",
                    (order_id, p, catalog[p][1], catalog[p][2], q))
    cur.execute("INSERT INTO payments (order_no, payment_key, pg_provider, method, amount, balance_amount, status, requested_at, version)"
                " VALUES (%s, %s, 'MOCK_PG', 'CARD', %s, %s, 'IN_PROGRESS', %s, 0)",
                (order_no, uuid.uuid4().hex, total, total, ts))
    payment_id = cur.lastrowid
    history(cur, payment_id, "READY", "IN_PROGRESS", "USER", ts)
    if rng.random() < args.unknown_rate:
        cur.execute("UPDATE payments SET status='UNKNOWN', unknown_reason='PG_TIMEOUT', version=version+1 WHERE id=%s", (payment_id,))
        history(cur, payment_id, "IN_PROGRESS", "UNKNOWN", "USER", ts, "PG_TIMEOUT")
        return ("unknown", payment_id, ts)
    approve(cur, rng, args, payment_id, order_no, total, ts, "USER", "IN_PROGRESS")
    return ("paid", payment_id, ts)


def approve(cur, rng, args, payment_id, order_no, amount, ts, by, frm):
    cur.execute("UPDATE payments SET status='DONE', approved_at=%s, unknown_reason=NULL, version=version+1 WHERE id=%s", (ts, payment_id))
    history(cur, payment_id, frm, "DONE", by, ts)
    cur.execute("UPDATE orders SET status='PAID', version=version+1, updated_at=%s WHERE order_no=%s", (ts, order_no))
    cur.execute("INSERT INTO internal_records (order_no, seq, amount, recorded_at, trade_date) VALUES (%s, 0, %s, %s, %s)",
                (order_no, amount, ts, kst_date(ts)))
    cur.execute("INSERT INTO settlement_items (payment_id, order_no, seller_id, amount, confirmed_date, status)"
                " VALUES (%s, %s, %s, %s, %s, 'PENDING_CONFIRMATION')",
                (payment_id, order_no, pick_seller(rng, args.sellers, args.hot_seller_share), amount, kst_date(ts) + timedelta(days=7)))


def resolve_unknown(cur, rng, args, payment_id):
    ts = now_utc()
    cur.execute("SELECT order_no, amount, status FROM payments WHERE id=%s FOR UPDATE", (payment_id,))
    order_no, amount, status = cur.fetchone()
    if status != "UNKNOWN":
        return
    if rng.random() < 0.7:  # PG 조회 결과 승인돼 있었다
        approve(cur, rng, args, payment_id, order_no, amount, ts, "RECOVERY_BATCH", "UNKNOWN")
    else:
        cur.execute("UPDATE payments SET status='ABORTED', version=version+1 WHERE id=%s", (payment_id,))
        history(cur, payment_id, "UNKNOWN", "ABORTED", "RECOVERY_BATCH", ts)
        cur.execute("UPDATE orders SET status='FAILED', version=version+1, updated_at=%s WHERE order_no=%s", (ts, order_no))


def partial_cancel(cur, rng, payment_id):
    ts = now_utc()
    cur.execute("SELECT order_no, balance_amount, cancel_count, status FROM payments WHERE id=%s FOR UPDATE", (payment_id,))
    order_no, balance, count, status = cur.fetchone()
    if status not in ("DONE", "PARTIAL_CANCELED") or balance <= 0:
        return
    cut = balance if rng.random() < 0.4 else max(100, (balance * rng.randrange(10, 60) // 100) // 100 * 100)
    left, seq = balance - cut, count + 1
    to = "CANCELED" if left == 0 else "PARTIAL_CANCELED"
    cur.execute("UPDATE payments SET balance_amount=%s, cancel_count=%s, status=%s, version=version+1 WHERE id=%s",
                (left, seq, to, payment_id))
    history(cur, payment_id, status, to, "USER", ts)
    cur.execute("INSERT INTO internal_records (order_no, seq, amount, recorded_at, trade_date) VALUES (%s, %s, %s, %s, %s)",
                (order_no, seq, -cut, ts, kst_date(ts)))
    cur.execute("UPDATE settlement_items SET amount=%s, last_cancel_seq=%s, status=IF(%s=0,'CANCELED',status), version=version+1"
                " WHERE payment_id=%s AND status='PENDING_CONFIRMATION'", (left, seq, left, payment_id))
    if left == 0:
        cur.execute("UPDATE orders SET status='CANCELED', version=version+1, updated_at=%s WHERE order_no=%s", (ts, order_no))


def confirm_due(cur, older_than_s):
    ts = now_utc()
    # 다중 테이블 UPDATE에는 LIMIT을 못 쓴다(MySQL 1221). 대상 id를 먼저 고른다.
    cur.execute("SELECT si.id FROM settlement_items si JOIN payments p ON p.id = si.payment_id"
                " WHERE si.status='PENDING_CONFIRMATION' AND p.approved_at < %s LIMIT 500",
                (ts - timedelta(seconds=older_than_s),))
    ids = [r[0] for r in cur.fetchall()]
    if not ids:
        return 0
    cur.execute("UPDATE settlement_items SET status='CONFIRMED', confirmed_date=%s, version=version+1"
                " WHERE id IN %s AND status='PENDING_CONFIRMATION'", (kst_date(ts), ids))
    return cur.rowcount


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=3306)
    ap.add_argument("--user", default="becommerce")
    ap.add_argument("--password", default="becommerce-local-only")
    ap.add_argument("--rate", type=float, default=20, help="초당 주문 수")
    ap.add_argument("--duration", type=float, default=60, help="초. 0이면 Ctrl-C까지")
    ap.add_argument("--sellers", type=int, default=50)
    ap.add_argument("--products", type=int, default=500)
    ap.add_argument("--users", type=int, default=10000)
    ap.add_argument("--hot-seller-share", type=float, default=0.0, help="판매자 2에 몰리는 주문 비율")
    ap.add_argument("--cancel-rate", type=float, default=0.1, help="주문 하나당 나중에 취소가 일어날 확률")
    ap.add_argument("--unknown-rate", type=float, default=0.02, help="승인 응답을 모르는 결제 비율")
    ap.add_argument("--confirm-after", type=float, default=30, help="승인 뒤 몇 초 지나면 구매 확정할지")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    rng = random.Random(args.seed)
    conn = connect(args)
    with conn.cursor() as cur:
        seed_catalog(cur, rng, args.sellers, args.products)
    conn.commit()

    stop = False

    def on_sig(*_):
        nonlocal stop
        stop = True
    signal.signal(signal.SIGINT, on_sig)
    signal.signal(signal.SIGTERM, on_sig)

    counts = {"orders": 0, "unknown": 0, "resolved": 0, "cancels": 0, "confirmed": 0}
    pending_unknown, paid = [], []
    start = last_report = last_confirm = time.monotonic()
    interval = 1.0 / args.rate
    next_at = start
    while not stop and (args.duration == 0 or time.monotonic() - start < args.duration):
        with conn.cursor() as cur:
            kind, pid, _ = place_order(cur, rng, args, args.products)
            conn.commit()
            counts["orders"] += 1
            if kind == "unknown":
                counts["unknown"] += 1
                pending_unknown.append((time.monotonic() + rng.uniform(3, 10), pid))
            else:
                paid.append(pid)
                if rng.random() < args.cancel_rate:
                    paid_pick = rng.choice(paid[-1000:])
                    partial_cancel(cur, rng, paid_pick)
                    conn.commit()
                    counts["cancels"] += 1
            due = [x for x in pending_unknown if x[0] <= time.monotonic()]
            for item in due:
                resolve_unknown(cur, rng, args, item[1])
                conn.commit()
                pending_unknown.remove(item)
                counts["resolved"] += 1
            if time.monotonic() - last_confirm >= 5:
                counts["confirmed"] += confirm_due(cur, args.confirm_after)
                conn.commit()
                last_confirm = time.monotonic()
        if time.monotonic() - last_report >= 5:
            el = time.monotonic() - start
            print(f"{el:6.0f}s {counts} 실제 주문률 {counts['orders'] / el:.1f}/s", flush=True)
            last_report = time.monotonic()
        next_at += interval
        delay = next_at - time.monotonic()
        if delay > 0:
            time.sleep(delay)
    el = time.monotonic() - start
    print(f"끝 {el:.0f}s {counts} 미확정 UNKNOWN {len(pending_unknown)}건", flush=True)
    conn.close()


if __name__ == "__main__":
    main()
