#!/usr/bin/env python3
"""Amazon_Fashion 을 정규화하고 품질 리포트를 만든다.

`docs/00-data.md`의 함정을 **실측으로 확인**하는 것이 목적이다.
  - 메타는 `parent_asin`으로 찾아야 한다(`asin`으로 찾으면 얼마나 실패하는가)
  - 일부 아이템은 메타가 없다
  - `price`는 수집 시점 가격(USD) — H&M의 정규화 상대가와 의미가 다르다
  - 리뷰 텍스트는 다국어·비ASCII가 섞인다

사용:
    python3 personalization/pipeline/normalize_amazon.py [--data-dir DIR]
"""
from __future__ import annotations

import argparse
import gzip
import json
from pathlib import Path

import pandas as pd


def iter_jsonl_gz(path: Path):
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                yield json.loads(line)


def load_reviews(path: Path) -> pd.DataFrame:
    rows = []
    for r in iter_jsonl_gz(path):
        text = r.get("text") or ""
        rows.append({
            "rating": r.get("rating"),
            "asin": r.get("asin"),
            "parent_asin": r.get("parent_asin"),
            "user_id": r.get("user_id"),
            "timestamp": r.get("timestamp"),
            "verified_purchase": bool(r.get("verified_purchase")),
            "helpful_vote": r.get("helpful_vote") or 0,
            "has_images": bool(r.get("images")),
            "text_len": len(text),
            "text_ascii": text.isascii(),
        })
    return pd.DataFrame(rows)


def load_meta(path: Path) -> pd.DataFrame:
    rows = []
    for m in iter_jsonl_gz(path):
        rows.append({
            "parent_asin": m.get("parent_asin"),
            "price": m.get("price"),
            "store": m.get("store"),
            "has_categories": bool(m.get("categories")),
            "n_categories": len(m.get("categories") or []),
            "n_details": len(m.get("details") or {}),
            "n_features": len(m.get("features") or []),
            "n_images": len(m.get("images") or []),
        })
    return pd.DataFrame(rows)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default="personalization/data")
    args = ap.parse_args()

    data = Path(args.data_dir)
    raw = data / "amazon" / "raw"
    out = data / "amazon" / "normalized"
    out.mkdir(parents=True, exist_ok=True)

    reviews = load_reviews(raw / "review.jsonl.gz")
    meta = load_meta(raw / "meta.jsonl.gz")
    reviews.to_parquet(out / "reviews.parquet", index=False)
    meta.to_parquet(out / "meta.parquet", index=False)

    # --- 함정 1: asin vs parent_asin ---
    meta_parent = set(meta["parent_asin"].dropna())
    meta_asin_used_as_parent = set(meta["parent_asin"].dropna())
    matched_by_parent = reviews["parent_asin"].isin(meta_parent).mean() * 100
    matched_by_asin = reviews["asin"].isin(meta_asin_used_as_parent).mean() * 100

    ts = pd.to_datetime(reviews["timestamp"], unit="ms", errors="coerce")

    report = {
        "reviews": {
            "rows": int(len(reviews)),
            "distinct_users": int(reviews["user_id"].nunique()),
            "distinct_asin": int(reviews["asin"].nunique()),
            "distinct_parent_asin": int(reviews["parent_asin"].nunique()),
            "date_min": str(ts.min()),
            "date_max": str(ts.max()),
            "rating_mean": round(float(reviews["rating"].mean()), 3),
            "verified_purchase_pct": round(float(reviews["verified_purchase"].mean() * 100), 2),
            "with_images_pct": round(float(reviews["has_images"].mean() * 100), 2),
            "text_len_median": int(reviews["text_len"].median()),
            "text_non_ascii_pct": round(float((~reviews["text_ascii"]).mean() * 100), 2),
            "helpful_vote_zero_pct": round(float((reviews["helpful_vote"] == 0).mean() * 100), 2),
        },
        "meta": {
            "rows": int(len(meta)),
            "distinct_parent_asin": int(meta["parent_asin"].nunique()),
            "price_present_pct": round(float(meta["price"].notna().mean() * 100), 2),
            "price_min": float(meta["price"].min()) if meta["price"].notna().any() else None,
            "price_max": float(meta["price"].max()) if meta["price"].notna().any() else None,
            "categories_present_pct": round(float(meta["has_categories"].mean() * 100), 2),
            "n_categories_mean": round(float(meta["n_categories"].mean()), 2),
            "details_keys_mean": round(float(meta["n_details"].mean()), 2),
            "images_mean": round(float(meta["n_images"].mean()), 2),
        },
        "join_check": {
            "review_row_matched_by_parent_asin_pct": round(float(matched_by_parent), 2),
            "review_row_matched_by_asin_pct": round(float(matched_by_asin), 2),
            "note": "메타는 parent_asin으로 찾아야 한다. asin으로 찾으면 이만큼 실패한다",
        },
    }

    (data / "amazon" / "quality-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    md = Path("personalization/docs/runs/amazon-quality-report.md")
    r = report
    lines = [
        "# Amazon_Fashion 데이터 품질 리포트",
        "",
        "> `normalize_amazon.py`가 원본에서 직접 계산한 값이다. 추정이 아니라 실측이다.",
        "",
        "## reviews",
        "",
        f"- 행 **{r['reviews']['rows']:,}** · 기간 **{r['reviews']['date_min'][:10]} ~ {r['reviews']['date_max'][:10]}**",
        f"- 사용자 {r['reviews']['distinct_users']:,}명 · asin {r['reviews']['distinct_asin']:,} ·"
        f" parent_asin {r['reviews']['distinct_parent_asin']:,}",
        f"- 평균 평점 {r['reviews']['rating_mean']} · 구매확인 {r['reviews']['verified_purchase_pct']}%",
        f"- 텍스트 길이 중앙값 {r['reviews']['text_len_median']}자 · **비ASCII 리뷰 {r['reviews']['text_non_ascii_pct']}%**(다국어 혼재)",
        f"- helpful_vote 0인 리뷰 {r['reviews']['helpful_vote_zero_pct']}% (초기 노출 편향 주의)",
        "",
        "## meta",
        "",
        f"- 행 **{r['meta']['rows']:,}** · parent_asin {r['meta']['distinct_parent_asin']:,}",
        f"- **price 있는 비율 {r['meta']['price_present_pct']}%** (수집 시점 USD: {r['meta']['price_min']} ~ {r['meta']['price_max']})",
        f"- categories 있는 비율 {r['meta']['categories_present_pct']}% (평균 {r['meta']['n_categories_mean']}개)",
        f"- details 키 평균 {r['meta']['details_keys_mean']}개 · 이미지 평균 {r['meta']['images_mean']}장",
        "",
        "## 함정 검증 — asin vs parent_asin",
        "",
        f"- **parent_asin으로 조인하면 리뷰의 {r['join_check']['review_row_matched_by_parent_asin_pct']}%가 메타를 찾는다**",
        f"- asin으로 조인하면 {r['join_check']['review_row_matched_by_asin_pct']}%만 찾는다",
        f"- {r['join_check']['note']}",
        "",
        "## 이 리포트가 바꾸는 것",
        "",
        "- 메타를 parent_asin으로 찾는다는 규칙이 실측으로 확인된다",
        "- price가 결측인 상품이 있어 가격 피처로 쓸 수 없다(게다가 수집 시점 값이라 시계열이 아니다)",
        "- 비ASCII 리뷰 비중이 있어 텍스트를 그대로 쓰면 언어 혼재가 들어온다",
    ]
    md.parent.mkdir(parents=True, exist_ok=True)
    md.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"\n리포트: {md}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
