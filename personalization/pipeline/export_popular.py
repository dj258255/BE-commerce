#!/usr/bin/env python3
"""M1 인기 통계를 **서빙용 표**로 내보낸다 — 홈의 "인기" 행이 진짜 인기가 되도록 (#198②).

왜 필요한가: 지금 `ItemPoolSource.popular()` 는 후보 집합을 **결정적 간격으로 퍼뜨려** 뽑는다.
그건 인기가 아니라 **편향 회피**다(id 순서가 카테고리 순서라 앞에서 자르면 한 대분류만 나온다 —
M7 실측). 이름만 "인기"였고 값은 인기가 아니었다.

여기서 만드는 것은 **실제 거래로 센 인기**다: H&M `transactions_train`(3,178만 행, 2018-09-20~2020-09-22)을
상품별로 세어 상위 K를 뽑는다. 두 창을 낸다:

- `recent_7d` — 데이터셋 **마지막 7일**의 구매 수 (M1 `popular_recent7d` 와 같은 정의)
- `all_time` — 전체 기간 구매 수 (M1 `popular_all`)

**"최근"은 벽시계가 아니라 데이터셋 기준이다.** 이 데이터는 2020-09-22 에 끝나므로 지금 시각 기준
"최근 7일"은 항상 비어 있다. 그래서 `computed_at` 에 **데이터 기준일**을 적는다 — 화면이 "요즘 인기"라고
말할 때 그 "요즘"이 언제인지 값이 스스로 밝히게 하려는 것이다.

사용:
    python3 personalization/pipeline/export_popular.py --emit-sql            # SQL 생성
    python3 personalization/pipeline/export_popular.py --emit-sql --load     # 생성 후 MySQL 적재
"""
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

import pandas as pd

TOP_K = 200
RECENT_DAYS = 7
WINDOW_RECENT = "recent_7d"
WINDOW_ALL = "all_time"
BATCH = 500


def load_transactions(data: Path) -> pd.DataFrame:
    tx = pd.read_parquet(
        data / "hm" / "normalized" / "transactions.parquet",
        columns=["t_dat", "article_id"],
    )
    tx["t_dat"] = pd.to_datetime(tx["t_dat"])
    # 문자열 → 범주형: 3,178만 행에서 groupby 메모리를 크게 줄인다.
    tx["article_id"] = tx["article_id"].astype("category")
    return tx


def top_articles(tx: pd.DataFrame, k: int, since: pd.Timestamp | None) -> pd.DataFrame:
    src = tx if since is None else tx[tx["t_dat"] >= since]
    counts = src.groupby("article_id", observed=True).size().sort_values(ascending=False).head(k)
    out = pd.DataFrame({"article_id": counts.index.astype(str), "purchase_count": counts.to_numpy()})
    out["rank"] = range(1, len(out) + 1)
    # `products.product_id` 는 `article_id` 의 정수형이다(승격 규칙: astype(int) — 0 패딩이 사라진다).
    out["product_id"] = out["article_id"].astype("int64")
    return out


def category_mix(ids: list[int], articles: pd.DataFrame) -> dict:
    """상위 K 가 **한 대분류에 몰리는가** — M7 이 id 순서로 겪은 그 문제를 값으로 확인한다.

    인기 통계가 몰려 있으면 홈의 다양성 규칙이 그 뒤에서 항목을 버린다(노출이 줄어든다).
    그 사실은 재봐야 알 수 있으므로 여기서 같이 낸다.
    """
    art = articles.copy()
    art["product_id"] = art["article_id"].astype("int64")
    sub = art[art["product_id"].isin(ids)]
    return sub["index_group_name"].value_counts().to_dict()


def emit_sql(rows_by_window: dict[str, pd.DataFrame], computed_at: str, out: Path) -> int:
    lines = [
        "-- M1 인기 통계 → 서빙용 표 (#198②). **손으로 고치지 말고 export_popular.py 로 다시 만든다.**",
        f"-- 데이터 기준일: {computed_at} (이 날짜가 \"최근\"의 기준이다 — 벽시계가 아니다)",
        "DELETE FROM product_popularity;",
    ]
    total = 0
    for window, df in rows_by_window.items():
        values = [
            f"({r.product_id}, '{window}', {r.rank}, {r.purchase_count}, '{computed_at}')"
            for r in df.itertuples()
        ]
        for i in range(0, len(values), BATCH):
            chunk = values[i:i + BATCH]
            # 컬럼명이 `window_kind`·`rank_no` 다 — MySQL 8 에서 `window`·`rank` 는 예약어다.
            lines.append("INSERT INTO product_popularity (product_id, window_kind, rank_no, purchase_count, computed_at) VALUES\n  "
                         + ",\n  ".join(chunk) + ";")
            total += len(chunk)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return total


def load_sql(path: Path, container: str, db: str, user: str, pw: str) -> None:
    with open(path, "rb") as f:
        subprocess.run(
            # `--default-character-set=utf8mb4` 가 없으면 mysql 클라이언트가 파일의 UTF-8 을
            # latin1 로 읽어 **한글이 이중 인코딩**된다(리뷰 적재에서 실제로 겪었다).
            ["docker", "exec", "-i", container, "mysql", "--default-character-set=utf8mb4",
             f"-u{user}", f"-p{pw}", db],
            stdin=f, check=True,
        )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default="personalization/data")
    ap.add_argument("--k", type=int, default=TOP_K)
    ap.add_argument("--recent-days", type=int, default=RECENT_DAYS)
    ap.add_argument("--out", default="personalization/data/hm/popularity.sql")
    ap.add_argument("--emit-sql", action="store_true")
    ap.add_argument("--load", action="store_true")
    ap.add_argument("--container", default="pay-mysql-1")
    ap.add_argument("--db", default="becommerce")
    ap.add_argument("--user", default="becommerce")
    ap.add_argument("--password", default="becommerce")
    args = ap.parse_args()

    data = Path(args.data_dir)
    tx = load_transactions(data)
    as_of = tx["t_dat"].max().normalize()
    recent_since = as_of - pd.Timedelta(days=args.recent_days - 1)
    print(f"거래 {len(tx):,}행 · 기간 {tx['t_dat'].min().date()} ~ {as_of.date()} · 상위 {args.k}개")

    rows = {
        WINDOW_RECENT: top_articles(tx, args.k, recent_since),
        WINDOW_ALL: top_articles(tx, args.k, None),
    }
    articles = pd.read_parquet(data / "hm" / "normalized" / "articles.parquet",
                              columns=["article_id", "index_group_name"])

    summary = {"computed_at": str(as_of.date()), "k": args.k, "windows": {}}
    for window, df in rows.items():
        mix = category_mix(list(df["product_id"].head(12)), articles)
        top12 = df.head(12)
        print(f"\n[{window}] 상위 12개 · 구매수 {top12['purchase_count'].iloc[0]:,}~{top12['purchase_count'].iloc[-1]:,}")
        print(f"  상위 12개의 대분류 분포: {mix} ({len(mix)}종)")
        summary["windows"][window] = {
            "top12_category_mix": mix,
            "top12_distinct_categories": len(mix),
            "top1_purchase_count": int(top12["purchase_count"].iloc[0]),
            "top12_purchase_count": int(top12["purchase_count"].iloc[-1]),
            "ids": [int(x) for x in top12["product_id"]],
        }

    if args.emit_sql or args.load:
        out = Path(args.out)
        written = emit_sql(rows, str(as_of.date()), out)
        print(f"\nSQL {out} · INSERT {written}행 (창 {len(rows)}개 × {args.k})")
        (out.parent / "popularity-summary.json").write_text(
            json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if args.load:
            load_sql(out, args.container, args.db, args.user, args.password)
            print("적재 완료")
    else:
        print("\n(--emit-sql 없이 실행 — SQL 을 만들지 않았다)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
