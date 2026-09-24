import tempfile
import time
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

from genpage2.dataset import (Example, _ago_token_ids, age_bucket, ago_bucket, build_context, build_page,
                              generate_examples, load_examples, stats, write_examples)
from genpage2.vocab import Vocab, content_rows


def catalogue():
    return pd.DataFrame({"article_id": ["0000000001", "0000000002", "0000000003", "0000000004", "0000000005", "0000000006", "0000000007", "0000000008", "0000000009"],
                         "section_no": [1.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0]})


def tx(rows):
    return pd.DataFrame(rows, columns=["t_dat", "customer_id", "article_id", "sales_channel_id"])


def vocab_and_rows():
    arts = catalogue()
    base = tx([("2020-01-01", "v", "0000000001", 1)] * 10)
    return Vocab.build(base, arts), content_rows(arts)


class DatasetTest(unittest.TestCase):
    def test_age_and_ago_boundaries(self):
        self.assertEqual([age_bucket(x) for x in [19.9, 20, 25, 30, 40, 50, 60, np.nan]], ["<20", "20-24", "25-29", "30-39", "40-49", "50-59", "60+", "NA"])
        r = pd.Timestamp("2020-10-01")
        self.assertEqual([ago_bucket(r, r - pd.Timedelta(days=x)) for x in [3, 4, 7, 8, 14, 15, 30, 31, 60, 61, 120, 121, 365, 366]],
                         ["AGO_0-3", "AGO_4-7", "AGO_4-7", "AGO_8-14", "AGO_8-14", "AGO_15-30", "AGO_15-30", "AGO_31-60", "AGO_31-60", "AGO_61-120", "AGO_61-120", "AGO_121-365", "AGO_121-365", "AGO_366+"])

    def test_context_filters_future_truncates_and_falls_back(self):
        vocab, rows = vocab_and_rows()
        events = tx([(pd.Timestamp("2020-01-01") + pd.Timedelta(days=i), "c", "0000000002" if i == 0 else "0000000001", 1 if i % 2 else 2) for i in range(62)] + [("2020-04-01", "c", "0000000001", 1)])
        tokens, content = build_context(vocab, events, "2020-03-05", {"age": 20}, rows)
        history_start = tokens.index(vocab.id("SEP_HISTORY")) + 1
        self.assertEqual((len(tokens) - history_start - 1) // 3, 60)
        self.assertNotIn(vocab.id("AGO_366+"), tokens)  # no future event's invalid age bucket
        self.assertEqual(tokens[history_start], vocab.id("ITEM_0000000001"))  # 60 newest, first fallback dropped
        fallback_events = tx([("2020-01-01", "c", "0000000002", 1)])
        ft, fc = build_context(vocab, fallback_events, "2020-01-02", {"age": 20}, rows)
        pos = ft.index(vocab.id("ITEM_FALLBACK"))
        self.assertEqual(fc[pos], rows["0000000002"])

    def test_context_has_no_future_tokens_or_content_rows(self):
        vocab, rows = vocab_and_rows()
        events = tx([
            ("2020-03-04", "c", "0000000001", 1),
            ("2020-03-06", "c", "0000000003", 2),
            ("2020-04-01", "c", "0000000004", 1),
        ])
        tokens, content = build_context(vocab, events, "2020-03-05", {"age": 20}, rows)
        self.assertEqual(tokens.count(vocab.id("SEP_HISTORY")), 1)
        self.assertEqual(tokens.count(vocab.id("ITEM_FALLBACK")), 0)
        self.assertEqual(content.count(rows["0000000001"]), 1)
        self.assertNotIn(rows["0000000003"], content)
        self.assertNotIn(rows["0000000004"], content)

    def test_page_repeat_order_caps_and_empty(self):
        vocab, _ = vocab_and_rows()
        history = tx([("2020-01-01", "c", "0000000001", 1)])
        purchases = tx([
            ("2020-02-01", "c", "0000000001", 1),  # repeat
            ("2020-02-02", "c", "0000000002", 1),  # outside vocabulary
            ("2020-02-03", "c", "0000000001", 1),
        ])
        page = build_page(vocab, history, purchases)
        self.assertEqual(page, [vocab.id("ROW_REPEAT"), vocab.item("0000000001"), vocab.id("EOS")])
        many = tx([(f"2020-02-{i + 1:02d}", "c", f"000000000{i + 1}", 1) for i in range(9)])
        # Teach all nine items into a separate temporary vocab; seven non-repeat rows prove 6-row cap.
        full = Vocab.build(pd.concat([tx([("2020-01-01", "v", a, 1)] * 10) for a in catalogue()["article_id"]]), catalogue())
        capped = build_page(full, history.iloc[0:0], many)
        self.assertEqual(sum(x in full.row_ids for x in capped), 6)
        self.assertIsNone(build_page(vocab, history.iloc[0:0], tx([("2020-02-01", "c", "0000000002", 1)])))

    def test_page_equal_count_rows_use_first_purchase_then_source_order(self):
        arts = catalogue().iloc[[0, 2]].copy()
        all_sales = pd.concat([tx([("2020-01-01", "v", article, 1)] * 10) for article in arts["article_id"]], ignore_index=True)
        vocab = Vocab.build(all_sales, arts)
        purchases = tx([
            ("2020-02-02", "c", "0000000003", 1),
            ("2020-02-03", "c", "0000000001", 1),
        ])
        page = build_page(vocab, purchases.iloc[0:0], purchases)
        self.assertEqual(page[:4], [vocab.row_of("0000000003"), vocab.item("0000000003"),
                                    vocab.row_of("0000000001"), vocab.item("0000000001")])
        same_day = tx([
            ("2020-02-04", "c", "0000000001", 1),
            ("2020-02-04", "c", "0000000003", 1),
        ])
        same_day_page = build_page(vocab, purchases.iloc[0:0], same_day)
        self.assertEqual(same_day_page[:4], [vocab.row_of("0000000001"), vocab.item("0000000001"),
                                            vocab.row_of("0000000003"), vocab.item("0000000003")])

    def test_customer_cap_eval_definition_and_round_trip(self):
        vocab, rows = vocab_and_rows()
        events = []
        # Each of the eight candidate weeks has one purchase of the sole vocab item.
        for k in range(1, 9):
            events.append((pd.Timestamp("2020-09-09") - pd.Timedelta(days=7 * k), "good", "0000000001", 1))
        events += [("2020-09-01", "eval", "0000000001", 1), ("2020-09-10", "eval", "0000000001", 1), ("2020-09-10", "bad", "0000000002", 1)]
        customers = pd.DataFrame({"customer_id": ["good", "eval", "bad"], "age": [20, 30, 40]})
        train, evaluation = generate_examples(vocab, tx(events), customers, "2020-09-09", rows)
        self.assertLessEqual(sum(x.customer_id == "good" for x in train), 4)
        self.assertEqual({x.customer_id for x in evaluation}, {"bad", "eval"})
        self.assertTrue(next(x for x in evaluation if x.customer_id == "eval").has_vocab_history)
        outside = next(x for x in evaluation if x.customer_id == "bad")
        self.assertFalse(outside.has_vocab_history)
        self.assertTrue(outside.page_empty)
        example = Example("x", pd.Timestamp("2020-01-01"), [1, 2], [-1, 3], [4, 5], ["0000000001"], ["0000000002"])
        with tempfile.TemporaryDirectory() as tmp:
            write_examples(Path(tmp), "train", [example])
            arrays, meta = load_examples(Path(tmp), "train")
        self.assertEqual(arrays["ctx_tokens"][arrays["ctx_offsets"][0]:arrays["ctx_offsets"][1]].tolist(), [1, 2])
        self.assertEqual(arrays["ctx_content"].tolist(), [-1, 3])
        self.assertEqual(arrays["page_tokens"][arrays["page_offsets"][0]:arrays["page_offsets"][1]].tolist(), [4, 5])
        self.assertEqual(meta.iloc[0].customer_id, "x")

    def test_customer_cap_keeps_recent_four_and_reservoir_is_seeded(self):
        vocab, rows = vocab_and_rows()
        events = []
        expected = []
        for k in range(1, 9):
            request = pd.Timestamp("2020-09-09") - pd.Timedelta(days=7 * k)
            purchase = request + pd.Timedelta(days=1)
            events.append((purchase, "cap", "0000000001", 1))
            expected.append(request)
        customers = pd.DataFrame({"customer_id": ["cap"], "age": [30]})
        first, _ = generate_examples(vocab, tx(events), customers, "2020-09-09", rows, max_train=2)
        second, _ = generate_examples(vocab, tx(events), customers, "2020-09-09", rows, max_train=2)
        requests = [x.request_date for x in first]
        self.assertEqual(requests, expected[:2])
        self.assertEqual([(x.request_date, x.truth) for x in first], [(x.request_date, x.truth) for x in second])

    def test_eval_empty_flags_and_npz_round_trip(self):
        vocab, rows = vocab_and_rows()
        customers = pd.DataFrame({"customer_id": ["cold"], "age": [30]})
        _, evaluation = generate_examples(vocab, tx([("2020-09-10", "cold", "0000000002", 1)]), customers, "2020-09-09", rows)
        self.assertEqual(len(evaluation), 1)
        empty = evaluation[0]
        self.assertEqual(empty.page_tokens.tolist(), [vocab.id("EOS")])
        self.assertFalse(empty.has_vocab_history)
        self.assertTrue(empty.page_empty)
        with tempfile.TemporaryDirectory() as tmp:
            write_examples(Path(tmp), "eval", [empty])
            arrays, meta = load_examples(Path(tmp), "eval")
        self.assertEqual(arrays["page_tokens"].tolist(), [vocab.id("EOS")])
        self.assertFalse(bool(meta.iloc[0].has_vocab_history))
        self.assertTrue(bool(meta.iloc[0].page_empty))

    def test_vector_ago_tokens_match_scalar_boundaries(self):
        vocab, _ = vocab_and_rows()
        request = pd.Timestamp("2020-10-01")
        day = request.to_datetime64().astype("datetime64[D]").astype(np.int64)
        distances = np.array([0, 3, 4, 7, 8, 14, 15, 30, 31, 60, 61, 120, 121, 365, 366], dtype=np.int64)
        actual = _ago_token_ids(vocab, int(day), day - distances)
        expected = np.array([vocab.id(ago_bucket(request, request - pd.Timedelta(days=int(distance)))) for distance in distances], dtype=np.int32)
        np.testing.assert_array_equal(actual, expected)

    def test_stats_matches_scalar_counts(self):
        vocab, _ = vocab_and_rows()
        example = Example("x", pd.Timestamp("2020-01-01"),
                          np.asarray([1, 2, 3], dtype=np.int32), np.asarray([-1, -1, -1], dtype=np.int32),
                          np.asarray([vocab.id("ROW_REPEAT"), vocab.item("0000000001"), vocab.id("EOS")], dtype=np.int32),
                          [], [])
        result = stats([example], [], vocab, 0.0)
        scalar_rows = sum(vocab.row_ids.start <= int(token) < vocab.row_ids.stop for token in example.page_tokens)
        scalar_items = sum(vocab.item_ids.start <= int(token) < vocab.item_ids.stop for token in example.page_tokens)
        self.assertEqual(result["page_rows"]["max"], scalar_rows)
        self.assertEqual(result["page_items"]["max"], scalar_items)
        self.assertEqual(result["context_length"]["max"], len(example.ctx_tokens))
        self.assertEqual(result["over_maxlen"], 0.0)

    def test_stats_200k_examples_is_vectorized(self):
        vocab, _ = vocab_and_rows()
        example = Example("x", pd.Timestamp("2020-01-01"),
                          np.asarray([1, 2, 3], dtype=np.int32), np.asarray([-1, -1, -1], dtype=np.int32),
                          np.asarray([vocab.id("ROW_REPEAT"), vocab.item("0000000001"), vocab.id("EOS")], dtype=np.int32),
                          [], [])
        examples = [example] * 200_000
        started = time.perf_counter()
        result = stats(examples, [], vocab, 0.0)
        elapsed = time.perf_counter() - started
        self.assertLess(elapsed, 2.0, f"stats took {elapsed:.3f}s")
        self.assertEqual(result["train_examples"], 200_000)
        self.assertEqual(result["page_rows"]["max"], 1)
        self.assertEqual(result["page_items"]["max"], 1)
