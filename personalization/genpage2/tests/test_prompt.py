from __future__ import annotations

import unittest

import pandas as pd

from genpage2.dataset import build_context
from genpage2.prompt import build_prompt
from genpage2.tests.test_dataset import tx, vocab_and_rows


class PromptTest(unittest.TestCase):
    def test_purchase_prompt_is_the_dataset_prompt(self):
        vocab, rows = vocab_and_rows()
        purchases = tx([
            ("2020-09-01", "c", "0000000001", 1, 12.0),
            ("2020-09-05", "c", "0000000001", 2, 20.0),
        ])
        expected = build_context(vocab, purchases, "2020-09-09", {"age": 30}, rows)
        actual, content, report = build_prompt(
            vocab, now="2020-09-09", profile={"age": 30}, content_rows=rows,
            events=[{"item": "0000000001", "at": "2020-09-01", "action": "STORE", "price": 12.0},
                    {"item": "0000000001", "at": "2020-09-05", "action": "ONLINE", "price": 20.0}],
        )
        self.assertEqual((actual, content), expected)
        self.assertEqual(report["missing"], {"at": 0, "action": 0, "price": 0, "profile": False, "now": False})

    def test_missing_values_and_session_are_reported_and_inserted_before_page(self):
        vocab, rows = vocab_and_rows()
        tokens, content, report = build_prompt(
            vocab, now=None, profile=None, content_rows=rows,
            events=[{"item": "1"}, {"item": "1", "action": "CLICK", "price": 2.0}],
        )
        page = tokens.index(vocab.id("SEP_PAGE"))
        self.assertEqual(tokens[page - 4:page], [vocab.item("0000000001"), vocab.id("ACT_CLICK"),
                                                  vocab.id("AGO_0-3"), vocab.id("PRICE_0")])
        self.assertEqual(content[page - 4], rows["0000000001"])
        self.assertEqual(report["missing"], {"at": 2, "action": 1, "price": 1, "profile": True, "now": True})

    def test_session_time_bucket_comes_from_at_and_zoned_instants_meet_naive_now(self):
        """X5(#328): a click from 5 days ago is AGO_4-7, not the request day."""
        vocab, rows = vocab_and_rows()
        tokens, _, report = build_prompt(
            vocab, now="2026-09-26T03:00:00Z", profile=None, content_rows=rows,
            events=[{"item": "1", "action": "VIEW", "at": "2026-09-21T23:59:00Z", "price": 2.0},
                    {"item": "1", "action": "CLICK", "at": "2026-09-26T02:59:00Z", "price": 2.0}],
        )
        page = tokens.index(vocab.id("SEP_PAGE"))
        self.assertEqual(tokens[page - 8:page],
                         [vocab.item("0000000001"), vocab.id("ACT_VIEW"), vocab.id("AGO_4-7"), vocab.id("PRICE_0"),
                          vocab.item("0000000001"), vocab.id("ACT_CLICK"), vocab.id("AGO_0-3"), vocab.id("PRICE_0")])
        # the request tokens follow `now`, not the training request date
        self.assertIn(vocab.id("DOW_5"), tokens)
        self.assertIn(vocab.id("MONTH_9"), tokens)
        self.assertEqual(report["missing"]["now"], False)

    def test_future_session_event_stays_in_request_day(self):
        vocab, rows = vocab_and_rows()
        tokens, _, _ = build_prompt(
            vocab, now="2026-09-26T03:00:00Z", profile=None, content_rows=rows,
            events=[{"item": "1", "action": "CLICK", "at": "2026-09-27T01:00:00Z", "price": 2.0}],
        )
        page = tokens.index(vocab.id("SEP_PAGE"))
        self.assertEqual(tokens[page - 2], vocab.id("AGO_0-3"))


if __name__ == "__main__":
    unittest.main()
