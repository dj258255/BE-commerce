from __future__ import annotations

import unittest
from pathlib import Path

import numpy as np
import torch

from genpage2.decode import GeneratedRow, PageDecoder, truncate_context
from genpage2.model import GenPageV2, ModelConfig


class FakeVocab:
    tokens = ["PAD", "SEP_HISTORY", "SEP_PAGE", "ROW_REPEAT", "ROW_A", "ROW_B", "ITEM_A", "ITEM_B", "ITEM_C", "ITEM_D", "ITEM_E", "ITEM_F"]
    item_ids = range(6, 12)
    row_ids = range(3, 6)
    article_of = {6: "A", 7: "B", 8: "C", 9: "D", 10: "E", 11: "F"}

    def id(self, name):
        return self.tokens.index(name)

    def item(self, article):
        return {v: k for k, v in self.article_of.items()}.get(article)

    def row_of(self, article):
        return {"A": 4, "B": 4, "C": 4, "D": 5, "E": 5, "F": 5}[article]


class FakeModel(torch.nn.Module):
    """Always rates invalid IDs highest; masking must make them harmless."""
    def __init__(self):
        super().__init__()
        self.vocab_size = 12

    def forward(self, tokens, content_idx):
        return tokens.float().unsqueeze(-1)

    def logits(self, hidden):
        b, t, _ = hidden.shape
        result = torch.full((b, t, self.vocab_size), -20.0, device=hidden.device)
        result[..., 0] = 1000.0                 # PAD: must never leak
        result[..., 2] = 999.0                 # SEP_PAGE: must never leak
        result[..., 6] = 90.0                  # A, then B/C/D/E priority
        result[..., 7] = 80.0
        result[..., 8] = 70.0
        result[..., 9] = 60.0
        result[..., 10] = 50.0
        result[..., 11] = 45.0
        result[..., 4] = 40.0                  # row A before row B/repeat
        result[..., 5] = 30.0
        result[..., 3] = 20.0
        # After a prefix item, the bulk distribution deliberately reverses A
        # row's remaining items so the hybrid assertion has a sharp oracle.
        last = hidden[..., 0].long()
        for bi in range(b):
            for ti in range(t):
                if int(last[bi, ti]) == 6:
                    result[bi, ti, 8] = 95.0
                    result[bi, ti, 7] = 85.0
        return result


class DecodeTest(unittest.TestCase):
    def setUp(self):
        self.vocab = FakeVocab()
        self.decoder = PageDecoder(FakeModel(), self.vocab, {a: n for n, a in enumerate("ABCDEF")}, "cpu")
        self.context = [1, 2]
        self.content = [-1, -1]

    def test_masks_invalid_duplicate_exclusions_and_previous_page(self):
        rows, violations = self.decoder.generate(
            self.context, self.content, history_articles=["A", "D"], prev_page=[4, 6],
            exclude_items={"B"}, exclude_rows={3}, n_rows=3, items_per_row=3, prefix=1,
        )
        self.assertEqual(violations, 0)
        self.assertTrue(rows)
        all_items = [item for row in rows for item in row.items]
        self.assertNotIn("A", all_items)
        self.assertNotIn("B", all_items)
        self.assertEqual(len(all_items), len(set(all_items)))
        self.assertNotIn(4, [row.row_token for row in rows])
        self.assertNotIn(3, [row.row_token for row in rows])
        for row in rows:
            for item in row.items:
                self.assertEqual(self.vocab.row_of(item), row.row_token)

    def test_pinned_minimum_and_early_stop(self):
        rows, _ = self.decoder.generate(self.context, self.content, history_articles=[], pinned={0: 5},
                                        n_rows=2, items_per_row=3)
        self.assertEqual(rows[0].row_token, 5)
        # A and B are excluded, leaving section A with one and B with two: no
        # row has the required three allowed items.
        rows, _ = self.decoder.generate(self.context, self.content, history_articles=[],
                                        exclude_items={"A", "B", "D", "E"}, n_rows=2)
        self.assertEqual(rows, [])

    def test_hybrid_bulk_uses_last_prefix_distribution(self):
        rows, _ = self.decoder.generate(self.context, self.content, history_articles=[], pinned={0: 4},
                                        n_rows=1, items_per_row=3, prefix=1)
        self.assertEqual(rows[0].items, ["A", "C", "B"])

    def test_batch_equals_single_and_seeded_sampling(self):
        examples = [
            {"ctx_tokens": [1, 2], "ctx_content": [-1, -1], "history_articles": []},
            {"ctx_tokens": [1, 3, 4, 5, 6, 7, 8, 2],
             "ctx_content": [-1, -1, -1, -1, -1, -1, -1, -1], "history_articles": ["A", "B", "C"]},
            {"ctx_tokens": [1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 3, 4, 5, 6, 7, 8, 2],
             "ctx_content": [-1] * 17, "history_articles": ["D", "E"]},
        ]
        kwargs = {"n_rows": 1, "items_per_row": 3, "prefix": 1}
        self.assertEqual(self.decoder.generate_batch(examples, **kwargs),
                         [self.decoder.generate(**example, **kwargs) for example in examples])
        first = self.decoder.generate(**examples[0], **kwargs, temperature=0.8,
                                      generator=torch.Generator().manual_seed(77))
        second = self.decoder.generate(**examples[0], **kwargs, temperature=0.8,
                                       generator=torch.Generator().manual_seed(77))
        self.assertEqual(first, second)

    def test_missing_page_separator_is_added_before_projection_for_single_and_batch(self):
        class RecordingDecoder(PageDecoder):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, **kwargs)
                self.projected = []

            def _project_and_trim(self, tokens, content, keep):
                self.projected.append((list(tokens), list(content)))
                return super()._project_and_trim(tokens, content, keep)

        decoder = RecordingDecoder(FakeModel(), self.vocab, {a: n for n, a in enumerate("ABCDEF")}, "cpu")
        kwargs = {"history_articles": [], "n_rows": 1, "items_per_row": 3, "prefix": 1}
        # A serving request may end at SEP_HISTORY.  It must reach the shared
        # projector as a valid prompt rather than failing in context.truncate.
        single = decoder.generate([1], [-1], **kwargs)
        batched = decoder.generate_batch([{"ctx_tokens": [1], "ctx_content": [-1], "history_articles": []}],
                                         n_rows=1, items_per_row=3, prefix=1)
        self.assertEqual(single, batched[0])
        self.assertEqual(decoder.projected[0], ([1, 2], [-1, -1]))
        self.assertEqual(decoder.projected[1], ([1, 2], [-1, -1]))

    def test_existing_page_separator_keeps_exact_generation(self):
        kwargs = {"history_articles": [], "n_rows": 1, "items_per_row": 3, "prefix": 1}
        expected = ([GeneratedRow(4, ["A", "C", "B"])], 0)
        self.assertEqual(self.decoder.generate([1, 2], [-1, -1], **kwargs), expected)
        self.assertEqual(self.decoder.generate_batch(
            [{"ctx_tokens": [1, 2], "ctx_content": [-1, -1], "history_articles": []}],
            n_rows=1, items_per_row=3, prefix=1,
        ), [expected])

    def test_truncate_keeps_prefix_page_and_whole_events(self):
        tokens = [10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23]
        content = list(range(len(tokens)))
        # SEP_HISTORY=12, two complete events, SEP_PAGE=19, then page token.
        kept, kept_content = truncate_context(tokens, content, 10, sep_history=12, sep_page=19)
        self.assertEqual(kept[:4], [10, 11, 12, 19])
        self.assertEqual(kept[4:], [20, 21, 22, 23])
        self.assertEqual(kept_content, [0, 1, 2, 9, 10, 11, 12, 13])

    def test_violation_checker_counts_deliberately_bad_page(self):
        bad = [GeneratedRow(4, ["A", "A", "D"]), GeneratedRow(4, ["B"])]
        self.assertEqual(self.decoder._violations(bad, history_articles=[], prev_page=None,
                                                   exclude_items={"D"}, exclude_rows=set()), 4)

    def test_real_genpage_model_batch_equals_single(self):
        cfg = ModelConfig(vocab_size=len(self.vocab.tokens), dim=8, layers=1, heads=2,
                          ffn=16, dropout=0.0, maxlen=64, content_dim=384)
        torch.manual_seed(3)
        model = GenPageV2(cfg, torch.zeros((6, 384)), tokens=self.vocab.tokens).eval()
        decoder = PageDecoder(model, self.vocab, {a: n for n, a in enumerate("ABCDEF")}, "cpu")
        examples = [
            {"ctx_tokens": [1, 2], "ctx_content": [-1, -1], "history_articles": []},
            {"ctx_tokens": [1, 3, 4, 5, 6, 7, 8, 2],
             "ctx_content": [-1] * 8, "history_articles": ["A", "B"]},
        ]
        kwargs = {"n_rows": 1, "items_per_row": 3, "prefix": 1}
        self.assertEqual(decoder.generate_batch(examples, **kwargs),
                         [decoder.generate(**example, **kwargs) for example in examples])
        self.assertEqual(decoder.generate(**examples[1], **kwargs, use_cache=True),
                         decoder.generate(**examples[1], **kwargs, use_cache=False))

    def test_real_forward_cached_matches_forward_with_ragged_batch(self):
        cfg = ModelConfig(vocab_size=len(self.vocab.tokens), dim=8, layers=1, heads=2,
                          ffn=16, dropout=0.0, maxlen=64, content_dim=384)
        torch.manual_seed(9)
        model = GenPageV2(cfg, torch.zeros((6, 384)), tokens=self.vocab.tokens).eval()
        prompt = torch.tensor([[1, 3, 4, 5, 0, 0], [1, 3, 4, 5, 6, 7]])
        prompt_content = torch.full_like(prompt, -1)
        cached_hidden, cache = model.forward_cached(prompt, prompt_content)
        for row, length in enumerate((4, 6)):
            expected = model(prompt[row:row + 1, :length], torch.full((1, length), -1))
            self.assertLess((cached_hidden[row, length - 1] - expected[0, -1]).abs().max().item(), 1e-5)
        one, cache = model.forward_cached(torch.tensor([[8], [8]]), torch.full((2, 1), -1), cache)
        two, cache = model.forward_cached(torch.tensor([[9], [9]]), torch.full((2, 1), -1), cache)
        for row, length in enumerate((6, 8)):
            source = prompt[row:row + 1, :length - 2].tolist()[0] + [8, 9]
            expected = model(torch.tensor([source]), torch.full((1, len(source)), -1))
            self.assertLess((two[row, 0] - expected[0, -1]).abs().max().item(), 1e-5)
        # A two-token chunk must be equivalent to the two one-token calls.
        chunk, _ = model.forward_cached(torch.tensor([[8, 9], [8, 9]]), torch.full((2, 2), -1),
                                         model.forward_cached(prompt, prompt_content)[1])
        self.assertLess((chunk[:, 1] - two[:, 0]).abs().max().item(), 1e-5)


if __name__ == "__main__":
    unittest.main()
