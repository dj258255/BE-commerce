"""Taobao 노출 블록을 읽어 고객별 노출열과 분할별 채점 행을 만든다.

블록 하나(`part-k`) 안에서만 `user_index` · `item_index` 가 뜻이 있으므로,
`data/` · `user_info/` · `item_info/` 를 같은 번호끼리 짝지어 읽고 전역 고객
코드를 붙여 이어 붙인다. 노출 하나 = `user_info` 열의 한 위치이고, 채점 행의
`seq_len` 이 그 위치다(가정은 :func:`verify_seq_len` 로 확인한다).

`full_action_seq` 는 클릭 여부로만 줄인다 — meta_data.json 의 action_vocab 에서
9~16 이 클릭이다. 장바구니 · 찜 · 구매는 쓰지 않는다.
"""
from __future__ import annotations

import json
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import numpy as np
import pyarrow.parquet as pq

from . import config

USER_FEATURES = (
    "cms_segid",
    "cms_group_id",
    "final_gender_code",
    "age_level",
    "pvalue_level",
    "shopping_level",
    "occupation",
    "new_user_class_level",
)

# 아이템 정적 특징. 이름은 item_info 열 이름 그대로다.
ITEM_FEATURES = ("cate_id", "campaign_id", "customer_id", "brand", "price_bucket")

CLICK_ACTION_MIN = 9
CLICK_ACTION_MAX = 16


@dataclass
class SplitData:
    """한 분할의 노출열(고객 순서로 이어 붙임)과 채점 행.

    `item` · `cate` · `brand` · `campaign` · `advertiser` · `price` · `ts` ·
    `click` 은 노출마다 하나이고 고객 순서로 이어 붙어 있다. 고객 u 의 노출은
    `offsets[u]:offsets[u+1]` 구간이다.

    `row_*` 는 채점 행마다 하나다. `row_pos` 는 `user_info` 열에서 그 노출의
    위치(`seq_len`)다. 그러므로 노출 인덱스는 `offsets[row_user] + row_pos`.
    """

    split: str
    item: np.ndarray
    cate: np.ndarray
    brand: np.ndarray
    campaign: np.ndarray
    advertiser: np.ndarray
    price: np.ndarray
    ts: np.ndarray
    click: np.ndarray
    offsets: np.ndarray
    user_ids: np.ndarray
    user_feats: np.ndarray
    row_user: np.ndarray
    row_pos: np.ndarray
    row_pid: np.ndarray
    row_hour: np.ndarray
    row_weekend: np.ndarray
    row_label: np.ndarray
    row_item: np.ndarray

    @property
    def n_users(self) -> int:
        return len(self.offsets) - 1

    @property
    def n_rows(self) -> int:
        return len(self.row_user)

    def row_exposure(self) -> np.ndarray:
        """채점 행마다 대응하는 노출 인덱스."""
        return (self.offsets[self.row_user] + self.row_pos).astype(np.int64)

    def exposure_day(self) -> np.ndarray:
        """노출마다 Asia/Shanghai 날짜(정수). 최소 날짜가 0 이 된다."""
        days = (self.ts.astype(np.int64) + config.TIMEZONE_OFFSET_SECONDS) // config.SECONDS_PER_DAY
        return (days - days.min()).astype(np.int32)

    def row_exposure_day(self) -> np.ndarray:
        return self.exposure_day()[self.row_exposure()]

    def eval_mask(self) -> np.ndarray:
        """G 가 채점할 수 있는 행(고객의 마지막 채점 행에서 뒤로 256개 안).

        R 도 같은 행에서 재야 두 모델을 나란히 놓을 수 있다. 창은 그 고객의 마지막
        채점 행까지이고 그 뒤(미래) 노출은 세지 않는다.
        """
        mask = np.zeros(self.n_rows, dtype=bool)
        order = np.lexsort((self.row_pos, self.row_user))
        user = self.row_user[order]
        position = self.row_pos[order]
        cuts = np.flatnonzero(np.diff(user)) + 1
        starts = np.concatenate([[0], cuts])
        ends = np.concatenate([cuts, [len(user)]])
        for start, end in zip(starts, ends):
            last = int(position[end - 1]) + 1
            window_start = max(last - config.MAX_HISTORY, 0)
            mask[order[start:end]] = position[start:end] >= window_start
        return mask

    def pre_day_history(self) -> np.ndarray:
        """고객마다 분할 채점 날 전 노출 수(이력 길이 지표용).

        고객 노출열 전체에서 그 날짜보다 이른 노출만 센다.
        """
        day = self.exposure_day()
        row_day = self.row_exposure_day()
        split_day = int(row_day.min()) if len(row_day) else 0
        prefix = np.concatenate([[0], np.cumsum(day < split_day)])
        return (prefix[self.offsets[1:]] - prefix[self.offsets[:-1]]).astype(np.int64)


# --------------------------------------------------------------------------- 읽기


def _parts(split: str) -> list[Path]:
    return sorted((config.data_dir() / split / "data").glob("part-*.parquet"))


def _block_number(path: Path) -> str:
    return path.stem


def user_table(split: str) -> list[tuple[str, np.ndarray, np.ndarray]]:
    """블록마다 고유 `(user_index, user_id)` 를 돌려준다."""
    blocks: list[tuple[str, np.ndarray, np.ndarray]] = []
    for path in _parts(split):
        table = pq.read_table(path, columns=["user_index", "user_id"]).to_pandas()
        unique = table.drop_duplicates("user_index").sort_values("user_index")
        blocks.append((_block_number(path), unique["user_index"].to_numpy(np.int64),
                       unique["user_id"].to_numpy(np.int64)))
    return blocks


def select_users(user_ids: np.ndarray, users: int | None) -> np.ndarray:
    """고객 id 해시로 고정한 표본을 고른다(`--users`).

    crc32 가 작은 순서로 앞에서 N 명을 고른다. 같은 데이터 · 같은 N 이면 언제나
    같은 표본이라 스모크 결과를 다시 만들 수 있다.
    """
    if users is None or users >= len(user_ids):
        return np.ones(len(user_ids), dtype=bool)
    if users < 1:
        raise ValueError("--users 는 1 이상이어야 합니다")
    hashes = np.fromiter((zlib.crc32(str(value).encode()) for value in user_ids),
                         dtype=np.uint32, count=len(user_ids))
    order = np.lexsort((np.arange(len(user_ids)), hashes))
    mask = np.zeros(len(user_ids), dtype=bool)
    mask[order[:users]] = True
    return mask


def _column(table, name: str, dtype) -> np.ndarray:
    """parquet 열을 요청한 numpy 타입으로 꺼낸다(청크 합치고 복사 허용)."""
    return table.column(name).combine_chunks().to_numpy(zero_copy_only=False).astype(dtype)


def _item_lookup(path: Path) -> dict[str, np.ndarray]:
    """블록의 `item_index -> 특징` 조회 배열. 아이템이 없는 칸은 0 이다."""
    table = pq.read_table(path)
    index = _column(table, "item_index", np.int64)
    size = int(index.max()) + 1
    lookup: dict[str, np.ndarray] = {"item_id": np.zeros(size, dtype=np.int32)}
    payload = lookup["item_id"]
    payload[index] = _column(table, "item_id", np.int32)
    for name in ITEM_FEATURES:
        array = np.zeros(size, dtype=np.int32)
        array[index] = _column(table, name, np.int32)
        lookup[name] = array
    return lookup


def _block_user_lookup(path: Path, wanted: set[int]) -> dict[int, dict[str, np.ndarray]]:
    """블록의 `user_info` 에서 원하는 `user_index` 만 꺼낸다."""
    table = pq.read_table(path)
    index = _column(table, "user_index", np.int64)
    items = table.column("full_item_seq").to_pylist()
    actions = table.column("full_action_seq").to_pylist()
    stamps = table.column("full_timestamp_seq").to_pylist()
    result: dict[int, dict[str, np.ndarray]] = {}
    for position, user in enumerate(index):
        user = int(user)
        if user not in wanted:
            continue
        result[user] = {
            "item": np.asarray(items[position], dtype=np.int64),
            "action": np.asarray(actions[position], dtype=np.int64),
            "ts": np.asarray(stamps[position], dtype=np.int64),
        }
    return result


def build(split: str, users: int | None = None) -> SplitData:
    """분할을 읽어 :class:`SplitData` 를 만든다.

    `users` 가 주어지면 `user_id` 해시로 고른 표본만 담는다.
    """
    blocks = user_table(split)
    user_block: list[np.ndarray] = []
    user_index: list[np.ndarray] = []
    user_ids: list[np.ndarray] = []
    for _, index, ids in blocks:
        user_block.append(np.full(len(index), len(user_block), dtype=np.int64))
        user_index.append(index)
        user_ids.append(ids)
    block_of_user = np.concatenate(user_block)
    index_of_user = np.concatenate(user_index)
    id_of_user = np.concatenate(user_ids)
    selected = select_users(id_of_user, users)

    parts: dict[str, list[np.ndarray]] = {
        key: [] for key in ("item", "cate", "brand", "campaign", "advertiser", "price", "ts", "click")
    }
    user_lengths: list[np.ndarray] = []
    kept_user_ids: list[np.ndarray] = []
    kept_feats: list[np.ndarray] = []
    rows: dict[str, list[np.ndarray]] = {
        key: [] for key in ("user", "pos", "pid", "hour", "weekend", "label", "item")
    }

    cursor = 0
    code_offset = 0
    for block_number, (block_name, index, _ids) in enumerate(blocks):
        count = len(index)
        block_slice = slice(cursor, cursor + count)
        cursor += count
        local_selected = selected[block_slice]
        if not local_selected.any():
            continue
        wanted = set(index[local_selected].tolist())

        # 이 블록의 선택 고객을 user_index 순으로 정렬하고, 그 순서로 전역 코드를
        # 매긴다. 노출열과 채점 행이 같은 코드를 쓰려면 순서가 하나여야 한다.
        ordered = np.sort(index[local_selected])
        new_code = {int(value): code_offset + code for code, value in enumerate(ordered)}
        code_offset += len(ordered)
        block_dir = config.data_dir() / split
        lookup = _item_lookup(block_dir / "item_info" / f"{block_name}.parquet")
        histories = _block_user_lookup(block_dir / "user_info" / f"{block_name}.parquet", wanted)

        kept_user_ids.append(_ids[local_selected][np.argsort(index[local_selected], kind="stable")])
        for user in ordered:
            history = histories[int(user)]
            items = history["item"]
            clicks = ((history["action"] >= CLICK_ACTION_MIN) & (history["action"] <= CLICK_ACTION_MAX))
            parts["item"].append(lookup["item_id"][items])
            parts["cate"].append(lookup["cate_id"][items])
            parts["brand"].append(lookup["brand"][items])
            parts["campaign"].append(lookup["campaign_id"][items])
            parts["advertiser"].append(lookup["customer_id"][items])
            parts["price"].append(lookup["price_bucket"][items])
            parts["ts"].append(history["ts"])
            parts["click"].append(clicks.astype(np.int8))
        lengths = np.array([len(histories[int(user)]["item"]) for user in ordered], dtype=np.int64)
        user_lengths.append(lengths)

        table = pq.read_table(
            block_dir / "data" / f"{block_name}.parquet",
            columns=["user_index", "item_index", "seq_len", *USER_FEATURES, "pid", "hour", "is_weekend", "is_click"],
        ).to_pandas()
        table = table[table["user_index"].isin(list(wanted))]
        table = table.sort_values(["user_index", "seq_len"], kind="stable")
        codes = np.array([new_code[int(value)] for value in table["user_index"]], dtype=np.int32)
        rows["user"].append(codes)
        rows["pos"].append(table["seq_len"].to_numpy(np.int32))
        rows["pid"].append(table["pid"].to_numpy(np.int16))
        rows["hour"].append(table["hour"].to_numpy(np.int16))
        rows["weekend"].append(table["is_weekend"].to_numpy(np.int16))
        rows["label"].append(table["is_click"].to_numpy(np.int8))
        rows["item"].append(lookup["item_id"][table["item_index"].to_numpy(np.int64)])
        first = table.drop_duplicates("user_index").sort_values("user_index")
        kept_feats.append(first[list(USER_FEATURES)].to_numpy(np.int16))

    if not kept_user_ids:
        raise ValueError(f"{split} 에서 고객을 하나도 고르지 못했습니다")

    ids_all = np.concatenate(kept_user_ids)
    offsets = np.zeros(len(ids_all) + 1, dtype=np.int64)
    offsets[1:] = np.cumsum(np.concatenate(user_lengths))

    data = SplitData(
        split=split,
        item=np.concatenate(parts["item"]).astype(np.int32),
        cate=np.concatenate(parts["cate"]).astype(np.int32),
        brand=np.concatenate(parts["brand"]).astype(np.int32),
        campaign=np.concatenate(parts["campaign"]).astype(np.int32),
        advertiser=np.concatenate(parts["advertiser"]).astype(np.int32),
        price=np.concatenate(parts["price"]).astype(np.int16),
        ts=np.concatenate(parts["ts"]).astype(np.int64),
        click=np.concatenate(parts["click"]).astype(np.int8),
        offsets=offsets,
        user_ids=ids_all.astype(np.int32),
        user_feats=np.concatenate(kept_feats).astype(np.int16),
        row_user=np.concatenate(rows["user"]).astype(np.int32),
        row_pos=np.concatenate(rows["pos"]).astype(np.int32),
        row_pid=np.concatenate(rows["pid"]).astype(np.int16),
        row_hour=np.concatenate(rows["hour"]).astype(np.int16),
        row_weekend=np.concatenate(rows["weekend"]).astype(np.int16),
        row_label=np.concatenate(rows["label"]).astype(np.int8),
        row_item=np.concatenate(rows["item"]).astype(np.int32),
    )
    return data


def verify_seq_len(data: SplitData, checks: int = 2000, seed: int = 0) -> dict[str, int | bool]:
    """채점 행의 `seq_len` 위치 노출이 행의 아이템과 같은지 표본으로 확인한다.

    `user_info` 열의 위치가 그 행의 노출이라는 가정을 데이터로 검증한다.
    """
    rng = np.random.default_rng(seed)
    count = min(checks, data.n_rows)
    if count == 0:
        return {"checked": 0, "matched": 0, "ok": True}
    picked = rng.choice(data.n_rows, size=count, replace=False)
    exposure = data.row_exposure()[picked]
    matched = int(np.sum(data.item[exposure] == data.row_item[picked]))
    clicks = int(np.sum(data.click[exposure] == data.row_label[picked]))
    return {"checked": int(count), "matched": matched, "ok": bool(matched == count),
            "click_matched": clicks, "click_ok": bool(clicks == count)}


# --------------------------------------------------------------------------- 캐시


def _cache_path(split: str) -> Path:
    return config.cache_dir() / f"{split}.npz"


def save_cache(data: SplitData) -> Path:
    path = _cache_path(data.split)
    path.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(
        path,
        split=np.array(data.split),
        item=data.item, cate=data.cate, brand=data.brand, campaign=data.campaign,
        advertiser=data.advertiser, price=data.price, ts=data.ts, click=data.click,
        offsets=data.offsets, user_ids=data.user_ids, user_feats=data.user_feats,
        row_user=data.row_user, row_pos=data.row_pos, row_pid=data.row_pid,
        row_hour=data.row_hour, row_weekend=data.row_weekend, row_label=data.row_label,
        row_item=data.row_item,
    )
    return path


def load_cache(split: str) -> SplitData:
    path = _cache_path(split)
    with np.load(path, allow_pickle=False) as archive:
        return SplitData(
            split=str(archive["split"]),
            item=archive["item"], cate=archive["cate"], brand=archive["brand"],
            campaign=archive["campaign"], advertiser=archive["advertiser"], price=archive["price"],
            ts=archive["ts"], click=archive["click"], offsets=archive["offsets"],
            user_ids=archive["user_ids"], user_feats=archive["user_feats"],
            row_user=archive["row_user"], row_pos=archive["row_pos"], row_pid=archive["row_pid"],
            row_hour=archive["row_hour"], row_weekend=archive["row_weekend"], row_label=archive["row_label"],
            row_item=archive["row_item"],
        )


def load_split(split: str, users: int | None = None, use_cache: bool = True) -> SplitData:
    """분할을 읽는다. 표본이 아니고 캐시가 있으면 캐시를 쓴다."""
    if users is None and use_cache and _cache_path(split).exists():
        return load_cache(split)
    data = build(split, users=users)
    if users is None and use_cache:
        save_cache(data)
    return data
