"""테스트용 합성 데이터. 노출열과 채점 행을 직접 만들어 넣는다."""
from __future__ import annotations

import numpy as np

from taobao.data import SplitData


def synthetic_split(split: str, users: list[list[tuple]], user_feats: np.ndarray | None = None) -> SplitData:
    """`users[u]` 의 각 원소는 `(item, cate, brand, campaign, advertiser, price, ts, click)`.

    채점 행은 모든 노출 위치에 만든다. 맥락(pid · 시 · 주말)은 고정값이다.
    """
    item: list[int] = []
    cate: list[int] = []
    brand: list[int] = []
    campaign: list[int] = []
    advertiser: list[int] = []
    price: list[int] = []
    ts: list[int] = []
    click: list[int] = []
    offsets = [0]
    rows: dict[str, list[int]] = {key: [] for key in ("user", "pos", "pid", "hour", "weekend", "label", "item")}
    for code, exposures in enumerate(users):
        for position, (i, c, b, cp, ad, pr, t, k) in enumerate(exposures):
            item.append(i)
            cate.append(c)
            brand.append(b)
            campaign.append(cp)
            advertiser.append(ad)
            price.append(pr)
            ts.append(t)
            click.append(k)
            rows["user"].append(code)
            rows["pos"].append(position)
            rows["pid"].append(2)
            rows["hour"].append(12)
            rows["weekend"].append(1)
            rows["label"].append(k)
            rows["item"].append(i)
        offsets.append(len(item))
    features = user_feats if user_feats is not None else np.ones((len(users), 8), dtype=np.int16)
    return SplitData(
        split=split,
        item=np.asarray(item, dtype=np.int32),
        cate=np.asarray(cate, dtype=np.int32),
        brand=np.asarray(brand, dtype=np.int32),
        campaign=np.asarray(campaign, dtype=np.int32),
        advertiser=np.asarray(advertiser, dtype=np.int32),
        price=np.asarray(price, dtype=np.int16),
        ts=np.asarray(ts, dtype=np.int64),
        click=np.asarray(click, dtype=np.int8),
        offsets=np.asarray(offsets, dtype=np.int64),
        user_ids=np.arange(1000, 1000 + len(users), dtype=np.int32),
        user_feats=np.asarray(features, dtype=np.int16),
        row_user=np.asarray(rows["user"], dtype=np.int32),
        row_pos=np.asarray(rows["pos"], dtype=np.int32),
        row_pid=np.asarray(rows["pid"], dtype=np.int16),
        row_hour=np.asarray(rows["hour"], dtype=np.int16),
        row_weekend=np.asarray(rows["weekend"], dtype=np.int16),
        row_label=np.asarray(rows["label"], dtype=np.int8),
        row_item=np.asarray(rows["item"], dtype=np.int32),
    )
