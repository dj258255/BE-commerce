"""미확정 복구가 확정 못 한 건에 막혀 뒤를 굶기는지 잰다(#248). 판정 기준은 이슈에 측정 전에 고정했다.

    uv run --with pymysql python3 tools/recovery_order_eval.py run    OUT NAME STUCK OK WINDOW_S
    python3 tools/recovery_order_eval.py                        report OUT

앱은 복구를 켠 채 떠 있어야 한다(주기 5초, 청크 50, 가짜 PG 가 `rq-stuck-` 키에 늘 "진행 중"으로 답한다).
막히는 건 STUCK 개를 가장 오래된 자리(10분 전)에, 풀리는 건 OK 개를 그 뒤(5분 전)에 넣고, payments 표를 2초마다 읽는다.
모두 MIN_AGE(1분)보다 오래돼 바로 복구 대상이다. 끝나면 넣은 행을 지운다.
"""
import json
import pathlib
import sys
import time

TICK = 2.0


def db():
    import pymysql
    return pymysql.connect(host="127.0.0.1", port=3306, user="root", password="root", database="becommerce",
                           autocommit=True)


def cleanup(c):
    c.execute("DELETE h FROM payment_history h JOIN payments p ON p.id = h.payment_id WHERE p.payment_key LIKE 'rq-%%'")
    c.execute("DELETE FROM payments WHERE payment_key LIKE 'rq-%%'")


def seed(c, stuck, ok):
    rows = []
    for i in range(stuck):
        rows.append((f"rq-order-s{i}", f"rq-stuck-{i}", 600 - i * 0.001))
    for i in range(ok):
        rows.append((f"rq-order-o{i}", f"rq-ok-{i}", 300 - i * 0.001))
    c.executemany(
        "INSERT INTO payments(amount, balance_amount, requested_at, version, pg_provider, order_no, payment_key, status, "
        "installment_months, cancel_count, recovery_attempts) "
        "VALUES (1000, 1000, NOW(6) - INTERVAL %s MICROSECOND, 0, 'TOSS_PAYMENTS', %s, %s, 'UNKNOWN', 0, 0, 0)",
        [(int(age * 1_000_000), order, key) for order, key, age in rows])


def snapshot(c):
    c.execute("SELECT SUM(payment_key LIKE 'rq-ok-%%' AND status = 'UNKNOWN'), "
              "SUM(payment_key LIKE 'rq-stuck-%%' AND recovery_attempts >= 1), "
              "COALESCE(MAX(CASE WHEN payment_key LIKE 'rq-stuck-%%' THEN recovery_attempts END), 0), "
              "COALESCE(MIN(CASE WHEN payment_key LIKE 'rq-stuck-%%' THEN recovery_attempts END), 0) "
              "FROM payments WHERE payment_key LIKE 'rq-%%'")
    ok_left, stuck_tried, stuck_max, stuck_min = c.fetchone()
    return {"ok_left": int(ok_left or 0), "stuck_tried": int(stuck_tried or 0),
            "stuck_attempts_max": int(stuck_max), "stuck_attempts_min": int(stuck_min)}


def run(out, name, stuck, ok, window):
    out = pathlib.Path(out)
    out.mkdir(parents=True, exist_ok=True)
    conn = db()
    c = conn.cursor()
    cleanup(c)
    seed(c, stuck, ok)
    t0 = time.time()
    timeline, resolved_at = [], None
    try:
        while time.time() - t0 < window:
            s = snapshot(c)
            s["t"] = round(time.time() - t0, 1)
            timeline.append(s)
            if s["ok_left"] == 0 and resolved_at is None:
                resolved_at = s["t"]
                if stuck == 0:
                    break
            time.sleep(TICK)
    finally:
        c.execute("SELECT status, COUNT(*) FROM payments WHERE payment_key LIKE 'rq-%%' GROUP BY status")
        final = dict(c.fetchall())
        cleanup(c)
        conn.close()
    last = timeline[-1]
    doc = {"name": name, "stuck": stuck, "ok": ok, "window_s": window, "resolved_s": resolved_at,
           "ok_left_at_end": last["ok_left"], "stuck_tried": last["stuck_tried"],
           "stuck_attempts_min": last["stuck_attempts_min"], "stuck_attempts_max": last["stuck_attempts_max"],
           "final_status": final, "timeline": timeline}
    (out / f"recovery-{name}.json").write_text(json.dumps(doc, indent=1))
    print(name, {k: v for k, v in doc.items() if k != "timeline"})


def report(out):
    out = pathlib.Path(out)
    runs = {json.loads(p.read_text())["name"]: json.loads(p.read_text()) for p in sorted(out.glob("recovery-*.json"))}
    base = runs.get("baseline", {}).get("resolved_s")
    print("| 정책 | 막힘 | 풀림 | 풀리는 건 전량 해소 | 기준선 대비 | 창 끝에 남은 풀리는 건 | 다시 조회된 막힌 건 | 막힌 건 시도 횟수(최소~최대) |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|")
    for name in ("baseline", "unordered", "oldest", "backoff"):
        r = runs.get(name)
        if not r:
            continue
        res = f"{r['resolved_s']:.0f}초" if r["resolved_s"] is not None else f"**{r['window_s']}초 안에 못 함**"
        ratio = f"{r['resolved_s'] / base:.2f}배" if base and r["resolved_s"] is not None else "—"
        tried = f"{r['stuck_tried']}/{r['stuck']}" if r["stuck"] else "—"
        attempts = f"{r['stuck_attempts_min']}~{r['stuck_attempts_max']}" if r["stuck"] else "—"
        print(f"| `{name}` | {r['stuck']} | {r['ok']} | {res} | {ratio} | {r['ok_left_at_end']} | {tried} | {attempts} |")


if __name__ == "__main__":
    if sys.argv[1] == "run":
        run(sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5]), float(sys.argv[6]))
    else:
        report(sys.argv[2])
