#!/usr/bin/env python3
"""가상 스레드 실험(#392)의 조건별 결과를 표로 만든다.

  python3 tools/vthreads_report.py <OUT>
"""
import csv
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from brownout_sweep_report import counters, p95_by_tag  # noqa: E402


def col_max(rows, key):
    xs = [float(r[key]) for r in rows if r.get(key) not in (None, "")]
    return max(xs) if xs else None


def prom(path, name, label=None):
    if not path.exists():
        return None
    total = 0.0
    found = False
    for line in path.read_text().splitlines():
        if line.startswith(name) and (label is None or label in line):
            total += float(line.rsplit(" ", 1)[1])
            found = True
    return total if found else None


def main(out):
    out = Path(out)
    lines = ["# 가상 스레드 대 PG 동시 호출 상한(#392)", "",
             "| 조건 | 무관한 조회 p95 | 결제 확정 p95 | 성공 200 | 결과 모름 202 | 거절 4xx | 5xx | 상한 거절 | PG 로 나간 동시 승인 최대 | Hikari 활성 최대 · 대기 최대 | 힙 최대(MB) | RSS 최대(MB) | 플랫폼 스레드 최대 | 고정 경고 |",
             "|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|"]
    for d in sorted(p for p in out.iterdir() if p.is_dir()):
        name = d.name
        k6 = (d / "k6.txt").read_text(errors="ignore") if (d / "k6.txt").exists() else ""
        p95 = p95_by_tag(k6)
        c = counters(k6)
        rows = list(csv.DictReader(open(out / f"sample-{name}.csv"))) if (out / f"sample-{name}.csv").exists() else []
        prom_final = d / "prometheus-final.txt"
        rejected = prom(prom_final, "payment_pg_approval_rejected_total", 'reason="concurrency_limit"')
        pinned = len(re.findall(r"onPinned|reason:MONITOR|<== monitors", (d / "app.log").read_text(errors="ignore"))) if (d / "app.log").exists() else None
        heap = col_max(rows, "heap_bytes")
        rss = col_max(rows, "rss_kb")

        def ms(v):
            return "-" if v is None else f"{v:,.0f}ms" if v >= 10 else f"{v:.1f}ms"

        def n(v):
            return "-" if v is None else f"{v:,.0f}"
        lines.append(f"| {name} | {ms(p95.get('read'))} | {ms(p95.get('confirm'))} | {n(c.get('checkout_ok_200'))} | "
                     f"{n(c.get('checkout_pending_202'))} | {n(c.get('checkout_rejected_4xx'))} | {n(c.get('checkout_failed_5xx'))} | "
                     f"{n(rejected)} | {n(col_max(rows, 'pg_inflight'))} | {n(col_max(rows, 'hikari_active'))} · {n(col_max(rows, 'hikari_pending'))} | "
                     f"{n(heap / 1e6 if heap else None)} | {n(rss / 1024 if rss else None)} | {n(col_max(rows, 'platform_threads'))} | {n(pinned)} |")
    print("\n".join(lines))


if __name__ == "__main__":
    main(sys.argv[1])
