#!/usr/bin/env python3
"""R1·R2 — ALS 학습과 오프라인 평가(MAP@12).

기준선을 **같은 하네스로** 재기 위해 채점·스플릿은 `features_hm.py`의 함수를 그대로 import 해 쓴다
(`load_transactions`·`split`·`map_at_k`). 새로 만들면 기준선과 비교가 성립하지 않는다.

실행:
    personalization/.venv/bin/python personalization/pipeline/train_als.py            # 스윕 전체
    personalization/.venv/bin/python personalization/pipeline/train_als.py f64-a40    # 하나만
    ALS_EXCLUDE_SEEN=1 personalization/.venv/bin/python personalization/pipeline/train_als.py f64-a10  # 대조

산출물:
    personalization/data/hm/model/{user_factors.npy,item_factors.npy,id_map.json}  (가장 높은 구성)
    personalization/data/hm/als-report.json

**alpha 를 스윕하는 이유.** implicit 의 ALS 는 신뢰도를 `C = 1 + alpha * r` 로 둔다. 라이브러리
기본값은 `alpha=1.0` 이라 한 번 산 상품의 신뢰도가 2, 안 산 상품이 1 이다 — 사실상 가중이 없다.
원 논문(Hu·Koren·Volinsky 2008)이 쓰는 값은 40 이다. **이 축을 안 열어 보고 "ALS 가 졌다" 고 적으면
안 해 본 것을 안 된다고 적는 것이다.** 그래서 alpha 를 바꿔 가며 같은 채점으로 잰다.

**이미 산 상품 제외 여부**: 제외하지 않는다(`EXCLUDE_SEEN=False`). 채점 대상은 "다음 주 구매"이고
그 안에 재구매가 섞여 있는데, 후보에서 이미 산 것을 빼면 그 몫을 구조적으로 버린다(패션 재구매가
강해 `repeat_last`가 인기의 2.7배다).
"""
from __future__ import annotations

import os

# implicit/OpenBLAS 가 macOS 에서 스레드 경고를 내는 것을 막는다. **라이브러리 import 전에** 설정해야 한다.
os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")

import json
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd
import scipy.sparse as sp
from implicit.als import AlternatingLeastSquares

# 스크립트 옆의 하네스를 import 한다 — 실행 위치와 무관하게.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from features_hm import load_transactions, map_at_k, split  # noqa: E402

DATA = Path("personalization/data")
HOLDOUT_DAYS = 7
K = 12
# 이미 산 상품 제외 여부 — 위 docstring 참고. 대조를 재현 가능하게 두려고 환경변수로 연다.
EXCLUDE_SEEN = os.environ.get("ALS_EXCLUDE_SEEN", "0") == "1"
SCORE_CHUNK = 1024     # 점수 행렬 청크(사용자). 전체를 한 번에 잡으면 수 GB 가 된다.
SEED = 42

# 이름 규칙: f<factors>-a<alpha>. 첫 구성이 R1 의 원래 값이고, 나머지는 alpha 축을 연 것이다.
CONFIGS = [
    {"name": "f64-a1",    "factors": 64,  "regularization": 0.05, "iterations": 15, "alpha": 1.0},
    {"name": "f64-a10",   "factors": 64,  "regularization": 0.05, "iterations": 15, "alpha": 10.0},
    {"name": "f64-a40",   "factors": 64,  "regularization": 0.05, "iterations": 15, "alpha": 40.0},
    {"name": "f128-a40",  "factors": 128, "regularization": 0.05, "iterations": 15, "alpha": 40.0},
]

_START = time.time()


def log(msg: str) -> None:
    """단계마다 한 줄 — 중간에 죽으면 어디서 죽었는지 알 수 있어야 한다."""
    print(f"[als +{time.time() - _START:6.1f}s] {msg}", flush=True)


def top_k_for(U, V, mat, users, k, exclude_seen):
    """사용자별 상위 k개 상품 인덱스와, 그 안에 '이미 산 상품'이 얼마나 섞였는지.

    점수 행렬은 청크로 잘라 만든다 — 전체를 한 번에 잡으면 수 GB 다.
    섞인 비율을 같이 재는 이유: 점수가 재구매에 기대고 있는지를 숫자로 봐야 하기 때문이다.
    """
    tops, seen_hits, seen_total = [], 0, 0
    Vt = np.ascontiguousarray(V.T)   # (factors, items)
    for start in range(0, len(users), SCORE_CHUNK):
        chunk = users[start:start + SCORE_CHUNK]
        scores = U[chunk] @ Vt       # (b, n_items)
        sub = mat[chunk].tocsr()
        if exclude_seen:
            rows, cols = sub.nonzero()
            scores[rows, cols] = -np.inf
        part = np.argpartition(-scores, k - 1, axis=1)[:, :k]
        order = np.argsort(-np.take_along_axis(scores, part, axis=1), axis=1)
        top = np.take_along_axis(part, order, axis=1)
        tops.append(top)
        rows_idx = np.repeat(np.arange(len(chunk)), k)
        seen_hits += int(np.count_nonzero(np.asarray(sub[rows_idx, top.ravel()]).ravel()))
        seen_total += top.size
    top_all = np.concatenate(tops, axis=0) if tops else np.empty((0, k), dtype=np.int64)
    return top_all, (seen_hits / seen_total if seen_total else 0.0)


def run_config(cfg, mat, tx, holdout, scored, u_index, i_index):
    """구성 하나를 학습하고 같은 하네스로 채점한다. (리포트, U, V) 를 돌려준다."""
    log(f"[{cfg['name']}] 학습 시작 — factors={cfg['factors']} reg={cfg['regularization']} "
        f"iters={cfg['iterations']} alpha={cfg['alpha']}")
    t0 = time.time()
    model = AlternatingLeastSquares(
        factors=cfg["factors"], regularization=cfg["regularization"],
        alpha=cfg["alpha"], iterations=cfg["iterations"], random_state=SEED,
    )
    model.fit(mat, show_progress=False)
    train_seconds = time.time() - t0
    U = np.asarray(model.user_factors, dtype=np.float32)
    V = np.asarray(model.item_factors, dtype=np.float32)
    log(f"[{cfg['name']}] 학습 완료 — {train_seconds:.1f}s · factors {U.shape}/{V.shape}")

    t1 = time.time()
    top_all, seen_ratio = top_k_for(U, V, mat, scored, K, EXCLUDE_SEEN)
    pred = pd.DataFrame({
        "customer_id": np.repeat(u_index.to_numpy(dtype=object)[scored], K),
        "article_id": i_index.to_numpy(dtype=object)[top_all.ravel()],
        "rank": np.tile(np.arange(1, K + 1), len(scored)),
    })
    # 채점기는 holdout 과 같은 category dtype 으로 merge 한다 — 기준선과 같은 경로를 타게 맞춘다.
    pred["customer_id"] = pd.Categorical(pred["customer_id"], categories=tx["customer_id"].cat.categories)
    pred["article_id"] = pd.Categorical(pred["article_id"], categories=tx["article_id"].cat.categories)
    score, n_eval = map_at_k(pred, holdout, K)
    log(f"[{cfg['name']}] MAP@{K} = {score:.6f} (평가 고객 {n_eval:,}) · "
        f"상위 {K}개 중 이미 산 상품 {seen_ratio:.1%} · 추천 생성 {time.time() - t1:.1f}s")

    return {
        **cfg,
        "map_at_k": round(float(score), 6),
        "evaluated_customers": int(n_eval),
        "train_seconds": round(train_seconds, 1),
        "already_purchased_in_top_k": round(seen_ratio, 4),
    }, U, V


def save_model(data_dir, best, best_uv, u_index, i_index):
    """가장 높은 구성의 팩터와 id 맵을 남긴다. 행 번호가 곧 인덱스다."""
    model_dir = data_dir / "hm" / "model"
    model_dir.mkdir(parents=True, exist_ok=True)
    np.save(model_dir / "user_factors.npy", best_uv[0])
    np.save(model_dir / "item_factors.npy", best_uv[1])
    (model_dir / "id_map.json").write_text(
        json.dumps({
            "config": best["name"],
            "customers": u_index.astype(str).tolist(),
            "items": i_index.astype(str).tolist(),
            "user_factors_shape": list(best_uv[0].shape),
            "item_factors_shape": list(best_uv[1].shape),
            "note": "행 번호 = 인덱스. article_id 는 문자열 유지(원본이 문자열이고 앞자리 0 이 날아간다).",
        }, ensure_ascii=False),
        encoding="utf-8",
    )


def main(argv) -> int:
    only = argv[1] if len(argv) > 1 else None
    configs = [c for c in CONFIGS if only is None or c["name"] == only]
    if not configs:
        print(f"그런 구성이 없다: {only} (있는 것: {[c['name'] for c in CONFIGS]})", file=sys.stderr)
        return 2

    log("거래 로드")
    tx = load_transactions(DATA)
    log(f"거래 {len(tx):,}행 · 기간 {tx['t_dat'].min().date()} ~ {tx['t_dat'].max().date()}")

    train, holdout, cutoff, last = split(tx, HOLDOUT_DAYS)
    log(f"split(holdout_days={HOLDOUT_DAYS}) → cutoff {cutoff.date()} · "
        f"train {len(train):,}행 · holdout {len(holdout):,}행")

    # 인덱스 ↔ id. category 순서(정렬됨)를 그대로 쓴다 — 행렬 인덱스가 여기에 대응한다.
    u_index = tx["customer_id"].cat.categories   # index → customer_id
    i_index = tx["article_id"].cat.categories    # index → article_id (문자열 유지: 앞자리 0)
    n_users, n_items = len(u_index), len(i_index)
    log(f"상품 {n_items:,} · 고객 {n_users:,}")

    log("상호작용 행렬 구성(고객 × 상품, 값 = 구매 횟수)")
    train_u = train["customer_id"].cat.codes.to_numpy()
    train_i = train["article_id"].cat.codes.to_numpy()
    mat = sp.coo_matrix(
        (np.ones(len(train), dtype=np.float32), (train_u, train_i)),
        shape=(n_users, n_items),
    ).tocsr()   # 중복 (u,i) 합산 → 구매 횟수
    log(f"행렬 nnz {mat.nnz:,} (train 행 {len(train):,} — 중복이 합쳐졌다)")

    # 홀드아웃 고객 중 train 이력이 있는 고객만 점수를 낼 수 있다(나머지는 콜드스타트).
    holdout_codes = np.unique(holdout["customer_id"].cat.codes.to_numpy())
    scored = np.intersect1d(holdout_codes, np.unique(train_u))
    cold = len(holdout_codes) - len(scored)
    log(f"홀드아웃 고객 {len(holdout_codes):,} · 점수 가능 {len(scored):,} · 콜드스타트 {cold:,}")

    baseline = json.loads((DATA / "hm" / "baseline-report.json").read_text(encoding="utf-8"))
    rl = baseline["baselines"]["repeat_last"]["map_at_k"]

    runs, best, best_uv = [], None, None
    for cfg in configs:
        rep, U, V = run_config(cfg, mat, tx, holdout, scored, u_index, i_index)
        if rep["evaluated_customers"] != baseline["baselines"]["repeat_last"]["evaluated_customers"]:
            log(f"경고: [{cfg['name']}] 평가 고객 수가 기준선과 다르다 — 비교가 성립하지 않는다")
        runs.append(rep)
        if best is None or rep["map_at_k"] > best["map_at_k"]:
            best, best_uv = rep, (U, V)

    log(f"가장 높은 구성 {best['name']} MAP@{K} {best['map_at_k']:.6f} — "
        f"repeat_last {rl:.6f} ({'넘음' if best['map_at_k'] > rl else '못 넘음'})")

    if EXCLUDE_SEEN:
        # 대조 실행이다. 본 실행의 팩터·리포트를 덮지 않는다.
        log("대조 실행(ALS_EXCLUDE_SEEN=1) — 팩터를 저장하지 않는다")
    else:
        log("산출물 저장 — 가장 높은 구성의 팩터와 id 맵")
        save_model(DATA, best, best_uv, u_index, i_index)

    report_path = DATA / "hm" / ("als-report-exclude-seen.json" if EXCLUDE_SEEN else "als-report.json")
    report = {
        "period": {"min": str(tx["t_dat"].min().date()), "max": str(last.date())},
        "cutoff": str(cutoff.date()),
        "holdout_days": HOLDOUT_DAYS,
        "k": K,
        "train_rows": int(len(train)),
        "holdout_rows": int(len(holdout)),
        "matrix_nnz": int(mat.nnz),
        "runs": runs,
        "best": best["name"],
        "map_at_k": best["map_at_k"],
        "evaluated_customers": best["evaluated_customers"],
        "baselines": baseline["baselines"],
        "beats_repeat_last": bool(best["map_at_k"] > rl),
        "coverage": {
            "holdout_customers": int(len(holdout_codes)),
            "scored_customers": int(len(scored)),
            "cold_start_customers": int(cold),
        },
        "fixed": {
            "confidence": "구매 횟수 (C = 1 + alpha * r)",
            "exclude_already_purchased": EXCLUDE_SEEN,
            "random_state": SEED,
        },
        "note": "train 구간만 학습에 쓴다. 채점은 features_hm.map_at_k(같은 cutoff·k·평가 집합). "
                "alpha 는 implicit 의 신뢰도 가중이고 라이브러리 기본값 1.0 은 사실상 가중이 없는 값이라 "
                "축을 열어 함께 쟀다. 이미 산 상품을 후보에서 제외하지 않았다 — 목표는 '다음 주 구매'이고 "
                "그 안에 재구매가 섞여 있어(repeat_last 0.023354) 제외하면 그 몫을 구조적으로 버린다.",
    }
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    log(f"완료 — {report_path.name} 작성 · 총 {time.time() - _START:.1f}s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
