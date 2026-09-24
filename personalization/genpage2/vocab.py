"""GenPage v2의 고정 토큰 어휘.

상품 id는 언제나 H&M 원본의 10자리 문자열로 보관한다.  이 모듈은 데이터셋과
서빙이 같은 id 배치와 콘텐츠 행 번호를 재현할 수 있게 의도적으로 단순한 JSON
형식만 사용한다.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pandas as pd

from . import config


SPECIAL = [
    "PAD", "BOS", "EOS", "SEP_PROFILE", "SEP_REQUEST", "SEP_HISTORY",
    "SEP_PAGE", "ITEM_FALLBACK", "ROW_FALLBACK", "UNK",
]
AGE_BUCKETS = ["<20", "20-24", "25-29", "30-39", "40-49", "50-59", "60+", "NA"]
CLUB_VALUES = ["ACTIVE", "PRE-CREATE", "LEFT CLUB", "NA"]
NEWS_VALUES = ["NONE", "Regularly", "Monthly", "NA"]


def _article_id(value: Any) -> str:
    """Return an article id without accidentally dropping leading zeroes."""
    value = str(value)
    return value.zfill(10) if value.isdigit() else value


def _section(value: Any) -> str:
    """Use the documented ROW_S<section_no> spelling for integer-valued floats."""
    if pd.isna(value):
        return "NA"
    value = float(value)
    return str(int(value)) if value.is_integer() else str(value)


def content_rows(articles: pd.DataFrame) -> dict[str, int]:
    """Map every catalogue article to its sorted, zero-based content row."""
    ids = sorted(_article_id(x) for x in articles["article_id"].tolist())
    if len(ids) != len(set(ids)):
        raise ValueError("articles.article_id must be unique")
    return {article_id: i for i, article_id in enumerate(ids)}


class Vocab:
    """Fixed vocabulary plus catalogue metadata required by the page decoder."""

    def __init__(self, tokens: list[str], article_rows: dict[str, int]):
        if len(tokens) != len(set(tokens)):
            raise ValueError("duplicate token in vocabulary")
        self.tokens = list(tokens)
        self._ids = {name: i for i, name in enumerate(self.tokens)}
        self._article_rows = dict(article_rows)
        self.article_of = {
            token_id: name.removeprefix("ITEM_")
            for token_id, name in enumerate(self.tokens)
            if name.startswith("ITEM_") and name != "ITEM_FALLBACK"
        }
        self._item_of = {article: token_id for token_id, article in self.article_of.items()}
        self._set_ranges()

    def _set_ranges(self) -> None:
        def span(first: str, last: str) -> range:
            return range(self.id(first), self.id(last) + 1)

        self.special_ids = range(0, len(SPECIAL))
        self.profile_ids = span("AGE_<20", "ACTIVE_NA")
        self.request_ids = span("DOW_0", "MONTH_12")
        self.action_ids = span("ACT_STORE", "ACT_CLICK")
        self.ago_ids = span("AGO_0-3", "AGO_366+")
        self.row_ids = range(self.id("ROW_REPEAT"), self.id("ROW_REPEAT") + self._row_count())
        item_start = self.id("ITEM_" + self.article_of[min(self.article_of)]) if self.article_of else len(self.tokens)
        self.item_ids = range(item_start, len(self.tokens))
        # Tokens that may occur before SEP_PAGE, excluding structural/special ids.
        self.context_ids = range(self.profile_ids.start, self.ago_ids.stop)

    def _row_count(self) -> int:
        return sum(name.startswith("ROW_") and name != "ROW_FALLBACK" for name in self.tokens)

    @classmethod
    def build(cls, tx_before_request: pd.DataFrame, articles: pd.DataFrame) -> "Vocab":
        """Build the vocabulary from sales strictly before the mode request time."""
        catalogue = articles[["article_id", "section_no"]].copy()
        catalogue["article_id"] = catalogue["article_id"].map(_article_id)
        if catalogue["article_id"].duplicated().any():
            raise ValueError("articles.article_id must be unique")
        counts = tx_before_request["article_id"].map(_article_id).value_counts()
        eligible = sorted(counts[counts >= config.MIN_COUNT].index.tolist())
        section_by_article = dict(zip(catalogue["article_id"], catalogue["section_no"], strict=True))
        unknown = set(eligible).difference(section_by_article)
        if unknown:
            raise ValueError(f"transactions include article absent from catalogue: {next(iter(unknown))}")

        profile = (
            [f"AGE_{x}" for x in AGE_BUCKETS]
            + [f"CLUB_{x}" for x in CLUB_VALUES]
            + [f"NEWS_{x}" for x in NEWS_VALUES]
            + ["FN_1", "FN_NA", "ACTIVE_1", "ACTIVE_NA"]
        )
        request = [f"DOW_{n}" for n in range(7)] + [f"MONTH_{n}" for n in range(1, 13)]
        actions = ["ACT_STORE", "ACT_ONLINE", "ACT_VIEW", "ACT_CLICK"]
        ago = ["AGO_0-3", "AGO_4-7", "AGO_8-14", "AGO_15-30", "AGO_31-60", "AGO_61-120", "AGO_121-365", "AGO_366+"]
        sections = sorted({_section(x) for x in catalogue["section_no"]}, key=lambda x: (x == "NA", float(x) if x != "NA" else 0))
        rows = ["ROW_REPEAT"] + [f"ROW_S{x}" for x in sections]
        return cls(
            SPECIAL + profile + request + actions + ago + rows + [f"ITEM_{x}" for x in eligible],
            {},
        )._with_rows(catalogue)

    def _with_rows(self, catalogue: pd.DataFrame) -> "Vocab":
        # This is separate from build so JSON construction has a single initializer.
        self._article_rows = {
            article: self.id(f"ROW_S{_section(section)}")
            for article, section in zip(catalogue["article_id"], catalogue["section_no"], strict=True)
        }
        return self

    def id(self, name: str) -> int:
        try:
            return self._ids[name]
        except KeyError as exc:
            raise KeyError(f"unknown vocabulary token: {name}") from exc

    def item(self, article_id: str) -> int | None:
        return self._item_of.get(_article_id(article_id))

    def row_of(self, article_id: str) -> int:
        try:
            return self._article_rows[_article_id(article_id)]
        except KeyError as exc:
            raise KeyError(f"unknown catalogue article: {article_id}") from exc

    def save(self, path: str | Path) -> None:
        with Path(path).open("w", encoding="utf-8") as f:
            json.dump({"tokens": self.tokens, "article_rows": self._article_rows}, f,
                      ensure_ascii=False, sort_keys=True, separators=(",", ":"))

    @staticmethod
    def load(path: str | Path) -> "Vocab":
        with Path(path).open(encoding="utf-8") as f:
            data = json.load(f)
        return Vocab(list(data["tokens"]), {str(k): int(v) for k, v in data["article_rows"].items()})
