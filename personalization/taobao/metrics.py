"""T1 의 지표 — 묶음 GAUC · 고객 GAUC · 전체 AUC · logloss · 묶음 nDCG · 짝 부트스트랩.

정의는 `docs/genpage-v2/T1-TAOBAO.md` 의 "지표" 절을 따른다.

- 주 지표 묶음 GAUC: 같은 초에 함께 보인 묶음(고객, 시각)마다 클릭 AUC 를 구해
  묶음 크기로 가중 평균한다. 클릭과 비클릭이 모두 있는 묶음만 센다.
- 보조: 고객 GAUC(고객마다 그날 노출 전체의 클릭 AUC, 노출 수 가중), 전체 AUC,
  logloss, 묶음 nDCG(같은 초 묶음 중 2개 이상 · 클릭 1개 이상), 이력 길이별 묶음
  GAUC(분할 채점 날 전 노출 50개 미만 · 이상).
- 선: 광고의 전날까지 평활 클릭률 하나로 매긴 묶음 GAUC.

부트스트랩은 고객을 복원 추출하고, 뽑힌 고객의 묶음을 **모두** 넣어
(Σ auc × 크기) / (Σ 크기) 를 양쪽에 같은 추출로 계산한다. 차이의 2.5 · 97.5
백분위를 구한다. 같은 추출을 짝에 함께 적용한다.
"""
from __future__ import annotations

import numpy as np

HISTORY_SPLIT = 50


def auc(labels: np.ndarray, scores: np.ndarray) -> float:
    """동점을 평균 순위로 처리한 AUC. 한쪽 클래스만 있으면 nan."""
    labels = np.asarray(labels)
    scores = np.asarray(scores, dtype=np.float64)
    order = np.argsort(scores, kind="mergesort")
    ordered_scores = scores[order]
    ordered_labels = labels[order]
    ranks = np.empty(len(scores), dtype=np.float64)
    start = 0
    while start < len(scores):
        stop = start
        while stop + 1 < len(scores) and ordered_scores[stop + 1] == ordered_scores[start]:
            stop += 1
        ranks[start:stop + 1] = (start + stop) / 2.0 + 1.0
        start = stop + 1
    n_pos = int(ordered_labels.sum())
    n_neg = len(labels) - n_pos
    if n_pos == 0 or n_neg == 0:
        return float("nan")
    return float((ranks[ordered_labels == 1].sum() - n_pos * (n_pos + 1) / 2.0) / (n_pos * n_neg))


def _group_bounds(groups: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    order = np.argsort(groups, kind="stable")
    ordered = groups[order]
    cuts = np.flatnonzero(ordered[1:] != ordered[:-1]) + 1
    starts = np.concatenate([[0], cuts])
    ends = np.concatenate([cuts, [len(ordered)]])
    return order, starts, ends


def grouped_auc(user: np.ndarray, label: np.ndarray, score: np.ndarray) -> dict[str, np.ndarray | float]:
    """고객별 클릭 AUC 와 노출 수 가중 평균(GAUC).

    클릭과 비클릭이 모두 있는 고객만 돌려준다. `users` · `aucs` · `weights` 는
    부트스트랩에 그대로 쓴다.
    """
    user = np.asarray(user)
    label = np.asarray(label)
    score = np.asarray(score, dtype=np.float64)
    order, starts, ends = _group_bounds(user)
    ordered_user = user[order]
    ordered_label = label[order]
    ordered_score = score[order]
    users: list[int] = []
    aucs: list[float] = []
    weights: list[int] = []
    for start, end in zip(starts, ends):
        part = ordered_label[start:end]
        if len(part) < 2 or part.min() == part.max():
            continue
        users.append(int(ordered_user[start]))
        aucs.append(auc(part, ordered_score[start:end]))
        weights.append(end - start)
    if not users:
        return {"gauc": float("nan"), "users": np.zeros(0, dtype=np.int64),
                "aucs": np.zeros(0), "weights": np.zeros(0, dtype=np.int64)}
    users_array = np.asarray(users, dtype=np.int64)
    aucs_array = np.asarray(aucs, dtype=np.float64)
    weights_array = np.asarray(weights, dtype=np.int64)
    return {
        "gauc": float((aucs_array * weights_array).sum() / weights_array.sum()),
        "users": users_array,
        "aucs": aucs_array,
        "weights": weights_array,
    }


def bundled_auc(user: np.ndarray, group: np.ndarray, label: np.ndarray,
                score: np.ndarray) -> dict[str, np.ndarray | float]:
    """주 지표 — 같은 초 묶음마다 클릭 AUC, 묶음 크기 가중 평균(묶음 GAUC).

    `group` 은 같은 초 묶음 번호다(고객, 시각). 클릭과 비클릭이 모두 있는 묶음만
    센다. `users` · `aucs` · `sizes` 는 부트스트랩에 쓴다.
    """
    user = np.asarray(user)
    group = np.asarray(group)
    label = np.asarray(label)
    score = np.asarray(score, dtype=np.float64)
    order, starts, ends = _group_bounds(group)
    ordered_user = user[order]
    ordered_label = label[order]
    ordered_score = score[order]
    users: list[int] = []
    aucs: list[float] = []
    sizes: list[int] = []
    for start, end in zip(starts, ends):
        part = ordered_label[start:end]
        if len(part) < 2 or part.min() == part.max():
            continue
        users.append(int(ordered_user[start]))
        aucs.append(auc(part, ordered_score[start:end]))
        sizes.append(end - start)
    if not users:
        return {"gauc": float("nan"), "users": np.zeros(0, dtype=np.int64),
                "aucs": np.zeros(0), "sizes": np.zeros(0, dtype=np.int64)}
    users_array = np.asarray(users, dtype=np.int64)
    aucs_array = np.asarray(aucs, dtype=np.float64)
    sizes_array = np.asarray(sizes, dtype=np.int64)
    return {
        "gauc": float((aucs_array * sizes_array).sum() / sizes_array.sum()),
        "users": users_array,
        "aucs": aucs_array,
        "sizes": sizes_array,
    }


def bundle_user_totals(user: np.ndarray, group: np.ndarray, label: np.ndarray,
                       score_a: np.ndarray, score_b: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """고객마다 자기 묶음들의 (Σ auc × 크기, Σ 크기) 를 모아 부트스트랩 입력으로 준다.

    고객별 가중 AUC = (Σ auc × 크기) / (Σ 크기) 이고 가중치 = Σ 크기다. 두 모델은
    같은 묶음 집합을 써야 한다(라벨 · 묶음이 같으므로 그렇다).
    """
    a = bundled_auc(user, group, label, score_a)
    b = bundled_auc(user, group, label, score_b)
    if a["users"].shape != b["users"].shape or not np.array_equal(a["users"], b["users"]):
        raise ValueError("두 모델의 묶음 집합이 다릅니다")
    users = a["users"]
    sizes = a["sizes"].astype(np.float64)
    unique, inverse = np.unique(users, return_inverse=True)
    denominator = np.bincount(inverse, weights=sizes)
    numerator_a = np.bincount(inverse, weights=a["aucs"] * sizes)
    numerator_b = np.bincount(inverse, weights=b["aucs"] * sizes)
    keep = denominator > 0
    return unique[keep], numerator_a[keep] / denominator[keep], numerator_b[keep] / denominator[keep], denominator[keep]


def logloss(labels: np.ndarray, probabilities: np.ndarray, eps: float = 1e-15) -> float:
    probabilities = np.clip(np.asarray(probabilities, dtype=np.float64), eps, 1 - eps)
    labels = np.asarray(labels, dtype=np.float64)
    return float(-(labels * np.log(probabilities) + (1 - labels) * np.log(1 - probabilities)).mean())


def bundled_ndcg(group: np.ndarray, label: np.ndarray, score: np.ndarray) -> dict[str, float | int]:
    """같은 초 묶음에서 클릭 광고의 순위를 본 nDCG.

    묶음은 `group` 이 같은 행들이다. 2개 이상이고 클릭이 1개 이상인 묶음만 센다.
    """
    group = np.asarray(group)
    label = np.asarray(label, dtype=np.float64)
    score = np.asarray(score, dtype=np.float64)
    order, starts, ends = _group_bounds(group)
    ordered_label = label[order]
    ordered_score = score[order]
    values: list[float] = []
    for start, end in zip(starts, ends):
        rel = ordered_label[start:end]
        if len(rel) < 2 or rel.sum() < 1:
            continue
        ranked = rel[np.argsort(-ordered_score[start:end], kind="mergesort")]
        discounts = 1.0 / np.log2(np.arange(2, len(rel) + 2))
        dcg = float((ranked * discounts).sum())
        ideal = float((np.sort(rel)[::-1] * discounts).sum())
        values.append(dcg / ideal if ideal > 0 else 0.0)
    return {"ndcg": float(np.mean(values)) if values else float("nan"), "bundles": len(values)}


def history_bundle_gauc(user: np.ndarray, group: np.ndarray, label: np.ndarray, score: np.ndarray,
                        history_length: np.ndarray) -> dict[str, float]:
    """분할 채점 날 전 노출 수 50개 미만 · 이상으로 나눈 묶음 GAUC."""
    short = np.asarray(history_length) < HISTORY_SPLIT
    return {
        "bundle_gauc_short": bundled_auc(user[short], group[short], label[short], score[short])["gauc"],
        "bundle_gauc_long": bundled_auc(user[~short], group[~short], label[~short], score[~short])["gauc"],
    }


def evaluate(user: np.ndarray, label: np.ndarray, score: np.ndarray, *,
             probability: np.ndarray | None = None, group: np.ndarray | None = None,
             history_length: np.ndarray | None = None) -> dict[str, float | int]:
    """한 모델의 지표 묶음. 주 지표는 `bundle_gauc`(묶음이 주어졌을 때)다.

    `probability` 가 없으면 logloss 는 건너뛴다. `group` 이 없으면 묶음 지표는
    건너뛰고 고객 GAUC 만 낸다.
    """
    customer = grouped_auc(user, label, score)
    summary: dict[str, float | int] = {"rows": int(len(label)), "clicks": int(np.asarray(label).sum())}
    if group is not None:
        bundle = bundled_auc(user, group, label, score)
        summary["bundle_gauc"] = bundle["gauc"]
        summary["bundle_gauc_bundles"] = int(len(bundle["sizes"]))
    summary["user_gauc"] = customer["gauc"]
    summary["user_gauc_users"] = int(len(customer["users"]))
    summary["auc"] = auc(label, score)
    if probability is not None:
        summary["logloss"] = logloss(label, probability)
    if group is not None:
        summary.update(bundled_ndcg(group, label, score))
        if history_length is not None:
            summary.update(history_bundle_gauc(user, group, label, score, history_length))
    return summary


def verdict(low: float, high: float) -> str:
    """(B − A) 구간이 0 을 어디에 두는지로 판정 문자열을 고른다."""
    if low > 0:
        return "이겼다"
    if high < 0:
        return "졌다"
    return "비겼다"


def paired_bootstrap(user_a: np.ndarray, auc_a: np.ndarray, user_b: np.ndarray, auc_b: np.ndarray,
                     weights: np.ndarray, *, bootstrap: int = 2000, seed: int = 7) -> dict[str, float | str]:
    """같은 고객에서 (B − A) 노출 수 가중 GAUC 차이의 95% 구간.

    두 모델이 같은 고객 집합(같은 순서)을 써야 한다. 아니면 오류를 낸다.
    """
    user_a = np.asarray(user_a)
    user_b = np.asarray(user_b)
    if not np.array_equal(user_a, user_b):
        raise ValueError("부트스트랩은 두 모델이 같은 고객 집합을 써야 합니다")
    weights = np.asarray(weights, dtype=np.float64)
    total = weights.sum()
    if total <= 0:
        raise ValueError("부트스트랩 가중치가 0 입니다")
    normalized = weights / total
    difference = np.asarray(auc_b, dtype=np.float64) - np.asarray(auc_a, dtype=np.float64)
    point = float((normalized * difference).sum())
    rng = np.random.default_rng(seed)
    count = len(difference)
    draws = np.empty(bootstrap, dtype=np.float64)
    for index in range(bootstrap):
        picked = rng.integers(0, count, size=count)
        picked_weight = normalized[picked]
        draws[index] = float((picked_weight * difference[picked]).sum() / picked_weight.sum())
    low, high = np.percentile(draws, [2.5, 97.5])
    return {
        "difference": point,
        "low": float(low),
        "high": float(high),
        "verdict": verdict(float(low), float(high)),
        "bootstrap": int(bootstrap),
        "seed": int(seed),
        "users": int(count),
    }
