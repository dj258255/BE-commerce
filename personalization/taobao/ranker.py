"""R: 특징 + LightGBM 이진 분류.

학습은 학습 분할로만 한다. 검증 분할은 조기 종료에만 쓰고, 요청한 분할(valid ·
test)에서 지표를 낸다. 예측은 행마다 `(user_id, 행 번호, 점수, 라벨)` 로 저장해
짝 비교가 파일만으로 돌게 한다.

LightGBM 과 torch 는 같은 프로세스에서 OpenMP 가 충돌한다(GenPage v2 의
requirements-genpage2.txt 주석). 그래서 이 모듈은 torch 를 부르지 않는다.
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from . import config, data as data_module, features, metrics

PARAMS: dict[str, Any] = {
    "objective": "binary",
    "metric": "auc",
    "learning_rate": 0.05,
    "n_estimators": 2000,
    "feature_fraction": 0.8,
    "bagging_fraction": 0.8,
    "bagging_freq": 1,
    "seed": config.SEED,
    "verbose": -1,
}
EARLY_STOPPING = 50


def _bundle_ids(split_data: data_module.SplitData) -> np.ndarray:
    """같은 초 묶음(고객, 시각)마다 붙는 묶음 번호."""
    exposure = split_data.row_exposure()
    user = split_data.row_user.astype(np.int64)
    stamp = split_data.ts[exposure]
    order = np.lexsort((stamp, user))
    ordered_user = user[order]
    ordered_stamp = stamp[order]
    changes = np.ones(len(order), dtype=bool)
    changes[1:] = (ordered_user[1:] != ordered_user[:-1]) | (ordered_stamp[1:] != ordered_stamp[:-1])
    codes = np.empty(len(order), dtype=np.int64)
    codes[order] = np.cumsum(changes) - 1
    return codes


def _baseline_index(names: list[str]) -> int:
    return names.index("g_ad_ctr")


def train_and_evaluate(split: str, leaves: int, *, users: int | None = None,
                       use_cache: bool = True, num_threads: int | None = None) -> dict[str, Any]:
    """학습 분할로 LightGBM 을 학습하고 `split` 에서 지표와 예측을 낸다."""
    import lightgbm as lgb

    started = time.perf_counter()
    train_data = data_module.load_split("train", users=users, use_cache=use_cache)
    eval_data = data_module.load_split(split, users=users, use_cache=use_cache)
    valid_data = eval_data if split == "valid" else data_module.load_split("valid", users=users, use_cache=use_cache)

    x_train, categorical, names = features.build_design(train_data)
    y_train = train_data.row_label.astype(np.int32)
    x_valid, _, _ = features.build_design(valid_data)
    y_valid = valid_data.row_label.astype(np.int32)
    x_eval, _, _ = features.build_design(eval_data)
    y_eval = eval_data.row_label.astype(np.int32)

    params = dict(PARAMS)
    params["num_leaves"] = int(leaves)
    if num_threads is not None:
        params["num_threads"] = int(num_threads)

    train_set = lgb.Dataset(x_train, label=y_train, categorical_feature=categorical, free_raw_data=True)
    valid_set = lgb.Dataset(x_valid, label=y_valid, categorical_feature=categorical, reference=train_set,
                            free_raw_data=True)
    booster = lgb.train(
        params, train_set, num_boost_round=params["n_estimators"], valid_sets=[valid_set],
        callbacks=[lgb.early_stopping(EARLY_STOPPING, verbose=False)],
    )

    mask = eval_data.eval_mask()
    row_index = np.flatnonzero(mask)
    probability = booster.predict(x_eval[mask], num_iteration=booster.best_iteration)
    user = eval_data.row_user[mask]
    label = y_eval[mask]
    group = _bundle_ids(eval_data)[mask]

    report: dict[str, Any] = {
        "model": "ranker",
        "split": split,
        "leaves": int(leaves),
        "params": params,
        "best_iteration": int(booster.best_iteration),
        "train_rows": int(len(y_train)),
        "eval_rows": int(mask.sum()),
        "eval_rows_before_window": int(len(mask)),
        "elapsed_seconds": time.perf_counter() - started,
        "metrics": metrics.evaluate(user, label, probability, probability=probability, group=group,
                                    history_length=eval_data.pre_day_history()[user]),
    }
    baseline = x_eval[mask, _baseline_index(names)]
    report["baseline"] = metrics.evaluate(user, label, baseline, group=group,
                                          history_length=eval_data.pre_day_history()[user])
    report["feature_importance"] = {
        name: float(value) for name, value in
        zip(names, booster.feature_importance(importance_type="gain"))
    }
    report["predictions"] = _save_predictions(split, "ranker", leaves, None,
                                              eval_data, row_index, user, label, probability)
    return report


def _save_predictions(split: str, model: str, leaves: int | None, tag: str | None,
                      eval_data: data_module.SplitData, row_index: np.ndarray,
                      user: np.ndarray, label: np.ndarray, score: np.ndarray) -> dict[str, Any]:
    name = f"{split}_{model}"
    if leaves is not None:
        name += f"_l{leaves}"
    if tag:
        name += f"_{tag}"
    destination = config.cache_dir() / "predictions" / f"{name}.parquet"
    destination.parent.mkdir(parents=True, exist_ok=True)
    frame = pd.DataFrame({
        "user_id": eval_data.user_ids[user].astype(np.int64),
        "row": row_index.astype(np.int64),
        "ts": eval_data.ts[eval_data.row_exposure()[row_index]].astype(np.int64),
        "score": np.asarray(score, dtype=np.float64),
        "label": np.asarray(label, dtype=np.int8),
    })
    frame.to_parquet(destination, index=False)
    return {"path": str(destination), "rows": int(len(frame))}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--split", choices=("valid", "test"), required=True)
    parser.add_argument("--leaves", type=int, choices=config.NUM_LEAVES, required=True)
    parser.add_argument("--users", type=int)
    parser.add_argument("--threads", type=int)
    parser.add_argument("--no-cache", action="store_true")
    parser.add_argument("--out")
    args = parser.parse_args(argv)
    report = train_and_evaluate(args.split, args.leaves, users=args.users,
                                use_cache=not args.no_cache, num_threads=args.threads)
    destination = Path(args.out) if args.out else (
        config.out_dir() / f"ranker_{args.split}_l{args.leaves}.json")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=float), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2, default=float))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
