#!/usr/bin/env python3
"""E5 결과 표 — 범위 × 부하를 한 표로 모으고 **예산 구간**을 분해한다.

`raw/summary.txt`의 `[E5] k=v  k=v` 줄을 읽는다(그 줄은 `k6/generation-budget.js`의
`handleSummary`가 뽑는다). `raw/<범위>/meta.txt`에서 그 런의 모델 설정도 읽어 <b>예상 지연</b>을
함께 적는다 — 예상과 실측을 나란히 놓아야 "스텁이 모델링한 것이 맞는지"를 볼 수 있다.

읽는 법:
  model_med     추론(생성) 구간의 중앙값 ← E5 의 주 지표
  model_share   전체(serving)에서 추론이 차지하는 비중
  context_med   예산의 컨텍스트 몫(활동 읽기)
  check_med     예산의 제약 확인 몫(E4)
  잔차          serving − (context + model + check). 계기에 안 잡힌 몫 — 0 으로 두면 예산이
                실제보다 깔끔해 보인다
  coverage      모델이 답한 비율. 범위가 느려지면 용량이 줄어 여기가 먼저 무너진다

사용:
  python3 tools/generation_report.py <raw 디렉터리>
"""
import sys
from pathlib import Path

SCOPE_ORDER = {"RANKING": 0, "PREFIX_AR_TOP_K": 1, "FULL_AR": 2}
SCOPE_LABEL = {
    "RANKING": "랭킹(직렬 0)",
    "PREFIX_AR_TOP_K": "앞부분 AR",
    "FULL_AR": "전체 AR",
}


def read_meta(path):
    meta = {}
    if not path.exists():
        return meta
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            key, value = line.strip().split("=", 1)
            meta[key] = value
    return meta


def parse_ms(value):
    """'54.0ms' → 54.0, 없으면 None."""
    if not value or value in ("n/a", "-"):
        return None
    return float(value.rstrip("ms"))


def load(raw_dir):
    path = Path(raw_dir) / "summary.txt"
    if not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.startswith("[E5]"):
            continue
        fields = {}
        for token in line.replace("[E5]", "").split():
            if "=" in token:
                key, value = token.split("=", 1)
                fields[key] = value
        if "scope" not in fields or "rate" not in fields:
            continue
        meta = read_meta(Path(raw_dir) / fields["scope"] / "meta.txt")
        fields["meta"] = meta
        rows.append(fields)
    rows.sort(key=lambda r: (SCOPE_ORDER.get(r["scope"], 9), int(r["rate"])))
    return rows


def expected_latency(scope, meta):
    """스텁이 모델링한 지연 = 기준 + 직렬 항목 수 × 항목당(ms)."""
    try:
        base = float(meta.get("model_latency_ms", "0"))
        per_item = float(meta.get("per_item_ms", "0"))
        result = int(meta.get("result_size", "0"))
        prefix = int(meta.get("ar_prefix", "0"))
    except ValueError:
        return None
    sequential = {
        "RANKING": 0,
        "PREFIX_AR_TOP_K": min(prefix, result),
        "FULL_AR": result,
    }.get(scope, 0)
    return base + sequential * per_item, sequential


def residual(row):
    """모델 경로의 serving − (context + model + check). 구간 계기에 안 잡힌 몫.

    **모델 경로의 e2e 로 계산한다.** 전체 응답의 중앙값에서 빼면 안 된다 — 과부하에서는 그 중앙값이
    폴백(수 ms)이라 잔차가 큰 음수가 된다(실제로 그렇게 나왔다).
    """
    serving = parse_ms(row.get("model_e2e_med"))
    parts = [parse_ms(row.get(k)) for k in ("context_med", "model_med", "check_med")]
    if serving is None or any(p is None for p in parts):
        return None
    return serving - sum(parts)


def main():
    if len(sys.argv) < 2:
        print("사용: python3 tools/generation_report.py <raw 디렉터리>", file=sys.stderr)
        return 2
    rows = load(sys.argv[1])
    if not rows:
        print("원자료가 없다", file=sys.stderr)
        return 1

    print("**모델 경로의 예산** — 아래 `추론·모델 몫·컨텍스트·제약·잔차`는 **모델이 답한 응답만** 모은 것이다")
    print("(폴백은 모델을 안 부르므로 섞으면 모델 몫이 coverage 처럼 희석된다). `e2e` 는 폴백까지 포함한")
    print("전체 응답이라 SLO 를 그대로 보여준다 — 그래서 과부하에서는 둘이 다르게 움직인다.")
    print()
    print("| 범위 | 부하 | **추론 중앙** | 모델 몫 | 컨텍스트 | 제약 | 잔차 | 모델경로 e2e | 전체 e2e 중앙 | 전체 e2e p95 | coverage | 개인화 | 달성 | rejected | timeout | dropped |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for r in rows:
        res = residual(r)
        print(f"| `{r['scope']}` | {r['rate']}/s | **{r.get('model_med', 'n/a')}** | "
              f"{r.get('model_share', 'n/a')} | {r.get('context_med', 'n/a')} | {r.get('check_med', 'n/a')} | "
              f"{'n/a' if res is None else f'{res:.1f}ms'} | {r.get('model_e2e_med', 'n/a')} | "
              f"{r.get('e2e_med', 'n/a')} | {r.get('e2e_p95', 'n/a')} | {r.get('coverage', 'n/a')} | "
              f"{r.get('personalized', 'n/a')} | "
              f"{r.get('achieved', 'n/a')} | {r.get('rejected', 'n/a')} | {r.get('timeout', 'n/a')} | "
              f"{r.get('dropped', 'n/a')} |")

    print()
    print("**예상 vs 실측** — 스텁이 모델링한 지연과 실제로 나온 추론 중앙값. 둘이 벌어지면")
    print("모델링이 틀렸다는 뜻이고, 그러면 아래 해석도 다시 봐야 한다.")
    print("주의: 실측 `modelMs` 는 **모델 용량을 기다린 시간을 포함한다**(서비스가 호출 전체를 잰다).")
    print("그래서 도착률이 용량에 가까워지면 실측이 순수 생성 지연보다 커지는 것이 정상이다.")
    print()
    print("| 범위 | 직렬 항목 | 예상 지연 | 실측 추론 중앙 | 용량(동시/지연) |")
    print("|---|---:|---:|---:|---:|")
    seen = set()
    for r in rows:
        if r["scope"] in seen:
            continue
        seen.add(r["scope"])
        exp = expected_latency(r["scope"], r["meta"])
        meta = r["meta"]
        concurrency = meta.get("model_concurrency", "?")
        if exp is None:
            print(f"| `{r['scope']}` | - | - | {r.get('model_med', 'n/a')} | - |")
            continue
        latency, sequential = exp
        capacity = f"{int(concurrency) * 1000 / latency:.0f}/s" if latency else "n/a"
        print(f"| `{r['scope']}` | {sequential} | {latency:.0f}ms | {r.get('model_med', 'n/a')} | {capacity} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
