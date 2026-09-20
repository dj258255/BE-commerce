#!/usr/bin/env python3
"""E1 원자료(k6 summary + meta)를 표로 모은다.

리포트의 "실제 출력" 절에 붙일 표를 손으로 옮겨 적지 않기 위한 것이다 — 전사 실수를 막는다.
(03-verification.md: "손으로 옮겨 적었다면 옮겨 적었다고 밝힌다" → 여기서는 기계가 옮긴다.)

사용: python3 tools/freshness_report.py <raw 디렉터리>
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
    """k6 `--summary-export` 는 지표를 **평평한 객체**로 쓴다({med:.., 'p(95)':..}).

    `handleSummary(data)` 로 받는 모양({values:{...}})과 다르다 — 둘 다 받아 준다.
    (이 차이 때문에 첫 시도에서 표가 전부 n/a 로 나왔다.)
    """
    node = summary.get("metrics", {}).get(metric)
    if not node:
        return None
    if "values" in node:
        return node["values"].get(key)
    return node.get(key)


def rate_value(summary, metric):
    """Rate 지표는 `--summary-export` 에서 `value` 로 나온다(`rate` 는 Counter 의 초당 비율이다).

    freshness_reflected → {"passes":99,"fails":18,"value":0.846}
    """
    node = summary.get("metrics", {}).get(metric)
    if not node:
        return None
    if "values" in node:
        return node["values"].get("rate")
    return node.get("value")


def pct(v):
    return "n/a" if v is None else f"{v * 100:.1f}%"


def ms(v):
    return "n/a" if v is None else f"{v:.1f}"


def num(v):
    return "n/a" if v is None else str(int(v))


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
            "transport": meta.get("transport", name),
            "delay": int(meta.get("consumer_delay_ms", 0)),
            "wait": int(meta.get("wait_ms", 0)),
            "reflected": rate_value(summary, "freshness_reflected"),
            "e2e_med": value(summary, "freshness_e2e_ms", "med"),
            "e2e_p95": value(summary, "freshness_e2e_ms", "p(95)"),
            "e2e_p99": value(summary, "freshness_e2e_ms", "p(99)"),
            "write_med": value(summary, "activity_write_ms", "med"),
            "write_p95": value(summary, "activity_write_ms", "p(95)"),
            "read_med": value(summary, "context_read_ms", "med"),
            "read_p95": value(summary, "context_read_ms", "p(95)"),
            "lag_p95": value(summary, "context_lag_ms", "p(95)"),
            "wait_p95": value(summary, "context_wait_ms", "p(95)"),
            "gave_up": value(summary, "context_wait_gave_up", "count"),
            "failed": rate_value(summary, "http_req_failed"),
            "dropped": value(summary, "dropped_iterations", "count"),
            "iterations": value(summary, "iterations", "count"),
        })
    rows.sort(key=lambda r: (r["transport"], r["delay"], r["wait"]))
    return rows


def main():
    if len(sys.argv) < 2:
        print("사용: python3 tools/freshness_report.py <raw 디렉터리>", file=sys.stderr)
        return 2
    rows = build_rows(sys.argv[1])
    if not rows:
        print("원자료가 없다", file=sys.stderr)
        return 1

    print("| 전달 방식 | consumer 지연 | 대기 상한 | 최신 반영률 | e2e 중앙 | e2e p95 | e2e p99 | 쓰기 중앙 | 쓰기 p95 | 읽기 중앙 | 읽기 p95 | 컨텍스트 나이 p95 | 대기 p95 | 포기 | 표본 | 요청 실패 | 부하 미달 |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for r in rows:
        print(f"| `{r['transport']}` | {r['delay']}ms | {r['wait']}ms | **{pct(r['reflected'])}** | "
              f"{ms(r['e2e_med'])}ms | {ms(r['e2e_p95'])}ms | {ms(r['e2e_p99'])}ms | "
              f"{ms(r['write_med'])}ms | {ms(r['write_p95'])}ms | "
              f"{ms(r['read_med'])}ms | {ms(r['read_p95'])}ms | "
              f"{ms(r['lag_p95'])}ms | {ms(r['wait_p95'])}ms | {num(r['gave_up'])} | {num(r['iterations'])} | "
              f"{pct(r['failed'])} | {num(r['dropped'])} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
