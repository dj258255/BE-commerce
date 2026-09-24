"""#252 감시 검증 표: 구간마다 감시 지표가 언제 바뀌었는가.

    python3 tools/cdc_health_probe_report.py OUT
"""
import pathlib
import re
import sys


def main(out):
    lines = (pathlib.Path(out) / "samples.txt").read_text().splitlines()
    marks, rows = {}, []
    for line in lines:
        parts = line.split()
        if len(parts) >= 3 and parts[1] == "MARK":
            marks[parts[2]] = int(parts[0])
            continue
        t = int(parts[0])
        rest = re.search(r"rest=(\S+)", line)
        run = re.search(r'cdc_connector_running\{[^}]*connector="catalog-cdc"[^}]*\}=([\d.]+)', line)
        age = re.search(r'cdc_heartbeat_age_seconds\{[^}]*connector="catalog-cdc"[^}]*\}=([-\d.]+)', line)
        rows.append((t, rest.group(1) if rest else "?", float(run.group(1)) if run else None,
                     float(age.group(1)) if age else None))

    def window(a, b):
        return [r for r in rows if marks.get(a, 0) <= r[0] < marks.get(b, 10**12)]

    def first(rs, pred, since):
        for r in rs:
            if pred(r):
                return r[0] - since
        return None

    print("| 구간 | 길이 | Connect REST 상태(본 것) | running 최소 | 하트비트 나이 최대 | running=0 까지 | 나이>60 까지 | 회복(running=1 · 나이<60) 까지 |")
    print("|---|---:|---|---:|---:|---:|---:|---:|")
    order = ["idle", "stall", "heal", "pause", "resume", "end"]
    for a, b in zip(order, order[1:]):
        rs = window(a, b)
        if not rs:
            continue
        states = sorted({r[1] for r in rs})
        runs = [r[2] for r in rs if r[2] is not None]
        ages = [r[3] for r in rs if r[3] is not None]
        z = first(rs, lambda r: r[2] == 0, marks[a])
        o = first(rs, lambda r: r[3] is not None and r[3] > 60, marks[a])
        ok = first(rs, lambda r: r[2] == 1 and r[3] is not None and r[3] < 60, marks[a]) if a in ("heal", "resume") else None
        print(f"| {a} | {marks[b] - marks[a]}초 | {' · '.join(states)} | {min(runs) if runs else '—'} "
              f"| {max(ages):.0f}초 | {'—' if z is None else f'{z}초'} | {'—' if o is None else f'{o}초'} "
              f"| {'—' if ok is None else f'{ok}초'} |" if ages else
              f"| {a} | {marks[b] - marks[a]}초 | {' · '.join(states)} | — | — | — | — | — |")


if __name__ == "__main__":
    main(sys.argv[1])
