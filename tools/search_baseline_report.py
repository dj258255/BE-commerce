#!/usr/bin/env python3
"""#172 검색 베이스라인 — 현재 경로의 지연을 표로 모으고 **사전 등록 판정 기준**에 대입한다.

`raw/summary.txt` 의 `[172] vus=N query=이름 p50=.. p95=.. p99=..` 줄을 읽는다.

판정 기준은 이슈 #172 에 **측정 전에** 적혀 있었다:
  ① 목록 p95 > 300ms 이고 그 원인이 집계인가 → 검색 엔진 검토
  ② 패싯 집계가 전체 지연의 50% 이상인가 → 사전 집계 또는 엔진 검토
  ③ 둘 다 아니면 → **도입하지 않는다**(운영 대상 추가는 그 자체로 비용)

이 스크립트는 그 셋을 **계산해서 보여준다** — 판단을 대신하지 않는다.

사용:
  python3 tools/search_baseline_report.py <raw 디렉터리>
"""
import sys
from pathlib import Path

QUERY_ORDER = ["list_filter", "list_broad", "list_deep", "list_sort_newest", "search", "facet"]
SLO_MS = 300.0
FACET_SHARE_THRESHOLD = 0.5


def ms(value):
    if value is None:
        return None
    return float(value.rstrip("ms"))


def load(raw_dir):
    path = Path(raw_dir) / "summary.txt"
    if not path.exists():
        return {}
    rows = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.startswith("[172]"):
            continue
        fields = {}
        for token in line.replace("[172]", "").split():
            if "=" in token:
                key, value = token.split("=", 1)
                fields[key] = value
        if "query" in fields and "vus" in fields:
            rows[(int(fields["vus"]), fields["query"])] = fields
    return rows


def num(v, digits=1):
    return "n/a" if v is None else f"{v:.{digits}f}"


def main():
    if len(sys.argv) < 2:
        print("사용: python3 tools/search_baseline_report.py <raw 디렉터리>", file=sys.stderr)
        return 2
    rows = load(sys.argv[1])
    if not rows:
        print("원자료가 없다", file=sys.stderr)
        return 1

    vus_list = sorted({v for v, _ in rows})

    print("**지연 (목록·검색·패싯, 현재 경로)** — 단위 ms. 동시성 축은 이슈 #172 가 고정한 값이다.")
    print()
    print("| 쿼리 | " + " | ".join(f"동시성 {v} p50 / **p95** / p99" for v in vus_list) + " |")
    print("|---|" + "---:|" * len(vus_list))
    for name in QUERY_ORDER:
        cells = []
        for vus in vus_list:
            f = rows.get((vus, name))
            if not f:
                cells.append("-")
                continue
            cells.append(f"{f.get('p50', '?')} / **{f.get('p95', '?')}** / {f.get('p99', '?')}")
        print(f"| `{name}` | " + " | ".join(cells) + " |")

    print()
    print("**판정 기준 대입** — 이슈에 측정 전에 적어 둔 기준 그대로다. 스크립트는 계산만 한다.")
    print()
    print("| 동시성 | 목록 p95 | 300ms 초과? | 패싯 p95 | 목록+패싯 p95 | **패싯 몫** | 50% 이상? |")
    print("|---:|---:|---|---:|---:|---:|---|")
    verdicts = []
    for vus in vus_list:
        listing = rows.get((vus, "list_broad")) or rows.get((vus, "list_filter"))
        facet = rows.get((vus, "facet"))
        list_p95 = ms(listing.get("p95")) if listing else None
        facet_p95 = ms(facet.get("p95")) if facet else None
        total = None
        share = None
        if list_p95 is not None and facet_p95 is not None:
            total = list_p95 + facet_p95
            share = facet_p95 / total if total else None
        over = "**예**" if (list_p95 is not None and list_p95 > SLO_MS) else "아니오"
        share_over = "**예**" if (share is not None and share >= FACET_SHARE_THRESHOLD) else "아니오"
        print(f"| {vus} | {num(list_p95)}ms | {over} | {num(facet_p95)}ms | {num(total)}ms | "
              f"{'n/a' if share is None else f'{share * 100:.1f}%'} | {share_over} |")
        verdicts.append((vus, list_p95, share))

    print()
    # ①은 "목록 p95 > 300ms **이고 그 원인이 집계**"일 때다. 그러므로 함께 봐야 하는 것이
    # **단순 조회의 p95** 다: 필터 없는 목록까지 같이 느리면 원인은 집계가 아니라 **포화**다.
    print("**①의 '원인이 집계인가'를 가르는 값** — 필터 없는 목록까지 느리면 원인은 포화다(집계가 아니다).")
    print()
    print("| 동시성 | 필터 목록 p95 | 필터 없는 목록 p95 | 깊은 페이지 p95 | 읽는 법 |")
    print("|---:|---:|---:|---:|---|")
    for vus in vus_list:
        filtered = ms((rows.get((vus, "list_filter")) or {}).get("p95"))
        broad = ms((rows.get((vus, "list_broad")) or {}).get("p95"))
        deep = ms((rows.get((vus, "list_deep")) or {}).get("p95"))
        reading = "-"
        if broad is not None:
            if broad > SLO_MS:
                reading = "**포화** — 단순 조회도 300ms 초과(집계가 원인이 아니다)"
            else:
                reading = "**여유** — 이 동시성에서는 목록이 SLO 안이다"
        print(f"| {vus} | {num(filtered)}ms | {num(broad)}ms | {num(deep)}ms | {reading} |")

    print()
    worst_list = max((v for _, v, _ in verdicts if v is not None), default=None)
    worst_share = max((s for _, _, s in verdicts if s is not None), default=None)
    share_met = worst_share is not None and worst_share >= FACET_SHARE_THRESHOLD
    list_met = worst_list is not None and worst_list > SLO_MS
    saturated = any(ms((rows.get((v, "list_broad")) or {}).get("p95") or "0ms") > SLO_MS for v in vus_list)

    if share_met:
        print(f"→ **기준 ②가 발동했다**: 패싯 몫이 최대 {worst_share * 100:.1f}% 로 50% 를 넘는다. "
              "이슈가 적은 조치는 **사전 집계 또는 엔진 검토**다 — 싼 쪽(사전 집계)부터 한다.")
    if list_met and not saturated:
        print(f"→ 기준 ①도 발동했다(목록 p95 최대 {num(worst_list)}ms).")
    elif list_met and saturated:
        print(f"→ 목록 p95 는 최대 {num(worst_list)}ms 로 300ms 를 넘지만, 그 동시성에서 "
              "**필터 없는 목록도 같이 느려졌다** — 원인은 집계가 아니라 **포화**다. "
              "기준 ①은 '집계가 원인일 때'이므로 **엔진 검토 조건으로 읽지 않는다.**")
    if not share_met and not list_met:
        print("→ 기준 ①② 모두 아니다. **도입하지 않는다** — 운영 대상 추가는 그 자체로 비용이다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
