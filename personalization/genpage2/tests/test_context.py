from __future__ import annotations

import unittest

import numpy as np
import torch

from genpage2.context import LEVELS, event_width, truncate, view
from genpage2.decode import PageDecoder


class FakeVocab:
    tokens = [
        "PAD", "BOS", "EOS", "SEP_PROFILE", "SEP_REQUEST", "SEP_HISTORY", "SEP_PAGE",
        "ITEM_FALLBACK", "ROW_FALLBACK", "AGE_20-24", "CLUB_ACTIVE", "NEWS_NONE", "FN_1", "ACTIVE_1",
        "DOW_3", "MONTH_9", "ACT_ONLINE", "AGO_4-7", "PRICE_2", "ITEM_A", "ITEM_B",
    ]

    def id(self, name):
        return self.tokens.index(name)


class ContextTest(unittest.TestCase):
    def setUp(self):
        self.vocab = FakeVocab()
        v = self.vocab
        self.full = np.asarray([
            v.id("BOS"), v.id("SEP_PROFILE"), v.id("AGE_20-24"), v.id("CLUB_ACTIVE"),
            v.id("NEWS_NONE"), v.id("FN_1"), v.id("ACTIVE_1"), v.id("SEP_REQUEST"),
            v.id("DOW_3"), v.id("MONTH_9"), v.id("SEP_HISTORY"),
            v.id("ITEM_A"), v.id("ACT_ONLINE"), v.id("AGO_4-7"), v.id("PRICE_2"),
            v.id("ITEM_B"), v.id("ACT_ONLINE"), v.id("AGO_4-7"), v.id("PRICE_2"), v.id("SEP_PAGE"),
        ])
        self.content = np.asarray([-1] * 11 + [21, -1, -1, -1, 22, -1, -1, -1, -1])

    def test_six_views_add_sources_one_at_a_time_and_keep_item_content_aligned(self):
        rendered = {level: view(self.full, self.content, self.vocab, level) for level in LEVELS}
        names = {level: [self.vocab.tokens[token] for token in values[0]] for level, values in rendered.items()}
        self.assertEqual([event_width(level) for level in LEVELS], [1, 2, 3, 4, 4, 4])
        self.assertEqual(names["items"], ["BOS", "SEP_HISTORY", "ITEM_A", "ITEM_B", "SEP_PAGE"])
        self.assertNotIn("ACT_ONLINE", names["items"])
        self.assertIn("ACT_ONLINE", names["+action"])
        self.assertIn("AGO_4-7", names["+time"])
        self.assertIn("PRICE_2", names["+price"])
        self.assertIn("AGE_20-24", names["+profile"])
        self.assertIn("DOW_3", names["full"])
        for before, after in zip(LEVELS, LEVELS[1:]):
            self.assertTrue(set(names[before]).issubset(names[after]))
        for level, (tokens, content) in rendered.items():
            with self.subTest(level=level):
                self.assertEqual(len(tokens), len(content))
                item_positions = [i for i, token in enumerate(tokens)
                                  if self.vocab.tokens[token].startswith("ITEM_")]
                # Filtering fields never reorders history: content row 21 is
                # still ITEM_A and 22 is still ITEM_B at every level.
                self.assertEqual([self.vocab.tokens[tokens[i]] for i in item_positions], ["ITEM_A", "ITEM_B"])
                self.assertEqual(content[item_positions].tolist(), [21, 22])
                self.assertLess(item_positions[0], item_positions[1])

    def test_truncate_removes_complete_oldest_event_at_every_level(self):
        for level in LEVELS:
            tokens, content = view(self.full, self.content, self.vocab, level)
            kept, kept_content = truncate(tokens, content, len(tokens) - event_width(level), vocab=self.vocab, level=level)
            names = [self.vocab.tokens[token] for token in kept]
            self.assertNotIn("ITEM_A", names, level)
            self.assertIn("ITEM_B", names, level)
            self.assertEqual(names[-1], "SEP_PAGE", level)
            b_at = names.index("ITEM_B")
            self.assertEqual(int(kept_content[b_at]), 22, level)

    def test_items_decoder_never_receives_profile_request_or_event_metadata(self):
        class DecoderVocab(FakeVocab):
            tokens = FakeVocab.tokens + ["ROW_REPEAT", "ROW_S1", "ITEM_C", "ITEM_D", "ITEM_E"]
            row_ids = range(21, 23)
            item_ids = range(19, 26)
            article_of = {19: "A", 20: "B", 23: "C", 24: "D", 25: "E"}

            def row_of(self, article):
                return 22

        class RecordingModel(torch.nn.Module):
            def __init__(self):
                super().__init__()
                self.seen = []

            def forward(self, tokens, content):
                self.seen.append(tokens.detach().cpu().tolist())
                return torch.zeros((*tokens.shape, 1), device=tokens.device)

            def logits(self, hidden):
                return torch.zeros((*hidden.shape[:2], len(DecoderVocab.tokens)), device=hidden.device)

        vocab, model = DecoderVocab(), RecordingModel()
        decoder = PageDecoder(model, vocab, {"A": 0, "B": 1, "C": 2, "D": 3, "E": 4}, "cpu", level="items")
        decoder.generate(self.full.tolist(), self.content.tolist(), history_articles=[], n_rows=1,
                         items_per_row=3, prefix=1, use_cache=False)
        first_prompt = model.seen[0][0]
        names = [vocab.tokens[token] for token in first_prompt]
        self.assertEqual(names, ["BOS", "SEP_HISTORY", "ITEM_A", "ITEM_B", "SEP_PAGE"])


if __name__ == "__main__":
    unittest.main()
