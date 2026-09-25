"""#271 · #316 결과 표. run 스크립트가 남긴 capacity.json 과 각 조건의 raw/summary.txt 를 모은다.

    python3 tools/overload_real_model_report.py OUT [--baseline V1-OUT]

--baseline 은 v1 결과 폴더다(#271). 부하를 그 런의 C 로 나눈 배수로 나란히 놓아, 같은 부하 배수에서
v2 가 무엇을 더 내는지(개인화를 얼마나 포기하는지)를 한 표에서 읽는다.
"""
import argparse
import json
import pathlib
import re
import sys

COLUMNS = ["배수", "부하", "달성", "coverage", "개인화", "기대 coverage(C ÷ 부하)", "serving p95", "p99",
           "model p95", "거절", "대기 초과", "실패", "dropped"]


def parse(line):
    return dict(kv.split("=", 1) for kv in re.findall(r"(\w+=\S+)", line))


def load(out):
    """용량과 결과 줄을 읽는다. 결과 줄은 [E3](추천 행)과 [PAGE](홈 2쪽)다."""
    cap = json.loads((out / "capacity.json").read_text(encoding="utf-8"))
    rows, pages = [], []
    for path in sorted(out.glob("*/raw/summary.txt")):
        name = path.parent.parent.name
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.startswith("[PAGE]"):
                pages.append((name, parse(line)))
            elif line.startswith("[E3]"):
                fields = parse(line)
                rows.append((name, int(fields["rate"]), fields))
    return cap, rows, pages


def table(head, rows):
    lines = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    lines.extend("| " + " | ".join(row) + " |" for row in rows)
    return "\n".join(lines)


def condition_rows(cap, rows, name=None):
    """조건 한 줄 — 구현(있으면) · 조건 · 배수(그 런의 C 기준) · 지표들."""
    C = cap["C"]
    result = []
    for condition, rate, fields in rows:
        row = [f"{rate / C:.2f}" if C else "-", str(rate), fields.get("achieved", "-"),
               fields.get("coverage", "-"), fields.get("personalized", "-"),
               f"{min(C / rate, 1.0):.1%}" if rate else "-",
               fields.get("serving_p95", "-"), fields.get("serving_p99", "-"), fields.get("model_p95", "-"),
               fields.get("rejected", "-"), fields.get("timeout", "-"), fields.get("failed", "-"),
               fields.get("dropped", "-")]
        result.append(([name, condition] if name else [condition]) + row)
    return result


def capacity_lines(cap):
    return [[f"`{r['path']}`", str(r["threads"]), f"{r['throughput']:.1f}/s", f"{r['p50_ms']:.1f}ms",
             f"{r['p95_ms']:.1f}ms", f"{r['p99_ms']:.1f}ms"] for r in cap["results"]]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out", type=pathlib.Path)
    parser.add_argument("--baseline", type=pathlib.Path, help="v1 결과 폴더(#271)")
    args = parser.parse_args(argv)
    cap, rows, pages = load(args.out)
    if not rows:
        print("원자료가 없다", file=sys.stderr)
        return 1

    print(f"### 모델 서버 용량(직접, 닫힌 루프 {cap.get('seconds', 20.0):.0f}초)\n")
    print(table(["경로", "동시성", "처리량", "p50", "p95", "p99"], capacity_lines(cap)))
    print(f"\nC = {cap['C']:.1f}/s · Lm = {cap['Lm']:.1f}ms · Cp = {cap['Cp']:.1f}/s"
          f" · 추천 행 p95 기준 (100 + Lm) × 1.2 = {(100 + cap['Lm']) * 1.2:.0f}ms\n")

    print("### 조건별\n")
    print(table(["조건"] + COLUMNS, condition_rows(cap, rows)))

    if args.baseline:
        base_cap, base_rows, base_pages = load(args.baseline)
        if base_rows:
            merged = condition_rows(base_cap, base_rows, "v1") + condition_rows(cap, rows, "v2")
            merged.sort(key=lambda row: (float(row[2]), row[0]))
            print("\n### v1(#271) 과 나란히 — 같은 부하 배수\n")
            print(table(["구현", "조건"] + COLUMNS, merged))
            print(f"\n배수 = 부하 ÷ 그 런의 C (v1 C = {base_cap['C']:.1f}/s · v2 C = {cap['C']:.1f}/s). "
                  "v1 은 부하를 C 의 배수로 잡지 않았으므로 배수가 정확히 겹치지 않는다 — 가까운 배수끼리 읽는다.\n")
            pages = pages + [(f"v1 {name}", fields) for name, fields in base_pages]
        else:
            print(f"\n### v1(#271) 과 나란히 — v1 원자료가 없다: {args.baseline}/*/raw/summary.txt\n")

    for name, fields in pages:
        print(f"- {name} 홈 2쪽: 부하 {fields['rate']}/s · 달성 {fields['achieved']}/s · 모델이 만든 쪽 "
              f"{fields['generated']} · p95 {fields['page_p95']} · p99 {fields['page_p99']} · dropped {fields['dropped']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
