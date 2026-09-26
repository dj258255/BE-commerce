#!/usr/bin/env python3
"""활동에 심을 상품 id 목록을 모델 어휘와 재고에서 만든다(#271 · #316).

어휘 형식이 버전마다 다르다 — v1 은 `items` 목록, v2 는 `tokens` 에서 `ITEM_` 접두어를 뗀 상품
토큰이다. 그 차이를 여기서만 다루어 하네스가 v1 · v2 를 같은 명령으로 돌리게 한다.

    python3 tools/genpage_activity_items.py --vocab <어휘.json> --out <items.json> [--limit 400]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


ITEM_FALLBACK = "ITEM_FALLBACK"


def vocab_article_ids(path: str | Path) -> list[int]:
    """어휘 파일에서 상품 id 를 오름차순으로 읽는다(v1 `items` · v2 `tokens`)."""
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if "items" in data:
        values = [str(value) for value in data["items"]]
    elif "tokens" in data:
        values = [name.removeprefix("ITEM_") for name in data["tokens"]
                  if isinstance(name, str) and name.startswith("ITEM_") and name != ITEM_FALLBACK]
    else:
        raise KeyError(f"어휘 형식을 모른다(items · tokens 이 없다): {path}")
    return sorted({int(value) for value in values if value.isdigit()})


def sample_ids(ids: list[int], limit: int) -> list[int]:
    """id 순서를 지킨 채 고르게 limit 개를 뽑는다 — 난수가 아니라 같은 입력이면 같은 목록이다."""
    if limit <= 0 or len(ids) <= limit:
        return list(ids)
    return list(ids[:: max(len(ids) // limit, 1)][:limit])


def stock_ids(host: str, port: int, user: str, password: str, database: str) -> list[int]:
    import pymysql

    conn = pymysql.connect(host=host, port=port, user=user, password=password, database=database)
    try:
        with conn.cursor() as cursor:
            cursor.execute("SELECT product_id FROM stock WHERE quantity > 0 ORDER BY product_id")
            return [int(row[0]) for row in cursor.fetchall()]
    finally:
        conn.close()


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vocab", required=True, type=Path, help="모델 어휘 JSON(v1 · v2)")
    parser.add_argument("--out", required=True, type=Path, help="활동 상품 id 목록을 쓸 경로")
    parser.add_argument("--limit", type=int, default=400)
    parser.add_argument("--db-host", default="127.0.0.1")
    parser.add_argument("--db-port", type=int, default=3306)
    parser.add_argument("--db-user", default="root")
    parser.add_argument("--db-password", default="root")
    parser.add_argument("--db-name", default="becommerce")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    candidates = stock_ids(args.db_host, args.db_port, args.db_user, args.db_password, args.db_name)
    vocab = set(vocab_article_ids(args.vocab))
    in_vocab = [product for product in candidates if product in vocab]
    chosen = sample_ids(in_vocab, args.limit)
    if not chosen:
        print(f"어휘 안에 재고 있는 상품이 없다: {args.vocab}", file=sys.stderr)
        return 1
    args.out.write_text(json.dumps(chosen), encoding="utf-8")
    print(f"활동 상품 {len(chosen)}개(후보 {len(in_vocab):,})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
