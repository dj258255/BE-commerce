"""재고를 언제 잡을지(#374) 부하 결과를 DB 에서 센다.

    python3 tools/stock_reservation_eval.py snapshot OUT NAME LABEL     # DB_PORT · DB_NAME 환경 변수
    python3 tools/stock_reservation_eval.py report OUT

전략마다 새 DB 를 쓰므로 DB 안의 주문이 곧 이번 부하의 주문이다. 결과 모름은 결제 키 접두어로 가른다
(unk-ok- = PG 에 승인으로 남음, unk-lost- = PG 에 없음).
"""
import json
import os
import sys
from pathlib import Path

INITIAL = int(os.environ.get("INITIAL_STOCK", "100"))
PRODUCT_ID = int(os.environ.get("PRODUCT_ID", "90374"))


def connect():
    import pymysql
    return pymysql.connect(host="127.0.0.1", port=int(os.environ.get("DB_PORT", "13374")), user="root",
                           password=os.environ.get("DB_PASSWORD", "root"), database=os.environ["DB_NAME"])


def one(cur, sql, *args):
    cur.execute(sql, args)
    row = cur.fetchone()
    return row[0] if row else None


def snapshot(out, name, label):
    con = connect()
    with con.cursor() as cur:
        stock = one(cur, "SELECT quantity FROM stock WHERE product_id = %s", PRODUCT_ID)
        cur.execute("SELECT status, COUNT(*) FROM orders GROUP BY status")
        orders = {s: n for s, n in cur.fetchall()}
        cur.execute("SELECT status, COALESCE(SUM(quantity), 0) FROM stock_reservations GROUP BY status")
        reservations = {s: int(n) for s, n in cur.fetchall()}
        net_cancels = one(cur, "SELECT COUNT(*) FROM compensation_tasks WHERE reason LIKE %s", "재고 부족%")
        cur.execute("""
            SELECT CASE WHEN p.payment_key LIKE 'unk-ok-%%' THEN 'ok' ELSE 'lost' END AS kind,
                   o.status,
                   TIMESTAMPDIFF(MICROSECOND, p.requested_at, o.updated_at) / 1e6 AS secs
              FROM payments p JOIN orders o ON o.order_no = p.order_no
             WHERE p.payment_key LIKE 'unk-%%'""")
        unknown = [{"kind": k, "order_status": s, "secs_to_last_update": float(t)} for k, s, t in cur.fetchall()]
        cur.execute("""
            SELECT COUNT(*) FROM compensation_tasks c JOIN payments p ON p.order_no = c.order_no
             WHERE c.reason LIKE %s AND p.payment_key LIKE 'unk-ok-%%'""", ("재고 부족%",))
        net_cancels_unknown = cur.fetchone()[0]
    con.close()
    paid = orders.get("PAID", 0)
    held = reservations.get("RESERVED", 0)
    snap = {
        "name": name, "label": label, "stock": stock, "orders": orders, "reservations": reservations,
        "paid": paid, "held": held, "net_cancels": net_cancels, "net_cancels_from_unknown": net_cancels_unknown,
        "unsold": INITIAL - paid,
        "safety": {
            "paid_le_initial": paid <= INITIAL,
            "stock_non_negative": stock is not None and stock >= 0,
            "deducted_eq_paid_plus_held": (INITIAL - stock) == paid + held,
        },
        "unknown": unknown,
    }
    Path(out, f"snap-{name}-{label}.json").write_text(json.dumps(snap, ensure_ascii=False, indent=1))
    print(json.dumps({k: snap[k] for k in ("name", "label", "stock", "paid", "held", "net_cancels", "unsold")},
                     ensure_ascii=False))


def k6_counts(out, name):
    p = Path(out, f"k6-{name}.json")
    if not p.exists():
        return {}
    metrics = json.loads(p.read_text()).get("metrics", {})
    return {k.replace("flash_", ""): int(v.get("count", 0)) for k, v in metrics.items() if k.startswith("flash_")}


def report(out):
    names = sorted({p.name.split("-")[1] for p in Path(out).glob("snap-*-final.json")},
                   key=lambda n: ["NONE", "CHECK", "AT_PAYMENT", "AT_ORDER"].index(n) if n in
                   ["NONE", "CHECK", "AT_PAYMENT", "AT_ORDER"] else 9)
    lines = ["# 재고를 언제 잡을지(#374) 부하 결과", "",
             f"한정 상품 재고 {INITIAL}. 값은 DB 에서 셌다(부하 끝 · 관찰 끝).", "",
             "| 전략 | 주문 생성 | 주문 단계 매진 | 이탈 | 결제 응답: 승인 · 결과 모름 · 망취소 · 결제 단계 매진 | 부하 끝: 판매 · 잡힌 재고 | 관찰 끝: 판매 · 팔지 못한 재고 | 망취소(그중 결과 모름) | 안전 |",
             "|---|---:|---:|---:|---|---|---|---|---|"]
    detail = []
    for n in names:
        end = json.loads(Path(out, f"snap-{n}-sale-end.json").read_text())
        fin = json.loads(Path(out, f"snap-{n}-final.json").read_text())
        k = k6_counts(out, n)
        safe = all(fin["safety"].values()) and all(end["safety"].values())
        lines.append(
            f"| {n} | {k.get('order_created', '-')} | {k.get('order_sold_out', '-')} | {k.get('abandoned', '-')} | "
            f"{k.get('confirm_paid', '-')} · {k.get('confirm_unknown', '-')} · {k.get('confirm_net_cancelled', '-')} · "
            f"{k.get('confirm_sold_out', '-')} | {end['paid']} · {end['held']} | {fin['paid']} · {fin['unsold']} | "
            f"{fin['net_cancels']}({fin['net_cancels_from_unknown']}) | {'통과' if safe else '위반 ' + json.dumps(fin['safety'])} |")
        secs = sorted(u["secs_to_last_update"] for u in fin["unknown"])
        by = {}
        for u in fin["unknown"]:
            by.setdefault((u["kind"], u["order_status"]), 0)
            by[(u["kind"], u["order_status"])] += 1
        if secs:
            detail.append(f"- {n}: 결과 모름 {len(secs)}건, 확인 → 주문 마지막 변경 중앙값 {secs[len(secs) // 2]:.0f}초 · "
                          f"최대 {secs[-1]:.0f}초, 끝 상태 {', '.join(f'{a}/{b} {c}' for (a, b), c in sorted(by.items()))}")
    lines += ["", "결과 모름 주문이 최종 상태가 되기까지:", ""] + detail
    text = "\n".join(lines) + "\n"
    Path(out, "report.md").write_text(text)
    print(text)


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "snapshot":
        snapshot(sys.argv[2], sys.argv[3], sys.argv[4])
    elif cmd == "report":
        report(sys.argv[2])
    else:
        raise SystemExit(__doc__)
