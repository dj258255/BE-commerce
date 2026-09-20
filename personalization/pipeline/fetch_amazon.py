#!/usr/bin/env python3
"""Amazon Reviews 2023 — Amazon_Fashion 만 내려받는다.

전체는 수백 GB라 쓰지 않는다. 패션 도메인 1개(5-core 이전 원본)만 받아, H&M의 텍스트·콜드스타트 보조로 쓴다.

사용:
    python3 personalization/pipeline/fetch_amazon.py [--data-dir DIR]
"""
from __future__ import annotations

import argparse
import sys
import urllib.request
from pathlib import Path

BASE = "https://mcauleylab.ucsd.edu/public_datasets/data/amazon_2023/raw"
FILES = {
    "review": f"{BASE}/review_categories/Amazon_Fashion.jsonl.gz",
    "meta": f"{BASE}/meta_categories/meta_Amazon_Fashion.jsonl.gz",
}


def download(url: str, dest: Path) -> None:
    tmp = dest.with_suffix(dest.suffix + ".part")
    with urllib.request.urlopen(url, timeout=120) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length", 0))
        done = 0
        while chunk := r.read(1 << 20):
            f.write(chunk)
            done += len(chunk)
            if total:
                print(f"\r    {dest.name} {done/total*100:5.1f}% ({done/1e6:.0f}/{total/1e6:.0f} MB)", end="", flush=True)
    print()
    tmp.replace(dest)


def remote_size(url: str) -> int | None:
    try:
        with urllib.request.urlopen(urllib.request.Request(url, method="HEAD"), timeout=30) as r:
            return int(r.headers.get("Content-Length", 0)) or None
    except Exception:
        return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default="personalization/data")
    args = ap.parse_args()

    root = Path(args.data_dir) / "amazon" / "raw"
    root.mkdir(parents=True, exist_ok=True)
    for name, url in FILES.items():
        dest = root / f"{name}.jsonl.gz"
        size = remote_size(url)
        if dest.exists() and (size is None or dest.stat().st_size == size):
            print(f"  건너뜀(이미 있음) {dest.name}")
            continue
        print(f"  받는 중 {name} ({size/1e6:.1f} MB)" if size else f"  받는 중 {name}")
        download(url, dest)

    total = sum(p.stat().st_size for p in root.glob("*.gz"))
    print(f"완료 — {total/1e6:.1f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
