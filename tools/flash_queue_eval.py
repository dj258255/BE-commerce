"""한정 상품 대기열(#383) 결과를 k6 로그와 DB 에서 센다.

    python3 tools/flash_queue_eval.py collect OUT NAME LIMIT     # DB_PORT · DB_NAME 환경 변수
    python3 tools/flash_queue_eval.py report OUT

공정성 = 이탈하지 않은 구매자 중 먼저 온 100명 가운데 최종 PAID 인 사람 수.
헛걸음 = 주문 또는 결제 단계에서 매진 응답을 받은 사람 수.
"""
import json
import os
import re
import sys
from pathlib import Path

INITIAL = int(os.environ.get("INITIAL_STOCK", "100"))
PRODUCT_ID = int(os.environ.get("PRODUCT_ID", "90374"))


def results(out, name):
    rows = []
    for line in Path(out, f"k6-{name}.txt").read_text(errors="ignore").splitlines():
        m = re.search(r"RESULT (\{.*\})", line)
        if m:
            rows.append(json.loads(m.group(1)))
    return sorted(rows, key=lambda r: r["idx"])


def pct(values, p):
    if not values:
        return None
    v = sorted(values)
    return v[min(len(v) - 1, int(round(p / 100 * (len(v) - 1))))]


def collect(out, name, limit, lease="0"):
    import pymysql
    con = pymysql.connect(host="127.0.0.1", port=int(os.environ["DB_PORT"]), user="root", password="root",
                          database=os.environ["DB_NAME"])
    with con.cursor() as cur:
        cur.execute("SELECT quantity FROM stock WHERE product_id = %s", (PRODUCT_ID,))
        stock = cur.fetchone()[0]
        cur.execute("SELECT order_no, status FROM orders")
        status = dict(cur.fetchall())
        cur.execute("SELECT COALESCE(SUM(quantity), 0) FROM stock_reservations WHERE status = 'RESERVED'")
        held = int(cur.fetchone()[0])
        cur.execute("SELECT COUNT(*) FROM compensation_tasks WHERE reason LIKE %s", ("재고 부족%",))
        net_cancels = cur.fetchone()[0]
    con.close()
    rows = results(out, name)
    buyers = [r for r in rows if r.get("outcome") != "abandoned"]
    first100 = buyers[:100]
    fair = sum(1 for r in first100 if status.get(r.get("orderNo")) == "PAID")
    paid = sum(1 for s in status.values() if s == "PAID")
    waits = [r["waitMs"] / 1000 for r in rows if r.get("queue") and r.get("outcome") != "gave_up"]
    outcomes = {}
    for r in rows:
        outcomes[r["outcome"]] = outcomes.get(r["outcome"], 0) + 1
    summary = {
        "name": name, "admit_limit": int(limit), "lease": int(lease), "arrivals": len(rows), "outcomes": outcomes,
        "paid": paid, "stock": stock, "held": held, "unsold": INITIAL - paid, "net_cancels": net_cancels,
        "fair_first100": fair, "first100_size": len(first100),
        "wasted": outcomes.get("sold_out_at_order", 0) + outcomes.get("sold_out_at_payment", 0),
        "gave_up": outcomes.get("gave_up", 0),
        "lease_lost": outcomes.get("lease_lost", 0),
        "wait_p50": pct(waits, 50), "wait_p95": pct(waits, 95), "wait_max": max(waits) if waits else None,
        "oversell": paid > INITIAL or stock < 0,
    }
    Path(out, f"summary-{name}.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1))
    print(json.dumps(summary, ensure_ascii=False))


def report(out):
    rows = [json.loads(p.read_text()) for p in sorted(Path(out).glob("summary-*.json"))]
    rows.sort(key=lambda r: (r["admit_limit"], r.get("lease", 0)))
    lines = ["# 한정 상품 대기열(#383)", "",
             f"재고 {INITIAL}. 판매 · 팔지 못한 재고 · 공정성은 DB 의 최종 주문 상태로 셌다.", "",
             "| 조건 | 입장 인원 | 입장 칸 만료(초) | 판매 | 팔지 못한 재고 | 먼저 온 100명 중 산 사람 | 헛걸음 | 대기 포기 | 칸 잃음 | 대기 p50 · p95 · 최대(초) | 망취소 | 초과 판매 |",
             "|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---|"]
    for r in rows:
        w = "-" if r["wait_p50"] is None else f"{r['wait_p50']:.1f} · {r['wait_p95']:.1f} · {r['wait_max']:.1f}"
        lines.append(f"| {r['name']} | {r['admit_limit'] or '없음'} | {r.get('lease') or '-'} | {r['paid']} | {r['unsold']} | "
                     f"{r['fair_first100']}/{r['first100_size']} | {r['wasted']} | {r['gave_up']} | {r.get('lease_lost', 0)} | {w} | "
                     f"{r['net_cancels']} | {'있음' if r['oversell'] else '0'} |")
    lines += ["", "결과 분포:", ""] + [f"- {r['name']}: {json.dumps(r['outcomes'], ensure_ascii=False)}" for r in rows]
    text = "\n".join(lines) + "\n"
    print(text)
    return text


if __name__ == "__main__":
    if sys.argv[1] == "collect":
        collect(*sys.argv[2:6])
    elif sys.argv[1] == "report":
        report(sys.argv[2])
    else:
        raise SystemExit(__doc__)
