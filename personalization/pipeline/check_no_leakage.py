#!/usr/bin/env python3
"""누출 회귀 테스트 — 미래 정보가 피처에 새면 실패한다.

무엇을 검사하나
--------------
1. **불변성**: 미래 행을 데이터에 **붙여도** cutoff 시점 피처가 달라지면 안 된다.
   달라진다면 그 피처가 미래를 쓰고 있다는 뜻이다.
2. **미래 전용 고객**: cutoff 이후에만 산 고객은 cutoff 피처에 나타나면 안 된다.
3. **인기 집계**: cutoff 이후 거래를 붙여도 popularity 순위가 달라지면 안 된다.
4. **탐지기 자가검증**: 일부러 누출을 심으면 이 검사가 **실패해야** 한다.
   (안 잡는 검사는 통과해도 의미가 없다 — 저장소의 `ApiSpecErrorCodesTest`와 같은 발상)

사용:
    python3 personalization/pipeline/check_no_leakage.py [--data-dir DIR]
종료 코드 0 = 통과, 1 = 누출 발견.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
from features_hm import baseline_popular, load_transactions, point_in_time_features  # noqa: E402

CUTOFF = pd.Timestamp("2020-09-01")


def build_sample(tx: pd.DataFrame, customers: int = 4000) -> pd.DataFrame:
    """검사는 빠르게 돌아야 한다 — 기준 시점 앞뒤가 모두 있는 고객만 표본으로 뜬다."""
    before = tx[tx["t_dat"] < CUTOFF]["customer_id"].astype("string").unique()
    after = tx[tx["t_dat"] >= CUTOFF]["customer_id"].astype("string").unique()
    both = set(before) & set(after)
    pick = list(both)[:customers]
    return tx[tx["customer_id"].astype("string").isin(pick)].copy()


def check_invariance(sample: pd.DataFrame) -> list[str]:
    """미래 행을 붙여도 cutoff 피처가 같아야 한다."""
    f_with_future = point_in_time_features(sample, CUTOFF)
    f_past_only = point_in_time_features(sample[sample["t_dat"] < CUTOFF], CUTOFF)
    if not f_with_future.equals(f_past_only):
        diff = f_with_future.merge(f_past_only, on="customer_id", suffixes=("_a", "_b"))
        bad = [c for c in diff.columns if c.endswith("_a")]
        return [f"미래 행을 붙이면 피처가 달라진다: {bad[:3]}"]
    return []


def check_future_only_customer(sample: pd.DataFrame) -> list[str]:
    """cutoff 이후에만 산 고객이 피처에 있으면 누출이다."""
    future_only = sample[sample["t_dat"] >= CUTOFF]["customer_id"].astype("string").unique()
    past = set(sample[sample["t_dat"] < CUTOFF]["customer_id"].astype("string").unique())
    future_only = [c for c in future_only if c not in past]
    if not future_only:
        return []
    feats = point_in_time_features(sample, CUTOFF)
    leaked = set(feats["customer_id"].astype("string")) & set(future_only)
    return [f"cutoff 이후에만 산 고객 {len(leaked)}명이 피처에 있다"] if leaked else []


def check_popularity(sample: pd.DataFrame) -> list[str]:
    """미래 거래를 붙여도 cutoff 시점 인기 순위가 같아야 한다."""
    past = sample[sample["t_dat"] < CUTOFF]
    a = baseline_popular(past, k=12, as_of=CUTOFF)
    b = baseline_popular(sample, k=12, as_of=CUTOFF)
    if a["article_id"].astype("string").tolist() != b["article_id"].astype("string").tolist():
        return ["미래 거래를 붙이면 인기 순위가 달라진다"]
    return []


def check_detector_detects(sample: pd.DataFrame) -> list[str]:
    """일부러 누출을 심으면 위 검사가 잡아야 한다. 못 잡으면 검사 자체가 무의미하다."""
    def leaky_features(tx: pd.DataFrame, cutoff: pd.Timestamp) -> pd.DataFrame:
        # 일부러 필터를 빼먹은 판본
        g = tx.groupby("customer_id", observed=True)
        return pd.DataFrame({"n_purchases": g.size()}).reset_index()

    f_leaky = leaky_features(sample, CUTOFF)
    f_past = leaky_features(sample[sample["t_dat"] < CUTOFF], CUTOFF)
    if f_leaky.equals(f_past):
        return ["누출을 심었는데 검사가 잡지 못한다 — 검사가 무의미하다"]
    return []


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default="personalization/data")
    args = ap.parse_args()

    tx = load_transactions(Path(args.data_dir))
    sample = build_sample(tx)
    print(f"표본 {len(sample):,}행 · 고객 {sample['customer_id'].nunique():,}명 · cutoff {CUTOFF.date()}")

    failures: list[str] = []
    for name, fn in [
        ("불변성(미래 행을 붙여도 피처 동일)", check_invariance),
        ("미래 전용 고객 미포함", check_future_only_customer),
        ("인기 집계 불변", check_popularity),
        ("탐지기 자가검증", check_detector_detects),
    ]:
        errs = fn(sample)
        failures += errs
        print(f"  {'실패' if errs else '통과'} — {name}")
        for e in errs:
            print(f"      {e}")

    if failures:
        print("\n누출 또는 검사 결함이 있습니다.", file=sys.stderr)
        return 1
    print("\n미래 정보가 피처에 새지 않는다.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
