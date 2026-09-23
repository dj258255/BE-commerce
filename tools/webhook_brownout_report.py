#!/usr/bin/env python3
"""웹훅 × 브라운아웃 스윕을 한 표로 모은다.

k6 의 Rate 지표는 이 버전에서 `value` 키로 나온다(`rate` 가 아니다). 처음에 `rate` 로 읽어
10초 초과가 전부 0% 로 보였다 — p95 가 13초인데 초과가 0% 라는 모순으로 알아챘다.
숫자를 손으로 옮기면 이런 것을 못 잡으므로 원자료에서 직접 만든다.

사용: python3 tools/webhook_brownout_report.py <스윕 디렉터리>
"""
import glob
import json
import os
import re
import sys


def rate_of(node):
    """k6 Rate — 이 버전은 value, 다른 버전은 rate. 둘 다 본다."""
    if node is None:
        return None
    for key in ("value", "rate"):
        if key in node:
            return node[key]
    return None


def main(argv):
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    root = argv[1]
    rows = []
    for d in sorted(glob.glob(os.path.join(root, "lat*")),
                    key=lambda x: (int(re.search(r"lat(\d+)", x).group(1)),
                                   int(re.search(r"lim(\d+)", x).group(1)))):
        f = os.path.join(d, "summary.json")
        if not os.path.exists(f):
            continue
        m = json.load(open(f, encoding="utf-8"))["metrics"]
        meta = dict(re.findall(r"(\w+)=(\S+)", open(os.path.join(d, "meta.txt"), encoding="utf-8").read()))
        w = m.get("webhook_ms", {})
        pay = m.get("http_req_duration{name:confirm}", {})
        over = rate_of(m.get("webhook_over_10s"))
        rows.append((meta["lat"], meta["limit"], w, pay, over, meta.get("webhook_rows_added", "?")))

    print("| PG 지연 | 상한 | **웹훅 p95** | 웹훅 p99 | **10초 초과** | 결제 p95 | 웹훅 저장 |")
    print("|---:|---:|---:|---:|---:|---:|---:|")
    for lat, lim, w, pay, over, rows_added in rows:
        limit = "없음" if lim == "0" else lim
        pct = "n/a" if over is None else f"{100 * over:.2f}%"
        print(f"| {lat}ms | {limit} | **{w.get('p(95)', 0):,.0f}ms** | {w.get('p(99)', 0):,.0f}ms | "
              f"**{pct}** | {pay.get('p(95)', 0):,.0f}ms | {rows_added} |")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
