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


if __name__ == "__main__":
    unittest.main()
