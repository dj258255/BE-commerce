"""run-checkout-screen-load.sh 결과를 조건별 한 줄로 묶는다.

사용: python3 tools/checkout_screen_report.py <OUT 디렉터리>

k6 카운터는 본 측정만, 서버 카운터와 DB 는 워밍업을 포함한다. 데드라인 생략은 서버 카운터에서
대조군(ctrl-, 남은 시간 0) 수를 빼 화면 고객 몫으로 본다. 대조군이 전부 생략됐는지(= ctrl 행이 모두
ABORTED 이고 그 수가 카운터 이하인지)를 함께 적어 "0"이 조건 미달의 0 이 아님을 남긴다.
"""
import csv
import os
import re
import sys

out = sys.argv[1]


def k6_counter(text, name):
    m = re.search(rf"^\s*{name}\.*:\s*([\d.]+)", text, re.M)
    return int(float(m.group(1))) if m else 0


def k6_trend(text, name, stat):
    m = re.search(rf"^\s*{name}\.*:.*?{re.escape(stat)}=([\d.]+)(ms|s|m)", text, re.M)
    if not m:
        return None
    v, unit = float(m.group(1)), m.group(2)
    return v / 1000 if unit == "ms" else v * 60 if unit == "m" else v


def prom(text, pattern):
    m = re.search(pattern + r"\S*\s+([\d.eE+]+)", text)
    return int(float(m.group(1))) if m else 0


def tsv(path):
    d = {}
    if os.path.exists(path):
        for line in open(path):
            parts = line.split()
            if len(parts) == 2 and parts[1].isdigit():
                d[parts[0]] = int(parts[1])
    return d


rows = []
for name in sorted(d for d in os.listdir(out) if os.path.isdir(os.path.join(out, d))):
    base = os.path.join(out, name)
    k6 = open(os.path.join(base, "k6.txt")).read() if os.path.exists(os.path.join(base, "k6.txt")) else ""
    pr = open(os.path.join(base, "prometheus-final.txt")).read() if os.path.exists(os.path.join(base, "prometheus-final.txt")) else ""
    busy = []
    rc = os.path.join(base, "resources.csv")
    if os.path.exists(rc):
        busy = [float(r["tomcat_busy"]) for r in csv.DictReader(open(rc)) if r.get("tomcat_busy")]
    ctrl = tsv(os.path.join(out, f"payments-ctrl-{name}.tsv"))
    screen = tsv(os.path.join(out, f"payments-screen-{name}.tsv"))
    skipped = prom(pr, r"payment_pg_approval_deadline_skipped_total")
    ctrl_total = sum(ctrl.values())
    rows.append({
        "조건": name,
        "화면 요청": k6_counter(k6, "screen_sent"),
        "바로 200": k6_counter(k6, "screen_first_ok_200"),
        "바로 202": k6_counter(k6, "screen_first_pending_202"),
        "바로 4xx": k6_counter(k6, "screen_first_rejected_4xx"),
        "5xx": k6_counter(k6, "screen_first_5xx"),
        "15초 포기": k6_counter(k6, "screen_client_timeout"),
        "포기→DONE": k6_counter(k6, "screen_timeout_then_done"),
        "포기→ABORTED": k6_counter(k6, "screen_timeout_then_aborted"),
        "조회 DONE": k6_counter(k6, "screen_poll_done"),
        "조회 ABORTED": k6_counter(k6, "screen_poll_aborted"),
        "조회 미확정": k6_counter(k6, "screen_poll_undecided"),
        "화면 대기 p95(s)": k6_trend(k6, "screen_confirm_ms", "p(95)"),
        "화면 대기 max(s)": k6_trend(k6, "screen_confirm_ms", "max"),
        "PG 승인 호출": prom(pr, r"fake_pg_approve_calls_total"),
        "상한 거절": prom(pr, r'payment_pg_approval_rejected_total\{[^}]*reason="concurrency_limit"'),
        "생략 카운터": skipped,
        "대조군 행": ctrl_total,
        "대조군 ABORTED": ctrl.get("ABORTED", 0),
        "화면 고객 생략": skipped - ctrl_total,
        "DB 화면 상태": " ".join(f"{k}:{v}" for k, v in sorted(screen.items())),
        "워커 busy max": int(max(busy)) if busy else None,
    })

keys = list(rows[0].keys()) if rows else []
print("| " + " | ".join(keys) + " |")
print("|" + "---|" * len(keys))
for r in rows:
    print("| " + " | ".join("" if r[k] is None else (f"{r[k]:.2f}" if isinstance(r[k], float) else str(r[k])) for k in keys) + " |")
