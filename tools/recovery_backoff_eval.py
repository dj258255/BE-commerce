"""미확정 복구의 백오프 간격이 무엇을 대가로 치르는지, PG 조회가 전부 실패할 때 복구가 PG 를 얼마나 두드리는지 잰다(#330).
판정 기준과 예측은 이슈에 측정 전에 고정했다.

    uv run --with pymysql python3 tools/recovery_backoff_eval.py release OUT NAME PORT STUCK OK STEP_S WINDOW_S
    uv run --with pymysql python3 tools/recovery_backoff_eval.py outage  OUT NAME PORT N WINDOW_S
    python3 tools/recovery_backoff_eval.py predict BASE_S CAP_S STUCK STEP_S
    python3 tools/recovery_backoff_eval.py report OUT

release: 막히는 건 STUCK 개(`rq-stuck-i`)를 가장 오래된 자리에, 풀리는 건 OK 개를 그 뒤에 넣는다. 가짜 PG 는 i 번째 막힌 건을
첫 "진행 중" 응답에서 (i+1) × STEP_S 초 뒤에 승인으로 바꾼다(`query-in-progress-release-step-ms`). 풀린 시각과 복구가 확정한
시각의 차이가 백오프 간격의 대가다.
outage: N 건(`rq-fail-i`)의 조회가 정해진 시간 동안 전부 예외로 끝난다(`query-fail-prefix`). 가짜 PG 에 닿은 조회 수를 2초마다 읽는다.

DB 는 DB_HOST · DB_PORT · DB_USER · DB_PASSWORD · DB_NAME 으로 고른다(기본은 compose 의 mysql).
"""
import json
import os
import pathlib
import re
import sys
import time
import urllib.request

TICK = 2.0


def db():
    import pymysql
    return pymysql.connect(host=os.environ.get("DB_HOST", "127.0.0.1"), port=int(os.environ.get("DB_PORT", "3306")),
                           user=os.environ.get("DB_USER", "root"), password=os.environ.get("DB_PASSWORD", "root"),
                           database=os.environ.get("DB_NAME", "becommerce"), autocommit=True)


def prom(port):
    """가짜 PG 조회 수와 첫 진행 중·첫 실패 시각(epoch ms)."""
    text = urllib.request.urlopen(f"http://localhost:{port}/actuator/prometheus", timeout=2).read().decode()
    out = {}
    for key, name in (("calls", "fake_pg_query_calls_total"), ("first_in_progress", "fake_pg_query_in_progress_first_epoch_ms"),
                      ("first_fail", "fake_pg_query_fail_first_epoch_ms")):
        m = re.search(rf"^{name}(?:{{[^}}]*}})? ([\d.eE+-]+)$", text, re.M)
        out[key] = float(m.group(1)) if m else None
    return out


def cleanup(c):
    c.execute("DELETE h FROM payment_history h JOIN payments p ON p.id = h.payment_id WHERE p.payment_key LIKE 'rq-%%'")
    c.execute("DELETE FROM payments WHERE payment_key LIKE 'rq-%%'")


def seed(c, rows):
    c.executemany(
        "INSERT INTO payments(amount, balance_amount, requested_at, version, pg_provider, order_no, payment_key, status, "
        "installment_months, cancel_count, recovery_attempts) "
        "VALUES (1000, 1000, NOW(6) - INTERVAL %s MICROSECOND, 0, 'TOSS_PAYMENTS', %s, %s, 'UNKNOWN', 0, 0, 0)",
        [(int(age * 1_000_000), order, key) for order, key, age in rows])


def release(out, name, port, stuck, ok, step_s, window):
    out = pathlib.Path(out)
    out.mkdir(parents=True, exist_ok=True)
    conn = db()
    c = conn.cursor()
    cleanup(c)
    seed(c, [(f"rq-order-s{i}", f"rq-stuck-{i}", 600 - i * 0.001) for i in range(stuck)]
         + [(f"rq-order-o{i}", f"rq-ok-{i}", 300 - i * 0.001) for i in range(ok)])
    t0 = time.time()
    confirmed, timeline, ok_done = {}, [], None
    while time.time() - t0 < window:
        now = time.time()
        c.execute("SELECT payment_key, status, recovery_attempts FROM payments WHERE payment_key LIKE 'rq-%%'")
        rows = c.fetchall()
        ok_left = sum(1 for k, s, _ in rows if k.startswith("rq-ok-") and s == "UNKNOWN")
        for k, s, _ in rows:
            if k.startswith("rq-stuck-") and s != "UNKNOWN" and k not in confirmed:
                confirmed[k] = now
        if ok_left == 0 and ok_done is None:
            ok_done = round(now - t0, 1)
        p = prom(port)
        timeline.append({"t": round(now - t0, 1), "ok_left": ok_left, "stuck_left": stuck - len(confirmed),
                         "stuck_tried": sum(1 for k, _, a in rows if k.startswith("rq-stuck-") and a >= 1), "calls": p["calls"]})
        if ok_left == 0 and len(confirmed) == stuck:
            break
        time.sleep(TICK)
    p = prom(port)
    c.execute("SELECT status, COUNT(*) FROM payments WHERE payment_key LIKE 'rq-%%' GROUP BY status")
    final = dict(c.fetchall())
    cleanup(c)
    conn.close()
    delays = []
    first = (p["first_in_progress"] or 0) / 1000
    for i in range(stuck):
        k = f"rq-stuck-{i}"
        if k in confirmed and first:
            delays.append(round(confirmed[k] - (first + (i + 1) * step_s), 1))
    doc = {"kind": "release", "name": name, "stuck": stuck, "ok": ok, "step_s": step_s, "window_s": window,
           "ok_resolved_s": ok_done, "stuck_confirmed": len(confirmed), "delays_s": delays,
           "pg_calls": p["calls"], "final_status": final, "timeline": timeline}
    (out / f"release-{name}.json").write_text(json.dumps(doc, indent=1))
    print(name, summarize_release(doc))


def outage(out, name, port, n, window):
    out = pathlib.Path(out)
    out.mkdir(parents=True, exist_ok=True)
    conn = db()
    c = conn.cursor()
    cleanup(c)
    seed(c, [(f"rq-order-f{i}", f"rq-fail-{i}", 600 - i * 0.001) for i in range(n)])
    t0 = time.time()
    timeline, done_at = [], None
    while time.time() - t0 < window:
        now = time.time()
        c.execute("SELECT SUM(status = 'UNKNOWN'), MIN(recovery_attempts), MAX(recovery_attempts) "
                  "FROM payments WHERE payment_key LIKE 'rq-fail-%%'")
        left, amin, amax = c.fetchone()
        p = prom(port)
        timeline.append({"t": round(now - t0, 1), "left": int(left or 0), "attempts_min": int(amin or 0),
                         "attempts_max": int(amax or 0), "calls": p["calls"], "first_fail": p["first_fail"]})
        if int(left or 0) == 0:
            done_at = now
            break
        time.sleep(TICK)
    c.execute("SELECT status, COUNT(*) FROM payments WHERE payment_key LIKE 'rq-%%' GROUP BY status")
    final = dict(c.fetchall())
    cleanup(c)
    conn.close()
    first_fail = (timeline[-1]["first_fail"] or 0) / 1000
    doc = {"kind": "outage", "name": name, "n": n, "window_s": window, "t0_epoch": t0, "first_fail_epoch": first_fail,
           "done_epoch": done_at, "final_status": final, "timeline": timeline}
    (out / f"outage-{name}.json").write_text(json.dumps(doc, indent=1))
    print(name, summarize_outage(doc))


def attempts(base_s, cap_s, horizon_s):
    """첫 시도를 0 으로 둔 시도 시각들: 간격은 base × 2^k, cap 에서 멈춘다."""
    t, k, out = 0.0, 0, [0.0]
    while t < horizon_s:
        t += min(base_s * 2 ** k, cap_s)
        k += 1
        out.append(t)
    return out


def predict(base_s, cap_s, stuck, step_s):
    """풀린 시각 r 뒤의 첫 시도 시각까지가 지연. 주기(5초)만큼 늦을 수 있어 실측은 이보다 최대 5초 크다."""
    tries = attempts(base_s, cap_s, (stuck + 1) * step_s + cap_s)
    delays, calls = [], 0
    for i in range(stuck):
        r = (i + 1) * step_s
        nxt = next(a for a in tries if a >= r)
        delays.append(nxt - r)
        calls += tries.index(nxt) + 1
    delays.sort()
    return {"mean_s": round(sum(delays) / len(delays), 1), "p50_s": delays[len(delays) // 2], "max_s": round(delays[-1], 1),
            "stuck_pg_calls": calls}


def summarize_release(r):
    d = sorted(r["delays_s"])
    return {"ok_resolved_s": r["ok_resolved_s"], "stuck_confirmed": f"{r['stuck_confirmed']}/{r['stuck']}",
            "delay_mean_s": round(sum(d) / len(d), 1) if d else None, "delay_p50_s": d[len(d) // 2] if d else None,
            "delay_max_s": d[-1] if d else None, "pg_calls": r["pg_calls"],
            "stuck_pg_calls": None if r["pg_calls"] is None else int(r["pg_calls"] - r["ok"])}


def summarize_outage(r):
    tl = r["timeline"]
    rates = []
    for a, b in zip(tl, tl[5:]):  # 약 10초 창
        if a["calls"] is not None and b["calls"] is not None and b["t"] > a["t"]:
            rates.append((b["calls"] - a["calls"]) / (b["t"] - a["t"]))
    return {"pg_calls_total": tl[-1]["calls"], "pg_calls_per_s_max_10s": round(max(rates), 2) if rates else None,
            "left_at_end": tl[-1]["left"], "attempts_max": tl[-1]["attempts_max"],
            "all_resolved_after_first_fail_s": round(r["done_epoch"] - r["first_fail_epoch"], 1)
            if r["done_epoch"] and r["first_fail_epoch"] else None}


def report(out):
    out = pathlib.Path(out)
    print("| 설정 | 풀리는 500건 해소 | 막힌 건 확정 | 확정 지연 평균 · 중앙 · 최대 | 막힌 건에 쓴 PG 조회 |")
    print("|---|---:|---:|---:|---:|")
    for p in sorted(out.glob("release-*.json")):
        r = json.loads(p.read_text())
        s = summarize_release(r)
        print(f"| `{r['name']}` | {s['ok_resolved_s']}초 | {s['stuck_confirmed']} | {s['delay_mean_s']} · {s['delay_p50_s']} · "
              f"{s['delay_max_s']}초 | {s['stuck_pg_calls']} |")
    for p in sorted(out.glob("outage-*.json")):
        r = json.loads(p.read_text())
        print(f"\n`{r['name']}` (전면 장애 {r['n']}건): {json.dumps(summarize_outage(r), ensure_ascii=False)}")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "release":
        release(sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5]), int(sys.argv[6]), float(sys.argv[7]), float(sys.argv[8]))
    elif cmd == "outage":
        outage(sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5]), float(sys.argv[6]))
    elif cmd == "predict":
        print(json.dumps(predict(float(sys.argv[2]), float(sys.argv[3]), int(sys.argv[4]), float(sys.argv[5]))))
    else:
        report(sys.argv[2])
