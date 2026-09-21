#!/usr/bin/env python3
"""합성 리뷰를 만든다 — **실데이터가 아니다**(#168).

왜 합성인가: H&M 코퍼스에 리뷰가 없고(구매 이력·상품 메타만), Amazon 리뷰 250만 건은 다른
카탈로그라 조인 키가 없다. 상세 화면을 채우려면 리뷰를 **만들 수밖에 없고**, 만들면 **밝혀야** 한다 —
그 판단은 ADR-046 에 있다.

**결정적이다**: 씨앗은 `product_id` 다. 같은 코퍼스에서 다시 돌리면 같은 리뷰가 나온다(가격·재고를
만드는 승격 파이프라인과 같은 규칙 — 합성은 **규칙을 남긴다**).

**어디에 만드는가**: 인기 상위 N개에만 붙인다(기본 200). 105,542개 전부에 붙이면 쓰이지도 않는
행이 30만 개 생긴다. 화면에서 실제로 보이는 자리에만 만든다.

**무엇을 만들지 않는가**: 평점 **평균**이나 리뷰 **수**를 `products` 에 비정규화하지 않는다.
그 순간 그 값이 상품의 품질 신호로 쓰이고, 화면이 "실제 평점 4.3"처럼 보인다. 집계를 두지 않으면
그 통로가 없다.

사용:
    python3 personalization/pipeline/seed_synthetic_reviews.py --emit-sql
    python3 personalization/pipeline/seed_synthetic_reviews.py --emit-sql --load
"""
from __future__ import annotations

import argparse
import random
import subprocess
from pathlib import Path

import pandas as pd

TOP_N = 200
RECENT_DAYS = 7
MIN_REVIEWS, MAX_REVIEWS = 2, 5
SOURCE_SYNTHETIC = "SYNTHETIC"
BATCH = 500

# 본문 템플릿 — `{name}` 이 들어간다. **일부러 일반적이다**: 그럴듯한 구체적 후기를 지어내면
# 데이터에 없는 사실(착용감·세탁 결과)을 만드는 것이기 때문이다.
BODIES = [
    "{name} 잘 받았습니다. 배송이 빨랐어요.",
    "가격 대비 만족합니다. {name} 무난해요.",
    "{name} 색감이 사진과 비슷합니다.",
    "사이즈가 잘 맞았습니다. {name} 재구매 의사 있어요.",
    "{name} 포장이 깔끔했어요.",
    "배송은 조금 걸렸지만 {name} 자체는 만족합니다.",
    "가볍고 편합니다. {name} 데일리로 쓰기 좋아요.",
    "무난한 품질입니다. {name} 가성비로는 괜찮아요.",
]
AUTHORS = ["구매자", "고객", "리뷰어", "사용자"]


def top_products(data: Path, n: int) -> list[tuple[int, str]]:
    """**두 창의 합집합** 상위 N개 (product_id, 이름).

    왜 합집합인가: 홈의 "인기" 행은 `recent_7d`(데이터 기준 마지막 7일)를 쓴다. 전체 기간 상위만
    고르면 **홈에 뜨는 상품에 리뷰가 없고**, 리뷰가 있는 상품은 홈에 안 뜨는 상태가 된다
    (실제로 처음에 그렇게 만들어 1위 상품이 리뷰 0건이었다). 리뷰는 **사용자가 실제로 보는 자리**에
    붙어야 하므로 두 창을 합친다.
    """
    tx = pd.read_parquet(data / "hm" / "normalized" / "transactions.parquet",
                         columns=["t_dat", "article_id"])
    tx["t_dat"] = pd.to_datetime(tx["t_dat"])
    tx["article_id"] = tx["article_id"].astype("category")
    as_of = tx["t_dat"].max().normalize()

    def top(since) -> list:
        src = tx if since is None else tx[tx["t_dat"] >= since]
        return list(src.groupby("article_id", observed=True).size()
                   .sort_values(ascending=False).head(n).index)

    ids = list(dict.fromkeys(top(as_of - pd.Timedelta(days=RECENT_DAYS - 1)) + top(None)))
    articles = pd.read_parquet(data / "hm" / "normalized" / "articles.parquet",
                               columns=["article_id", "prod_name"])
    names = dict(zip(articles["article_id"].astype(str), articles["prod_name"]))
    return [(int(str(a)), str(names.get(str(a), "상품"))) for a in ids]


def reviews_for(product_id: int, name: str) -> list[dict]:
    """**결정적** 생성 — 씨앗이 상품 id 라 같은 상품은 항상 같은 리뷰를 받는다."""
    rng = random.Random(product_id)
    count = rng.randint(MIN_REVIEWS, MAX_REVIEWS)
    out = []
    for _ in range(count):
        body = rng.choice(BODIES).format(name=name)
        # 별점은 3~5 에 몰리게 둔다(합성이라는 사실을 숨기려는 것이 아니라, 분포를 정하는 것도
        # 우리라는 사실을 규칙으로 남기려는 것).
        out.append({
            "rating": rng.choice([3, 4, 4, 5, 5, 5]),
            "body": body,
            "author": f"{rng.choice(AUTHORS)}{product_id % 1000:03d}",
            "days_ago": rng.randint(1, 400),
        })
    return out


def emit_sql(rows: list[tuple[int, dict]], as_of: str, out: Path) -> int:
    lines = [
        "-- 합성 리뷰(#168). **손으로 고치지 말고 seed_synthetic_reviews.py 로 다시 만든다.**",
        f"-- 기준일 {as_of} · source 는 전부 {SOURCE_SYNTHETIC} 이다(실데이터가 아니다).",
        "DELETE FROM product_reviews;",
    ]
    values = []
    for product_id, r in rows:
        body = r["body"].replace("'", "''")
        values.append(f"({product_id}, '{SOURCE_SYNTHETIC}', {r['rating']}, '{body}', "
                      f"'{r['author']}', DATE_SUB('{as_of}', INTERVAL {r['days_ago']} DAY))")
    for i in range(0, len(values), BATCH):
        lines.append("INSERT INTO product_reviews (product_id, source, rating, body, author_label, created_at) VALUES\n  "
                     + ",\n  ".join(values[i:i + BATCH]) + ";")
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return len(values)


def load_sql(path: Path, container: str, db: str, user: str, pw: str) -> None:
    with open(path, "rb") as f:
        subprocess.run(["docker", "exec", "-i", container, "mysql",
                        "--default-character-set=utf8mb4", f"-u{user}", f"-p{pw}", db],
                       stdin=f, check=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default="personalization/data")
    ap.add_argument("--top-n", type=int, default=TOP_N)
    ap.add_argument("--out", default="personalization/data/hm/synthetic_reviews.sql")
    ap.add_argument("--emit-sql", action="store_true")
    ap.add_argument("--load", action="store_true")
    ap.add_argument("--container", default="pay-mysql-1")
    ap.add_argument("--db", default="becommerce")
    ap.add_argument("--user", default="becommerce")
    ap.add_argument("--password", default="becommerce")
    args = ap.parse_args()

    data = Path(args.data_dir)
    products = top_products(data, args.top_n)
    if not products:
        print("상품을 찾지 못했다 — transactions.parquet 을 확인하라")
        return 1

    tx = pd.read_parquet(data / "hm" / "normalized" / "transactions.parquet", columns=["t_dat"])
    as_of = str(pd.to_datetime(tx["t_dat"]).max().date())

    rows: list[tuple[int, dict]] = []
    for product_id, name in products:
        for review in reviews_for(product_id, name):
            rows.append((product_id, review))

    rated = [r["rating"] for _, r in rows]
    print(f"상품 {len(products):,}개 · 리뷰 {len(rows):,}건 (상품당 {MIN_REVIEWS}~{MAX_REVIEWS}) · 기준일 {as_of}")
    print(f"별점 분포(합성): 평균 {sum(rated) / len(rated):.2f} — **이 값을 어디에도 저장·집계하지 않는다**")

    if args.emit_sql or args.load:
        out = Path(args.out)
        written = emit_sql(rows, as_of, out)
        print(f"SQL {out} · INSERT {written:,}행")
        if args.load:
            load_sql(out, args.container, args.db, args.user, args.password)
            print("적재 완료")
    else:
        print("(--emit-sql 없이 실행 — SQL 을 만들지 않았다)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
