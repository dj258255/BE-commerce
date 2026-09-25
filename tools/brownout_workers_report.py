"""run-pg-brownout.sh 실행 폴더 여러 개를 워커 · 상한 · 타임아웃별 한 표로(#335). 지연 × 상한 스윕은 brownout_sweep_report.py 가 맡는다.

    python3 tools/brownout_workers_report.py OUT [도착률=50] [지연ms=3000]
"""
import csv
import pathlib
import re
import sys


def num(text, name):
    m = re.search(rf"^\s*{re.escape(name)}\.*: (\d+)", text, re.M)
    return int(m.group(1)) if m else 0


def p95(text, tag):
    m = re.search(rf"http_req_duration\{{name:{tag}\}}\s*\n\s*\S+ 'p\(95\)<\d+' p\(95\)=([\d.]+)(ms|s|µs)", text)
    if not m:
        return None
    v = float(m.group(1))
    return v * 1000 if m.group(2) == "s" else v / 1000 if m.group(2) == "µs" else v


def main(out, rate=50.0, lat=3000.0):
    print("| 실행 | 워커 | 상한 | 타임아웃 | 거절률(예측) | 성공 · 미확정 · 거절 · 5xx | 조회 p95 | 결제 p95 | 워커 busy 최대 · 평균 | 미확정 배수 |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for d in sorted(pathlib.Path(out).iterdir()):
        k6 = d / "k6.txt"
        if not k6.exists():
            continue
        t = k6.read_text(errors="ignore")
        m = re.match(r"w(\d+)-cap(\d+)(?:-rto(\d+))?", d.name)
        w, cap, rto = int(m.group(1)), int(m.group(2)), int(m.group(3) or 5000)
        ok, pend, rej, fail = (num(t, n) for n in ("checkout_ok_200", "checkout_pending_202", "checkout_rejected_4xx", "checkout_failed_5xx"))
        total = ok + pend + rej
        pred = max(0.0, 1 - cap / (rate * min(lat, rto) / 1000))
        log = (d / "run.log").read_text(errors="ignore") if (d / "run.log").exists() else ""
        busy = re.search(r"tomcat busy\s+max=(\d+) avg=([\d.]+)", log)
        drain = "—"
        dc = d / "drain.csv"
        if dc.exists():
            rows = list(csv.DictReader(dc.open()))
            if rows:
                first = int(rows[0]["t"])
                peak = max(int(r["unknown"] or 0) for r in rows)
                zero = next((int(r["t"]) - first for r in rows if r["unknown"] == "0"), None)
                drain = f"{peak}건 → {'0 까지 ' + str(zero) + '초' if zero is not None else '끝나지 않음'}"
        rp, cp = p95(t, "read"), p95(t, "confirm")
        print(f"| `{d.name}` | {w} | {cap} | {rto / 1000:.0f}초 | {rej / total:.1%}({pred:.1%}) | {ok} · {pend} · {rej} · {fail} "
              f"| {'—' if rp is None else f'{rp:,.1f}ms'} | {'—' if cp is None else f'{cp:,.0f}ms'} "
              f"| {busy.group(1) + ' · ' + busy.group(2) if busy else '—'} | {drain} |")


if __name__ == "__main__":
    main(sys.argv[1], *(float(a) for a in sys.argv[2:4]))
