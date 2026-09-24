"""#264 표: 조건 디렉터리마다 [E3] 요약 줄을 모은다.

    python3 tools/overload_sensitivity_report.py OUT
"""
import pathlib
import sys


def rows(out):
    for d in sorted(pathlib.Path(out).iterdir()):
        s = d / "raw" / "summary.txt"
        if not s.exists():
            continue
        for line in s.read_text().splitlines():
            if line.startswith("[E3]"):
                f = dict(t.split("=", 1) for t in line.replace("[E3]", "").split() if "=" in t)
                yield d.name, f


def main(out):
    print("| 조건 | 정책 | 부하 | 달성 | coverage | serving p95 | model p95 | 거절 | 대기 초과 |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---:|")
    for name, f in rows(out):
        print(f"| {name} | {f['policy']} | {f['rate']} | {f['achieved']} | {f['coverage']} | {f['serving_p95']} "
              f"| {f['model_p95']} | {f['rejected']} | {f['timeout']} |")


if __name__ == "__main__":
    main(sys.argv[1])
