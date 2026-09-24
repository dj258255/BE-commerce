"""#250 표: 조회 부하별 웹훅·결제·조회 지연과 워커·커넥션 최대치.

    python3 tools/webhook_browse_report.py OUT
"""
import json
import pathlib
import re
import sys


def metric(summary, name, stat):
    m = summary["metrics"].get(name)
    if not m:
        return None
    return m.get(stat, m.get("value") if stat == "rate" else None)


def peaks(path):
    busy = pend = 0.0
    if path.exists():
        for line in path.read_text().splitlines():
            for k, v in re.findall(r"(\w+)=([\d.eE+-]+)", line):
                if k == "tomcat_threads_busy_threads":
                    busy = max(busy, float(v))
                elif k == "hikaricp_connections_pending":
                    pend = max(pend, float(v))
    return busy, pend


def main(out):
    out = pathlib.Path(out)
    print("| 실행 | 조회/s | 웹훅 p95 | 웹훅 p99 | **10초 초과** | 웹훅 저장 | 결제 p95 | 조회 p95 | 조회 2xx | 조회 503 | 조회 오류 | 바쁜 워커 최대 | 커넥션 대기 최대 |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    def key(p):
        name, b = p.name.rsplit("-browse", 1)
        return (name, int(b))
    for d in sorted((p for p in out.iterdir() if p.is_dir() and "-browse" in p.name), key=key):
        s = json.loads((d / "summary.json").read_text()) if (d / "summary.json").exists() else None
        if not s:
            continue
        name, b = key(d)
        meta = (d / "meta.txt").read_text() if (d / "meta.txt").exists() else ""
        saved = re.search(r"webhook_rows_added=(-?\d+)", meta)
        busy, pend = peaks(d / "samples.txt")
        def ms(v):
            return "—" if v is None else f"{v:,.0f}ms"
        def n(v):
            return "—" if v is None else f"{int(v):,}"
        over = metric(s, "webhook_over_10s", "rate")
        print(f"| {name} | {b} | {ms(metric(s, 'webhook_ms', 'p(95)'))} | {ms(metric(s, 'webhook_ms', 'p(99)'))} "
              f"| **{'—' if over is None else f'{over:.2%}'}** | {saved.group(1) if saved else '—'} "
              f"| {ms(metric(s, 'http_req_duration{name:confirm}', 'p(95)'))} | {ms(metric(s, 'browse_ms', 'p(95)'))} "
              f"| {n(metric(s, 'browse_2xx', 'count'))} | {n(metric(s, 'browse_503', 'count'))} | {n(metric(s, 'browse_error', 'count'))} "
              f"| {busy:.0f} | {pend:.0f} |")


if __name__ == "__main__":
    main(sys.argv[1])
