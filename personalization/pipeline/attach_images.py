#!/usr/bin/env python3
"""내려받은 이미지를 상품에 붙인다 — `products.image_url`을 채운다.

경로 규칙: `/uploads/{article_id}.jpg`. article_id는 상품 id를 10자리로 되돌린 값이다
(`docs/00-data.md` §3 — 저장은 BIGINT라 앞의 0이 없지만 파일명은 10자리다).

**전체 카탈로그가 아니라 이미지가 있는 상품만** 채운다. 나머지는 NULL이고 화면은 그라디언트로 폴백한다.

사용:
    python3 personalization/pipeline/attach_images.py --emit-sql
    python3 personalization/pipeline/attach_images.py --load
"""
from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

BATCH = 1000


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default="personalization/data")
    ap.add_argument("--emit-sql", action="store_true")
    ap.add_argument("--load", action="store_true")
    ap.add_argument("--container", default="pay-mysql-1")
    ap.add_argument("--db", default="becommerce")
    ap.add_argument("--user", default="becommerce")
    ap.add_argument("--password", default="becommerce")
    args = ap.parse_args()

    data = Path(args.data_dir)
    images = sorted(p.stem for p in (data / "hm" / "images").glob("*.jpg"))
    if not images:
        raise SystemExit("이미지가 없습니다 — fetch_hm_images.py 를 먼저 돌리세요")

    ids = [int(s) for s in images if s.isdigit()]
    print(f"이미지 {len(ids):,}장 · 예: {images[0]} → product_id {ids[0]}")

    lines = [
        "-- 이미지 부착 (생성물). 이미지가 있는 상품만 image_url을 채운다.",
        f"-- 대상 {len(ids):,}개 — 전체 카탈로그의 일부다. 나머지는 NULL(화면은 그라디언트 폴백).",
        "",
        "SET NAMES utf8mb4;",
        "",
    ]
    for i in range(0, len(ids), BATCH):
        chunk = ids[i:i + BATCH]
        lines.append(
            "UPDATE products SET image_url = CONCAT('/uploads/', LPAD(product_id, 10, '0'), '.jpg') "
            f"WHERE product_id IN ({','.join(str(x) for x in chunk)});"
        )
    out = data / "hm" / "images.sql"
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"SQL: {out} ({out.stat().st_size/1e6:.1f} MB)")

    if args.load:
        with open(out, "rb") as f:
            subprocess.run(
                # `--default-character-set=utf8mb4` 가 없으면 한글이 이중 인코딩된다(리뷰 적재에서 겪었다).
                ["docker", "exec", "-i", args.container, "mysql", "--default-character-set=utf8mb4",
                 f"-u{args.user}", f"-p{args.password}", args.db],
                stdin=f, check=True,
            )
        print(f"적재 완료 → {args.db}.products.image_url")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
