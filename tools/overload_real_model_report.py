"""#271 결과 표. run-overload-real-model.sh 가 남긴 capacity.json 과 각 조건의 raw/summary.txt 를 모은다."""
import json
import pathlib
import re
import sys

OUT = pathlib.Path(sys.argv[1])
cap = json.loads((OUT / "capacity.json").read_text())
C, Lm, Cp = cap["C"], cap["Lm"], cap["Cp"]
limit = (100 + Lm) * 1.2  # A′·B′ 의 추천 행 p95 기준

print("### 모델 서버 용량(직접, 닫힌 루프 20초)\n")
print("| 경로 | 동시성 | 처리량 | p50 | p95 | p99 |")
print("|---|---:|---:|---:|---:|---:|")
for r in cap["results"]:
    print(f"| `{r['path']}` | {r['threads']} | {r['throughput']:.1f}/s | {r['p50_ms']:.1f}ms | {r['p95_ms']:.1f}ms | {r['p99_ms']:.1f}ms |")
print(f"\nC = {C:.1f}/s · Lm = {Lm:.1f}ms · Cp = {Cp:.1f}/s · A 의 p95 기준 (100 + Lm) × 1.2 = {limit:.0f}ms\n")


def parse(line):
    return dict(kv.split("=", 1) for kv in re.findall(r"(\w+=\S+)", line))


print("| 조건 | 부하 | 달성 | coverage | 기대 coverage(C ÷ 부하) | serving p95 | p99 | 거절 | 대기 초과 | 실패 | dropped |")
print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
pages = []
for d in sorted(OUT.glob("*/raw/summary.txt")):
    name = d.parent.parent.name
    for line in d.read_text().splitlines():
        if line.startswith("[PAGE]"):
            pages.append((name, parse(line)))
            continue
        if not line.startswith("[E3]"):
            continue
        v = parse(line)
        rate = int(v["rate"])
        expect = min(C / rate, 1.0)
        print(f"| {name} | {rate} | {v['achieved']} | {v['coverage']} | {expect:.1%} | {v['serving_p95']} | {v['serving_p99']} "
              f"| {v['rejected']} | {v['timeout']} | {v['failed']} | {v['dropped']} |")
for name, v in pages:
    print(f"\n- {name} 홈 2쪽: 부하 {v['rate']}/s · 달성 {v['achieved']}/s · 모델이 만든 쪽 {v['generated']} · "
          f"p95 {v['page_p95']} · p99 {v['page_p99']} · dropped {v['dropped']}")
