#!/usr/bin/env python3
"""E3 결과 표 — 정책 × 부하 단계를 한 표로 모은다.

`raw/summary.txt`의 `[E3] k=v  k=v` 줄을 읽는다. 그 줄은 `k6/inference-overload.js`의
`handleSummary`가 뽑는다 — **k6의 `--summary-export` JSON은 평평해서(중첩 지표가 안 들어간다)**
그대로는 표를 못 만든다. 그래서 요약 줄을 별도로 남기고 여기서 파싱한다.

읽는 법:
  coverage      모델이 만든 추천으로 답한 비율
  personalized  모델이 답했고 **재료도 있었다** ← E3 의 주 지표(personalization coverage)
  serving_p95   요청 전체 p95. 폴백은 빠르고 모델은 느리다 — **coverage와 함께 읽어야** 한다
  rejected      정책이 줄을 끊은 횟수(BOUNDED·ADMISSION)
  timeout       모델 용량을 못 기다린 횟수(UNBOUNDED에서 주로)

사용:
  python3 tools/degradation_report.py <raw 디렉터리>
"""
import sys
from collections import defaultdict
from pathlib import Path


def load(raw_dir):
    path = Path(raw_dir) / "summary.txt"
    if not path.exists():
        return {}
    rows = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.startswith("[E3]"):
            continue
        fields = {}
        for token in line.replace("[E3]", "").split():
            if "=" in token:
                key, value = token.split("=", 1)
                fields[key] = value
        if "policy" in fields and "rate" in fields:
            rows[(fields["policy"], int(fields["rate"]))] = fields
    return rows


def main():
    if len(sys.argv) < 2:
        print("사용: python3 tools/degradation_report.py <raw 디렉터리>", file=sys.stderr)
        return 2
    rows = load(sys.argv[1])
    if not rows:
        print("원자료가 없다", file=sys.stderr)
        return 1

    policies = sorted({p for p, _ in rows})
    rates = sorted({r for _, r in rows})

    print("| 부하 (req/s) | " + " | ".join(f"`{p}` coverage / p95" for p in policies) + " |")
    print("|---:|" + "---:|" * len(policies))
    for rate in rates:
        cells = []
        for policy in policies:
            fields = rows.get((policy, rate))
            if not fields:
                cells.append("-")
                continue
            cells.append(f"**{fields.get('coverage', 'n/a')}** / {fields.get('serving_p95', 'n/a')}")
        print(f"| {rate} | " + " | ".join(cells) + " |")

    print()
    print("**달성 부하** — 표의 `rate` 는 요청한 부하이고 아래는 실제로 통과한 양이다.")
    print("정책이 앱을 마비시키면(스레드가 줄에 갇히면) 여기가 낮아진다 — 그 자체가 결과다.")
    print()
    print("| 부하 | " + " | ".join(f"`{p}`" for p in policies) + " |")
    print("|---:|" + "---:|" * len(policies))
    for rate in rates:
        cells = []
        for policy in policies:
            fields = rows.get((policy, rate))
            cells.append(fields.get("achieved", "-") if fields else "-")
        print(f"| {rate} | " + " | ".join(cells) + " |")

    print()
    print("| 부하 | 정책 | 달성 | coverage | **개인화** | serving p95 | serving p99 | model p95 | rejected | timeout | zero_ctx | dropped |")
    print("|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for rate in rates:
        for policy in policies:
            f = rows.get((policy, rate))
            if not f:
                continue
            print(f"| {rate} | `{policy}` | {f.get('achieved')} | {f.get('coverage')} | "
                  f"**{f.get('personalized')}** | {f.get('serving_p95')} | "
                  f"{f.get('serving_p99')} | {f.get('model_p95')} | {f.get('rejected')} | "
                  f"{f.get('timeout')} | {f.get('zero_ctx')} | {f.get('dropped')} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
