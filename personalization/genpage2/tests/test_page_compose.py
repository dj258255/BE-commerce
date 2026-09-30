from __future__ import annotations

import argparse
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np
import pandas as pd

from genpage2.decode import GeneratedRow
from genpage2.evaluate import evaluate_pages
from genpage2.merge_eval import _collect_pages
from genpage2.page_compose import (build_pages, compose_b, g_full_inputs, g_row_inputs, read_scores, run)
from genpage2.ranker import write_scores

_TOKENS = (["ROW_REPEAT", "ROW_S1", "ROW_S2"]
           + [f"ITEM_{letter}" for letter in "ABCDEFGHIJKLMNOP"])
_ITEMS = {name[len("ITEM_"):]: index for index, name in enumerate(_TOKENS)
          if name.startswith("ITEM_")}
_ROWS = {letter: 1 for letter in "ABCDEFGH"} | {letter: 2 for letter in "IJKLMNOP"}


class FakeVocab:
    tokens = _TOKENS

    def id(self, name):
        return self.tokens.index(name)

    def item(self, article):
        return _ITEMS.get(article)

    def row_of(self, article):
        return _ROWS[article]


class ComposeBTest(unittest.TestCase):
    def setUp(self):
        self.vocab = FakeVocab()

    def test_rows_ordered_by_top_items_score_sum(self):
        scored = [("A", 0.5), ("B", 0.4), ("C", 0.9), ("I", 0.3), ("J", 0.2), ("K", 0.1)]
        rows = compose_b(scored, [], self.vocab, rows=6, items=2)
        # row1: C(0.9)+A(0.5)=1.4, row2: I(0.3)+J(0.2)=0.5 -> row1 이 먼저.
        self.assertEqual([row.row_token for row in rows], [1, 2])
        self.assertEqual(rows[0].items, ["C", "A"])
        self.assertEqual(rows[1].items, ["I", "J"])

    def test_history_items_go_to_repeat_row_and_each_product_once(self):
        scored = [("I", 0.95), ("A", 0.5), ("B", 0.4), ("C", 0.9), ("J", 0.3), ("K", 0.2)]
        rows = compose_b(scored, [("I")], self.vocab, rows=6, items=8)
        repeat = next(row for row in rows if row.row_token == self.vocab.id("ROW_REPEAT"))
        self.assertEqual(repeat.items, ["I"])
        # 다시 사기 행으로 간 상품은 자기 섹션 행에 다시 나오지 않는다.
        section2 = next(row for row in rows if row.row_token == 2)
        self.assertNotIn("I", section2.items)
        all_items = [article for row in rows for article in row.items]
        self.assertEqual(len(all_items), len(set(all_items)))

    def test_top_rows_limit(self):
        scored = [("A", 0.9), ("B", 0.8), ("C", 0.7), ("I", 0.6)]
        rows = compose_b(scored, [], self.vocab, rows=1, items=8)
        self.assertEqual([row.row_token for row in rows], [1])
        self.assertEqual(rows[0].items, ["A", "B", "C"])


class GRowInputsTest(unittest.TestCase):
    def setUp(self):
        self.vocab = FakeVocab()

    def test_allowed_rows_need_eight_items(self):
        scored = [(letter, 1.0 - index / 100) for index, letter in enumerate("ABCDEFGH")]
        scored += [(letter, 0.5 - index / 100) for index, letter in enumerate("IJKLMNO")]
        row_items, allowed_rows = g_row_inputs(scored, [], self.vocab)
        # row2 는 7개뿐이라 막히고 row1 만 남는다.
        self.assertEqual(allowed_rows, {1})
        self.assertEqual(row_items[1], list("ABCDEFGH"))

    def test_row_items_follow_score_order(self):
        scored = [("H", 0.9), ("A", 0.8), ("B", 0.7), ("C", 0.6),
                  ("D", 0.5), ("E", 0.4), ("F", 0.3), ("G", 0.2)]
        row_items, allowed_rows = g_row_inputs(scored, [], self.vocab)
        self.assertEqual(allowed_rows, {1})
        self.assertEqual(row_items[1], list("HABCDEFG"))

    def test_repeat_row_allowed_only_with_eight_history_items(self):
        history = list("IJKLMNOP")
        scored = [(letter, 1.0 - index / 100) for index, letter in enumerate("ABCDEFGH")]
        scored += [(letter, 0.5 - index / 100) for index, letter in enumerate("IJKLMNOP")]
        row_items, allowed_rows = g_row_inputs(scored, history, self.vocab)
        self.assertEqual(allowed_rows, {self.vocab.id("ROW_REPEAT"), 1})
        self.assertEqual(row_items[self.vocab.id("ROW_REPEAT")], list("IJKLMNOP"))


class GFullInputsTest(unittest.TestCase):
    def test_allowed_items_are_top_n_and_repeat_row_uses_recency(self):
        vocab = FakeVocab()
        history = ["D", "C", "A", "not-in-vocab"]
        scored = [("A", 0.9), ("B", 0.8), ("C", 0.7), ("I", 0.6), ("P", 0.5)]
        allowed_items, row_items = g_full_inputs(scored, history, vocab, top=3)
        self.assertEqual(allowed_items, {"A", "B", "C"})
        # 최근 산 순서(중복 없이, 어휘 밖 상품 제외).
        self.assertEqual(row_items[vocab.id("ROW_REPEAT")], ["D", "C", "A"])

    def test_repeat_row_items_keep_full_history_beyond_eight(self):
        vocab = FakeVocab()
        # 최근 순서로 12개. 8개로 자르면 뒤 4개가 사라져 모델이 그 자리를 채운다.
        history = list("ABCDEFGHIJKL")
        scored = [(letter, 0.5) for letter in "ABCDEFGHIJKL"]
        _, row_items = g_full_inputs(scored, history, vocab)
        self.assertEqual(row_items[vocab.id("ROW_REPEAT")], list("ABCDEFGHIJKL"))
        self.assertGreater(len(row_items[vocab.id("ROW_REPEAT")]), 8)


class BuildPagesTest(unittest.TestCase):
    def test_b_build_pages_needs_no_decoder(self):
        vocab = FakeVocab()
        meta = pd.DataFrame({"customer_id": ["c1", "c2"], "history": [[], ["I"]]})
        scores = {"c1": [("A", 0.9), ("B", 0.8), ("C", 0.7)],
                  "c2": [("I", 0.95), ("J", 0.3), ("K", 0.2)]}
        pages, violations = build_pages("B", meta, None, scores, vocab=vocab)
        self.assertEqual(violations, {"c1": 0, "c2": 0})
        self.assertEqual([row.row_token for row in pages["c1"]], [1])
        self.assertEqual([row.row_token for row in pages["c2"]], [vocab.id("ROW_REPEAT"), 2])


class ScoresRoundTripTest(unittest.TestCase):
    def test_write_then_read_keeps_order(self):
        meta = pd.DataFrame({"customer_id": ["c2", "c1"]})
        scores = {"c1": [("A", 0.5), ("B", 0.4)], "c2": []}
        with tempfile.TemporaryDirectory() as temporary:
            path = write_scores(Path(temporary) / "scores.json.gz", meta, scores)
            loaded = read_scores(path)
        self.assertEqual(loaded["c1"], [("A", 0.5), ("B", 0.4)])
        self.assertEqual(loaded["c2"], [])


class RunReportTest(unittest.TestCase):
    def test_run_b_report_shape_is_readable_by_merge(self):
        vocab = FakeVocab()
        meta = pd.DataFrame({"customer_id": ["c1", "c2"], "truth": [["A"], ["I"]],
                             "history": [[], ["I"]]})
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            mode_dir = base / "hm" / "model" / "genpage2" / "validate"
            mode_dir.mkdir(parents=True)
            meta.to_parquet(mode_dir / "eval_meta.parquet")
            np.savez(mode_dir / "eval.npz", ctx_tokens=np.array([1, 1]), ctx_content=np.array([-1, -1]),
                     ctx_offsets=np.array([0, 1, 2]))
            scores = {"c1": [("A", 0.9), ("B", 0.8), ("C", 0.7)],
                      "c2": [("I", 0.95), ("J", 0.3)]}
            write_scores(base / "scores.json.gz", meta, scores)
            with patch("genpage2.page_compose.load_eval_assets", return_value=(vocab, None, None)):
                report = run(argparse.Namespace(
                    mode="validate", limit=None, scores=str(base / "scores.json.gz"), variant="B",
                    ckpt=None, shard=None, out=str(base / "b.json"), batch=256, device="cpu",
                    data_dir=str(base), threads=None,
                ))
        self.assertEqual(report["shard"], {"index": 1, "total": 1, "customers": 2})
        self.assertEqual([record["customer_id"] for record in report["pages"]], ["c1", "c2"])
        pages = _collect_pages([report], meta)
        self.assertIsInstance(pages["c1"][0], GeneratedRow)
        metrics = evaluate_pages(meta, pages, vocab=vocab)
        self.assertIn("page_ndcg", metrics)
        self.assertIn("row_hit", metrics)
        self.assertEqual(metrics["violations"], 0)


if __name__ == "__main__":
    unittest.main()
