"""T1 Taobao 실험의 상수와 경로.

값의 출처는 `docs/genpage-v2/T1-TAOBAO.md` 다. 실험을 다시 돌릴 때 바뀌면 안
되는 값(시드·시간대·이력 상한·튜닝 후보)만 여기에 모은다.
"""
from __future__ import annotations

import os
from pathlib import Path

SEED = 7

# 시각은 초 단위 epoch 이고 날짜는 Asia/Shanghai(UTC+8) 기준이다(meta_data.json).
TIMEZONE_OFFSET_SECONDS = 8 * 3600
SECONDS_PER_DAY = 86400

# 한 고객의 이력은 마지막 256개 노출까지 본다.
MAX_HISTORY = 256

# 전체 통계 평활 클릭률의 사전 횟수.
SMOOTH_PRIOR = 20.0

# R 튜닝 후보(num_leaves).
NUM_LEAVES = (31, 63, 127)

# G 튜닝 후보(층 수 · 은닉 차원). R 과 같은 수의 세 설정이다.
SEQ_CONFIGS: dict[str, dict[str, int]] = {
    "d64_l2": {"dim": 64, "layers": 2},
    "d128_l2": {"dim": 128, "layers": 2},
    "d64_l4": {"dim": 64, "layers": 4},
}
SEQ_DEFAULT = "d64_l2"

SPLITS = ("train", "valid", "test")

# 분할별 채점 날짜(문서의 표). 이력 길이 지표와 전날 통계의 경계에 쓴다.
SPLIT_DAY = {"train": "2017-05-06", "valid": "2017-05-12", "test": "2017-05-13"}

# meta_data.json 의 vocab_size. 임베딩 표 크기는 이 값 + 1(패딩)이다.
VOCAB = {
    "item": 846812,
    "cate": 6409,
    "brand": 88145,
    "campaign": 354106,
    "price_bucket": 11,
    "cms_segid": 99,
    "cms_group_id": 15,
    "final_gender_code": 4,
    "age_level": 9,
    "pvalue_level": 5,
    "shopping_level": 5,
    "occupation": 4,
    "new_user_class_level": 6,
    "pid": 4,
    "hour": 25,
    "is_weekend": 3,
}

# 직전 노출과의 시간 간격 구간(초). searchsorted 로 구간 번호를 매긴다.
GAP_THRESHOLDS = (0, 1, 2, 5, 10, 20, 30, 60, 120, 300, 600, 1800, 3600,
                  7200, 21600, 43200, 86400, 172800, 604800)


def data_dir() -> Path:
    """원자료 위치. 저장소 밖(gitignore)이 기본이다.

    워크트리에는 데이터가 없을 수 있어(원자료는 메인 체크아웃에만 둔다) 순서대로
    찾는다: 환경변수 `TAOBAO_DATA`, 이 저장소의 `personalization/data/taobao-ad`,
    홈 아래 메인 체크아웃의 같은 자리.
    """
    override = os.environ.get("TAOBAO_DATA")
    if override:
        return Path(override)
    local = Path(__file__).resolve().parents[1] / "data" / "taobao-ad"
    if local.exists():
        return local
    return Path.home() / "Desktop" / "pay" / "personalization" / "data" / "taobao-ad"


def cache_dir() -> Path:
    return data_dir() / "cache"


def out_dir() -> Path:
    return data_dir() / "runs"
