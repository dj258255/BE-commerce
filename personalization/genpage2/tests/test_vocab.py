import tempfile
import unittest
from pathlib import Path

import pandas as pd

from genpage2.vocab import Vocab, content_rows


def articles():
    return pd.DataFrame({"article_id": ["0000000010", "0000000002", "0000000001"], "section_no": [2.0, 1.0, 1.0]})


def transactions():
    return pd.DataFrame({"article_id": ["0000000001"] * 10 + ["0000000002"] * 9,
                         "t_dat": pd.Timestamp("2020-01-01")})


class VocabTest(unittest.TestCase):
    def test_layout_min_count_and_content_rows(self):
        vocab = Vocab.build(transactions(), articles())
        self.assertEqual(vocab.tokens[:10], ["PAD", "BOS", "EOS", "SEP_PROFILE", "SEP_REQUEST", "SEP_HISTORY", "SEP_PAGE", "ITEM_FALLBACK", "ROW_FALLBACK", "UNK"])
        self.assertIsNotNone(vocab.item("0000000001"))
        self.assertIsNone(vocab.item("0000000002"))
        self.assertLess(vocab.row_ids.start, vocab.item_ids.start)
        self.assertEqual(list(vocab.item_ids), [vocab.item("0000000001")])
        self.assertEqual(vocab.row_of("0000000010"), vocab.id("ROW_S2"))
        self.assertEqual(content_rows(articles()), {"0000000001": 0, "0000000002": 1, "0000000010": 2})

    def test_json_round_trip(self):
        vocab = Vocab.build(transactions(), articles())
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "vocab.json"
            vocab.save(path)
            loaded = Vocab.load(path)
        self.assertEqual(loaded.tokens, vocab.tokens)
        self.assertEqual(loaded.article_of, vocab.article_of)
        self.assertEqual(loaded.row_of("0000000002"), vocab.row_of("0000000002"))
