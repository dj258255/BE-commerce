"""노출 상품의 반응을 보상 점수로 바꾼다.

숫자(buy 3.0 · click 1.0 · skip −0.2 · unseen 0.0)는 **고른 설정값**이다. 실제
사용자 행동이나 만족도를 흉내 낸다는 주장이 아니고, 원문 GenPage 의 보상 시스템
자리에 H&M 에 없는 값을 우리가 정해 넣은 것이다. C1(WBC) · C2(RL) 후학습이 이
점수를 감독 신호의 크기로 쓴다.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

FEEDBACKS = ("unseen", "skip", "click", "buy")


@dataclass(frozen=True)
class RewardConfig:
    """반응 종류별 보상. 값은 고른 설정값이다."""

    buy: float = 3.0
    click: float = 1.0
    skip: float = -0.2
    unseen: float = 0.0


def item_reward(feedback: str, cfg: RewardConfig | None = None) -> float:
    """노출된 상품 하나의 보상."""
    cfg = cfg if cfg is not None else RewardConfig()
    if feedback not in FEEDBACKS:
        raise ValueError(f"unknown feedback {feedback!r}; expected one of {FEEDBACKS}")
    return float(getattr(cfg, feedback))


def row_reward(item_rewards: Iterable[float]) -> float:
    """행 하나의 보상 = 그 행 상품 보상의 합(원문: 행 보상 = 행 상품 보상 합)."""
    return float(sum(item_rewards))


def page_reward(item_rewards: Iterable[float]) -> float:
    """페이지 보상 = 노출된 상품 보상의 합."""
    return float(sum(item_rewards))
