from __future__ import annotations

import unittest

import numpy as np
import pandas as pd

from genpage2.candidates import _neighbor_matrix, _neighbors_for, build_candidates, build_similar_candidates


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


class SimilarVocab:
    """0000000005 만 어휘 밖에 둔다(요청 뒤에만 있는 상품을 흉내낸다)."""

    items = {"0000000001", "0000000002", "0000000003", "0000000004"}

    def item(self, article):
        return 1 if article in self.items else None


class SimilarCandidatesTest(unittest.TestCase):
    def setUp(self):
        # 행 번호는 article_id 오름차순: 1,2,3,4,5.
        self.content_rows = {f"{index:010d}": index - 1 for index in range(1, 6)}
        self.content = np.asarray([
            [1.0, 0.0, 0.0, 0.0],   # 0000000001 A
            [0.9, 0.1, 0.0, 0.0],   # 0000000002 B — A 다음으로 가깝다
            [0.0, 1.0, 0.0, 0.0],   # 0000000003 C
            [0.0, 0.0, 1.0, 0.0],   # 0000000004 D
            [0.9, 0.1, 0.0, 0.0],   # 0000000005 E — 어휘 밖
        ], dtype=np.float32)

    def test_neighbors_exclude_self_and_out_of_vocab_and_respect_cosine_order(self):
        vocab = SimilarVocab()
        articles, matrix = _neighbor_matrix(vocab, self.content, self.content_rows)
        self.assertEqual(articles, ["0000000001", "0000000002", "0000000003", "0000000004"])
        index_of = {article: index for index, article in enumerate(articles)}
        neighbors = _neighbors_for(["0000000001"], articles, matrix, index_of, 2, chunk=512)
        self.assertEqual(neighbors["0000000001"], ["0000000002", "0000000003"])

    def test_build_uses_recent_vocab_history_deduped_and_limited_by_k(self):
        meta = pd.DataFrame({"customer_id": ["one", "two"],
                             "history": [["0000000001", "0000000005", "0000000001", "0000000003"], []]})
        candidates = build_similar_candidates(meta, vocab=SimilarVocab(), content=self.content,
                                              content_rows=self.content_rows, recent_k=2, neighbors=2)
        # recent = [A, C]; A 의 이웃 {B,C}, C 의 이웃 {B,A} → 합집합 {A,B,C}
        self.assertEqual(candidates["one"], {"0000000001", "0000000002", "0000000003"})
        self.assertNotIn("0000000005", candidates["one"])
        self.assertEqual(candidates["two"], set())

    def test_zero_neighbors_and_negative_are_handled(self):
        meta = pd.DataFrame({"customer_id": ["one"], "history": [["0000000001"]]})
        self.assertEqual(build_similar_candidates(meta, vocab=SimilarVocab(), content=self.content,
                                                  content_rows=self.content_rows, recent_k=1, neighbors=0)["one"],
                         set())
        with self.assertRaises(ValueError):
            build_similar_candidates(meta, vocab=SimilarVocab(), content=self.content,
                                     content_rows=self.content_rows, recent_k=-1, neighbors=1)


if __name__ == "__main__":
    unittest.main()
