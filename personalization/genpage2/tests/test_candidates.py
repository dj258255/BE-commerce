from __future__ import annotations

import unittest

import pandas as pd

from genpage2.candidates import build_candidates


class FakeVocab:
    def __init__(self):
        self.items = {"A", "B", "C", "D", "E"}
        self.sections = {"A": 1, "B": 1, "C": 2, "D": 2, "E": 3}

    def item(self, article):
        return article if article in self.items else None

    def row_of(self, article):
        return self.sections[article]


class CandidatesTest(unittest.TestCase):
    def test_history_recent_popular_sections_and_future_are_handled(self):
        meta = pd.DataFrame({
            "customer_id": ["one", "two"],
            "history": [["A", "outside-vocab"], ["D"]],
        })
        transactions = pd.DataFrame({
            "t_dat": ["2020-09-08", "2020-09-08", "2020-09-08", "2020-09-08", "2020-09-10"],
            # B와 C는 같은 판매 수다. top_n=1이면 article_id가 앞선 B여야 한다.
            "article_id": ["B", "C", "B", "C", "E"],
        })
        candidates = build_candidates(meta, transactions, pd.Timestamp("2020-09-09"),
                                      top_n=1, per_section_m=1, vocab=FakeVocab())
        self.assertEqual(candidates["one"], {"A", "B"})
        self.assertEqual(candidates["two"], {"B", "C", "D"})
        self.assertNotIn("E", set().union(*candidates.values()))


if __name__ == "__main__":
    unittest.main()
