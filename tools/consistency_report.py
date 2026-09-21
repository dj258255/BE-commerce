#!/usr/bin/env python3
"""E2 원자료(조건별 compare.json)를 표로 모은다 — 리포트에 손으로 옮겨 적지 않기 위해서다.

사용: python3 tools/consistency_report.py <raw 디렉터리>
"""
import json
import os
import sys


def load(raw_dir):
    rows = []
    for name in sorted(os.listdir(raw_dir)):
        path = os.path.join(raw_dir, name, "compare.json")
        if not os.path.isfile(path):
            continue
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        rows.append(data)
    # 조건 순서를 사람이 읽는 순서로 고정한다(사전 등록한 조건 목록과 같게).
    order = ["baseline", "disorder", "late", "window", "ttl", "duplicate",
             "redelivery-before", "redelivery-after"]
    rows.sort(key=lambda d: order.index(d["condition"]) if d["condition"] in order else 99)
    return rows


def cause_summary(data):
    fired = [c for c in data.get("causes", []) if c["users"]]
    if not fired:
        return "불일치 없음"
    return " · ".join(f"{c['cause']} {c['sharePct']}%" for c in fired)


def sample_note(data):
    if not data.get("samples"):
        return "-"
    s = data["samples"][0]
    return (f"{'+'.join(s['causes'])}: user {s['userId']} "
            f"online {s['onlineCount']}건(seq {s['onlineSeq']}) / 로그 {s['logCount']}건(seq {s['logMaxSeq']})")


def main():
    if len(sys.argv) < 2:
        print("사용: python3 tools/consistency_report.py <raw 디렉터리>", file=sys.stderr)
        return 2
    rows = load(sys.argv[1])
    if not rows:
        print("원자료가 없다", file=sys.stderr)
        return 1

    print("| 조건 | 사용자 | 컨텍스트 일치율 | 창 집계 일치율 | 불일치 원인 | 대표 사례 |")
    print("|---|---:|---:|---:|---|---|")
    for d in rows:
        print(f"| `{d['condition']}` | {d['users']} | **{d['contextMatchPct']}%** | "
              f"{d['windowCountMatchPct']}% | {cause_summary(d)} | {sample_note(d)} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
