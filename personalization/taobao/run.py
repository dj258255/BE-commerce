"""T1 CLI — 단계별로 돌린다.

    python -m taobao.run ranker  --split valid --leaves 63
    python -m taobao.run seq     --split valid --config d64_l2 [--no-unclicked]
    python -m taobao.run compare --a 예측A.parquet --b 예측B.parquet

`ranker` 와 `seq` 는 각각 `taobao.ranker` · `taobao.seqmodel` 을 부른다. 예측은
행마다 `(user_id, 행 번호, 점수, 라벨)` 로 저장되므로 `compare` 는 파일만으로
같은 고객 짝 부트스트랩을 돌린다.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from . import config, metrics


def _read_predictions(path: str | Path) -> pd.DataFrame:
    frame = pd.read_parquet(path)
    missing = {"user_id", "row", "ts", "score", "label"} - set(frame.columns)
    if missing:
        raise ValueError(f"{path} 에 {sorted(missing)} 열이 없습니다")
    return frame


def bundle_ids(user: np.ndarray, ts: np.ndarray) -> np.ndarray:
    """(고객, 시각) 이 같은 행에 같은 묶음 번호를 준다."""
    user = np.asarray(user, dtype=np.int64)
    ts = np.asarray(ts, dtype=np.int64)
    order = np.lexsort((ts, user))
    ordered_user = user[order]
    ordered_ts = ts[order]
    changes = np.ones(len(order), dtype=bool)
    changes[1:] = (ordered_user[1:] != ordered_user[:-1]) | (ordered_ts[1:] != ordered_ts[:-1])
    codes = np.empty(len(order), dtype=np.int64)
    codes[order] = np.cumsum(changes) - 1
    return codes


def compare(a_path: str | Path, b_path: str | Path, *, bootstrap: int = 2000,
            seed: int = config.SEED, out: str | Path | None = None) -> dict[str, Any]:
    """두 예측을 행 번호로 맞추고 (B − A) 묶음 GAUC 차이의 95% 구간을 낸다.

    주 지표는 묶음 GAUC 다. 고객 GAUC 짝 구간도 보조로 함께 낸다. 부트스트랩은
    고객을 복원 추출하고 뽑힌 고객의 묶음을 모두 넣어 양쪽에 같은 추출로 계산한다.
    """
    a = _read_predictions(a_path).sort_values("row", kind="stable").reset_index(drop=True)
    b = _read_predictions(b_path).sort_values("row", kind="stable").reset_index(drop=True)
    if len(a) != len(b):
        raise ValueError(f"행 수가 다릅니다: A {len(a)}, B {len(b)}")
    for column in ("row", "user_id", "ts", "label"):
        if not np.array_equal(a[column].to_numpy(), b[column].to_numpy()):
            raise ValueError(f"두 예측의 {column} 이(가) 다릅니다")

    user = a["user_id"].to_numpy(np.int64)
    label = a["label"].to_numpy(np.int64)
    group = bundle_ids(user, a["ts"].to_numpy(np.int64))

    bundle_users, value_a, value_b, weights = metrics.bundle_user_totals(
        user, group, label, a["score"].to_numpy(), b["score"].to_numpy())
    bundle_interval = metrics.paired_bootstrap(bundle_users, value_a, bundle_users, value_b, weights,
                                               bootstrap=bootstrap, seed=seed)

    grouped_a = metrics.grouped_auc(user, label, a["score"].to_numpy())
    grouped_b = metrics.grouped_auc(user, label, b["score"].to_numpy())
    user_interval = metrics.paired_bootstrap(
        grouped_a["users"], grouped_a["aucs"], grouped_b["users"], grouped_b["aucs"],
        grouped_a["weights"], bootstrap=bootstrap, seed=seed)

    with np.errstate(invalid="ignore"):
        overall_a = metrics.auc(label, a["score"].to_numpy())
        overall_b = metrics.auc(label, b["score"].to_numpy())
    report: dict[str, Any] = {
        "a": str(a_path),
        "b": str(b_path),
        "rows": int(len(a)),
        "bundles": int(group.max()) + 1 if len(group) else 0,
        "bundle_gauc": {"a": metrics.bundled_auc(user, group, label, a["score"].to_numpy())["gauc"],
                        "b": metrics.bundled_auc(user, group, label, b["score"].to_numpy())["gauc"],
                        "difference": bundle_interval},
        "user_gauc": {"a": grouped_a["gauc"], "b": grouped_b["gauc"], "difference": user_interval},
        "auc": {"a": overall_a, "b": overall_b},
    }
    if out is not None:
        destination = Path(out)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=float), encoding="utf-8")
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    subparsers = parser.add_subparsers(dest="command", required=True)

    from . import ranker as _ranker
    from . import seqmodel as _seqmodel

    ranker_parser = subparsers.add_parser("ranker", help="R: 특징 + LightGBM")
    ranker_parser.add_argument("--split", choices=("valid", "test"), required=True)
    ranker_parser.add_argument("--leaves", type=int, choices=config.NUM_LEAVES, required=True)
    ranker_parser.add_argument("--users", type=int)
    ranker_parser.add_argument("--threads", type=int)
    ranker_parser.add_argument("--no-cache", action="store_true")
    ranker_parser.add_argument("--out")

    seq_parser = subparsers.add_parser("seq", help="G: 노출 순차 모델")
    seq_parser.add_argument("--split", choices=("valid", "test"), required=True)
    seq_parser.add_argument("--config", choices=tuple(config.SEQ_CONFIGS), default=config.SEQ_DEFAULT)
    seq_parser.add_argument("--no-unclicked", action="store_true")
    seq_parser.add_argument("--users", type=int)
    seq_parser.add_argument("--epochs", type=int, default=3)
    seq_parser.add_argument("--batch-size", type=int, default=256)
    seq_parser.add_argument("--device", default="auto")
    seq_parser.add_argument("--no-cache", action="store_true")
    seq_parser.add_argument("--from-ckpt")
    seq_parser.add_argument("--out")

    compare_parser = subparsers.add_parser("compare", help="두 예측의 짝 부트스트랩")
    compare_parser.add_argument("--a", required=True)
    compare_parser.add_argument("--b", required=True)
    compare_parser.add_argument("--bootstrap", type=int, default=2000)
    compare_parser.add_argument("--seed", type=int, default=config.SEED)
    compare_parser.add_argument("--out", required=True)

    args = parser.parse_args(argv)
    if args.command == "ranker":
        return _ranker.main(["--split", args.split, "--leaves", str(args.leaves),
                             *(["--users", str(args.users)] if args.users else []),
                             *(["--threads", str(args.threads)] if args.threads else []),
                             *(["--no-cache"] if args.no_cache else []),
                             *(["--out", args.out] if args.out else [])])
    if args.command == "seq":
        return _seqmodel.main(["--split", args.split, "--config", args.config,
                               *(["--no-unclicked"] if args.no_unclicked else []),
                               *(["--users", str(args.users)] if args.users else []),
                               "--epochs", str(args.epochs), "--batch-size", str(args.batch_size),
                               "--device", args.device,
                               *(["--no-cache"] if args.no_cache else []),
                               *(["--from-ckpt", args.from_ckpt] if args.from_ckpt else []),
                               *(["--out", args.out] if args.out else [])])
    report = compare(args.a, args.b, bootstrap=args.bootstrap, seed=args.seed, out=args.out)
    print(json.dumps(report, ensure_ascii=False, indent=2, default=float))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
