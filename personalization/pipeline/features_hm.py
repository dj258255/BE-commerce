#!/usr/bin/env python3
"""H&M 시간 스플릿 + point-in-time 피처 + baseline + MAP@12 평가.

왜 시간 스플릿인가: 랜덤 스플릿은 미래를 새게 한다. 홀드아웃은 **train 마지막 주**를 떼어 쓰고,
피처는 **그 직전 시점까지의 정보로만** 계산한다(`docs/02-experiments.md` 공통 규칙 1·2).

baseline을 먼저 재는 이유: 인기만 추천해도 지표가 높게 나온다. baseline 없이 모델을 재면
"좋아졌다"를 말할 수 없다.

사용:
    python3 personalization/pipeline/features_hm.py [--data-dir DIR] [--holdout-days 7] [--k 12]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import pandas as pd

TOP_K = 12
HOLDOUT_DAYS = 7


def load_transactions(data: Path) -> pd.DataFrame:
    tx = pd.read_parquet(
        data / "hm" / "normalized" / "transactions.parquet",
        columns=["t_dat", "customer_id", "article_id", "price"],
    )
    tx["t_dat"] = pd.to_datetime(tx["t_dat"])
    tx["customer_id"] = tx["customer_id"].astype("category")
    tx["article_id"] = tx["article_id"].astype("category")
    return tx


def split(tx: pd.DataFrame, holdout_days: int):
    """홀드아웃 = 마지막 N일. **그 이전만 train**이라는 사실이 누출 방지의 근거다."""
    last = tx["t_dat"].max().normalize()
    cutoff = last - pd.Timedelta(days=holdout_days - 1)  # cutoff 이후가 홀드아웃
    train = tx[tx["t_dat"] < cutoff]
    holdout = tx[tx["t_dat"] >= cutoff]
    return train, holdout, cutoff, last


def point_in_time_features(tx: pd.DataFrame, cutoff: pd.Timestamp) -> pd.DataFrame:
    """cutoff 시점에 알 수 있는 것만 쓴다.

    **함수 안에서 미래를 잘라낸다** — 호출자가 필터를 빠뜨려도 누출이 생기지 않게.
    잘라내기가 제대로 됐는지는 `check_no_leakage.py`가 검사한다(미래 행을 붙여도 값이 같아야 한다).
    """
    train = tx[tx["t_dat"] < cutoff]
    g = train.groupby("customer_id", observed=True)
    feats = pd.DataFrame({
        "n_purchases": g.size(),
        "n_distinct_articles": g["article_id"].nunique(),
        "avg_price_relative": g["price"].mean().round(6),
        "last_purchase": g["t_dat"].max(),
    })
    feats["days_since_last"] = (cutoff - feats["last_purchase"]).dt.days
    feats = feats.drop(columns=["last_purchase"])
    feats["as_of"] = cutoff
    return feats.reset_index()


def baseline_popular(
    tx: pd.DataFrame, k: int, since: pd.Timestamp | None = None, as_of: pd.Timestamp | None = None
) -> pd.DataFrame:
    """인기 baseline. `as_of`를 주면 **그 시점 이전만** 센다(누출 방지)."""
    src = tx if as_of is None else tx[tx["t_dat"] < as_of]
    if since is not None:
        src = src[src["t_dat"] >= since]
    counts = src.groupby("article_id", observed=True).size().sort_values(ascending=False).head(k)
    return pd.DataFrame({
        "article_id": counts.index.astype("category"),
        "rank": range(1, len(counts) + 1),
    })


def baseline_repeat(train: pd.DataFrame, k: int) -> pd.DataFrame:
    """고객이 최근에 산 것을 다시 추천한다. 재구매가 25%라 이 baseline은 무시할 수 없다."""
    tr = train.sort_values("t_dat")
    last_pair = tr.drop_duplicates(["customer_id", "article_id"], keep="last")
    last_pair = last_pair.sort_values("t_dat", ascending=False)
    last_pair["rank"] = last_pair.groupby("customer_id", observed=True).cumcount() + 1
    return last_pair[last_pair["rank"] <= k][["customer_id", "article_id", "rank"]]


def map_at_k(pred: pd.DataFrame, holdout: pd.DataFrame, k: int) -> tuple[float, int]:
    """MAP@k. 분모는 min(|정답|, k) — Kaggle H&M 규약과 같다.

    **한 건도 못 맞춘 고객도 평균에 포함한다**(0으로). 빼면 지표가 부풀려진다 —
    실제로 처음 구현에서 이 실수를 했고, baseline마다 '평가 고객 수'가 달라 그게 드러났다.
    """
    pairs = holdout[["customer_id", "article_id"]].drop_duplicates()
    truth_size = pairs.groupby("customer_id", observed=True).size()

    if "customer_id" in pred.columns:
        hits = pairs.merge(pred, on=["customer_id", "article_id"], how="inner")
    else:
        hits = pairs.merge(pred, on="article_id", how="inner")

    if hits.empty:
        return 0.0, int(len(truth_size))

    hits = hits.sort_values(["customer_id", "rank"])
    hits["hit_no"] = hits.groupby("customer_id", observed=True).cumcount() + 1
    hits["prec"] = hits["hit_no"] / hits["rank"]
    sum_prec = hits.groupby("customer_id", observed=True)["prec"].sum()

    # 정답이 있는 **모든** 고객 기준(미적중 고객은 0).
    # `reindex(fill_value=)` 는 '새 라벨'만 채우고 기존 NaN은 못 채운다 — fillna가 따로 필요하다.
    # (이걸 빠뜨려 미적중 고객이 평균에서 빠진 채로 처음 계산됐고, 값이 안 변해 보고 알아챘다)
    ap = (sum_prec / truth_size.clip(upper=k)).reindex(truth_size.index).fillna(0.0)
    return float(ap.mean()), int(len(truth_size))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default="personalization/data")
    ap.add_argument("--holdout-days", type=int, default=HOLDOUT_DAYS)
    ap.add_argument("--k", type=int, default=TOP_K)
    args = ap.parse_args()

    data = Path(args.data_dir)
    tx = load_transactions(data)
    train, holdout, cutoff, last = split(tx, args.holdout_days)
    print(f"기간 {tx['t_dat'].min().date()} ~ {last.date()} · cutoff {cutoff.date()}")
    print(f"train {len(train):,} · holdout {len(holdout):,}")

    # point-in-time 피처 (cutoff 기준)
    feats = point_in_time_features(train, cutoff)
    out = data / "hm" / "features"
    out.mkdir(parents=True, exist_ok=True)
    feats.to_parquet(out / f"user_features_asof_{cutoff.date()}.parquet", index=False)
    print(f"피처 {len(feats):,}행 → {out.name}/user_features_asof_{cutoff.date()}.parquet")

    # baselines
    recent_since = cutoff - pd.Timedelta(days=7)
    b_recent = baseline_popular(train, args.k, since=recent_since)
    b_all = baseline_popular(train, args.k)
    b_repeat = baseline_repeat(train, args.k)
    print(f"popular_recent {len(b_recent)}개 · popular_all {len(b_all)}개 · repeat {len(b_repeat):,}행")

    results = {}
    for name, pred in [("popular_recent7d", b_recent), ("popular_all", b_all), ("repeat_last", b_repeat)]:
        score, n = map_at_k(pred, holdout, args.k)
        results[name] = {"map_at_k": round(score, 6), "evaluated_customers": n}
        print(f"MAP@{args.k} {name}: {score:.6f} ({n:,}명)")

    report = {
        "period": {"min": str(tx["t_dat"].min().date()), "max": str(last.date())},
        "cutoff": str(cutoff.date()),
        "holdout_days": args.holdout_days,
        "k": args.k,
        "train_rows": int(len(train)),
        "holdout_rows": int(len(holdout)),
        "customers_with_features": int(len(feats)),
        "baselines": results,
        "note": "피처는 cutoff 이전 데이터로만 계산한다. 누출은 check_no_leakage.py가 검사한다.",
    }
    (data / "hm" / "baseline-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    md = Path("personalization/docs/runs/hm-baseline-report.md")
    lines = [
        f"# H&M baseline 평가 — MAP@{args.k}",
        "",
        "> `features_hm.py`가 직접 계산한 값이다. 시간 스플릿(마지막 "
        f"{args.holdout_days}일 홀드아웃) + point-in-time 피처.",
        "",
        "## 설정",
        "",
        f"- 기간 {report['period']['min']} ~ {report['period']['max']} · **cutoff {report['cutoff']}**",
        f"- train {report['train_rows']:,}행 · holdout {report['holdout_rows']:,}행",
        f"- 피처를 만든 고객 {report['customers_with_features']:,}명",
        "",
        f"## MAP@{args.k}",
        "",
        "| baseline | MAP@" + str(args.k) + " | 평가 고객 |",
        "|---|---:|---:|",
    ]
    for name, v in results.items():
        lines.append(f"| {name} | {v['map_at_k']:.6f} | {v['evaluated_customers']:,} |")
    lines += [
        "",
        "## 읽는 법",
        "",
        "- **모델은 이 숫자들을 이겨야 한다.** 특히 `repeat_last`는 재구매가 25%라 강한 baseline이다.",
        "- `popular_recent7d`가 `popular_all`보다 높으면 **최신성이 지표에 기여**한다는 뜻이다(실험 E1과 연결).",
        "- 홀드아웃 고객만 평가한다 — 학습에 없던 고객은 콜드스타트 경로로 따로 다룬다.",
        "",
        "## 한계",
        "",
        "- 암묵적 양성만 있다. 네거티브를 정의하지 않았으므로 랭킹 학습은 아직 하지 않는다(베이스라인만).",
        "- 오프라인 지표는 게이트다. 최종 판정은 온라인 A/B이고, 실사용자가 없으면 그 경계를 남긴다.",
    ]
    md.parent.mkdir(parents=True, exist_ok=True)
    md.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\n리포트: {md}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
