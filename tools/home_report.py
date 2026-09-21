#!/usr/bin/env python3
"""M7 홈 조립 결과 — 규칙 수준 × 사용자를 한 표로 모으고 **규칙이 화면에서 무엇을 바꾸는가**를 낸다.

`raw/<규칙>/home-user-<n>.json`(홈 API 응답)과 `meta.txt` 를 읽는다. 응답이 조립의 결과와
그 과정(`stats`)을 함께 담고 있어서 파서가 별도로 계산할 필요가 없다 — **그래서 stats 를 응답에 넣었다.**

읽는 법:
  행/항목        화면에 실제로 나간 구조
  중복·품절 제외 규칙이 버린 것. **0 이면 규칙이 할 일이 없었다는 뜻**이고, 그때 "규칙이 효과가 없다"는
                 말은 규칙의 성질이 아니라 그 데이터의 성질이다
  distinct 대분류 화면에 있는 대분류 수. 행 수가 같은데 이 값이 낮으면 화면이 한 카테고리에 몰린 것이다
  조립 ms        카탈로그 읽기 + 조립. 모델 추론은 inference_ms 로 따로 나온다
  잔차           total − (context + inference + constraint). 조립 자체의 비용이 여기 남는다

사용:
  python3 tools/home_report.py <raw 디렉터리>
"""
import json
import sys
from pathlib import Path

RULES_ORDER = {"NONE": 0, "DEDUP": 1, "FULL": 2}


def read_meta(path):
    meta = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if "=" in line:
                key, value = line.strip().split("=", 1)
                meta[key] = value
    return meta


def load(raw_dir):
    rows = []
    for rules_dir in sorted(Path(raw_dir).iterdir()):
        if not rules_dir.is_dir():
            continue
        meta = read_meta(rules_dir / "meta.txt")
        for home in sorted(rules_dir.glob("home-user-*.json")):
            try:
                page = json.loads(home.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                continue
            rows.append((meta.get("rules", rules_dir.name), meta, page))
    rows.sort(key=lambda r: (RULES_ORDER.get(r[0], 9), r[2].get("userId", "")))
    return rows


def avg(values):
    values = [v for v in values if v is not None]
    return sum(values) / len(values) if values else None


def num(v, digits=1):
    return "n/a" if v is None else f"{v:.{digits}f}"


def main():
    if len(sys.argv) < 2:
        print("사용: python3 tools/home_report.py <raw 디렉터리>", file=sys.stderr)
        return 2
    rows = load(sys.argv[1])
    if not rows:
        print("원자료가 없다", file=sys.stderr)
        return 1

    print("**사용자별** — 한 화면이 실제로 어떻게 조립됐는가")
    print()
    print("| 규칙 | 사용자 | 출처 | 행 | 항목 | 중복 제외 | 품절 제외 | **다양성 상한** | 미매칭 | distinct 대분류 | context | 추론 | 제약 | 잔차 |")
    print("|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for rules, _meta, page in rows:
        stats = page.get("stats", {})
        latency = page.get("latency", {})
        item_count = sum(len(r.get("items", [])) for r in page.get("rows", []))
        residual = None
        if all(k in latency for k in ("totalMs", "contextMs", "inferenceMs", "constraintMs")):
            residual = (latency["totalMs"] - latency["contextMs"]
                        - latency["inferenceMs"] - latency["constraintMs"])
        print(f"| `{rules}` | {page.get('userId', '?')} | {page.get('source', '?')} | "
              f"{len(page.get('rows', []))} | {item_count} | {stats.get('duplicates', '?')} | "
              f"{stats.get('outOfStock', '?')} | **{stats.get('cappedOut', '?')}** | "
              f"{stats.get('unmatched', '?')} | "
              f"{stats.get('distinctCategories', '?')} | {latency.get('contextMs', '?')}ms | "
              f"{latency.get('inferenceMs', '?')}ms | {latency.get('constraintMs', '?')}ms | "
              f"{'n/a' if residual is None else f'{residual}ms'} |")

    print()
    print("**규칙별 평균** — 규칙을 올리면서 무엇을 얻고 무엇을 내주는가")
    print()
    print("| 규칙 | 화면 | 행 | 항목 | 중복 제외 | 다양성 상한 | distinct 대분류 | 추론 중앙 | 제약 중앙 | 잔차 |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    seen = []
    for rules, _meta, _page in rows:
        if rules not in seen:
            seen.append(rules)
    for rules in sorted(seen, key=lambda r: RULES_ORDER.get(r, 9)):
        group = [p for r, _m, p in rows if r == rules]
        item_counts = [sum(len(x.get("items", [])) for x in p.get("rows", [])) for p in group]
        residuals = []
        for p in group:
            latency = p.get("latency", {})
            if all(k in latency for k in ("totalMs", "contextMs", "inferenceMs", "constraintMs")):
                residuals.append(latency["totalMs"] - latency["contextMs"]
                                 - latency["inferenceMs"] - latency["constraintMs"])
        print(f"| `{rules}` | {len(group)} | "
              f"**{num(avg([len(p.get('rows', [])) for p in group]))}** | "
              f"**{num(avg(item_counts))}** | {num(avg([p.get('stats', {}).get('duplicates') for p in group]))} | "
              f"**{num(avg([p.get('stats', {}).get('cappedOut') for p in group]))}** | "
              f"**{num(avg([p.get('stats', {}).get('distinctCategories') for p in group]))}** | "
              f"{num(avg([p.get('latency', {}).get('inferenceMs') for p in group]))}ms | "
              f"{num(avg([p.get('latency', {}).get('constraintMs') for p in group]))}ms | "
              f"{num(avg(residuals))}ms |")
    print()
    print("**행별 구성** — 행마다 몇 개가 나갔고 어느 대분류에 몰렸는가(#198 재측정에서 필요해졌다)")
    print()
    print("| 규칙 | 행 | 항목 중앙 | **평균 표시 순위** | distinct 대분류 중앙 | 대분류 분포(예시) |")
    print("|---|---|---:|---:|---:|---|")
    by_strategy = {}
    for rules, _meta, page in rows:
        for row in page.get("rows", []):
            key = (rules, row.get("strategy", "?"))
            entry = by_strategy.setdefault(key, {"mix": {}, "counts": [], "distinct": [], "ranks": []})
            mix = {}
            for item in row.get("items", []):
                category = item.get("category") or "(없음)"
                mix[category] = mix.get(category, 0) + 1
            # **평균 표시 순위** — 후보 리스트의 몇 번째를 꺼내 썼는가(1부터). 되채우기의 대가다:
            # 화면이 길어지는 만큼 이 값이 내려가면 "더 나쁜 후보를 사서 채웠다"는 뜻이다(#198①).
            ranks = [item.get("rank") for item in row.get("items", []) if item.get("rank")]
            entry["counts"].append(len(row.get("items", [])))
            entry["distinct"].append(len(mix))
            if ranks:
                entry["ranks"].append(sum(ranks) / len(ranks))
            for category, n in mix.items():
                entry["mix"][category] = entry["mix"].get(category, 0) + n
    for key in sorted(by_strategy, key=lambda k: (RULES_ORDER.get(k[0], 9), k[1])):
        entry = by_strategy[key]
        mix_text = ", ".join(f"{c} {n}" for c, n in
                             sorted(entry["mix"].items(), key=lambda x: -x[1])[:4]) or "-"
        print(f"| `{key[0]}` | `{key[1]}` | **{num(avg(entry['counts']))}** | "
              f"**{num(avg(entry['ranks']))}** | **{num(avg(entry['distinct']))}** | {mix_text} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
