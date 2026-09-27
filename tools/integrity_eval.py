#!/usr/bin/env python3
"""정합성 점검 실험(#389)의 원자료를 표로 만든다.

  python3 tools/integrity_eval.py <OUT>

integrity-<전략>.jsonl: 부하 · 부하 뒤 5초마다 유예별 점검 결과와 마지막 점검(final)
integrity-inject.jsonl: 오염을 하나씩 넣을 때마다의 점검 결과(누적)
"""
import json
import sys
from pathlib import Path


def load(path):
    rows = []
    for line in Path(path).read_text().splitlines():
        line = line.strip()
        if line:
            rows.append(json.loads(line))
    return rows


def counts(row):
    inv = row.get("invariants") or {}
    return {k: v.get("count", 0) for k, v in inv.items()}


def main(out):
    out = Path(out)
    lines = ["# 정합성 불변식 점검(#389)", ""]

    for f in sorted(out.glob("integrity-*.jsonl")):
        if f.name == "integrity-inject.jsonl":
            continue
        rows = load(f)
        name = f.stem.replace("integrity-", "")
        errors = sum(1 for r in rows if "error" in r)
        final = [r for r in rows if r.get("phase") == "final"]
        lines += [f"## {name}", "", f"점검 호출 {len(rows)}번, 실패 {errors}번.", ""]
        lines += ["| 유예(초) | 부하 중 최대 위반 합 | 부하 뒤 최대 위반 합 | 위반이 나온 점검 수 / 전체 | 나온 불변식 |",
                  "|---:|---:|---:|---|---|"]
        graces = sorted({r["grace"] for r in rows if r.get("phase") in ("load", "drain")})
        for g in graces:
            rs = [r for r in rows if r.get("grace") == g and r.get("phase") in ("load", "drain") and "error" not in r]
            load_max = max((r["total"] for r in rs if r["phase"] == "load"), default=0)
            drain_max = max((r["total"] for r in rs if r["phase"] == "drain"), default=0)
            hit = sum(1 for r in rs if r["total"] > 0)
            seen = {}
            for r in rs:
                for k, v in counts(r).items():
                    if v > 0:
                        seen[k] = max(seen.get(k, 0), v)
            seen_s = ", ".join(f"{k} 최대 {v}" for k, v in sorted(seen.items())) or "-"
            lines.append(f"| {g} | {load_max} | {drain_max} | {hit} / {len(rs)} | {seen_s} |")
        if final:
            fc = counts(final[-1])
            nz = {k: v for k, v in fc.items() if v}
            lines += ["", f"마지막 점검(유예 0): 위반 합 {final[-1].get('total')} {json.dumps(nz, ensure_ascii=False) if nz else ''}"]
            samples = {k: v.get("samples") for k, v in (final[-1].get("invariants") or {}).items() if v.get("samples")}
            if samples:
                lines.append(f"예시: {json.dumps(samples, ensure_ascii=False)}")
        lines.append("")

    lags = sorted(out.glob("lag-*.tsv"))
    if lags:
        lines += ["## 결제 승인 뒤 기록이 따라오기까지", "",
                  "| 전략 | 기록 | 건수 | p50(ms) | p99(ms) | 최대(ms) |", "|---|---|---:|---:|---:|---:|"]
        for f in lags:
            by = {}
            for line in f.read_text().splitlines():
                parts = line.split("\t")
                if len(parts) == 2 and parts[1] not in ("", "NULL"):
                    by.setdefault(parts[0], []).append(int(parts[1]) / 1000)
            for kind, xs in sorted(by.items()):
                xs.sort()
                pct = lambda q: xs[min(len(xs) - 1, max(0, int(round(q * len(xs))) - 1))]
                lines.append(f"| {f.stem.replace('lag-', '')} | {kind} | {len(xs)} | {pct(0.5):.1f} | {pct(0.99):.1f} | {xs[-1]:.1f} |")
        lines.append("")

    inj = out / "integrity-inject.jsonl"
    if inj.exists():
        rows = load(inj)
        before = next((r for r in rows if r["phase"] == "before-inject"), None)
        lines += ["## 일부러 어긋낸 데이터", "",
                  f"배치를 끄고 다시 띄운 뒤 오염 전 위반 합: {before.get('total') if before else '-'}", "",
                  "| 넣은 오염 | 늘어난 불변식 | 의도한 불변식만 1 늘었나 |", "|---|---|---|"]
        prev = counts(before) if before else None
        for r in rows:
            c = counts(r)
            if r["phase"] == "before-inject":
                continue
            target = r["phase"].split(":", 1)[1]
            delta = {k: c.get(k, 0) - (prev or {}).get(k, 0) for k in c}
            grew = {k: d for k, d in delta.items() if d}
            ok = grew == {target: 1}
            lines.append(f"| {target} | {json.dumps(grew, ensure_ascii=False) or '-'} | {'예' if ok else '**아니오**'} |")
            prev = c
        err = out / "inject-errors.txt"
        if err.exists() and err.read_text().strip():
            lines += ["", "오염 SQL 오류:", "", "```", err.read_text().strip(), "```"]
    print("\n".join(lines))


if __name__ == "__main__":
    main(sys.argv[1])
