"""GenPage v2 공통 상수와 경로. 값의 뜻은 docs/genpage-v2/DESIGN.md §0."""

import os
from pathlib import Path

import pandas as pd

VALIDATE_REQUEST = pd.Timestamp("2020-09-09")
FINAL_REQUEST = pd.Timestamp("2020-09-16")
TARGET_DAYS = 7
MIN_COUNT = 10
HISTORY_EVENTS = 60
MAX_ROWS = 6
ITEMS_PER_ROW = 8
MAXLEN = 320
EVENT_WIDTH = 4
SEED = 7

MODES = {"validate": VALIDATE_REQUEST, "final": FINAL_REQUEST}


def data_dir() -> Path:
    return Path(os.environ.get("GENPAGE_DATA", Path(__file__).resolve().parents[1] / "data"))


def normalized_dir() -> Path:
    return data_dir() / "hm" / "normalized"


def out_dir() -> Path:
    """산출물 위치. 저장소 밖(data 는 gitignore)이고 커밋하지 않는다."""
    return data_dir() / "hm" / "model" / "genpage2"


def request_of(mode: str) -> pd.Timestamp:
    if mode not in MODES:
        raise ValueError(f"mode 는 {sorted(MODES)} 중 하나: {mode}")
    return MODES[mode]
