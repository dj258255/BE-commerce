from __future__ import annotations

import gzip
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np
import pandas as pd

from genpage2 import paired_compare, ranker
from genpage2.decode import GeneratedRow
from genpage2.paired_compare import (average_precision_at_12, customer_metrics, load_any_pages,
                                     load_genpage_pages, load_ranker_pages, paired_bootstrap, verdict)
from genpage2.ranker import FEATURE_NAMES, Transactions, write_pages

REQUEST = pd.Timestamp("2020-09-09")


def _frame(rows):
    return pd.DataFrame(rows, columns=["t_dat", "customer_id", "article_id", "price"])


def _meta():
    return pd.DataFrame({
        "customer_id": ["c1", "c2", "c3"],
        "truth": [["A", "B"], ["C"], ["D"]],
        "history": [["A"], [], ["D"]],
        "has_vocab_history": [True, False, True],
    })


A_PAGES = {"c1": ["A", "X", "B"], "c2": ["X"], "c3": ["D"]}


def _shard(pages):
    return {"pages": [{"customer_id": customer,
                       "rows": [{"row_token": index + 1, "items": items}
                                for index, items in enumerate(rows)]}
                      for customer, rows in pages.items()]}


def _write(path: Path, payload) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    if str(path).endswith(".gz"):
        with gzip.open(path, "wt", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False)
    else:
        path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    return path


class AveragePrecisionTest(unittest.TestCase):
    def test_hand_calculated_per_customer_ap(self):
        # 정답 {A,B}: 1위 A(1/1) · 3위 B(2/3) -> (1 + 2/3) / 2.
        self.assertAlmostEqual(average_precision_at_12(["A", "X", "B"], {"A", "B"}), (1 + 2 / 3) / 2)
        self.assertAlmostEqual(average_precision_at_12(["X"], {"C"}), 0.0)
        # 정답이 12개보다 크면 분모가 12 다(Kaggle 규약).
        truth = {str(i) for i in range(13)}
        self.assertAlmostEqual(average_precision_at_12(["0000000000"], truth), 0.0 / 12)

    def test_customer_vectors_and_mean_match_evaluate(self):
        meta = _meta()
        vectors = customer_metrics(meta, A_PAGES)
        self.assertAlmostEqual(float(vectors["map_at_12"][0]), (1 + 2 / 3) / 2)
        self.assertAlmostEqual(float(vectors["map_at_12"].mean()), (1 + 2 / 3) / 2 / 3 + 0.0 + 1.0 / 3)
        self.assertAlmostEqual(float(vectors["page_hit"].mean()), (1 + 0 + 1) / 3)
        # c1 새 정답 {B}, c2 새 정답 {C}, c3 새 정답 없음.
        self.assertAlmostEqual(float(vectors["new_item_hit"].mean()), (1 + 0 + 0) / 3)
        checks = paired_compare._self_check(meta, A_PAGES, vectors)
        self.assertTrue(all(check["match"] for check in checks.values()))


class ShardReadingTest(unittest.TestCase):
    def test_reads_json_and_gz_and_flattens_rows_in_order(self):
        meta = _meta()
        shard = _shard({"c1": [["A", "X"], ["B"]], "c2": [["C"]], "c3": [["D"]]})
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            plain = _write(base / "b.json", shard)
            packed = _write(base / "b.json.gz", shard)
            from_plain = load_genpage_pages([plain], meta)
            from_packed = load_genpage_pages([packed], meta)
        self.assertEqual(from_plain, from_packed)
        self.assertEqual(from_plain["c1"], ["A", "X", "B"])

    def test_missing_customer_raises_for_both_inputs(self):
        meta = _meta()
        with self.assertRaises(ValueError):
            load_ranker_pages(_write(Path(tempfile.mkdtemp()) / "a.json",
                                     [{"customer_id": "c1", "items": ["A"]},
                                      {"customer_id": "c2", "items": ["C"]}]), meta)
        with tempfile.TemporaryDirectory() as temporary:
            shard = _write(Path(temporary) / "b.json",
                           _shard({"c1": [["A"]], "c2": [["C"]]}))
            with self.assertRaises(ValueError):
                load_genpage_pages([shard], meta)


class AnyPagesTest(unittest.TestCase):
    def test_a_accepts_flat_or_shard_and_keeps_rows(self):
        meta = _meta()
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            shard = _write(base / "a.json",
                           _shard({"c1": [["A", "X"], ["B"]], "c2": [["C"]], "c3": []}))
            objects = load_any_pages([shard], meta)
            self.assertEqual([row.items for row in objects["c1"]], [["A", "X"], ["B"]])
            flat = write_pages(base / "flat.json.gz", meta, A_PAGES)
            flat_pages = load_any_pages([flat], meta)
            self.assertEqual(flat_pages["c1"], ["A", "X", "B"])

    def test_customer_metrics_include_page_ndcg_and_row_hit(self):
        meta = _meta()
        with tempfile.TemporaryDirectory() as temporary:
            shard = _write(Path(temporary) / "b.json",
                           _shard({"c1": [["A"], ["B"]], "c2": [["C"]], "c3": [["D"]]}))
            pages = paired_compare.load_genpage_objects([shard], meta)
            vectors = customer_metrics(meta, pages)
        self.assertIn("page_ndcg", vectors)
        self.assertIn("row_hit", vectors)
        self.assertAlmostEqual(float(vectors["row_hit"][0]), 1.0)
        checks = paired_compare._self_check(meta, pages, vectors)
        self.assertTrue(all(check["match"] for check in checks.values()))


class BootstrapTest(unittest.TestCase):
    def _vectors(self, values):
        array = np.asarray(values, dtype=np.float64)
        return {"map_at_12": array, "page_hit": array, "new_item_hit": array}

    def test_constant_positive_difference_wins(self):
        intervals = paired_bootstrap(self._vectors([0.0, 0.0, 0.0]), self._vectors([1.0, 1.0, 1.0]),
                                     bootstrap=200, seed=7)
        for name in ("map_at_12", "page_hit", "new_item_hit"):
            self.assertAlmostEqual(intervals[name]["low"], 1.0)
            self.assertAlmostEqual(intervals[name]["high"], 1.0)
            self.assertEqual(intervals[name]["verdict"], "B 가 이겼다")

    def test_constant_negative_difference_loses_and_zero_ties(self):
        lost = paired_bootstrap(self._vectors([1.0, 1.0]), self._vectors([0.0, 0.0]),
                                bootstrap=50, seed=7)
        self.assertEqual(lost["map_at_12"]["verdict"], "B 가 졌다")
        tied = paired_bootstrap(self._vectors([0.5, 0.5]), self._vectors([0.5, 0.5]),
                                bootstrap=50, seed=7)
        self.assertEqual(tied["map_at_12"]["verdict"], "비겼다")
        self.assertAlmostEqual(tied["map_at_12"]["low"], 0.0)

    def test_is_reproducible_for_a_fixed_seed(self):
        rng = np.random.default_rng(1)
        a = self._vectors(rng.random(30))
        b = self._vectors(rng.random(30))
        first = paired_bootstrap(a, b, bootstrap=100, seed=7)
        second = paired_bootstrap(a, b, bootstrap=100, seed=7)
        self.assertEqual(first, second)

    def test_verdict_boundaries(self):
        self.assertEqual(verdict(0.001, 0.5), "B 가 이겼다")
        self.assertEqual(verdict(-0.5, 0.0), "비겼다")
        self.assertEqual(verdict(-0.5, -0.001), "B 가 졌다")


class WritePagesTest(unittest.TestCase):
    def test_write_pages_writes_customer_items_in_meta_order(self):
        meta = _meta()
        with tempfile.TemporaryDirectory() as temporary:
            path = write_pages(Path(temporary) / "pages.json.gz",
                               meta, {"c3": ["D"], "c1": ["A", "B"]})
            with gzip.open(path, "rt", encoding="utf-8") as handle:
                records = json.load(handle)
        self.assertEqual(records, [{"customer_id": "c1", "items": ["A", "B"]},
                                   {"customer_id": "c2", "items": []},
                                   {"customer_id": "c3", "items": ["D"]}])


class RunRankerPagesSinkTest(unittest.TestCase):
    def test_run_ranker_fills_pages_sink(self):
        txn = Transactions.from_frame(_frame([("2020-09-01", "c1", "A", 0.02)]))
        meta = pd.DataFrame({"customer_id": ["c1"], "truth": [["A"]]})
        empty = np.empty((0, len(FEATURE_NAMES)), dtype=np.float32)
        with patch("genpage2.ranker.build_training_week",
                   return_value=(empty, np.empty(0, dtype=np.int32), np.empty(0, dtype=np.int64), [])), \
             patch("genpage2.ranker.train_ranker",
                   return_value=(None, 1, np.zeros(len(FEATURE_NAMES)))), \
             patch("genpage2.ranker.rank_customers", return_value=({"c1": ["A", "B"]}, {})), \
             patch("genpage2.ranker.evaluate_pages",
                   return_value={"map_at_12": 0.0, "page_recall": 0.0,
                                 "new_item_recall": 0.0, "repeat_item_recall": 0.0}):
            sink: dict[str, list[str]] = {}
            report = ranker.run_ranker("validate", txn, meta, vocab=None, content=np.zeros((0, 2)),
                                       content_rows={}, ages={}, cfg=dict(ranker.RANKER_CONFIGS["base"]),
                                       pages_sink=sink)
        self.assertEqual(sink, {"c1": ["A", "B"]})
        self.assertNotIn("pages", report)


class RunEndToEndTest(unittest.TestCase):
    def test_run_reads_data_dir_and_reports_intervals(self):
        meta = _meta()
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            mode_dir = base / "hm" / "model" / "genpage2" / "validate"
            mode_dir.mkdir(parents=True)
            meta.to_parquet(mode_dir / "eval_meta.parquet")
            np.savez(mode_dir / "eval.npz", ctx_tokens=np.array([1, 1]),
                     ctx_content=np.array([-1, -1]), ctx_offsets=np.array([0, 1, 2]))
            a_path = write_pages(base / "a.json.gz", meta, A_PAGES)
            b_path = _write(base / "b.json", _shard({"c1": [["A", "X"], ["B"]],
                                                     "c2": [["C"]], "c3": [["D"]]}))
            report = paired_compare.run(a_path, [b_path], mode="validate", limit=None,
                                        bootstrap=100, seed=7, out=base / "paired.json",
                                        data_dir_path=base)
            self.assertTrue((base / "paired.json").exists())
        self.assertEqual(report["customers"], 3)
        self.assertAlmostEqual(report["means"]["a"]["map_at_12"], (1 + 2 / 3) / 2 / 3 + 1.0 / 3)
        self.assertTrue(all(check["match"] for check in report["checks"]["a"].values()))
        self.assertTrue(all(check["match"] for check in report["checks"]["b"].values()))
        self.assertAlmostEqual(report["difference"]["map_at_12"], 1.0 / 3)
        for name in ("map_at_12", "page_hit", "new_item_hit"):
            self.assertEqual(report["intervals"][name]["verdict"], "비겼다")

    def test_run_accepts_a_shard_and_reports_new_metrics(self):
        meta = _meta()
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            mode_dir = base / "hm" / "model" / "genpage2" / "validate"
            mode_dir.mkdir(parents=True)
            meta.to_parquet(mode_dir / "eval_meta.parquet")
            np.savez(mode_dir / "eval.npz", ctx_tokens=np.array([1, 1]),
                     ctx_content=np.array([-1, -1]), ctx_offsets=np.array([0, 1, 2]))
            a_shard = _write(base / "a.json",
                             _shard({"c1": [["A", "X"], ["B"]], "c2": [["C"]], "c3": [["D"]]}))
            b_shard = _write(base / "b.json",
                             _shard({"c1": [["A"], ["X", "B"]], "c2": [["C"]], "c3": [["D"]]}))
            report = paired_compare.run([a_shard], [b_shard], mode="validate", limit=None,
                                        bootstrap=50, seed=7, data_dir_path=base)
        self.assertEqual(report["a"], str(a_shard))
        for name in ("page_ndcg", "row_hit"):
            self.assertIn(name, report["means"]["a"])
            self.assertIn(name, report["means"]["b"])
            self.assertIn(name, report["difference"])
            self.assertIn(name, report["intervals"])
        self.assertTrue(all(check["match"] for check in report["checks"]["a"].values()))
        self.assertTrue(all(check["match"] for check in report["checks"]["b"].values()))


if __name__ == "__main__":
    unittest.main()
