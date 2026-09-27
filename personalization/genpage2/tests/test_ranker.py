from __future__ import annotations

import json
import subprocess
import sys
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

from genpage2 import ranker
from genpage2.evaluate import _candidates_for
from genpage2.ranker import (FEATURE_NAMES, RANKER_CONFIGS, SOURCE_NAMES, Transactions, _copurchase_counts,
                             _day_number, build_features, build_ranker_candidates, build_training_week,
                             candidate_pool, run_ranker)

REQUEST = pd.Timestamp("2020-09-09")


class FakeVocab:
    def __init__(self, items, sections):
        self._items = set(items)
        self._sections = dict(sections)
        self.tokens = [f"ROW_S{s}" for s in sorted(set(sections.values()))]

    def item(self, article):
        return 1 if article in self._items else None

    def row_of(self, article):
        return self.tokens.index(f"ROW_S{self._sections[article]}")


def _content(articles, dimension=4, seed=0):
    rng = np.random.default_rng(seed)
    matrix = rng.normal(size=(len(articles), dimension)).astype(np.float32)
    return matrix, {article: index for index, article in enumerate(articles)}


def _frame(rows):
    return pd.DataFrame(rows, columns=["t_dat", "customer_id", "article_id", "price"])


def _candidate_only_cfg():
    """C1 · C5 만 남기고 나머지 출처를 끈다."""
    cfg = dict(RANKER_CONFIGS["base"])
    cfg.update({"c2_top": 0, "c3_top": 0, "c4_neighbors": 0})
    return cfg


class CandidateSourceTest(unittest.TestCase):
    def setUp(self):
        self.articles = ["0000000001", "0000000002", "0000000003", "0000000004", "0000000005"]
        self.vocab = FakeVocab(self.articles, {a: index + 1 for index, a in enumerate(self.articles)})
        self.content, self.content_rows = _content(self.articles)

    def _meta(self, customer, history=None):
        return pd.DataFrame({"customer_id": [customer], "history": [history or []]})

    def test_c1_uses_only_the_four_week_window(self):
        rows = [
            ("2020-07-26", "c1", "0000000001", 0.02),  # r-45: 4주 밖
            ("2020-08-30", "c1", "0000000002", 0.02),  # r-10: 4주 안
        ]
        candidates = build_ranker_candidates(self._meta("c1"), Transactions.from_frame(_frame(rows)), REQUEST,
                                             vocab=self.vocab, content=self.content, content_rows=self.content_rows,
                                             cfg=_candidate_only_cfg())
        self.assertEqual(candidates["c1"]["C1"], {"0000000002"})
        self.assertNotIn("0000000001", candidates["c1"]["C1"])

    def test_c5_is_global_same_day_copurchase_within_window(self):
        rows = [
            ("2020-08-30", "c1", "0000000002", 0.02),  # c1 이 최근에 산 질의 상품
            ("2020-09-04", "c2", "0000000002", 0.02),  # 다른 고객이 같은 날 B 와 C 를 함께 삼
            ("2020-09-04", "c2", "0000000003", 0.02),
            ("2020-07-20", "c3", "0000000002", 0.02),  # c5_days(28일) 밖의 동시 구매는 세지 않는다
            ("2020-07-20", "c3", "0000000005", 0.02),
        ]
        candidates = build_ranker_candidates(self._meta("c1"), Transactions.from_frame(_frame(rows)), REQUEST,
                                             vocab=self.vocab, content=self.content, content_rows=self.content_rows,
                                             cfg=_candidate_only_cfg())
        self.assertEqual(candidates["c1"]["C1"], {"0000000002"})
        self.assertEqual(candidates["c1"]["C5"], {"0000000003"})
        # C5 는 C1 의 부분집합이 아니다(고객이 산 적 없는 상품을 더한다).
        self.assertNotIn("0000000003", candidates["c1"]["C1"])

    def test_future_transactions_do_not_leak_into_candidates_or_features(self):
        articles = self.articles + ["0000000006"]
        vocab = FakeVocab(articles, {a: index + 1 for index, a in enumerate(articles)})
        content, content_rows = _content(articles)
        past = _frame([
            ("2020-08-20", "c1", "0000000001", 0.02),
            ("2020-09-05", "c1", "0000000002", 0.03),
            ("2020-08-01", "c2", "0000000003", 0.01),
            ("2020-08-10", "c2", "0000000006", 0.01),  # 0000000006 은 어휘에 있지만 c1 은 안 삼
        ])
        future = _frame([
            ("2020-09-10", "c1", "0000000006", 0.05),
            ("2020-09-11", "c1", "0000000001", 0.05),
            ("2020-09-12", "c2", "0000000003", 0.05),
        ])
        meta = self._meta("c1", history=["0000000001"])
        config = dict(RANKER_CONFIGS["base"])

        def build(txn):
            candidates = build_ranker_candidates(meta, txn, REQUEST, vocab=vocab, content=content,
                                                 content_rows=content_rows, cfg=config)
            pool = candidate_pool(candidates)
            matrix, rows = build_features(meta, txn, REQUEST, pool, candidates, vocab=vocab, content=content,
                                          content_rows=content_rows, ages={"c1": 30.0}, cfg=config)
            return candidates, pool, matrix, rows

        only_past = build(Transactions.from_frame(past))
        with_future = build(Transactions.from_frame(pd.concat([past, future], ignore_index=True)))
        self.assertEqual(only_past[0], with_future[0])
        self.assertEqual(only_past[1], with_future[1])
        self.assertEqual(only_past[3], with_future[3])
        np.testing.assert_array_equal(only_past[2], with_future[2])
        # c1 이 요청 전에 산 적 없는 상품은 재구매 후보에 없다(미래 구매도 마찬가지).
        self.assertNotIn("0000000006", only_past[0]["c1"]["C1"])


class FeatureTest(unittest.TestCase):
    def setUp(self):
        self.articles = ["0000000001", "0000000002"]
        self.vocab = FakeVocab(self.articles, {"0000000001": 1, "0000000002": 2})
        self.content, self.content_rows = _content(self.articles)

    def _features(self):
        # c1: A(r-3, 0.02), A(r-20, 0.04), B(r-50, 0.01)
        rows = [
            ("2020-09-06", "c1", "0000000001", 0.02),
            ("2020-08-20", "c1", "0000000001", 0.04),
            ("2020-07-21", "c1", "0000000002", 0.01),
        ]
        txn = Transactions.from_frame(_frame(rows))
        meta = pd.DataFrame({"customer_id": ["c1"], "history": [["0000000001"]]})
        selected = {"c1": ["0000000001"]}
        candidates = {"c1": {name: set() for name in SOURCE_NAMES}}
        matrix, feature_rows = build_features(meta, txn, REQUEST, selected, candidates, vocab=self.vocab,
                                              content=self.content, content_rows=self.content_rows,
                                              ages={"c1": 30.0}, cfg=RANKER_CONFIGS["base"])
        return matrix[0], feature_rows

    def test_hand_calculated_customer_item_features(self):
        vector, rows = self._features()
        self.assertEqual(rows, [("c1", "0000000001")])
        value = {name: vector[FEATURE_NAMES.index(name)] for name in FEATURE_NAMES}
        self.assertAlmostEqual(value["cust_count"], 3.0)
        self.assertAlmostEqual(value["cust_count_4w"], 2.0)
        self.assertAlmostEqual(value["cust_days_since_last"], 3.0)
        self.assertAlmostEqual(value["cust_avg_price"], (0.02 + 0.04 + 0.01) / 3)
        self.assertAlmostEqual(value["cust_item_count"], 2.0)
        self.assertAlmostEqual(value["cust_item_count_4w"], 2.0)
        self.assertAlmostEqual(value["cust_item_days_since_last"], 3.0)
        self.assertAlmostEqual(value["item_sales_1d"], 0.0)
        self.assertAlmostEqual(value["item_sales_7d"], 1.0)
        self.assertAlmostEqual(value["item_sales_28d"], 2.0)
        self.assertAlmostEqual(value["item_trend_7_28"], 4 / 3)
        self.assertAlmostEqual(value["item_price_median"], 0.03)
        self.assertAlmostEqual(value["item_days_since_first_sale"], 20.0)
        self.assertAlmostEqual(value["item_section"], 1.0)
        self.assertAlmostEqual(value["price_ratio"], 0.03 / ((0.02 + 0.04 + 0.01) / 3), places=5)
        self.assertAlmostEqual(value["cust_section_share"], 2 / 3)
        self.assertAlmostEqual(value["max_cosine_recent5"], 1.0, places=5)
        for name in SOURCE_NAMES:
            self.assertAlmostEqual(value[f"src_{name}"], 0.0)

    def test_source_flags_mark_membership(self):
        rows = [
            ("2020-09-06", "c1", "0000000001", 0.02),
            ("2020-08-20", "c1", "0000000002", 0.02),
        ]
        txn = Transactions.from_frame(_frame(rows))
        meta = pd.DataFrame({"customer_id": ["c1"], "history": [["0000000001"]]})
        selected = {"c1": ["0000000001"]}
        candidates = {"c1": {"C1": {"0000000001"}, "C2": {"0000000001"}, "C3": set(),
                             "C4": set(), "C5": {"0000000001"}}}
        matrix, _ = build_features(meta, txn, REQUEST, selected, candidates, vocab=self.vocab,
                                   content=self.content, content_rows=self.content_rows, ages={"c1": 30.0},
                                   cfg=RANKER_CONFIGS["base"])
        value = {name: matrix[0][FEATURE_NAMES.index(name)] for name in FEATURE_NAMES}
        self.assertAlmostEqual(value["src_C1"], 1.0)
        self.assertAlmostEqual(value["src_C2"], 1.0)
        self.assertAlmostEqual(value["src_C3"], 0.0)
        self.assertAlmostEqual(value["src_C4"], 0.0)
        self.assertAlmostEqual(value["src_C5"], 1.0)


class TrainingLabelAlignmentTest(unittest.TestCase):
    def test_labels_line_up_with_the_feature_rows(self):
        articles = [f"{i:010d}" for i in range(1, 7)]
        vocab = FakeVocab(articles, {a: index + 1 for index, a in enumerate(articles)})
        content, content_rows = _content(articles)
        # c1 은 요청 뒤(창 안)에 0000000005 를 산다. 후보 음성은 더 작은 id 라
        # 라벨을 상품 id 순으로 맞추지 않으면 어긋난다.
        rows = [
            ("2020-09-05", "c1", "0000000006", 0.02),
            ("2020-09-10", "c1", "0000000005", 0.02),
            ("2020-09-06", "c2", "0000000001", 0.02),
            ("2020-09-06", "c2", "0000000004", 0.02),
            ("2020-09-06", "c3", "0000000005", 0.02),
        ]
        txn = Transactions.from_frame(_frame(rows))
        ages = {str(c): 30.0 for c in txn.customers}
        matrix, labels, group, feature_rows = build_training_week(
            txn, REQUEST, vocab=vocab, content=content, content_rows=content_rows, ages=ages,
            cfg=dict(RANKER_CONFIGS["base"]), limit=None, rng=np.random.default_rng(7))
        self.assertGreater(len(group), 0)
        self.assertEqual(len(feature_rows), len(labels))
        truth = {"c1": {"0000000005"}}
        start = 0
        seen_positive = False
        for size in group.tolist():
            block = feature_rows[start:start + size]
            for (customer, article), label in zip(block, labels[start:start + size].tolist()):
                self.assertEqual(int(label), int(article in truth.get(customer, set())), (customer, article))
                seen_positive = seen_positive or int(label) == 1
            start += size
        self.assertTrue(seen_positive)


def synthetic_reports():
    """작은 합성 데이터에서 ``run_ranker`` 를 두 번 돌려 보고서 두 개를 돌려준다.

    LightGBM 과 torch 는 같은 프로세스에서 libomp 가 충돌한다(macOS). 테스트 스위트는
    torch 를 먼저 쓰므로, 이 함수는 **새 프로세스**에서만 부른다.
    """
    rng = np.random.default_rng(3)
    articles = [f"{i:010d}" for i in range(1, 25)]
    vocab = FakeVocab(articles, {a: (index % 4) + 1 for index, a in enumerate(articles)})
    content, content_rows = _content(articles, dimension=6, seed=1)
    customers = [f"c{i:03d}" for i in range(80)]
    start = pd.Timestamp("2020-08-01")
    rows = []
    for customer in customers:
        for _ in range(int(rng.integers(6, 40))):
            day = start + pd.Timedelta(days=int(rng.integers(0, 49)))
            rows.append((day, customer, articles[int(rng.integers(0, len(articles)))],
                         float(rng.uniform(0.01, 0.05))))
    txn = Transactions.from_frame(_frame(rows))
    request_day = _day_number(REQUEST)
    mask = (txn.day >= request_day) & (txn.day < request_day + 7)
    codes = np.unique(txn.cust[mask])
    meta = pd.DataFrame({
        "customer_id": [str(txn.customers[c]) for c in codes.tolist()],
        "history": [["0000000001", "0000000002"] for _ in codes],
        "truth": [[str(txn.articles[a]) for a in np.unique(txn.art[mask][txn.cust[mask] == c])]
                  for c in codes.tolist()],
    })
    ages = {str(c): float(30 + index % 15) for index, c in enumerate(txn.customers)}
    cfg = dict(RANKER_CONFIGS["base"])
    cfg.update({"n_estimators": 40, "max_train_customers": 60, "early_stopping": 10})
    cfg["_name"] = "base"

    def once():
        return run_ranker("validate", txn, meta, vocab=vocab, content=content, content_rows=content_rows,
                          ages=ages, cfg=dict(cfg))

    return {"first": once(), "second": once()}


class TrainPredictTest(unittest.TestCase):
    def test_training_and_prediction_run_and_are_seed_reproducible(self):
        root = Path(__file__).resolve().parents[2]
        code = ("import json; from genpage2.tests.test_ranker import synthetic_reports; "
                "print(json.dumps(synthetic_reports()))")
        completed = subprocess.run([sys.executable, "-c", code], cwd=str(root),
                                   capture_output=True, text=True, timeout=600)
        self.assertEqual(completed.returncode, 0, completed.stderr[-2000:])
        reports = json.loads(completed.stdout.strip().splitlines()[-1])
        first, second = reports["first"], reports["second"]
        self.assertGreater(first["train_rows"], 0)
        self.assertGreaterEqual(first["iterations"], 1)
        self.assertEqual(first["metrics"], second["metrics"])
        self.assertEqual(first["feature_importance_top20"], second["feature_importance_top20"])
        self.assertGreaterEqual(first["metrics"]["map_at_12"], 0.0)
        self.assertLessEqual(len(first["feature_importance_top20"]), 20)


class EvaluateRankerCandidatesTest(unittest.TestCase):
    def setUp(self):
        self.articles = ["0000000001", "0000000002", "0000000003", "0000000004"]
        self.vocab = FakeVocab(self.articles, {a: index + 1 for index, a in enumerate(self.articles)})
        self.content, self.content_rows = _content(self.articles)
        self.rows = [
            ("2020-08-20", "c1", "0000000001", 0.02),
            ("2020-09-05", "c1", "0000000002", 0.02),
            ("2020-09-04", "c2", "0000000001", 0.02),
            ("2020-09-04", "c2", "0000000003", 0.02),
            ("2020-08-01", "c2", "0000000004", 0.01),
        ]
        self.meta = pd.DataFrame({"customer_id": ["c1", "c2"],
                                  "history": [["0000000001"], ["0000000001", "0000000004"]]})

    def test_ranker_candidates_pool_matches_the_ranker(self):
        tx = _frame(self.rows)
        given = _candidates_for(self.meta, tx, REQUEST, vocab=self.vocab, content=self.content,
                                content_rows=self.content_rows, candidate_config=None, similar_config=None,
                                ranker_candidates=True)
        expected = candidate_pool(build_ranker_candidates(
            self.meta, Transactions.from_frame(tx), REQUEST, vocab=self.vocab, content=self.content,
            content_rows=self.content_rows, cfg=dict(RANKER_CONFIGS[ranker.DEFAULT_CONFIG])))
        self.assertEqual(set(given), set(expected))
        for customer in expected:
            self.assertEqual(given[customer], expected[customer])
            self.assertGreater(len(given[customer]), 0)

    def test_default_candidates_option_is_unchanged(self):
        tx = _frame(self.rows)
        self.assertIsNone(_candidates_for(self.meta, tx, REQUEST, vocab=self.vocab, content=self.content,
                                          content_rows=self.content_rows, candidate_config=None,
                                          similar_config=None))


class CopurchaseUnitTest(unittest.TestCase):
    def test_counts_respect_window_and_window_size(self):
        articles = ["0000000001", "0000000002", "0000000003"]
        vocab = FakeVocab(articles, {a: index + 1 for index, a in enumerate(articles)})
        rows = [
            ("2020-09-05", "c1", "0000000001", 0.02),
            ("2020-09-05", "c1", "0000000002", 0.02),
            ("2020-07-01", "c1", "0000000001", 0.02),
            ("2020-07-01", "c1", "0000000003", 0.02),
        ]
        txn = Transactions.from_frame(_frame(rows))
        cfg = dict(RANKER_CONFIGS["base"])
        query = np.array([txn.article_codes["0000000001"]], dtype=np.int64)
        counts = _copurchase_counts(txn, _day_number(REQUEST), query, cfg)
        bucket = counts[int(query[0])]
        self.assertEqual(bucket[txn.article_codes["0000000002"]], 1)
        self.assertNotIn(txn.article_codes["0000000003"], bucket)


if __name__ == "__main__":
    unittest.main()
