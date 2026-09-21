#!/usr/bin/env python3
"""A/B 반복 측정 — 조건마다 **여러 런**의 폭(min/중앙/최대)을 낸다.

왜 필요한가(#198): 조건당 한 번만 재면 **실행 간 변동**과 **효과**를 구분할 수 없다.
실제로 되채우기 판정이 기준(평균 순위 2배)의 **경계**(1.91배)에 걸려 있었다 — 그때 폭을 모르면
결론이 운에 달린다. 이 스크립트는 같은 조건의 런들을 묶어 **폭을 함께** 낸다.

**폭이 효과보다 크면 결론을 내지 않는다** — 통제되지 않은 값을 결론으로 쓰지 않는다는 규칙 그대로다.

사용:
  python3 tools/home_ab_report.py 이름=디렉터리 [이름=디렉터리 ...]
  # 예: python3 tools/home_ab_report.py 되채우기1=.../refill1-r1 .../refill1-r2 ... 되채우기2=...
"""
import json
import sys
from pathlib import Path


def runs_of(directory: Path):
    """디렉터리 아래(또는 그 자체)의 홈 응답 JSON 을 모두 찾는다."""
    if not directory.exists():
        return []
    return sorted(directory.rglob("home-user-*.json"))


def metrics(paths):
    items, capped, ranks, residual, distinct = [], [], [], [], []
    for path in paths:
        try:
            page = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue
        rows = page.get("rows", [])
        item_count = sum(len(r.get("items", [])) for r in rows)
        items.append(item_count)
        capped.append(page.get("stats", {}).get("cappedOut", 0))
        distinct.append(page.get("stats", {}).get("distinctCategories", 0))
        latency = page.get("latency", {})
        if all(k in latency for k in ("totalMs", "contextMs", "inferenceMs", "constraintMs")):
            residual.append(latency["totalMs"] - latency["contextMs"]
                            - latency["inferenceMs"] - latency["constraintMs"])
        shown = [i.get("rank") for r in rows for i in r.get("items", []) if i.get("rank")]
        if shown:
            ranks.append(sum(shown) / len(shown))
    return {"items": items, "capped": capped, "ranks": ranks,
            "residual": residual, "distinct": distinct}


def stat(values):
    if not values:
        return "n/a"
    return f"{min(values):.1f} / **{sum(values) / len(values):.1f}** / {max(values):.1f}"


def main() -> int:
    groups: dict[str, list[Path]] = {}
    for arg in sys.argv[1:]:
        if "=" not in arg:
            print(f"형식: 이름=디렉터리 (받은 값: {arg})", file=sys.stderr)
            return 2
        name, directory = arg.split("=", 1)
        groups.setdefault(name, []).append(Path(directory))

    print("**조건별 폭** — min / **평균** / max. 런 하나가 아니라 여러 런을 묶어 낸 값이다.")
    print()
    print("| 조건 | 런 수 | 항목 | 다양성 상한 제외 | 평균 표시 순위 | distinct 대분류 | 잔차 |")
    print("|---|---:|---|---:|---:|---:|---:|")
    for name, dirs in groups.items():
        paths = [p for d in dirs for p in runs_of(d)]
        m = metrics(paths)
        print(f"| {name} | {len(paths)} | {stat(m['items'])} | {stat(m['capped'])} | "
              f"{stat(m['ranks'])} | {stat(m['distinct'])} | {stat(m['residual'])} |")

    print()
    names = list(groups)
    if len(names) == 2:
        a = metrics([p for d in groups[names[0]] for p in runs_of(d)])
        b = metrics([p for d in groups[names[1]] for p in runs_of(d)])
        if a["items"] and b["items"]:
            delta = (sum(b["items"]) / len(b["items"])) - (sum(a["items"]) / len(a["items"]))
            pct = delta / (sum(a["items"]) / len(a["items"])) * 100
            print(f"→ 항목 변화: **{delta:+.1f}개 ({pct:+.1f}%)** "
                  f"[{names[0]} {min(a['items']):.0f}~{max(a['items']):.0f} → "
                  f"{names[1]} {min(b['items']):.0f}~{max(b['items']):.0f}]")
        if a["ranks"] and b["ranks"]:
            ra, rb = sum(a["ranks"]) / len(a["ranks"]), sum(b["ranks"]) / len(b["ranks"])
            print(f"→ 평균 표시 순위: {ra:.1f} → {rb:.1f} (**{rb / ra:.2f}배**) "
                  f"[폭 {min(a['ranks']):.1f}~{max(a['ranks']):.1f} → {min(b['ranks']):.1f}~{max(b['ranks']):.1f}]")
        if a["residual"] and b["residual"]:
            xa, xb = sum(a["residual"]) / len(a["residual"]), sum(b["residual"]) / len(b["residual"])
            print(f"→ 잔차: {xa:.1f}ms → {xb:.1f}ms (**{(xb - xa) / xa * 100:+.1f}%**)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
