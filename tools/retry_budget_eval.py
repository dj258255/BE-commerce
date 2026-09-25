"""조회 재시도가 일시 장애를 흡수하는지, PG 호출을 부풀리는지 가른다(#334). 판정 기준과 예측은 이슈에 측정 전에 고정했다.

    uv run --with pymysql python3 tools/retry_budget_eval.py run    OUT NAME PORT N WINDOW_S
    python3 tools/retry_budget_eval.py                        predict FAIL_RATE MAX_ATTEMPTS N
    python3 tools/retry_budget_eval.py                        report OUT

미확정 N 건(`rq-fail-i`)을 넣는다. 가짜 PG 는 이 키의 조회를 호출마다 FAIL_RATE 확률로 예외로 끝낸다(`query-fail-rate`).
2초마다 남은 미확정 · 가짜 PG 에 닿은 조회 · 재시도 · 재시도 소진을 읽는다. DB 는 recovery_backoff_eval 과 같은 DB_* 환경 변수로 고른다.
"""
import json
import math
import pathlib
import re
import sys
import time
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from recovery_backoff_eval import cleanup, db, seed  # noqa: E402

TICK = 2.0
METRICS = {"calls": "fake_pg_query_calls_total", "retries": "payment_pg_query_retry_total",
           "exhausted": "payment_pg_query_retry_exhausted_total"}


def prom(port):
    text = urllib.request.urlopen(f"http://localhost:{port}/actuator/prometheus", timeout=2).read().decode()
    out = {}
    for key, name in METRICS.items():
        m = re.search(rf"^{name}(?:{{[^}}]*}})? ([\d.eE+-]+)$", text, re.M)
        out[key] = float(m.group(1)) if m else 0.0
    return out


def run(out, name, port, n, window):
    out = pathlib.Path(out)
    out.mkdir(parents=True, exist_ok=True)
    conn = db()
    c = conn.cursor()
    cleanup(c)
    seed(c, [(f"rq-order-f{i}", f"rq-fail-{i}", 600 - i * 0.001) for i in range(n)])
    start = prom(port)
    t0 = time.time()
    timeline, done = [], None
    while time.time() - t0 < window:
        now = time.time()
        c.execute("SELECT SUM(status = 'UNKNOWN'), MAX(recovery_attempts) FROM payments WHERE payment_key LIKE 'rq-fail-%%'")
        left, amax = c.fetchone()
        p = prom(port)
        timeline.append({"t": round(now - t0, 1), "left": int(left or 0), "attempts_max": int(amax or 0),
                         **{k: p[k] - start[k] for k in METRICS}})
        if int(left or 0) == 0:
            done = round(now - t0, 1)
            break
        time.sleep(TICK)
    c.execute("SELECT status, COUNT(*) FROM payments WHERE payment_key LIKE 'rq-%%' GROUP BY status")
    final = dict(c.fetchall())
    cleanup(c)
    conn.close()
    doc = {"name": name, "n": n, "window_s": window, "drained_s": done, "final_status": final, "timeline": timeline}
    (out / f"retry-{name}.json").write_text(json.dumps(doc, indent=1))
    print(name, summarize(doc))


def predict(f, m, n):
    """서킷을 빼고 본 값. 호출마다 따로 실패하면 한 건이 성공할 때까지의 조회는 재시도와 무관하게 평균 1/(1-f)."""
    per_attempt_fail = f ** m
    passes = 1 if per_attempt_fail == 0 else max(1, math.ceil(math.log(1 / n) / math.log(per_attempt_fail)))
    tries = [0]
    for k in range(passes - 1):
        tries.append(tries[-1] + min(60 * 2 ** k, 600))  # 백오프 1·2·4…분, 상한 10분(현행)
    return {"calls_per_payment": round(1 / (1 - f), 3), "retries_per_payment": round(f * (1 - f ** (m - 1)) / (1 - f) if m > 1 else 0, 3),
            "left_after_first_pass": round(n * per_attempt_fail, 2), "passes_to_drain": passes,
            "drain_s_approx": 100 + tries[-1]}


def summarize(r):
    last = r["timeline"][-1]
    n = r["n"]
    return {"drained_s": r["drained_s"], "left_at_end": last["left"], "pg_calls": int(last["calls"]),
            "calls_per_payment": round(last["calls"] / n, 3), "retries": int(last["retries"]), "exhausted": int(last["exhausted"]),
            "attempts_max": last["attempts_max"]}


def report(out):
    out = pathlib.Path(out)
    print("| 실행 | 전량 확정 | 창 끝 남은 건 | PG 에 닿은 조회(건당) | 재시도 | 재시도 소진 | 복구 시도 최대 | 서킷에 막힌 시도 | 읽기 p95 |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    for p in sorted(out.glob("retry-*.json")):
        r = json.loads(p.read_text())
        s = summarize(r)
        name = r["name"]
        blocked = "—"
        log = out / f"app-{name}.log"
        if log.exists():
            blocked = str(log.read_text(errors="ignore").count("is OPEN and does not permit"))
        p95 = "—"
        k6 = out / f"k6-{name}.json"
        if k6.exists():
            m = json.loads(k6.read_text())["metrics"].get("http_req_duration", {})
            p95 = f"{m.get('p(95)', 0):.1f}ms"
        drained = f"{s['drained_s']:.0f}초" if s["drained_s"] is not None else f"**{r['window_s']:.0f}초 안에 못 함**"
        print(f"| `{name}` | {drained} | {s['left_at_end']} | {s['pg_calls']:,}({s['calls_per_payment']}) | {s['retries']:,} | {s['exhausted']:,} "
              f"| {s['attempts_max']} | {blocked} | {p95} |")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "run":
        run(sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5]), float(sys.argv[6]))
    elif cmd == "predict":
        print(json.dumps(predict(float(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]))))
    else:
        report(sys.argv[2])
