#!/usr/bin/env python3
"""H&M 상품 이미지를 내려받아 `personalization/data/hm/images/{article_id}.jpg`로 푼다.

왜 이 경로인가: Kaggle 원본은 인증이 필요해 못 받는다. 대신 Hugging Face에 **이미지가 들어 있는**
캡션 데이터셋이 있고, parquet의 `image.path`에 **원본 파일명(= article_id.jpg)** 이 남아 있다(실측 확인).
전체 105,542장이 아니라 **부분집합**이라는 사실은 리포트에 적는다.

사용:
    python3 personalization/pipeline/fetch_hm_images.py [--data-dir DIR]
"""
from __future__ import annotations

import argparse
import io
import sys
import urllib.request
from pathlib import Path

import pyarrow.parquet as pq

BASE = "https://huggingface.co/datasets/tomytjandra/h-and-m-fashion-caption/resolve/main/data"
FILES = [f"train-{i:05d}-of-00016.parquet" for i in range(16)]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default="personalization/data")
    ap.add_argument("--only", type=int, default=None, help="앞 N개 파일만 (배관 확인용)")
    args = ap.parse_args()

    out = Path(args.data_dir) / "hm" / "images"
    out.mkdir(parents=True, exist_ok=True)
    files = FILES[: args.only] if args.only else FILES

    saved = skipped = 0
    for name in files:
        url = f"{BASE}/{name}"
        print(f"  받는 중 {name}")
        with urllib.request.urlopen(url, timeout=600) as r:
            buf = io.BytesIO(r.read())
        t = pq.read_table(buf, columns=["image"])
        for v in t.column("image").to_pylist():
            path = (v or {}).get("path")
            data = (v or {}).get("bytes")
            if not path or not data:
                continue
            dest = out / Path(path).name
            if dest.exists() and dest.stat().st_size == len(data):
                skipped += 1
                continue
            dest.write_bytes(data)
            saved += 1
        print(f"    누적 저장 {saved:,} · 건너뜀 {skipped:,}")

    total = len(list(out.glob("*.jpg")))
    print(f"완료 — 이미지 {total:,}장, {sum(p.stat().st_size for p in out.glob('*.jpg'))/1e9:.2f} GB → {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
