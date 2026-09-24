#!/usr/bin/env python3
"""X3(#317) 원자료를 표로 모은다 — 재고 확인 방식 × 품절 속도.

`raw/<런>/race.json`(+ `meta.txt`)을 읽는다. 표를 손으로 옮기면 전사 실수가 섞인다(E1·E4 와 같은 이유).

사용: python3 tools/x3_report.py <raw 디렉터리>
"""
import argparse
import json
import pathlib
import sys

MODE_ORDER = {"NONE": 0, "POST": 1, "PRE": 2, "POST_FINAL": 3}


def read_meta(path):
    meta = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if "=" in line:
                key, value = line.strip().split("=", 1)
                meta[key] = value
    return meta


def load(raw_dir):
    runs = []
    for run_dir in sorted(pathlib.Path(raw_dir).iterdir()):
        race = run_dir / "race.json"
        if not run_dir.is_dir() or not race.exists():
            continue
        meta = read_meta(run_dir / "meta.txt")
        summary = json.loads(race.read_text(encoding="utf-8")).get("summary", {})
        runs.append((meta, summary))
    runs.sort(key=lambda r: (MODE_ORDER.get(r[1].get("mode"), 9), r[1].get("sellout_rate") or 0))
    return runs


def pct(v):
    return "n/a" if v is None else f"{v * 100:.2f}%"


def num(v, digits=2):
    return "n/a" if v is None else f"{v:.{digits}f}"


def main():
    parser = argparse.ArgumentParser(description="X3(#317) 원자료를 표로 모은다 — 재고 확인 방식 × 품절 속도")
    parser.add_argument("raw_dir", help="raw 디렉터리(런마다 race.json · meta.txt)")
    args = parser.parse_args()
    runs = load(args.raw_dir)
    if not runs:
        print("원자료(race.json)가 없다", file=sys.stderr)
        return 1
    print("| 방식 | 품절 R/s | **응답 시점 품절 노출률** | 홈 p95 | 홈 p50 | 2쪽 p95 | **규칙 행 폴백** | "
          "재고 조회 | 조회 ms | 뺀 항목 | 요청 | 오류 |")
    print("|---|---:|" + "---:|" * 11)
    for meta, s in runs:
        print(f"| `{s.get('mode', '?')}` | {num(s.get('sellout_rate'), 0)} | "
              f"**{pct(s.get('exposure_rate'))}** | {num(s.get('p95_ms'))}ms | {num(s.get('p50_ms'))}ms | "
              f"{num(s.get('p95_page2_ms'))}ms | **{pct(s.get('rule_fallback_rate'))}** | "
              f"{num(s.get('stock_lookups'))} | {num(s.get('stock_lookup_ms'))}ms | "
              f"{num(s.get('stock_removed'))} | {num(s.get('requests'), 0)} | {num(s.get('errors'), 0)} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
