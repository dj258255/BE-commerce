#!/usr/bin/env python3
"""E4 원자료(k6 summary + meta)를 표로 모은다.

E1 과 같은 이유로 기계가 옮긴다 — 리포트의 표를 손으로 적으면 전사 실수가 섞인다.
(E1 에서 k6 요약 JSON 이 `handleSummary(data)` 와 모양이 달라 표가 전부 n/a 로 나온 적이 있다.
그래서 두 모양을 모두 받는다.)

사용: python3 tools/constraint_report.py <raw 디렉터리>
"""
import json
import os
import sys


def read_meta(path):
    meta = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if "=" in line:
                key, value = line.strip().split("=", 1)
                meta[key] = value
    return meta


def value(summary, metric, key):
    node = summary.get("metrics", {}).get(metric)
    if not node:
        return None
    if "values" in node:
        return node["values"].get(key)
    return node.get(key)


def rate_value(summary, metric):
    node = summary.get("metrics", {}).get(metric)
    if not node:
        return None
    if "values" in node:
        return node["values"].get("rate")
    return node.get("value")


def pct(v):
    return "n/a" if v is None else f"{v * 100:.2f}%"


def num(v, digits=2):
    return "n/a" if v is None else f"{v:.{digits}f}"


def build_rows(raw_dir):
    rows = []
    for name in sorted(os.listdir(raw_dir)):
        run_dir = os.path.join(raw_dir, name)
        meta_path = os.path.join(run_dir, "meta.txt")
        summary_path = os.path.join(run_dir, "summary.json")
        if not (os.path.isfile(meta_path) and os.path.isfile(summary_path)):
            continue
        meta = read_meta(meta_path)
        with open(summary_path, encoding="utf-8") as f:
            summary = json.load(f)
        rows.append({
            "policy": meta.get("constraint_policy", name),
            "flip": meta.get("flip_rate", "?"),
            "target": meta.get("sold_out_target", "?"),
            "violation": rate_value(summary, "constraint_violation"),
            "violationsPer": value(summary, "constraint_violations", "avg"),
            "coverage": rate_value(summary, "recommend_coverage"),
            "servingMed": value(summary, "recommend_serving_ms", "med"),
            "servingP95": value(summary, "recommend_serving_ms", "p(95)"),
            "checkMed": value(summary, "recommend_check_ms", "med"),
            "auditMed": value(summary, "recommend_audit_ms", "med"),
            "filtered": value(summary, "constraint_filtered", "avg"),
            "changes": value(summary, "constraint_window_changes", "avg"),
            "ageMed": value(summary, "constraint_snapshot_age_ms", "med"),
            "soldOutMed": value(summary, "constraint_sold_out", "med"),
            "control": rate_value(summary, "constraint_control_ok"),
            "flips": value(summary, "constraint_flips", "count"),
            "iters": value(summary, "iterations", "count"),
            "dropped": value(summary, "dropped_iterations", "count"),
        })
    order = {"NONE": 0, "AT_GENERATION_START": 1, "AFTER_GENERATION": 2, "AT_RESPONSE": 3}
    rows.sort(key=lambda r: (order.get(r["policy"], 9), r["flip"]))
    return rows


def main():
    if len(sys.argv) < 2:
        print("사용: python3 tools/constraint_report.py <raw 디렉터리>", file=sys.stderr)
        return 2
    rows = build_rows(sys.argv[1])
    if not rows:
        print("원자료가 없다", file=sys.stderr)
        return 1

    print("| 정책 | 변화율 | **위반율** | 응답당 위반 | coverage | serving 중앙 | serving p95 | **확인 중앙** | 계기 중앙 | 확인이 뺀 개수 | 창 안 변경 | stale 창 중앙 | 품절 수 중앙 | 제어(K 유지) | 표본 | 부하 미달 |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for r in rows:
        print(f"| `{r['policy']}` | {r['flip']}/s | **{pct(r['violation'])}** | {num(r['violationsPer'])} | "
              f"{pct(r['coverage'])} | {num(r['servingMed'])}ms | {num(r['servingP95'])}ms | "
              f"**{num(r['checkMed'])}ms** | {num(r['auditMed'])}ms | {num(r['filtered'])} | {num(r['changes'])} | "
              f"{num(r['ageMed'])}ms | {num(r['soldOutMed'])}/{r['target']} | {pct(r['control'])} | "
              f"{num(r['iters'], 0)} | {num(r['dropped'], 0)} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
