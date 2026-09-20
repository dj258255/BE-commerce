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

**카테고리**: H&M `index_group_name`(5종)을 **대분류**로, `garment_group_name`(21종)을 **중분류**로 쓴다.
둘은 **직교**한다 — 중분류 21종 중 2종만 한 대분류에 속한다(니트는 여성복·남성복·아동복·Divided에 다 있다).
그래서 노드는 (대분류 × 중분류) **조합**이고, 5×21=105 중 **실제 존재하는 72개**만 만든다(V56 주석 참고).
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
import re
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

# 중분류 이름. **번역이 확실한 것만 한국어로** 하고, 확실하지 않으면 원문을 그대로 쓴다 —
# 그럴듯한 한국어를 지어내면 데이터에 없는 의미를 만드는 것이기 때문이다.
# 어느 쪽이든 `source_name`에 H&M 원문을 남겨 추적할 수 있게 한다.
SUBCATEGORY_NAMES = {
    "Accessories": "액세서리",
    "Blouses": "블라우스",
    "Dresses Ladies": "드레스",
    "Dresses/Skirts girls": "여아 드레스·스커트",
    "Jersey Basic": "저지 베이직",
    "Jersey Fancy": "저지 팬시",
    "Knitwear": "니트",
    "Outdoor": "아웃도어",
    "Shirts": "셔츠",
    "Shoes": "슈즈",
    "Shorts": "반바지",
    "Skirts": "스커트",
    "Socks and Tights": "양말·타이츠",
    "Special Offers": "특가",
    "Swimwear": "수영복",
    "Trousers": "팬츠",
    "Trousers Denim": "데님 팬츠",
    "Under-, Nightwear": "언더웨어·나이트웨어",
    "Unknown": "기타",
    "Dressed": "Dressed",                                  # H&M 내부 분류 — 번역이 불확실해 원문 유지
    "Woven/Jersey/Knitted mix Baby": "Woven/Jersey/Knitted mix Baby",
}

# 색상 이름. **번역이 확실한 것만 한국어로** 하고, 확실하지 않으면 원문을 그대로 쓴다 — 중분류와
# 같은 규칙이다. 옮기면 의미가 흐려지는 H&M 고유 색상명(Mole, Yellowish Green, Bluish Green)과
# 소스에 문자열로 들어온 `undefined`는 원문을 남긴다. `Unknown`은 화면용 '기타'로 모은다.
COLOUR_NAMES = {
    "Black": "블랙",
    "Blue": "블루",
    "White": "화이트",
    "Pink": "핑크",
    "Grey": "그레이",
    "Red": "레드",
    "Beige": "베이지",
    "Green": "그린",
    "Khaki green": "카키 그린",
    "Yellow": "옐로우",
    "Orange": "오렌지",
    "Brown": "브라운",
    "Metal": "메탈",
    "Turquoise": "터키석",
    "Mole": "Mole",                                  # H&M 고유 색상명 — 옮기면 의미가 흐려져 원문 유지
    "Lilac Purple": "라일락 퍼플",
    "Unknown": "기타",
    "undefined": "undefined",                        # 소스에 문자열로 들어온 값 — 원문 유지
    "Yellowish Green": "Yellowish Green",            # H&M 고유 색상명 — 원문 유지
    "Bluish Green": "Bluish Green",                  # H&M 고유 색상명 — 원문 유지
}

# 상품 종류(`product_type_name`)는 **H&M 영어 원문을 그대로** 쓴다. 131종류라 한국어로 옮기면
# 대부분 추측이 되고(니트·저지·탑의 경계가 우리 데이터엔 없다), 상품 이름 자체가 영어(H&M 원문)라
# 종류만 한국어로 두면 오히려 화면에서 따로 논다. 색상(`perceived_colour_master_name`)만 20종류라
# 검증 가능한 범위에서 번역한다.

CODE_MAX = 40  # categories.code varchar(40)


def slug(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


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
    prod["subcategory_code"] = prod["category_code"] + "." + prod["garment_group_name"].map(slug)

    # 이름을 못 정한 중분류가 있으면 조용히 NULL을 넣지 않고 멈춘다(화면에 코드가 노출되는 걸 막는다).
    unknown = set(prod["garment_group_name"]) - set(SUBCATEGORY_NAMES)
    if unknown:
        raise SystemExit(f"중분류 이름 매핑이 없다: {sorted(unknown)}")
    too_long = prod["subcategory_code"].str.len() > CODE_MAX
    if too_long.any():
        raise SystemExit(f"중분류 코드가 {CODE_MAX}자를 넘는다: {sorted(set(prod.loc[too_long, 'subcategory_code']))[:3]}")

    # 색상·상품 종류 — 패싯 필터(V57). 색상 이름도 못 정하면 조용히 NULL을 넣지 않고 멈춘다.
    prod["colour_code"] = prod["perceived_colour_master_name"].map(slug)
    prod["colour_name"] = prod["perceived_colour_master_name"].map(COLOUR_NAMES)
    unknown_colour = set(prod["perceived_colour_master_name"]) - set(COLOUR_NAMES)
    if unknown_colour:
        raise SystemExit(f"색상 이름 매핑이 없다: {sorted(unknown_colour)}")
    # 종류는 원문 유지(위 주석 참고).
    prod["product_type"] = prod["product_type_name"]

    prod["brand"] = BRAND
    prod["image_url"] = None
    prod["featured"] = prod["article_id"].isin(featured_ids).astype(int)
    prod["created_at"] = prod["article_id"].map(first_seen).fillna(cutoff)
    prod["description"] = prod["detail_desc"]

    # product_id는 BIGINT라 10자리 문자열을 정수로. 앞의 0은 저장되지 않는다(이미지 경로에서 %010d로 복원).
    prod["product_id"] = prod["article_id"].astype(int)

    # --- 대분류 (parent_code 없음) ---
    parents = (
        prod.groupby("category_code", observed=True)
        .size()
        .reset_index(name="n")
        .sort_values("n", ascending=False)
        .reset_index(drop=True)
    )
    parents["name"] = parents["category_code"].map({v[0]: v[1] for v in CATEGORY_NAMES.values()})
    parents["sort_order"] = parents.index + 1
    parents["parent_code"] = None
    parents["source_name"] = parents["category_code"].map({v[0]: k for k, v in CATEGORY_NAMES.items()})
    parents = parents.rename(columns={"category_code": "code"})

    # --- 중분류 (조합 노드) — 정렬은 부모 안에서 상품 수 내림차순 ---
    children = (
        prod.groupby(["category_code", "subcategory_code"], observed=True)
        .size()
        .reset_index(name="n")
    )
    children["sort_order"] = (
        children.groupby("category_code", observed=True)["n"]
        .rank(ascending=False, method="first")
        .astype(int)
    )
    children = children.sort_values(["sort_order", "subcategory_code"]).reset_index(drop=True)
    children["parent_code"] = children["category_code"]
    # 이름의 출처(H&M 원문)를 남긴다 — 한국어 이름을 우리가 정했으므로 근거가 추적돼야 한다.
    source_of = prod.drop_duplicates("subcategory_code").set_index("subcategory_code")["garment_group_name"]
    children["source_name"] = children["subcategory_code"].map(source_of)
    children["name"] = children["source_name"].map(SUBCATEGORY_NAMES)
    children = children.rename(columns={"subcategory_code": "code"})[
        ["code", "name", "sort_order", "parent_code", "source_name", "n"]
    ]

    cats = pd.concat([parents, children], ignore_index=True)

    keep = ["product_id", "prod_name", "price", "category_code", "subcategory_code",
            "colour_code", "colour_name", "product_type",
            "description", "image_url", "brand", "featured", "created_at"]
    return prod[keep], cats


def emit_sql(prod: pd.DataFrame, cats: pd.DataFrame, path: Path) -> None:
    lines = [
        "-- H&M 카탈로그 승격 (생성물 — 손으로 고치지 않는다)",
        f"-- 상품 {len(prod):,}개 · 대분류 {int(cats['parent_code'].isna().sum())}개"
        f" · 중분류 {int(cats['parent_code'].notna().sum())}개",
        "-- Flyway 마이그레이션이 아니다: 스키마가 아니라 데이터다(docs/04-storage.md §2).",
        "-- 멱등: 재실행하면 같은 product_id를 갱신한다.",
        "",
        "SET NAMES utf8mb4;",
        "",
    ]
    for _, c in cats.iterrows():
        lines.append(
            "INSERT INTO categories (code, name, description, sort_order, parent_code, source_name) VALUES "
            f"({sql_str(c['code'])}, {sql_str(c['name'])}, NULL, {int(c['sort_order'])}, "
            f"{sql_str(c['parent_code'])}, {sql_str(c['source_name'])}) "
            "ON DUPLICATE KEY UPDATE name=VALUES(name), sort_order=VALUES(sort_order), "
            "parent_code=VALUES(parent_code), source_name=VALUES(source_name);"
        )
    lines.append("")

    cols = ("(product_id, name, price, category_code, subcategory_code, colour_code, colour_name,"
            " product_type, description, image_url, brand, featured, created_at)")
    rows = []
    for _, r in prod.iterrows():
        rows.append(
            f"({int(r['product_id'])}, {sql_str(r['prod_name'])}, {int(r['price'])}, "
            f"{sql_str(r['category_code'])}, {sql_str(r['subcategory_code'])}, "
            f"{sql_str(r['colour_code'])}, {sql_str(r['colour_name'])}, {sql_str(r['product_type'])}, "
            f"{sql_str(r['description'])}, {sql_str(r['image_url'])}, {sql_str(r['brand'])}, {int(r['featured'])}, "
            f"{sql_str(pd.Timestamp(r['created_at']).strftime('%Y-%m-%d %H:%M:%S'))})"
        )
    for i in range(0, len(rows), BATCH):
        chunk = rows[i:i + BATCH]
        lines.append(
            f"INSERT INTO products {cols} VALUES\n  " + ",\n  ".join(chunk) +
            "\nON DUPLICATE KEY UPDATE name=VALUES(name), price=VALUES(price), "
            "category_code=VALUES(category_code), subcategory_code=VALUES(subcategory_code), "
            "colour_code=VALUES(colour_code), colour_name=VALUES(colour_name), product_type=VALUES(product_type), "
            "description=VALUES(description), "
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
    parents = cats[cats["parent_code"].isna()]
    children = cats[cats["parent_code"].notna()]
    print(f"상품 {len(prod):,}개 · 대분류 {len(parents)}개 · 중분류 {len(children)}개")
    print(parents[["code", "name", "sort_order", "n"]].to_string(index=False))
    print(f"중분류 {len(children)}개 (부모별 상위 3개)")
    for code, grp in children.groupby("parent_code", sort=False):
        head = ", ".join(f"{r['name']}({r['n']:,})" for _, r in grp.head(3).iterrows())
        print(f"  {code:12s} {head}")
    # 부모 카운트 == 자식 합. 이게 깨지면 트리 집계가 틀린 것이다.
    rolled = children.groupby("parent_code", observed=True)["n"].sum()
    for _, p in parents.iterrows():
        assert int(p["n"]) == int(rolled.get(p["code"], 0)), f"부모 {p['code']} 카운트가 자식 합과 다르다"
    print("부모 카운트 == 자식 합 확인")
    print("\n가격 분포(원):", prod['price'].describe()[['min', '25%', '50%', '75%', 'max']].astype(int).to_dict())
    print(f"featured {int(prod['featured'].sum())}개 · 거래 없는 상품 가격은 카테고리 중앙값으로 채움")

    # 패싯(V57) — 색상·종류가 몇 값으로, 얼마나 퍼져 있는지. 필터가 실제로 쓸모 있는지 여기서 보인다.
    col_counts = prod.groupby("colour_name", observed=True).size().sort_values(ascending=False)
    print(f"\n색상 {col_counts.size}종 — 상위 5개:",
          ", ".join(f"{name}({int(n):,})" for name, n in col_counts.head(5).items()))
    type_counts = prod.groupby("product_type", observed=True).size().sort_values(ascending=False)
    print(f"상품 종류 {type_counts.size}종 — 상위 5개:",
          ", ".join(f"{name}({int(n):,})" for name, n in type_counts.head(5).items()))

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
