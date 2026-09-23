#!/usr/bin/env python3
"""브라운아웃 스윕(지연 × 상한) 원자료를 한 표로 모은다.

왜 별도 스크립트인가
--------------------
숫자를 손으로 옮기면 틀린다. 이 저장소는 그걸 한 번 겪어서 "실측을 남긴다 — 리포트의 수치는
스크립트가 원본에서 직접 계산한다"를 원칙으로 두고 있다(`personalization/pipeline/README.md`).

무엇을 읽나
-----------
- `k6.txt`  — THRESHOLDS 절의 태그별 p95(confirm/read), CUSTOM 절의 결제 결과 카운터
- `resources.csv` — 1초 간격 hikari active / tomcat busy 샘플
- `prometheus-final.txt` — 앱이 마지막에 내놓은 결제 지표(미확정 포함)

사용: python3 tools/brownout_sweep_report.py docs/performance/runs/<스윕 디렉터리>
"""
import csv
import os
import re
import sys


def p95_by_tag(k6_text):
    """THRESHOLDS 절에서 태그별 p95 를 ms 로 뽑는다."""
    out = {}
    for m in re.finditer(r"http_req_duration\{name:(\w+)\}\s*\n\s*[✓✗][^\n]*?p\(95\)=([0-9.]+)(µs|ms|s)\b", k6_text):
        tag, val, unit = m.group(1), float(m.group(2)), m.group(3)
        out[tag] = val / 1000 if unit == "µs" else (val * 1000 if unit == "s" else val)
    return out


def counters(k6_text):
    out = {}
    for name in ("checkout_ok_200", "checkout_pending_202", "checkout_rejected_4xx", "checkout_failed_5xx"):
        m = re.search(rf"{name}\.*:\s*(\d+)", k6_text)
        out[name] = int(m.group(1)) if m else 0
    return out


def resources(path):
    if not os.path.exists(path):
        return None, None
    rows = list(csv.DictReader(open(path)))
    def col(k):
        return [float(r[k]) for r in rows if r.get(k)]
    h, t = col("hikari_active"), col("tomcat_busy")
    return (max(h) if h else None), (max(t) if t else None)


def main(argv):
    if len(argv) < 2:
        print(__doc__); return 2
    root = argv[1]
    rows = []
    for f in sorted(os.listdir(root)):
        if not f.endswith(".rawdir"):
            continue
        lat, lim = re.match(r"lat(\d+)-lim(\d+)\.rawdir", f).groups()
        raw = open(os.path.join(root, f)).read().strip()
        k6 = os.path.join(raw, "k6.txt")
        if not os.path.exists(k6):
            print(f"  건너뜀(원자료 없음): {f}", file=sys.stderr); continue
        text = open(k6, encoding="utf-8", errors="ignore").read()
        p95 = p95_by_tag(text)
        c = counters(text)
        hi, tb = resources(os.path.join(raw, "resources.csv"))
        total = sum(c.values()) or 1
        rows.append({
            "lat": int(lat), "lim": int(lim),
            "confirm_p95": p95.get("confirm"), "read_p95": p95.get("read"),
            "ok": c["checkout_ok_200"], "pending": c["checkout_pending_202"],
            "rejected": c["checkout_rejected_4xx"], "failed": c["checkout_failed_5xx"],
            "reject_pct": 100.0 * c["checkout_rejected_4xx"] / total,
            "hikari_max": hi, "tomcat_max": tb,
        })
    rows.sort(key=lambda r: (r["lat"], r["lim"]))

    def fmt(v, suffix=""):
        return "—" if v is None else f"{v:,.0f}{suffix}"

    print("| PG 지연 | 상한 | 결제 p95 | 조회 p95 | 성공 | 미확정 | 거절 | 거절률 | hikari max | 워커 max |")
    print("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for r in rows:
        lim = "없음" if r["lim"] == 0 else str(r["lim"])
        print(f"| {r['lat']}ms | {lim} | {fmt(r['confirm_p95'],'ms')} | {fmt(r['read_p95'],'ms')} | "
              f"{r['ok']:,} | {r['pending']:,} | {r['rejected']:,} | {r['reject_pct']:.1f}% | "
              f"{fmt(r['hikari_max'])} | {fmt(r['tomcat_max'])} |")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
