from __future__ import annotations

import argparse
import tempfile
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

from genpage2.decode import GeneratedRow
from genpage2.evaluate import evaluate_pages, map_at_12, repeat_last_pages, run


class FakeVocab:
    def id(self, name):
        return 2

    def row_of(self, article):
        return {"A": 3, "B": 3, "C": 4}[article]


class EvaluateTest(unittest.TestCase):
    def setUp(self):
        self.meta = pd.DataFrame({
            "customer_id": ["one", "two"],
            "truth": [["A", "B"], ["C"]],
            "history": [["A", "X", "A"], ["Z"]],
            "has_vocab_history": [True, False],
        })

    def test_metrics_match_hand_calculation(self):
        pages = {
            "one": [GeneratedRow(2, ["A"]), GeneratedRow(3, ["B"])],
            "two": [GeneratedRow(4, ["C"])],
        }
        content = np.array([[1., 0.], [0., 1.], [1., 0.]])
        report = evaluate_pages(self.meta, pages, vocab=FakeVocab(), content=content,
                                content_rows={"A": 0, "B": 1, "C": 2}, violations={"one": 0, "two": 0})
        self.assertAlmostEqual(report["map_at_12"], 1.0)
        self.assertAlmostEqual(report["map_at_12_has_vocab_history"], 1.0)
        self.assertAlmostEqual(report["page_recall"], 1.0)
        self.assertAlmostEqual(report["row_recall"], 1.0)
        self.assertAlmostEqual(report["diversity"], 0.5)
        self.assertEqual(report["rows_mean"], 1.5)
        self.assertEqual(report["items_mean"], 1.5)

    def test_repeat_order_and_baselines_only_harness(self):
        self.assertEqual(repeat_last_pages(self.meta)["one"], ["A", "X"])
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            mode = base / "hm" / "model" / "genpage2" / "validate"
            mode.mkdir(parents=True)
            self.meta.to_parquet(mode / "eval_meta.parquet")
            np.savez(mode / "eval.npz", ctx_tokens=np.array([1, 1]), ctx_content=np.array([-1, -1]),
                     ctx_offsets=np.array([0, 1, 2]))
            normal = base / "hm" / "normalized"
            normal.mkdir(parents=True)
            pd.DataFrame({"t_dat": ["2020-09-08", "2020-09-08"], "article_id": ["A", "C"]}).to_parquet(
                normal / "transactions.parquet"
            )
            report = run(argparse.Namespace(mode="validate", ckpt=None, limit=None, data_dir=str(base), out=None,
                                            batch=256, device="cpu", baselines_only=True))
        self.assertIn("repeat_last", report["results"])
        self.assertIn("popular_last_week", report["results"])
        self.assertIsNotNone(report["results"]["repeat_last"]["map_at_12_has_vocab_history"])
        self.assertIsNotNone(report["results"]["popular_last_week"]["map_at_12_has_vocab_history"])
        self.assertIsNone(report["results"]["repeat_last"]["ms_per_page"])
        self.assertIsNone(report["results"]["popular_last_week"]["ms_per_page"])

    def test_map_denominator_includes_missing_wrong_and_large_truth(self):
        meta = pd.DataFrame({
            "customer_id": ["hit", "wrong", "missing", "large"],
            "truth": [["A", "B"], ["C"], ["D"], [str(i) for i in range(13)]],
        })
        pages = {"hit": ["A", "X"], "wrong": ["X"], "large": ["0000000000"]}
        score, customers = map_at_12(meta, pages)
        # APs are 1/2, 0, 0, 1/12; all four customers are in the denominator.
        self.assertEqual(customers, 4)
        self.assertAlmostEqual(score, (0.5 + 1 / 12) / 4)


if __name__ == "__main__":
    unittest.main()
