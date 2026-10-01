"""R 의 특징 — 문서의 "R: 특징 + LightGBM" 절을 그대로 만든다.

입력 규칙(두 모델 공통):
- 그 고객의 **시각이 t 보다 이른** 노출과 각각의 클릭 여부. 같은 초의 묶음
  동료는 보지 않는다(시각이 같은 것은 제외한다).
- 전체 통계(광고 · 카테고리 · 브랜드 · 캠페인별 노출 · 클릭 수)는 그 노출의
  **전날까지**만.
"""
from __future__ import annotations

import numpy as np

from . import config
from .data import SplitData

CATEGORICAL = (
    "cms_segid", "cms_group_id", "final_gender_code", "age_level", "pvalue_level",
    "shopping_level", "occupation", "new_user_class_level",
    "pid", "hour", "is_weekend", "cate_id", "brand", "price_bucket",
)

NUMERIC = (
    "cust_exp", "cust_click", "cust_ctr",
    "ad_exp", "ad_click", "cate_exp", "cate_click", "brand_exp", "brand_click",
    "campaign_exp", "campaign_click",
    "cate_last_exp_ago", "cate_last_click_ago", "brand_last_exp_ago", "brand_last_click_ago",
    "g_ad_exp", "g_ad_click", "g_ad_ctr",
    "g_cate_exp", "g_cate_click", "g_cate_ctr",
    "g_brand_exp", "g_brand_click", "g_brand_ctr",
    "g_campaign_exp", "g_campaign_click", "g_campaign_ctr",
)

FEATURE_NAMES = CATEGORICAL + NUMERIC
CATEGORICAL_INDEX = list(range(len(CATEGORICAL)))

# G 의 곁채널이 쓰는 수치 특징 — 고객 이력 15 + 전날까지 전체 통계 12 = 27.
# 변환은 이름 묶음으로 나눈다: 카운트는 log1p, 클릭률은 그대로, 지난 시간은
# log1p 하고 한 번도 없었으면(`*_ago` < 0) 표시 칸을 1 로 덧붙인다.
SIDE_COUNT = (
    "cust_exp", "cust_click", "ad_exp", "ad_click", "cate_exp", "cate_click",
    "brand_exp", "brand_click", "campaign_exp", "campaign_click",
    "g_ad_exp", "g_ad_click", "g_cate_exp", "g_cate_click",
    "g_brand_exp", "g_brand_click", "g_campaign_exp", "g_campaign_click",
)
SIDE_RATE = ("cust_ctr", "g_ad_ctr", "g_cate_ctr", "g_brand_ctr", "g_campaign_ctr")
SIDE_AGO = ("cate_last_exp_ago", "cate_last_click_ago", "brand_last_exp_ago", "brand_last_click_ago")
SIDE_NUMERIC = SIDE_COUNT + SIDE_RATE + SIDE_AGO


def side_design(data: SplitData) -> tuple[np.ndarray, list[str]]:
    """곁채널 입력 — 수치 27개에 변환을 적용한 행렬과 열 이름.

    `build_design` 이 만든 행렬에서 :data:`SIDE_NUMERIC` 이름으로 골라 쓴다. 이름이
    없으면 멈춘다. 카운트는 log1p, 클릭률은 그대로, 지난 시간은 log1p 하고 값이 없으면
    (한 번도 없었으면) 표시 칸 1 · 값 0 으로 둔다. 그래서 열은 27 + 표시 칸 4 = 31 개다.
    표준화는 하지 않는다(학습 분할 통계로 하는 것은 부르는 쪽 몫).
    """
    matrix, _, names = build_design(data)
    index = {name: position for position, name in enumerate(names)}
    missing = [name for name in SIDE_NUMERIC if name not in index]
    if missing:
        raise KeyError(f"곁채널 특징이 설계 행렬에 없습니다: {missing}")
    columns: list[np.ndarray] = []
    labels: list[str] = []
    for name in SIDE_NUMERIC:
        column = matrix[:, index[name]].astype(np.float32)
        if name in SIDE_AGO:
            present = column >= 0
            columns.append(np.where(present, np.log1p(np.maximum(column, 0.0)), 0.0).astype(np.float32))
            labels.append(name)
            columns.append((~present).astype(np.float32))
            labels.append(f"{name}_missing")
        elif name in SIDE_COUNT:
            columns.append(np.log1p(np.maximum(column, 0.0)).astype(np.float32))
            labels.append(name)
        else:
            columns.append(column)
            labels.append(name)
    return np.stack(columns, axis=1), labels


def _cumulative(counts: np.ndarray) -> np.ndarray:
    """(id, day) 격자를 (id, day+1) 누적으로. `cum[e, d]` = d 보다 이른 날의 합."""
    size, days = counts.shape
    result = np.zeros((size, days + 1), dtype=np.int64)
    result[:, 1:] = np.cumsum(counts, axis=1, dtype=np.int64)
    return result


def global_grids(data: SplitData) -> tuple[dict[str, tuple[np.ndarray, np.ndarray]], np.ndarray]:
    """(누적 노출, 누적 클릭) 격자와 전날까지의 전역 평균 클릭률.

    `grids[name][0][e, d]` 는 d 보다 이른 날의 그 개체 노출 수다. `mean[d]` 는
    d 보다 이른 날들의 전체 클릭률이다.
    """
    day = data.exposure_day()
    n_days = int(day.max()) + 1
    click = data.click.astype(np.int64)
    grids: dict[str, tuple[np.ndarray, np.ndarray]] = {}
    for name, column in (("ad", data.item), ("cate", data.cate), ("brand", data.brand),
                         ("campaign", data.campaign)):
        size = int(column.max()) + 1
        flat = column.astype(np.int64) * n_days + day
        exposures = np.bincount(flat, minlength=size * n_days).reshape(size, n_days)
        clicks = np.bincount(flat, weights=click, minlength=size * n_days).reshape(size, n_days).astype(np.int64)
        grids[name] = (_cumulative(exposures.astype(np.int64)), _cumulative(clicks))
    total_exp = _cumulative(np.bincount(day, minlength=n_days).reshape(1, -1).astype(np.int64))[0]
    total_clk = _cumulative(np.bincount(day, weights=click, minlength=n_days).astype(np.int64).reshape(1, -1))[0]
    mean = np.zeros(n_days + 1, dtype=np.float64)
    nonzero = total_exp > 0
    mean[nonzero] = total_clk[nonzero] / total_exp[nonzero]
    return grids, mean


def _history_features(data: SplitData) -> np.ndarray:
    """고객 이력(시각 < t) 특징. 채점 행 순서로 돌려준다."""
    result = np.zeros((data.n_rows, 15), dtype=np.float32)
    row_order = np.lexsort((data.row_pos, data.row_user))
    rows_user = data.row_user[row_order]
    rows_pos = data.row_pos[row_order]

    # 고객별 채점 행 구간.
    cuts = np.flatnonzero(np.diff(rows_user)) + 1
    starts = np.concatenate([[0], cuts])
    ends = np.concatenate([cuts, [len(rows_user)]])

    for start, end in zip(starts, ends):
        user = int(rows_user[start])
        left, right = int(data.offsets[user]), int(data.offsets[user + 1])
        times = data.ts[left:right]
        items = data.item[left:right]
        cates = data.cate[left:right]
        brands = data.brand[left:right]
        campaigns = data.campaign[left:right]
        clicks = data.click[left:right]
        positions = rows_pos[start:end]
        indices = row_order[start:end]

        exp_total = 0
        click_total = 0
        ad_exp: dict[int, int] = {}
        ad_click: dict[int, int] = {}
        cate_exp: dict[int, int] = {}
        cate_click: dict[int, int] = {}
        brand_exp: dict[int, int] = {}
        brand_click: dict[int, int] = {}
        camp_exp: dict[int, int] = {}
        camp_click: dict[int, int] = {}
        cate_last_exp: dict[int, int] = {}
        cate_last_click: dict[int, int] = {}
        brand_last_exp: dict[int, int] = {}
        brand_last_click: dict[int, int] = {}

        group_start = 0
        pointer = 0
        length = right - left
        while group_start < length:
            group_end = group_start
            while group_end + 1 < length and times[group_end + 1] == times[group_start]:
                group_end += 1
            moment = int(times[group_start])
            while pointer < len(positions) and int(positions[pointer]) <= group_end:
                row = int(indices[pointer])
                item = int(items[positions[pointer]])
                cate = int(cates[positions[pointer]])
                brand = int(brands[positions[pointer]])
                campaign = int(campaigns[positions[pointer]])
                result[row, 0] = exp_total
                result[row, 1] = click_total
                result[row, 2] = click_total / exp_total if exp_total else 0.0
                result[row, 3] = ad_exp.get(item, 0)
                result[row, 4] = ad_click.get(item, 0)
                result[row, 5] = cate_exp.get(cate, 0)
                result[row, 6] = cate_click.get(cate, 0)
                result[row, 7] = brand_exp.get(brand, 0)
                result[row, 8] = brand_click.get(brand, 0)
                result[row, 9] = camp_exp.get(campaign, 0)
                result[row, 10] = camp_click.get(campaign, 0)
                last = cate_last_exp.get(cate)
                result[row, 11] = moment - last if last is not None else -1.0
                last = cate_last_click.get(cate)
                result[row, 12] = moment - last if last is not None else -1.0
                last = brand_last_exp.get(brand)
                result[row, 13] = moment - last if last is not None else -1.0
                last = brand_last_click.get(brand)
                result[row, 14] = moment - last if last is not None else -1.0
                pointer += 1
            for index in range(group_start, group_end + 1):
                item = int(items[index])
                cate = int(cates[index])
                brand = int(brands[index])
                campaign = int(campaigns[index])
                clicked = int(clicks[index])
                exp_total += 1
                click_total += clicked
                ad_exp[item] = ad_exp.get(item, 0) + 1
                cate_exp[cate] = cate_exp.get(cate, 0) + 1
                brand_exp[brand] = brand_exp.get(brand, 0) + 1
                camp_exp[campaign] = camp_exp.get(campaign, 0) + 1
                cate_last_exp[cate] = moment
                brand_last_exp[brand] = moment
                if clicked:
                    ad_click[item] = ad_click.get(item, 0) + 1
                    cate_click[cate] = cate_click.get(cate, 0) + 1
                    brand_click[brand] = brand_click.get(brand, 0) + 1
                    camp_click[campaign] = camp_click.get(campaign, 0) + 1
                    cate_last_click[cate] = moment
                    brand_last_click[brand] = moment
            group_start = group_end + 1
    return result


def build_design(data: SplitData) -> tuple[np.ndarray, list[int], list[str]]:
    """R 의 설계 행렬과 범주 특징 위치, 특징 이름을 돌려준다."""
    rows = data.n_rows
    matrix = np.zeros((rows, len(FEATURE_NAMES)), dtype=np.float32)

    # 범주 특징.
    for column, name in enumerate(CATEGORICAL[:8]):
        matrix[:, column] = data.user_feats[data.row_user, column]
    matrix[:, 8] = data.row_pid
    matrix[:, 9] = data.row_hour
    matrix[:, 10] = data.row_weekend
    exposure = data.row_exposure()
    matrix[:, 11] = data.cate[exposure]
    matrix[:, 12] = data.brand[exposure]
    matrix[:, 13] = data.price[exposure]

    offset = len(CATEGORICAL)
    matrix[:, offset:offset + 15] = _history_features(data)

    # 전체 통계(전날까지).
    grids, global_mean = global_grids(data)
    day = data.row_exposure_day()
    start = offset + 15
    for index, name in enumerate(("ad", "cate", "brand", "campaign")):
        ids = getattr(data, {"ad": "item", "cate": "cate", "brand": "brand",
                             "campaign": "campaign"}[name])[exposure]
        exposures, clicks = grids[name]
        exp_prev = exposures[ids, day]
        click_prev = clicks[ids, day]
        mean_prev = global_mean[day]
        ctr = (click_prev + config.SMOOTH_PRIOR * mean_prev) / (exp_prev + config.SMOOTH_PRIOR)
        matrix[:, start + index * 3] = exp_prev
        matrix[:, start + index * 3 + 1] = click_prev
        matrix[:, start + index * 3 + 2] = ctr

    return matrix, CATEGORICAL_INDEX, list(FEATURE_NAMES)
