"""run-approve-resend-load.sh 결과를 조건별 한 줄로 묶는다.

사용: python3 tools/resend_budget_report.py <OUT 디렉터리>

DB 최종 상태는 복구 배치가 미확정을 다 푼 뒤 센 값이고 워밍업을 포함한다. 서버 카운터도 워밍업을 포함한다.
확정 실패 비율 = ABORTED ÷ 전체 결제 행.
"""
import os
import re
import sys

out = sys.argv[1]


def read(path):
    return open(path).read() if os.path.exists(path) else ""


def prom(text, pattern):
    m = re.search(pattern + r"\S*\s+([\d.eE+]+)", text)
    return int(float(m.group(1))) if m else 0


rows = []
for name in sorted(d for d in os.listdir(out) if os.path.isdir(os.path.join(out, d))):
    base = os.path.join(out, name)
    pr = read(os.path.join(base, "prometheus-drained.txt")) or read(os.path.join(base, "prometheus-final.txt"))
    status = {}
    for line in read(os.path.join(out, f"payments-{name}.tsv")).splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].isdigit():
            status[parts[0]] = int(parts[1])
    total = sum(status.values())
    aborted = status.get("ABORTED", 0)
    rows.append({
        "조건": name,
        "결제 행": total,
        "DONE": status.get("DONE", 0),
        "ABORTED": aborted,
        "UNKNOWN(남음)": status.get("UNKNOWN", 0),
        "확정 실패": f"{100 * aborted / total:.2f}%" if total else "",
        "상한 거절": prom(pr, r'payment_pg_approval_rejected_total\{[^}]*reason="concurrency_limit"'),
        "PG 승인 호출": prom(pr, r"fake_pg_approve_calls_total"),
        "재전송": prom(pr, r"payment_pg_approval_resent_total"),
        "재전송 성공": prom(pr, r"payment_pg_approval_resent_succeeded_total"),
        "건너뛴 재전송": prom(pr, r'payment_pg_approval_resent_skipped_total\{[^}]*reason="budget"'),
    })

keys = list(rows[0].keys()) if rows else []
print("| " + " | ".join(keys) + " |")
print("|" + "---|" * len(keys))
for r in rows:
    print("| " + " | ".join(str(r[k]) for k in keys) + " |")
