#!/usr/bin/env python3
"""H&M 상품을 커머스 `products`로 승격한다.

왜 필요한가: 스토어프론트가 실제로 팔아야 개인화가 화면에 붙는다(`docs/04-storage.md` §2).
Flyway 마이그레이션으로 넣지 않는 이유: 이건 **스키마가 아니라 데이터**다. 10만 행을 마이그레이션에
박으면 모든 부팅·CI가 그 비용을 낸다. 개발/데모 적재 단계로 분리한다.

## 합성 규칙 (H&M에는 없는 값이라 반드시 규칙을 남긴다)

**가격**: H&M `price`는 통화가 아니라 정규화된 상대가다(실측 median 0.025 · max 0.59).
상품별 상대가 중앙값을 구해, 전체 상품 중앙값이 `MEDIAN_KRW`가 되도록 **선형 사상**한다.
    krw = round(rel / median_rel * MEDIAN_KRW / 100) * 100,  clamp [1,000, 500,000]
거래가 없는 상품(995개)은 그 카테고리의 중앙값을 쓴다. **결정적이고 되돌릴 수 있다** — 규칙을 바꾸면 재실행.

**카테고리**: H&M `index_group_name`(5종)을 그대로 쓴다.
**브랜드**: `H&M` (H&M에는 브랜드 컬럼이 없다).
**이미지**: 아직 없다(린 슬라이스에서 제외) → NULL. 화면은 그라디언트로 폴백한다.
**featured**: 마지막 30일 구매 수 상위 8개.
**created_at**: 그 상품의 첫 거래일(거래가 없으면 카탈로그 기준일).

## 사용

    python3 personalization/pipeline/promote_products.py --emit-sql            # SQL 생성
    python3 personalization/pipeline/promote_products.py --emit-sql --load     # 생성 후 MySQL에 적재
"""
from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

import pandas as pd

MEDIAN_KRW = 29_900          # 전체 상품 상대가 중앙값을 이 원화 값에 맞춘다
MIN_KRW, MAX_KRW = 1_000, 500_000
PRICE_STEP = 100
BRAND = "H&M"
FEATURED_TOP = 8
FEATURED_WINDOW_DAYS = 30
BATCH = 500
STOCK_DEFAULT = 1000        # 합성 재고 — H&M에는 재고가 없다

CATEGORY_NAMES = {
    "Ladieswear": ("ladieswear", "여성복"),
    "Menswear": ("menswear", "남성복"),
    "Divided": ("divided", "Divided"),
    "Baby/Children": ("kids", "아동복"),
    "Sport": ("sport", "스포츠"),
}


def esc(s: str) -> str:
    return s.replace("\\", "\\\\").replace("'", "''")


def sql_str(s) -> str:
    return "NULL" if s is None or (isinstance(s, float) and pd.isna(s)) else f"'{esc(str(s))}'"


def build_catalog(data: Path) -> tuple[pd.DataFrame, pd.DataFrame]:
    art = pd.read_parquet(data / "hm" / "normalized" / "articles.parquet")
    tx = pd.read_parquet(
        data / "hm" / "normalized" / "transactions.parquet",
        columns=["t_dat", "article_id", "price"],
    )
    tx["t_dat"] = pd.to_datetime(tx["t_dat"])

    # 상품별 상대가 중앙값 → 원화 사상
    rel = tx.groupby("article_id", observed=True)["price"].median()
    cat_of = art.set_index("article_id")["index_group_name"]
    rel = pd.DataFrame({"rel": rel})
    rel["category"] = cat_of.reindex(rel.index).values
    median_rel = float(rel["rel"].median())

    def to_krw(v: float) -> int:
        krw = round(v / median_rel * MEDIAN_KRW / PRICE_STEP) * PRICE_STEP
        return int(min(max(krw, MIN_KRW), MAX_KRW))

    rel["price_krw"] = rel["rel"].map(to_krw)
    cat_median = rel.groupby("category", observed=True)["rel"].median().map(to_krw)

    first_seen = tx.groupby("article_id", observed=True)["t_dat"].min()
    cutoff = tx["t_dat"].max()
    recent = tx[tx["t_dat"] >= cutoff - pd.Timedelta(days=FEATURED_WINDOW_DAYS)]
    featured_ids = set(recent.groupby("article_id", observed=True).size().sort_values(ascending=False).head(FEATURED_TOP).index)

    prod = art.copy()
    prod["price"] = prod["article_id"].map(rel["price_krw"])
    missing = prod["price"].isna()
    prod.loc[missing, "price"] = prod.loc[missing, "index_group_name"].map(cat_median)
    prod["price"] = prod["price"].fillna(MEDIAN_KRW).astype(int)
    prod["category_code"] = prod["index_group_name"].map(lambda g: CATEGORY_NAMES[g][0])
    prod["brand"] = BRAND
    prod["image_url"] = None
    prod["featured"] = prod["article_id"].isin(featured_ids).astype(int)
    prod["created_at"] = prod["article_id"].map(first_seen).fillna(cutoff)
    prod["description"] = prod["detail_desc"]

    # product_id는 BIGINT라 10자리 문자열을 정수로. 앞의 0은 저장되지 않는다(이미지 경로에서 %010d로 복원).
    prod["product_id"] = prod["article_id"].astype(int)

    cats = (
        prod.groupby("category_code", observed=True)
        .size()
        .reset_index(name="n")
        .sort_values("n", ascending=False)
        .reset_index(drop=True)
    )
    cats["name"] = cats["category_code"].map({v[0]: v[1] for v in CATEGORY_NAMES.values()})
    cats["sort_order"] = cats.index + 1

    keep = ["product_id", "prod_name", "price", "category_code", "description", "image_url", "brand", "featured", "created_at"]
    return prod[keep], cats[["category_code", "name", "sort_order", "n"]]


def emit_sql(prod: pd.DataFrame, cats: pd.DataFrame, path: Path) -> None:
    lines = [
        "-- H&M 카탈로그 승격 (생성물 — 손으로 고치지 않는다)",
        f"-- 상품 {len(prod):,}개 · 카테고리 {len(cats)}개",
        "-- Flyway 마이그레이션이 아니다: 스키마가 아니라 데이터다(docs/04-storage.md §2).",
        "-- 멱등: 재실행하면 같은 product_id를 갱신한다.",
        "",
        "SET NAMES utf8mb4;",
        "",
    ]
    for _, c in cats.iterrows():
        lines.append(
            "INSERT INTO categories (code, name, description, sort_order) VALUES "
            f"({sql_str(c['category_code'])}, {sql_str(c['name'])}, NULL, {int(c['sort_order'])}) "
            "ON DUPLICATE KEY UPDATE name=VALUES(name), sort_order=VALUES(sort_order);"
        )
    lines.append("")

    cols = "(product_id, name, price, category_code, description, image_url, brand, featured, created_at)"
    rows = []
    for _, r in prod.iterrows():
        rows.append(
            f"({int(r['product_id'])}, {sql_str(r['prod_name'])}, {int(r['price'])}, {sql_str(r['category_code'])}, "
            f"{sql_str(r['description'])}, {sql_str(r['image_url'])}, {sql_str(r['brand'])}, {int(r['featured'])}, "
            f"{sql_str(pd.Timestamp(r['created_at']).strftime('%Y-%m-%d %H:%M:%S'))})"
        )
    for i in range(0, len(rows), BATCH):
        chunk = rows[i:i + BATCH]
        lines.append(
            f"INSERT INTO products {cols} VALUES\n  " + ",\n  ".join(chunk) +
            "\nON DUPLICATE KEY UPDATE name=VALUES(name), price=VALUES(price), "
            "category_code=VALUES(category_code), description=VALUES(description), "
            "brand=VALUES(brand), featured=VALUES(featured);"
        )

    # 재고: H&M에는 재고가 없다. **합성**이다 — 없으면 승인 시점 조건부 차감이 0행이라
    # 주문은 만들어지고 승인에서 OUT_OF_STOCK으로 실패한다(화면은 살 수 있는 것처럼 보인다).
    lines.append("")
    lines.append("-- 합성 재고 (H&M에 재고 없음). 이 사실을 리포트에 남긴다.")
    stock_rows = [f"({int(r['product_id'])}, {STOCK_DEFAULT}, 0)" for _, r in prod.iterrows()]
    for i in range(0, len(stock_rows), BATCH):
        chunk = stock_rows[i:i + BATCH]
        lines.append(
            "INSERT INTO stock (product_id, quantity, version) VALUES\n  " + ",\n  ".join(chunk) +
            "\nON DUPLICATE KEY UPDATE quantity=VALUES(quantity);"
        )

    # 레거시 데모 상품이 '추천' 자리를 차지하지 않게 한다. (k6·통합 테스트가 1~3번을 참조하므로 행은 남긴다)
    lines.append("")
    lines.append("-- 레거시 데모 상품(1~999)은 남기되 추천에서는 뺀다. 참조(k6·테스트) 때문이다.")
    lines.append("UPDATE products SET featured = 0 WHERE product_id < 1000;")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def load_sql(path: Path, container: str, db: str, user: str, pw: str) -> None:
    with open(path, "rb") as f:
        subprocess.run(
            ["docker", "exec", "-i", container, "mysql", f"-u{user}", f"-p{pw}", db],
            stdin=f, check=True,
        )


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
    prod, cats = build_catalog(data)
    print(f"상품 {len(prod):,}개 · 카테고리 {len(cats)}개")
    print(cats.to_string(index=False))
    print("\n가격 분포(원):", prod['price'].describe()[['min', '25%', '50%', '75%', 'max']].astype(int).to_dict())
    print(f"featured {int(prod['featured'].sum())}개 · 거래 없는 상품 가격은 카테고리 중앙값으로 채움")

    if args.emit_sql or args.load:
        out = data / "hm" / "catalog.sql"
        out.parent.mkdir(parents=True, exist_ok=True)
        emit_sql(prod, cats, out)
        print(f"\nSQL: {out} ({out.stat().st_size/1e6:.1f} MB)")
        if args.load:
            load_sql(out, args.container, args.db, args.user, args.password)
            print(f"적재 완료 → {args.db}.products")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
