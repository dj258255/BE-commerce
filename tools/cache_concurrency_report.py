"""#266 표: 크기 × 스레드 × 코덱.

    python3 tools/cache_concurrency_report.py OUT
"""
import json
import pathlib
import sys


def main(out):
    out = pathlib.Path(out)
    rows = {}
    for p in out.glob("*-*-*.json"):
        codec, size, t = p.stem.split("-")
        rows[(int(size), int(t), codec)] = json.loads(p.read_text())
    print("| 크기 | 스레드 | 코덱 | 저장/원본 | 넣고 읽기 p95 | p99 | get p99 | 연산당 CPU |")
    print("|---:|---:|---|---:|---:|---:|---:|---:|")
    for (size, t, codec) in sorted(rows, key=lambda k: (k[0], k[1], ["NONE", "LZ4", "SNAPPY"].index(k[2]))):
        r = rows[(size, t, codec)]
        print(f"| {size // 1024}KB | {t} | {codec} | {r['ratio']:.3f} | {r['totalP95']:.2f}ms | {r['totalP99']:.2f}ms "
              f"| {r['getP99']:.2f}ms | {r['cpuPerOpUs']:.0f}µs |")


if __name__ == "__main__":
    main(sys.argv[1])
