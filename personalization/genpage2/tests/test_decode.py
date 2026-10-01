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

    def test_allowed_items_mask_products_and_row_eligibility(self):
        rows, violations = self.decoder.generate(
            self.context, self.content, history_articles=[], allowed_items={"A", "B", "C"},
            n_rows=2, items_per_row=3,
        )
        self.assertEqual(violations, 0)
        self.assertEqual([row.row_token for row in rows], [4])
        self.assertEqual(set(rows[0].items), {"A", "B", "C"})
        # B 행에는 카탈로그상 세 상품이 있지만 후보에는 두 개뿐이다.
        rows, _ = self.decoder.generate(
            self.context, self.content, history_articles=[], pinned={0: 5},
            allowed_items={"A", "B", "C", "D", "E"}, n_rows=1, items_per_row=3,
        )
        self.assertEqual(rows, [])

    def test_allowed_mask_matches_python_set_membership_filtering(self):
        # 후보 집합을 켠 경로는 종전에 상품 토큰마다 파이썬 반복으로
        # ``article in allowed_items`` 를 셌다. 어휘 크기 마스크 인덱싱이
        # 모든 행에서 그 결과와 같아야 한다.
        allowed = {"A", "C", "D", "F"}
        mask = self.decoder._allowed_mask(allowed)
        self.assertIsNone(self.decoder._allowed_mask(None))
        used = torch.zeros(len(self.vocab.tokens), dtype=torch.bool, device="cpu")
        used[6] = True                                   # A 는 허용이지만 이미 썼다.
        history = ["A", "D", "F"]
        for row in self.vocab.row_ids:
            item_ids = (self.decoder._history_ids(history) if row == self.decoder._row_repeat
                        else self.decoder._row_item_ids[row])
            keep = torch.tensor([self.vocab.article_of[int(token)] in allowed for token in item_ids],
                                dtype=torch.bool)
            expected = item_ids[keep]
            expected = expected[~used[expected]] if expected.numel() else expected
            self.assertEqual(self.decoder._items_for_row(row, history, used, mask).tolist(),
                             expected.tolist())

    def test_candidate_generation_unchanged_single_and_batch(self):
        # 고치기 전 코드로 뽑은 정확한 결과를 고정한다(작은 실제 모델 예).
        cfg = ModelConfig(vocab_size=len(self.vocab.tokens), dim=8, layers=1, heads=2,
                          ffn=16, dropout=0.0, maxlen=64, content_dim=384)
        torch.manual_seed(17)
        model = GenPageV2(cfg, torch.zeros((6, 384)), tokens=self.vocab.tokens).eval()
        decoder = PageDecoder(model, self.vocab, {a: n for n, a in enumerate("ABCDEF")}, "cpu")
        examples = [
            {"ctx_tokens": [1, 2], "ctx_content": [-1, -1], "history_articles": [],
             "allowed_items": {"A", "B", "C"}},
            {"ctx_tokens": [1, 3, 4, 5, 2], "ctx_content": [-1] * 5, "history_articles": ["A", "D"],
             "allowed_items": {"A", "B", "D", "E", "F"}},
        ]
        kwargs = {"n_rows": 2, "items_per_row": 3, "prefix": 1}
        expected = [([GeneratedRow(4, ["B", "C", "A"])], 0),
                    ([GeneratedRow(5, ["F", "E", "D"])], 0)]
        singles = [decoder.generate(**example, **kwargs) for example in examples]
        self.assertEqual(singles, expected)
        self.assertEqual(decoder.generate_batch(examples, **kwargs), expected)

    def test_hybrid_bulk_uses_last_prefix_distribution(self):
        rows, _ = self.decoder.generate(self.context, self.content, history_articles=[], pinned={0: 4},
                                        n_rows=1, items_per_row=3, prefix=1)
        self.assertEqual(rows[0].items, ["A", "C", "B"])

    def test_pinned_items_fill_repeat_row_in_order_then_model_fills(self):
        rows, violations = self.decoder.generate(
            self.context, self.content, history_articles=["D", "E", "F", "C"], pinned={0: 3},
            pinned_items={0: ["F", "E"]}, n_rows=1, items_per_row=4, prefix=1,
        )
        self.assertEqual(violations, 0)
        self.assertEqual(rows[0].row_token, 3)
        # 주어진 순서가 앞에 오고, 남은 칸은 모델이 이력 안에서 채운다.
        self.assertEqual(rows[0].items[:2], ["F", "E"])
        self.assertEqual(set(rows[0].items), {"C", "D", "E", "F"})
        self.assertEqual(len(rows[0].items), 4)

    def test_pinned_items_short_prefix_is_topped_up_to_items_per_row(self):
        rows, violations = self.decoder.generate(
            self.context, self.content, history_articles=["D", "E", "F"], pinned={0: 3},
            pinned_items={0: ["F"]}, n_rows=1, items_per_row=3, prefix=1,
        )
        self.assertEqual(violations, 0)
        self.assertEqual(rows[0].items[0], "F")
        self.assertEqual(len(rows[0].items), 3)

    def test_pinned_items_are_ignored_when_default_is_none(self):
        baseline = self.decoder.generate(self.context, self.content, history_articles=["D", "E", "F"],
                                         pinned={0: 3}, n_rows=1, items_per_row=3, prefix=1)
        self.assertEqual(self.decoder.generate(self.context, self.content, history_articles=["D", "E", "F"],
                                               pinned={0: 3}, pinned_items=None, n_rows=1, items_per_row=3,
                                               prefix=1), baseline)

    def test_min_row_items_default_keeps_previous_behaviour(self):
        baseline = self.decoder.generate(self.context, self.content, history_articles=[],
                                         n_rows=2, items_per_row=3, prefix=1)
        self.assertEqual(self.decoder.generate(self.context, self.content, history_articles=[],
                                               min_row_items=None, n_rows=2, items_per_row=3,
                                               prefix=1), baseline)
        # 3 은 지금까지의 고정값이라 명시해도 같다.
        self.assertEqual(self.decoder.generate(self.context, self.content, history_articles=[],
                                               min_row_items=3, n_rows=2, items_per_row=3,
                                               prefix=1), baseline)

    def test_min_row_items_one_allows_a_single_product_row(self):
        # FakeModel 은 행 A(4)=40 > B(5)=30 이고, 허용 상품을 A · D 로 좁히면 행마다
        # 쓸 수 있는 상품이 하나뿐이다. 최소 1개면 짧은 행이 나오고, 기본 3개면 막힌다.
        rows, violations = self.decoder.generate(self.context, self.content, history_articles=[],
                                                 allowed_items={"A", "D"}, min_row_items=1,
                                                 n_rows=2, items_per_row=3, prefix=1)
        self.assertEqual(violations, 0)
        self.assertEqual([(row.row_token, row.items) for row in rows], [(4, ["A"]), (5, ["D"])])
        self.assertEqual(self.decoder.generate(self.context, self.content, history_articles=[],
                                               allowed_items={"A", "D"}, n_rows=2, items_per_row=3,
                                               prefix=1)[0], [])

    def test_batch_min_row_items_matches_single(self):
        examples = [
            {"ctx_tokens": [1, 2], "ctx_content": [-1, -1], "history_articles": [],
             "allowed_items": {"A", "D"}},
            {"ctx_tokens": [1, 3, 4, 5, 2], "ctx_content": [-1] * 5, "history_articles": ["A"],
             "allowed_items": {"A", "D", "E"}},
        ]
        kwargs = {"min_row_items": 1, "n_rows": 2, "items_per_row": 3, "prefix": 1}
        self.assertEqual(self.decoder.generate_batch(examples, **kwargs),
                         [self.decoder.generate(**example, **kwargs) for example in examples])

    def test_row_items_only_stops_the_row_at_its_prefix(self):
        # 허용 상품 마스크만으로는 이력 상품(A)이 자기 섹션 행(4)의 남은 칸을 채울 수
        # 있다. row_items_only 는 그 채우기를 막아 행을 row_items 앞에서 끝낸다.
        rows, violations = self.decoder.generate(
            self.context, self.content, history_articles=["A"],
            allowed_items={"A", "B", "C", "D"}, allowed_rows={4, 5},
            row_items={4: ["B", "C"], 5: ["D"]}, min_row_items=1, row_items_only=True,
            n_rows=2, items_per_row=3, prefix=1,
        )
        self.assertEqual(violations, 0)
        self.assertEqual([(row.row_token, row.items) for row in rows], [(4, ["B", "C"]), (5, ["D"])])

    def test_row_items_only_false_matches_previous_behaviour(self):
        baseline = self.decoder.generate(self.context, self.content, history_articles=[],
                                         allowed_rows={5}, row_items={5: ["F"]},
                                         n_rows=1, items_per_row=3, prefix=1)
        self.assertEqual(baseline[0][0].items, ["F", "D", "E"])
        self.assertEqual(self.decoder.generate(self.context, self.content, history_articles=[],
                                               allowed_rows={5}, row_items={5: ["F"]},
                                               row_items_only=False, n_rows=1, items_per_row=3,
                                               prefix=1), baseline)

    def test_batch_row_items_only_matches_single(self):
        examples = [
            {"ctx_tokens": [1, 2], "ctx_content": [-1, -1], "history_articles": ["A"],
             "allowed_items": {"A", "B", "C", "D"}, "allowed_rows": {4, 5},
             "row_items": {4: ["B", "C"], 5: ["D"]}},
            {"ctx_tokens": [1, 2], "ctx_content": [-1, -1], "history_articles": [],
             "allowed_items": {"D", "E"}, "allowed_rows": {5}, "row_items": {5: ["E"]}},
        ]
        kwargs = {"min_row_items": 1, "row_items_only": True, "n_rows": 2, "items_per_row": 3,
                  "prefix": 1}
        self.assertEqual(self.decoder.generate_batch(examples, **kwargs),
                         [self.decoder.generate(**example, **kwargs) for example in examples])

    def test_allowed_rows_restricts_row_choice(self):
        unrestricted, _ = self.decoder.generate(self.context, self.content, history_articles=[],
                                                n_rows=1, items_per_row=3, prefix=1)
        self.assertEqual(unrestricted[0].row_token, 4)
        restricted, _ = self.decoder.generate(self.context, self.content, history_articles=[],
                                              allowed_rows={5}, n_rows=1, items_per_row=3, prefix=1)
        self.assertEqual(restricted[0].row_token, 5)
        self.assertEqual(set(restricted[0].items), {"D", "E", "F"})

    def test_row_items_fill_the_chosen_row_in_order(self):
        rows, violations = self.decoder.generate(
            self.context, self.content, history_articles=[], allowed_rows={5},
            row_items={5: ["F"]}, n_rows=1, items_per_row=3, prefix=1,
        )
        self.assertEqual(violations, 0)
        # 주어진 순서가 앞에 오고, 남은 칸은 그 행의 점수 순(D, E)으로 모델이 채운다.
        self.assertEqual(rows[0].items, ["F", "D", "E"])

    def test_row_items_skip_duplicates_and_out_of_row_articles(self):
        rows, violations = self.decoder.generate(
            self.context, self.content, history_articles=[], allowed_rows={5},
            row_items={5: ["D", "D", "A", "E"]}, n_rows=1, items_per_row=3, prefix=1,
        )
        self.assertEqual(violations, 0)
        # D 는 한 번만 들어가고, A 는 row5 밖이라 건너뛴다.
        self.assertEqual(rows[0].items, ["D", "E", "F"])

    def test_row_items_default_none_matches_previous_behaviour(self):
        baseline = self.decoder.generate(self.context, self.content, history_articles=["D", "E", "F"],
                                         n_rows=2, items_per_row=3, prefix=1)
        self.assertEqual(self.decoder.generate(self.context, self.content, history_articles=["D", "E", "F"],
                                               row_items=None, allowed_rows=None, n_rows=2, items_per_row=3,
                                               prefix=1), baseline)

    def test_row_bias_none_and_zero_match_previous_behaviour(self):
        baseline = self.decoder.generate(self.context, self.content, history_articles=["D", "E", "F"],
                                         allowed_rows={4, 5}, n_rows=2, items_per_row=3, prefix=1)
        self.assertEqual(self.decoder.generate(self.context, self.content, history_articles=["D", "E", "F"],
                                               allowed_rows={4, 5}, row_bias=None, n_rows=2, items_per_row=3,
                                               prefix=1), baseline)
        # 모든 bias 가 0 이면 log_softmax 가 단조라 선택이 같다.
        self.assertEqual(self.decoder.generate(self.context, self.content, history_articles=["D", "E", "F"],
                                               allowed_rows={4, 5}, row_bias={4: 0.0, 5: 0.0},
                                               n_rows=2, items_per_row=3, prefix=1), baseline)

    def test_row_bias_steers_greedy_row_choice(self):
        # FakeModel 은 row A(4) 40 > row B(5) 30 > repeat(3) 20 이다.
        default, _ = self.decoder.generate(self.context, self.content, history_articles=[],
                                           allowed_rows={4, 5}, n_rows=1, items_per_row=3, prefix=1)
        self.assertEqual(default[0].row_token, 4)
        biased, violations = self.decoder.generate(self.context, self.content, history_articles=[],
                                                   allowed_rows={4, 5}, row_bias={5: 100.0},
                                                   n_rows=1, items_per_row=3, prefix=1)
        self.assertEqual(violations, 0)
        self.assertEqual(biased[0].row_token, 5)
        self.assertEqual(set(biased[0].items), {"D", "E", "F"})

    def test_row_items_beyond_eight_fill_in_recency_order(self):
        # 다시 사기 행의 row_items 로 이력 전체를 주면, 최근 8개 안에 허용 밖 상품이
        # 있어도 9번째 이후 최근 상품이 그 자리를 채운다. 모델이 채우면 그 상품의
        # 점수 순(여기서는 L,K,J,I)이 되어 결과가 갈린다.
        class BigVocab:
            tokens = ["PAD", "SEP_HISTORY", "SEP_PAGE", "ROW_REPEAT", "ROW_A"] + [
                f"ITEM_{letter}" for letter in "ABCDEFGHIJKL"]
            item_ids = range(5, 17)
            row_ids = range(3, 5)
            article_of = {5 + index: letter for index, letter in enumerate("ABCDEFGHIJKL")}

            def id(self, name):
                return self.tokens.index(name)

            def item(self, article):
                return {value: key for key, value in self.article_of.items()}.get(article)

            def row_of(self, article):
                return 4

        class ByIdModel(torch.nn.Module):
            def __init__(self, vocab_size):
                super().__init__()
                self.vocab_size = vocab_size

            def forward(self, tokens, content_idx):
                return tokens.float().unsqueeze(-1)

            def logits(self, hidden):
                shape = hidden.shape
                return torch.arange(self.vocab_size, dtype=hidden.dtype,
                                    device=hidden.device).expand(shape[0], shape[1], self.vocab_size)

        vocab = BigVocab()
        decoder = PageDecoder(ByIdModel(len(vocab.tokens)), vocab, {}, "cpu")
        history = list("ABCDEFGHIJKL")  # A 가 가장 최근
        allowed_items = {"A", "B", "C", "D", "I", "J", "K", "L"}  # E ~ H 는 허용 밖
        kwargs = {"history_articles": history, "allowed_items": allowed_items,
                  "allowed_rows": {3}, "n_rows": 1, "items_per_row": 8, "prefix": 1}
        full, violations = decoder.generate(self.context, self.content,
                                            row_items={3: list("ABCDEFGHIJKL")}, **kwargs)
        self.assertEqual(violations, 0)
        self.assertEqual(full[0].items, ["A", "B", "C", "D", "I", "J", "K", "L"])
        # 최근 8개로 자르면 뒤 네 개가 모두 허용 밖이라 그 자리를 모델이 점수 순으로 채운다.
        short, _ = decoder.generate(self.context, self.content,
                                    row_items={3: list("ABCDEFGH")}, **kwargs)
        self.assertEqual(short[0].items, ["A", "B", "C", "D", "L", "K", "J", "I"])

    def test_batch_equals_single_with_pinned_items(self):
        examples = [
            {"ctx_tokens": [1, 2], "ctx_content": [-1, -1], "history_articles": ["D", "E", "F", "C"],
             "pinned": {0: 3}, "pinned_items": {0: ["F", "E"]}},
            {"ctx_tokens": [1, 2], "ctx_content": [-1, -1], "history_articles": ["A", "B", "C"],
             "pinned": {0: 3}, "pinned_items": {0: ["C", "A"]}},
        ]
        kwargs = {"n_rows": 1, "items_per_row": 4, "prefix": 1}
        self.assertEqual(self.decoder.generate_batch(examples, **kwargs),
                         [self.decoder.generate(**example, **kwargs) for example in examples])

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

    def test_batch_equals_single_with_per_example_allowed_and_pinned(self):
        examples = [
            {"ctx_tokens": [1, 2], "ctx_content": [-1, -1], "history_articles": [],
             "pinned": {0: 4}, "allowed_items": {"A", "B", "C"}},
            {"ctx_tokens": [1, 2], "ctx_content": [-1, -1], "history_articles": [],
             "pinned": {0: 5}, "allowed_items": {"D", "E", "F"}},
        ]
        kwargs = {"n_rows": 1, "items_per_row": 3, "prefix": 1}
        self.assertEqual(self.decoder.generate_batch(examples, **kwargs),
                         [self.decoder.generate(**example, **kwargs) for example in examples])

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

    def test_real_model_batch_equals_single_with_ragged_pinned_items(self):
        cfg = ModelConfig(vocab_size=len(self.vocab.tokens), dim=8, layers=1, heads=2,
                          ffn=16, dropout=0.0, maxlen=64, content_dim=384)
        torch.manual_seed(11)
        model = GenPageV2(cfg, torch.zeros((6, 384)), tokens=self.vocab.tokens).eval()
        decoder = PageDecoder(model, self.vocab, {a: n for n, a in enumerate("ABCDEF")}, "cpu")
        # 고정 목록 길이가 사용자마다 다르다: 2개 · 1개 · 0개.
        examples = [
            {"ctx_tokens": [1, 2], "ctx_content": [-1, -1], "history_articles": ["D", "E", "F", "C"],
             "pinned": {0: 3}, "pinned_items": {0: ["F", "E"]}},
            {"ctx_tokens": [1, 2], "ctx_content": [-1, -1], "history_articles": ["A", "B", "C"],
             "pinned": {0: 3}, "pinned_items": {0: ["C"]}},
            {"ctx_tokens": [1, 2], "ctx_content": [-1, -1], "history_articles": ["D", "E", "F"],
             "pinned": {0: 3}, "pinned_items": {0: []}},
        ]
        # 두 번째 행부터는 앞 행의 캐시를 이어 쓰므로, 패딩이 캐시 길이에 남으면
        # 여기서 결과가 갈린다.
        kwargs = {"n_rows": 3, "items_per_row": 3, "prefix": 1}
        batched = decoder.generate_batch(examples, **kwargs)
        singles = [decoder.generate(**example, **kwargs) for example in examples]
        self.assertEqual(batched, singles)
        for example in examples:
            self.assertEqual(decoder.generate(**example, **kwargs, use_cache=True),
                             decoder.generate(**example, **kwargs, use_cache=False))

    def test_real_model_batch_equals_single_with_row_items_and_allowed_rows(self):
        cfg = ModelConfig(vocab_size=len(self.vocab.tokens), dim=8, layers=1, heads=2,
                          ffn=16, dropout=0.0, maxlen=64, content_dim=384)
        torch.manual_seed(13)
        model = GenPageV2(cfg, torch.zeros((6, 384)), tokens=self.vocab.tokens).eval()
        decoder = PageDecoder(model, self.vocab, {a: n for n, a in enumerate("ABCDEF")}, "cpu")
        examples = [
            {"ctx_tokens": [1, 2], "ctx_content": [-1, -1], "history_articles": ["D", "E", "F", "C"],
             "row_items": {5: ["F", "E"]}, "allowed_rows": {5}},
            {"ctx_tokens": [1, 2], "ctx_content": [-1, -1], "history_articles": ["A", "B", "C"],
             "row_items": {4: ["C"]}, "allowed_rows": {4}},
            {"ctx_tokens": [1, 3, 4, 5, 6, 7, 8, 2], "ctx_content": [-1] * 8,
             "history_articles": ["D"], "row_items": {3: ["D"]}, "allowed_rows": {3, 4, 5}},
        ]
        kwargs = {"n_rows": 2, "items_per_row": 3, "prefix": 1}
        batched = decoder.generate_batch(examples, **kwargs)
        singles = [decoder.generate(**example, **kwargs) for example in examples]
        self.assertEqual(batched, singles)
        for example in examples:
            self.assertEqual(decoder.generate(**example, **kwargs, use_cache=True),
                             decoder.generate(**example, **kwargs, use_cache=False))

    def test_real_model_batch_equals_single_with_row_bias(self):
        cfg = ModelConfig(vocab_size=len(self.vocab.tokens), dim=8, layers=1, heads=2,
                          ffn=16, dropout=0.0, maxlen=64, content_dim=384)
        torch.manual_seed(23)
        model = GenPageV2(cfg, torch.zeros((6, 384)), tokens=self.vocab.tokens).eval()
        decoder = PageDecoder(model, self.vocab, {a: n for n, a in enumerate("ABCDEF")}, "cpu")
        examples = [
            {"ctx_tokens": [1, 2], "ctx_content": [-1, -1], "history_articles": [],
             "allowed_rows": {4, 5}, "row_bias": {4: 0.5, 5: -0.5}},
            {"ctx_tokens": [1, 3, 4, 5, 2], "ctx_content": [-1] * 5, "history_articles": ["D"],
             "allowed_rows": {3, 4, 5}, "row_bias": {3: 1.0, 4: 0.0, 5: -1.0}},
        ]
        kwargs = {"n_rows": 2, "items_per_row": 3, "prefix": 1}
        batched = decoder.generate_batch(examples, **kwargs)
        singles = [decoder.generate(**example, **kwargs) for example in examples]
        self.assertEqual(batched, singles)
        for example in examples:
            self.assertEqual(decoder.generate(**example, **kwargs, use_cache=True),
                             decoder.generate(**example, **kwargs, use_cache=False))

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
