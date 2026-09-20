#!/usr/bin/env python3
"""H&M 원본을 내려받는다.

Kaggle은 인증이 필요하므로 Hugging Face 미러(parquet)를 쓴다. 이미 받은 파일은 크기로 확인해 건너뛴다
(멱등 — 재실행이 같은 결과를 내야 한다. `03-verification.md`).

사용:
    python3 personalization/pipeline/fetch_hm.py [--data-dir DIR]
"""
from __future__ import annotations

import argparse
import os
import sys
import urllib.request
from pathlib import Path

BASE = "https://huggingface.co/datasets/dinhlnd1610/HM-Personalized-Fashion-Recommendations/resolve/main"

FILES = [
    "articles/train-00000-of-00001.parquet",
    "customers/train-00000-of-00001.parquet",
] + [f"transactions/train-{i:05d}-of-00007.parquet" for i in range(7)]


def remote_size(url: str) -> int | None:
    req = urllib.request.Request(url, method="HEAD")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return int(r.headers.get("Content-Length", 0)) or None
    except Exception:
        return None


def download(url: str, dest: Path) -> None:
    tmp = dest.with_suffix(dest.suffix + ".part")
    with urllib.request.urlopen(url, timeout=120) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length", 0))
        done = 0
        while chunk := r.read(1 << 20):
            f.write(chunk)
            done += len(chunk)
            if total:
                pct = done / total * 100
                print(f"\r    {dest.name} {pct:5.1f}% ({done/1e6:.0f}/{total/1e6:.0f} MB)", end="", flush=True)
    print()
    tmp.replace(dest)  # 완성된 파일만 남긴다 — 중간에 죽어도 반쪽 파일이 섞이지 않는다


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default="personalization/data")
    args = ap.parse_args()

    root = Path(args.data_dir) / "hm" / "raw"
    root.mkdir(parents=True, exist_ok=True)
    print(f"받는 위치: {root}")

    for rel in FILES:
        dest = root / rel.replace("/", "__")
        url = f"{BASE}/{rel}"
        size = remote_size(url)
        if dest.exists() and (size is None or dest.stat().st_size == size):
            print(f"  건너뜀(이미 있음) {dest.name}")
            continue
        print(f"  받는 중 {rel} ({size/1e6:.1f} MB)" if size else f"  받는 중 {rel}")
        download(url, dest)

    total = sum(p.stat().st_size for p in root.glob("*.parquet"))
    print(f"완료 — {len(list(root.glob('*.parquet')))}개 파일, {total/1e6:.1f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
