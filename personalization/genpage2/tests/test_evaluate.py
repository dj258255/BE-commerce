from __future__ import annotations

import argparse
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import numpy as np
import pandas as pd

from genpage2.decode import GeneratedRow
from genpage2.evaluate import _load_decoder, _repeat_pin, evaluate_pages, map_at_12, repeat_last_pages, run


class FakeVocab:
    def id(self, name):
        return 2

    def row_of(self, article):
        return {"A": 3, "B": 3, "C": 4}[article]

    def item(self, article):
        return {"A": 10, "B": 11, "C": 12}.get(article)


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

    def test_repeat_pin_requires_three_distinct_vocab_history_items(self):
        vocab = FakeVocab()
        self.assertEqual(_repeat_pin(["A", "B", "C", "A"], vocab), {0: 2})
        self.assertIsNone(_repeat_pin(["A", "B", "not-in-vocab"], vocab))

    def test_run_passes_per_customer_pin_and_candidates_to_decoder(self):
        meta = self.meta.copy()
        meta.at[0, "history"] = ["A", "B", "C", "A"]
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            mode = base / "hm" / "model" / "genpage2" / "validate"
            mode.mkdir(parents=True)
            meta.to_parquet(mode / "eval_meta.parquet")
            np.savez(mode / "eval.npz", ctx_tokens=np.array([1, 1]), ctx_content=np.array([-1, -1]),
                     ctx_offsets=np.array([0, 1, 2]))
            normal = base / "hm" / "normalized"
            normal.mkdir(parents=True)
            pd.DataFrame({"t_dat": ["2020-09-08", "2020-09-10"], "article_id": ["B", "C"]}).to_parquet(
                normal / "transactions.parquet"
            )
            decoder = MagicMock()
            captured = []

            def generate_batch(examples, **_kwargs):
                captured.extend(examples)
                return [([GeneratedRow(3, ["A"])], 0) for _ in examples]

            decoder.generate_batch.side_effect = generate_batch
            with patch("genpage2.evaluate._load_decoder", return_value=(
                decoder, FakeVocab(), np.array([[1.0, 0.0]]), {"A": 0}, "cpu",
            )):
                report = run(argparse.Namespace(
                    mode="validate", ckpt="checkpoint", limit=None, data_dir=str(base), out=None,
                    batch=256, device="cpu", baselines_only=False, pin_repeat=True, candidates="1,1",
                ))
        self.assertEqual(captured[0]["pinned"], {0: 2})
        self.assertIn("B", captured[0]["allowed_items"])
        self.assertEqual(report["options"], {"pin_repeat": True, "candidates": [1, 1]})
        self.assertGreater(report["candidates_mean"], 0)

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

    def test_checkpoint_context_level_is_given_to_decoder(self):
        class DecoderVocab:
            tokens = ["PAD", "BOS", "EOS", "SEP_PROFILE", "SEP_REQUEST", "SEP_HISTORY", "SEP_PAGE",
                      "ROW_REPEAT", "ROW_S1", "ITEM_A"]
            item_ids = range(9, 10)
            row_ids = range(7, 9)
            article_of = {9: "A"}

            def id(self, name):
                return self.tokens.index(name)

            def row_of(self, article):
                return 8

        model = MagicMock()
        model.cfg = SimpleNamespace(maxlen=17)
        model.to.return_value = model
        model.eval.return_value = model
        with patch("genpage2.vocab.Vocab.load", return_value=DecoderVocab()), \
             patch("genpage2.content.load_content", return_value=(np.zeros((1, 2)), {"A": 0})), \
             patch("genpage2.model.load_checkpoint", return_value=(model, SimpleNamespace(), {"context": "items"})):
            decoder, *_ = _load_decoder(Path("/mode"), Path("/checkpoint"), "cpu")
        self.assertEqual(decoder.level, "items")
        self.assertEqual(decoder.maxlen, 17)


if __name__ == "__main__":
    unittest.main()
