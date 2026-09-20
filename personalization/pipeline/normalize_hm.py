#!/usr/bin/env python3
"""H&M 원본을 정규화하고 품질 리포트를 만든다.

정규화 규칙은 `docs/00-data.md`의 함정 목록에서 온다.
  - `article_id`·`customer_id`는 **10자리 zero-padded 문자열**로 유지(숫자로 읽으면 앞자리가 날아간다)
  - `-1`·`Unknown` 같은 **센티널을 결측으로** 바꾼다
  - `price`는 통화가 아니라 **정규화된 상대가**다 — 금액으로 쓰지 않는다고 리포트에 명시

사용:
    python3 personalization/pipeline/normalize_hm.py [--data-dir DIR]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import pandas as pd

SENTINELS = (-1,)


def read_raw(raw: Path, name: str) -> pd.DataFrame:
    files = sorted(raw.glob(f"{name}__*.parquet"))
    if not files:
        raise SystemExit(f"원본이 없습니다: {raw}/{name}__*.parquet — fetch_hm.py 를 먼저 돌리세요")
    return pd.concat([pd.read_parquet(f) for f in files], ignore_index=True)


def pad_article(s: pd.Series) -> pd.Series:
    """article_id → 10자리 zero-pad 문자열. 원본 parquet이 int64라 앞의 0이 날아가 있다."""
    return s.astype("string").str.zfill(10)


def pad_product_code(s: pd.Series) -> pd.Series:
    """product_code → 7자리 zero-pad. 여기도 원본에서 앞의 0이 날아가 있다."""
    return s.astype("string").str.zfill(7)


def normalize(raw: Path, out: Path) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    report: dict = {}

    # ---------- articles ----------
    a = read_raw(raw, "articles")
    a["article_id"] = pad_article(a["article_id"])
    a["product_code"] = pad_product_code(a["product_code"])
    # 센티널 → 결측: product_type_no 등이 -1이면 'Unknown'
    for col in ["product_type_no", "graphical_appearance_no", "colour_group_code",
                "perceived_colour_value_id", "perceived_colour_master_id", "department_no",
                "index_group_no", "section_no", "garment_group_no"]:
        if col in a.columns:
            a.loc[a[col].isin(SENTINELS), col] = pd.NA
    a["detail_desc"] = a["detail_desc"].replace({"": pd.NA})
    a.to_parquet(out / "articles.parquet", index=False)

    report["articles"] = {
        "rows": int(len(a)),
        "columns": list(a.columns),
        "distinct_product_code": int(a["product_code"].nunique()),
        "variants_per_product_mean": round(len(a) / max(a["product_code"].nunique(), 1), 2),
        "detail_desc_missing_pct": round(float(a["detail_desc"].isna().mean() * 100), 2),
        "index_group_name_top": a["index_group_name"].value_counts().head(5).to_dict(),
        "section_name_cardinality": int(a["section_name"].nunique()),
        "sentinel_columns": ["product_type_no 등 번호 컬럼의 -1 → 결측 처리"],
    }

    # ---------- customers ----------
    c = read_raw(raw, "customers")
    # customer_id는 64자 hex 문자열이다 — 자릿수 복원 대상이 아니다.
    c["customer_id"] = c["customer_id"].astype("string")
    c["age"] = pd.to_numeric(c["age"], errors="coerce")
    c.to_parquet(out / "customers.parquet", index=False)

    report["customers"] = {
        "rows": int(len(c)),
        "age_missing_pct": round(float(c["age"].isna().mean() * 100), 2),
        "age_median": float(c["age"].median()),
        "club_member_status": c["club_member_status"].value_counts(dropna=False).head(5).to_dict(),
        "fashion_news_frequency": c["fashion_news_frequency"].value_counts(dropna=False).head(5).to_dict(),
        "postal_code_cardinality": int(c["postal_code"].nunique()),
    }

    # ---------- transactions ----------
    files = sorted(raw.glob("transactions__*.parquet"))
    if not files:
        raise SystemExit("transactions 원본이 없습니다")
    parts = []
    for f in files:
        t = pd.read_parquet(f, columns=["t_dat", "customer_id", "article_id", "price", "sales_channel_id"])
        t["customer_id"] = t["customer_id"].astype("string")
        t["article_id"] = pad_article(t["article_id"])
        t["t_dat"] = pd.to_datetime(t["t_dat"])
        t["price"] = t["price"].astype("float32")
        t["sales_channel_id"] = t["sales_channel_id"].astype("int8")
        # 메모리: id를 범주형으로 (31.8M행을 감당하기 위해)
        t["customer_id"] = t["customer_id"].astype("category")
        t["article_id"] = t["article_id"].astype("category")
        parts.append(t)
    tx = pd.concat(parts, ignore_index=True)
    del parts

    # 재구매율: 같은 고객이 같은 상품을 두 번 이상 산 비중
    pair_counts = tx.groupby(["customer_id", "article_id"], observed=True).size()
    repeat_tx = int((pair_counts[pair_counts > 1]).sum())
    total_tx = int(len(tx))

    tx.to_parquet(out / "transactions.parquet", index=False)

    report["transactions"] = {
        "rows": total_tx,
        "date_min": str(tx["t_dat"].min().date()),
        "date_max": str(tx["t_dat"].max().date()),
        "distinct_customers": int(tx["customer_id"].nunique()),
        "distinct_articles": int(tx["article_id"].nunique()),
        "repeat_purchase_tx_pct": round(repeat_tx / total_tx * 100, 2),
        "pairs_total": int(len(pair_counts)),
        "price_min": float(tx["price"].min()),
        "price_median": float(tx["price"].median()),
        "price_max": float(tx["price"].max()),
        "price_note": "통화 금액이 아니라 정규화된 상대가다 — 원장·정산의 KRW 금액으로 쓸 수 없다",
        "sales_channel": tx["sales_channel_id"].value_counts().to_dict(),
    }

    # 커버리지: 상품/고객의 콜드스타트 규모
    # (concat 후 범주형이 유지된다는 보장이 없어 unique 값을 직접 쓴다)
    tx_articles = set(tx["article_id"].astype("string").unique())
    tx_customers = set(tx["customer_id"].astype("string").unique())
    report["coverage"] = {
        "articles_without_transactions": int(len(set(a["article_id"]) - tx_articles)),
        "customers_without_transactions": int(len(set(c["customer_id"]) - tx_customers)),
        "note": "거래가 없는 상품(콜드스타트)은 텍스트·이미지 신호로만 추천할 수 있다",
    }
    return report


def write_report(report: dict, path: Path) -> None:
    r = report
    lines = [
        "# H&M 데이터 품질 리포트",
        "",
        "> `normalize_hm.py`가 원본에서 직접 계산한 값이다. 추정이 아니라 실측이다.",
        "",
        "## articles",
        "",
        f"- 행 **{r['articles']['rows']:,}** · product_code **{r['articles']['distinct_product_code']:,}**개"
        f" (variant 평균 {r['articles']['variants_per_product_mean']}개)",
        f"- `detail_desc` 결측 **{r['articles']['detail_desc_missing_pct']}%**",
        f"- `section_name` 종류 {r['articles']['section_name_cardinality']:,}",
        f"- index_group 상위: {r['articles']['index_group_name_top']}",
        "",
        "## customers",
        "",
        f"- 행 **{r['customers']['rows']:,}**",
        f"- `age` 결측 **{r['customers']['age_missing_pct']}%** (중앙값 {r['customers']['age_median']})",
        f"- club_member_status: {r['customers']['club_member_status']}",
        f"- postal_code 종류 {r['customers']['postal_code_cardinality']:,} (마스킹됨)",
        "",
        "## transactions",
        "",
        f"- 행 **{r['transactions']['rows']:,}** · 기간 **{r['transactions']['date_min']} ~ {r['transactions']['date_max']}**",
        f"- 고객 {r['transactions']['distinct_customers']:,}명 · 상품 {r['transactions']['distinct_articles']:,}개",
        f"- **재구매 비중 {r['transactions']['repeat_purchase_tx_pct']}%**"
        f" — 같은 고객이 같은 상품을 두 번 이상 산 거래의 비율",
        f"- price: min {r['transactions']['price_min']}, median {r['transactions']['price_median']},"
        f" max {r['transactions']['price_max']}",
        f"  - {r['transactions']['price_note']}",
        f"- sales_channel: {r['transactions']['sales_channel']}",
        "",
        "## 커버리지 (콜드스타트 규모)",
        "",
        f"- 거래가 없는 상품 **{r['coverage']['articles_without_transactions']:,}개**",
        f"- 거래가 없는 고객 {r['coverage']['customers_without_transactions']:,}명",
        f"- {r['coverage']['note']}",
        "",
        "## 이 리포트가 바꾸는 것",
        "",
        "- 재구매 비중이 크면 **repeat-aware 지표**가 필요하다(같은 상품 반복이 성과를 부풀린다)",
        "- 거래 없는 상품이 있으면 콜드스타트 경로가 실재한다 — 텍스트 신호(Amazon_Fashion)의 쓰임",
        "- price를 금액으로 쓰지 않는다는 결정이 여기서 확인된다",
    ]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default="personalization/data")
    args = ap.parse_args()

    data = Path(args.data_dir)
    raw, out = data / "hm" / "raw", data / "hm" / "normalized"

    report = normalize(raw, out)
    (data / "hm" / "quality-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    md = Path("personalization/docs/runs")
    md.mkdir(parents=True, exist_ok=True)
    write_report(report, md / "hm-quality-report.md")

    print(json.dumps(report, ensure_ascii=False, indent=2)[:1200])
    print(f"\n리포트: {md / 'hm-quality-report.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
