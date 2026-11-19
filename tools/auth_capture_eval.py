"""승인 · 매입 실험(#476) 결과를 DB · 앱 로그 · 지표에서 센다.

    python3 tools/auth_capture_eval.py snapshot OUT NAME     # DB_PORT · DB_NAME 환경 변수
    python3 tools/auth_capture_eval.py report OUT            # OUTAGE_MS 환경 변수

L(PG 승인 → 취소)은 가짜 PG 로그(FAKE_PG_CANCEL)에서 읽는다. 결과 모름 건은 우리가 승인을 안 시각이 아니라
PG 가 승인을 남긴 시각이 매입 기준이라 DB 의 approved_at 을 쓰지 않는다.
"""
import json
import os
import re
import statistics
import sys
from datetime import timezone
from pathlib import Path

LOG_RE = re.compile(r"FAKE_PG_CANCEL key=(\S+) approvedAtMs=(\S+) canceledAtMs=(\d+) sinceApproveMs=(-?\d+) afterCapture=(\w+)")


def connect():
    import pymysql
    return pymysql.connect(host="127.0.0.1", port=int(os.environ.get("DB_PORT", "13476")), user="root",
                           password="root", database=os.environ["DB_NAME"])


def ms(dt):
    return int(dt.replace(tzinfo=timezone.utc).timestamp() * 1000)


def snapshot(out, name):
    con = connect()
    with con.cursor() as cur:
        cur.execute("SELECT status, COUNT(*) FROM orders GROUP BY status")
        orders = {s: n for s, n in cur.fetchall()}
        cur.execute("SELECT COUNT(*) FROM payments WHERE status IN ('DONE','PARTIAL_CANCELED','CANCELED')")
        approved_payments = cur.fetchone()[0]
        cur.execute("""
            SELECT c.order_no, p.payment_key, c.status, c.retry_count, c.created_at, c.updated_at, c.last_outcome
              FROM compensation_tasks c LEFT JOIN payments p ON p.order_no = c.order_no
             ORDER BY c.created_at""")
        tasks = [{"order_no": o, "payment_key": k, "status": s, "retry_count": r,
                  "created_ms": ms(c), "updated_ms": ms(u), "last_outcome": lo}
                 for o, k, s, r, c, u, lo in cur.fetchall()]
    con.close()
    Path(out, f"db-{name}.json").write_text(json.dumps(
        {"orders": orders, "approved_payments": approved_payments, "tasks": tasks}, ensure_ascii=False, indent=1))
    print(name, "orders", orders, "tasks", len(tasks), "failed", sum(t["status"] == "FAILED" for t in tasks))


def metrics(out, name):
    """prometheus 텍스트에서 읽는다. 이름의 점은 밑줄로, 카운터는 _total 이 붙는다."""
    m = {}
    for line in Path(out, f"prom-{name}.txt").read_text().splitlines():
        key, val = line.split("{")[0].split(" ")[0], float(line.rsplit(" ", 1)[1])
        key = key.removesuffix("_total").replace("_", ".")
        m[key] = val
    return m


def cancels(out, name):
    rows = []
    for line in Path(out, f"app-{name}.log").read_text(errors="replace").splitlines():
        g = LOG_RE.search(line)
        if g:
            rows.append({"key": g[1], "approved_ms": None if g[2] == "null" else int(g[2]), "canceled_ms": int(g[3]),
                         "since_approve_ms": int(g[4]), "after_capture": g[5] == "true"})
    return rows


def pct(xs, q):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(q * (len(xs) - 1))))] if xs else None


def report(out):
    outage = int(os.environ.get("OUTAGE_MS", "180000"))
    result = {}
    for name in ("R1", "R2", "R3"):
        db = json.loads(Path(out, f"db-{name}.json").read_text())
        m = metrics(out, name)
        cs = cancels(out, name)
        tasks = db["tasks"]
        net = [t for t in tasks]
        failed = [t for t in net if t["status"] == "FAILED"]
        done = [t for t in net if t["status"] == "DONE"]
        pending = [t for t in net if t["status"] == "PENDING"]
        lat = [c["since_approve_ms"] / 1000 for c in cs if c["since_approve_ms"] >= 0]
        r = {
            "paid_orders": db["orders"].get("PAID", 0),
            "net_cancel_tasks": len(net), "done": len(done), "failed": len(failed), "pending": len(pending),
            "pg_cancel_success": len(cs),
            "cancel_failed_calls": m.get("fake.pg.cancel.failed"),
            "after_capture": sum(c["after_capture"] for c in cs),
            "before_capture": sum(not c["after_capture"] for c in cs),
            "L_s": {"n": len(lat), "min": min(lat) if lat else None, "p50": statistics.median(lat) if lat else None,
                    "max": max(lat) if lat else None},
        }
        first_fail = m.get("fake.pg.cancel.fail.first.epoch.ms") or 0
        if first_fail > 0:
            end = first_fail + outage
            # 예측: 첫 시도(적재 뒤 5초 안) + 약 40초가 장애 끝 전이면 다섯 번 모두 장애 안에서 실패해 FAILED
            pred_failed = [t for t in net if t["created_ms"] + 40_000 < end]
            borderline = [t for t in net if end - 45_000 <= t["created_ms"] <= end - 35_000]
            after_end = [c["canceled_ms"] - end for c in cs]
            r["outage"] = {
                "start_ms": int(first_fail), "end_ms": int(end),
                "tasks_created_rel_start_s": [round((t["created_ms"] - first_fail) / 1000, 1) for t in net],
                "predicted_failed": len(pred_failed), "borderline": len(borderline),
                "cancel_after_outage_end_s": {"max": max(after_end) / 1000 if after_end else None,
                                              "min": min(after_end) / 1000 if after_end else None},
                "failed_task_retry_counts": [t["retry_count"] for t in failed],
            }
        result[name] = r
    Path(out, "summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=1))
    print(json.dumps(result, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    {"snapshot": lambda: snapshot(sys.argv[2], sys.argv[3]), "report": lambda: report(sys.argv[2])}[sys.argv[1]]()
