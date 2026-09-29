"""run-pg-read-timeout-compare.sh 결과를 조건별 한 줄로 묶는다.

사용: python3 tools/pg_read_timeout_report.py <OUT 디렉터리>

k6 카운터(화면 고객이 본 것)는 본 측정만, 서버 카운터와 DB 는 워밍업을 포함한다. 확정까지 걸린 시간은
요청부터 승인 확정(approved_at)까지다. 복구 배치가 확정하면 그 시각이 들어간다.
"""
import csv
import os
import re
import sys

out = sys.argv[1]


def k6_counter(text, name):
    m = re.search(rf"^\s*{name}\.*:\s*([\d.]+)", text, re.M)
    return int(float(m.group(1))) if m else 0


def prom(text, pattern):
    m = re.search(pattern + r"\S*\s+([\d.eE+]+)", text)
    return int(float(m.group(1))) if m else 0


def read(path):
    return open(path).read() if os.path.exists(path) else ""


def pct(values, p):
    if not values:
        return None
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))]


rows = []
for name in sorted(d for d in os.listdir(out) if os.path.isdir(os.path.join(out, d))):
    base = os.path.join(out, name)
    k6 = read(os.path.join(base, "k6.txt"))
    pr = read(os.path.join(base, "prometheus-drained.txt")) or read(os.path.join(base, "prometheus-final.txt"))
    status = {}
    for line in read(os.path.join(out, f"payments-{name}.tsv")).splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].isdigit():
            status[parts[0]] = int(parts[1])
    secs = []
    for line in read(os.path.join(out, f"done-seconds-{name}.tsv")).splitlines():
        try:
            secs.append(float(line.strip()))
        except ValueError:
            pass
    busy = []
    rc = os.path.join(base, "resources.csv")
    if os.path.exists(rc):
        busy = [float(r["tomcat_busy"]) for r in csv.DictReader(open(rc)) if r.get("tomcat_busy")]
    drain = read(os.path.join(base, "drain.csv")).strip().splitlines()
    drain_s = None
    if len(drain) > 2:
        t0, t1 = int(drain[1].split(",")[0]), int(drain[-1].split(",")[0])
        drain_s = t1 - t0
    sent = k6_counter(k6, "screen_sent")
    first_unknown = k6_counter(k6, "screen_first_pending_202")
    rows.append({
        "조건": name,
        "화면 요청": sent,
        "바로 200": k6_counter(k6, "screen_first_ok_200"),
        "바로 202(결과 모름)": first_unknown,
        "바로 4xx": k6_counter(k6, "screen_first_rejected_4xx"),
        "15초에 끊음": k6_counter(k6, "screen_client_timeout"),
        "조회로 DONE": k6_counter(k6, "screen_poll_done"),
        "조회 미확정": k6_counter(k6, "screen_poll_undecided"),
        "DB DONE": status.get("DONE", 0),
        "DB ABORTED": status.get("ABORTED", 0),
        "DB UNKNOWN(남음)": status.get("UNKNOWN", 0),
        "DONE까지 p50(s)": pct(secs, 50),
        "p95(s)": pct(secs, 95),
        "max(s)": max(secs) if secs else None,
        "15초 안 DONE": sum(1 for s in secs if s <= 15),
        "상한 거절": prom(pr, r'payment_pg_approval_rejected_total\{[^}]*reason="concurrency_limit"'),
        "PG 한도 거절": prom(pr, r"fake_pg_contract_rejected_total"),
        "PG 승인 호출": prom(pr, r"fake_pg_approve_calls_total"),
        "PG 쪽 동시 처리 max": prom(pr, r"fake_pg_side_inflight_max"),
        "워커 max": int(max(busy)) if busy else None,
        "복구 대기(s)": drain_s,
    })

keys = list(rows[0].keys()) if rows else []
print("| " + " | ".join(keys) + " |")
print("|" + "---|" * len(keys))
for r in rows:
    print("| " + " | ".join("" if r[k] is None else (f"{r[k]:.1f}" if isinstance(r[k], float) else str(r[k])) for k in keys) + " |")
