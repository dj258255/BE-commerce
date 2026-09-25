#!/usr/bin/env python3
"""X3(#317) 원자료를 표로 모은다 — 재고 확인 방식 × 품절 속도.

`raw/<런>/race.json`(+ `meta.txt`)을 읽는다. 표를 손으로 옮기면 전사 실수가 섞인다(E1·E4 와 같은 이유).

두 열이 1차 수정의 핵심이다.
  품절 후보 적중률   응답에 담긴 상품이 품절 후보에 든 비율 — 품절 주입이 **노출 상품에 닿았는지**(2번 요구).
                    `NONE` · R>0 에서 0 이면 그 런은 무효(`유효` 열에 `무효`).
  60초 품절 비율      측정 60초 동안 후보 중 실제로 0 이 된 비율(3·6번 요구). 괄호는 (팔린 수/후보 수).

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
    headers = ["방식", "품절 R/s", "**응답 시점 품절 노출률**", "홈 p95", "홈 p50", "2쪽 p95",
               "**규칙 행 폴백**", "재고 조회", "조회 ms", "뺀 항목", "요청", "오류",
               "**품절 후보 적중률**", "**60초 품절 비율**", "유효"]
    print("| " + " | ".join(headers) + " |")
    print("|" + "|".join(["---"] + ["---:"] * (len(headers) - 2) + ["---"]) + "|")
    for _meta, s in runs:
        cells = [
            f"`{s.get('mode', '?')}`",
            num(s.get("sellout_rate"), 0),
            f"**{pct(s.get('exposure_rate'))}**",
            f"{num(s.get('p95_ms'))}ms",
            f"{num(s.get('p50_ms'))}ms",
            f"{num(s.get('p95_page2_ms'))}ms",
            f"**{pct(s.get('rule_fallback_rate'))}**",
            num(s.get("stock_lookups")),
            f"{num(s.get('stock_lookup_ms'))}ms",
            num(s.get("stock_removed")),
            num(s.get("requests"), 0),
            num(s.get("errors"), 0),
            f"**{pct(s.get('candidate_hit_rate'))}**",
            f"**{pct(s.get('sold_out_fraction'))}** ({num(s.get('sold_out'), 0)}/{num(s.get('candidates_total'), 0)})",
            "무효" if s.get("valid") is False else "유효",
        ]
        print("| " + " | ".join(cells) + " |")
    invalid = [(s.get("mode"), s.get("sellout_rate")) for _, s in runs if s.get("valid") is False]
    if invalid:
        print("무효 런 " + str(len(invalid)) + "개(품절 후보가 응답에 닿지 않음): "
              + ", ".join(f"{m}·R={num(r, 0)}" for m, r in invalid), file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
