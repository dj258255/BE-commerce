#!/usr/bin/env python3
"""E6 결과 표 — 코덱 × 값 크기를 한 표로 모으고 **손익 교차점**을 찾는다.

`raw/<코덱>/bench-<크기>.json`(계기 엔드포인트의 응답)을 읽는다. 저장 형식이 JSON 이라
`[E3]`/`[E4]` 처럼 요약 줄을 파싱하지 않아도 된다 — 이 엔드포인트는 표 하나를 그대로 돌려준다.

읽는 법:
  stored/raw    실제 저장 바이트 / 원본 바이트. **1 미만이면 이득**, 1을 넘으면 손해다
                (표식 + base64 팽창 33% 포함 — 이게 청구서다)
  set/get p50   왕복 지연의 중앙값(압축·해제 포함). tail 은 p95 로 본다
  cpu ms        앱이 쓴 CPU(이 스레드). 압축의 대가가 여기 있다

사용:
  python3 tools/cache_compression_report.py <raw 디렉터리>
"""
import json
import sys
from pathlib import Path

CODEC_ORDER = {"NONE": 0, "LZ4": 1, "SNAPPY": 2}


def load(raw_dir):
    rows = []
    for codec_dir in sorted(Path(raw_dir).iterdir()):
        if not codec_dir.is_dir():
            continue
        meta = {}
        meta_path = codec_dir / "meta.txt"
        if meta_path.exists():
            for line in meta_path.read_text(encoding="utf-8").splitlines():
                if "=" in line:
                    key, value = line.strip().split("=", 1)
                    meta[key] = value
        for bench in sorted(codec_dir.glob("bench-*.json")):
            try:
                row = json.loads(bench.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                continue
            row["codec"] = meta.get("codec", codec_dir.name)
            row["threshold"] = meta.get("threshold_bytes", "?")
            rows.append(row)
    rows.sort(key=lambda r: (CODEC_ORDER.get(r["codec"], 9), r["rawBytes"]))
    return rows


def human(n):
    if n is None:
        return "n/a"
    if n >= 1024 * 1024:
        return f"{n / 1024 / 1024:.1f}MB"
    if n >= 1024:
        return f"{n / 1024:.1f}KB"
    return f"{n}B"


def num(v, digits=2):
    return "n/a" if v is None or v < 0 else f"{v:.{digits}f}"


def main():
    if len(sys.argv) < 2:
        print("사용: python3 tools/cache_compression_report.py <raw 디렉터리>", file=sys.stderr)
        return 2
    rows = load(sys.argv[1])
    if not rows:
        print("원자료가 없다", file=sys.stderr)
        return 1

    print("| 코덱 | 요청 | 실제 원본 | **저장/원본** | 저장 합계 | set p50 | set p95 | get p50 | get p95 | get p99 | 압축 p50 | 해제 p50 | CPU | 표본 |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for r in rows:
        ratio = r.get("ratio")
        mark = ""
        if ratio is not None:
            mark = " **이득**" if ratio < 1 else ""
        print(f"| `{r['codec']}` | {human(r.get('requestedBytes'))} | {human(r.get('rawBytes'))} | "
              f"**{num(ratio, 3)}**{mark} | {human(r.get('storedBytesTotal'))} | "
              f"{num(r.get('setP50'))}ms | {num(r.get('setP95'))}ms | {num(r.get('getP50'))}ms | "
              f"{num(r.get('getP95'))}ms | {num(r.get('getP99'))}ms | {num(r.get('compressP50'))}ms | "
              f"{num(r.get('decompressP50'))}ms | {num(r.get('appCpuMs'))}ms | {r.get('count', '?')} |")

    print()
    print("**교차점** — 크기별로 저장량이 처음 1 미만이 되는 지점. 이 아래는 압축이 손해다.")
    print()
    sizes = sorted({r.get("rawBytes") for r in rows if r.get("rawBytes")})
    codecs = sorted({r["codec"] for r in rows}, key=lambda c: CODEC_ORDER.get(c, 9))
    print("| 실제 원본 | " + " | ".join(f"`{c}`" for c in codecs) + " |")
    print("|---:|" + "---:|" * len(codecs))
    for size in sizes:
        cells = []
        for codec in codecs:
            match = [r for r in rows if r["codec"] == codec and r.get("rawBytes") == size]
            if not match or match[0].get("ratio") is None:
                cells.append("-")
                continue
            ratio = match[0]["ratio"]
            cells.append(f"{ratio:.3f}" + (" (이득)" if ratio < 1 else ""))
        print(f"| {human(size)} | " + " | ".join(cells) + " |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
