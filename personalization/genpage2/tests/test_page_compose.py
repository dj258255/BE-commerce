from __future__ import annotations

import argparse
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np
import pandas as pd
import torch

from genpage2.decode import GeneratedRow, PageDecoder
from genpage2.evaluate import evaluate_pages
from genpage2.merge_eval import _collect_pages
from genpage2.model import GenPageV2, ModelConfig
from genpage2.page_compose import (_row_groups, build_pages, compose_b, g_full_inputs, g_row_inputs,
                                   g_row_thin_inputs, h_inputs, h_thin_inputs, read_scores,
                                   row_score_sums, run)
from genpage2.ranker import write_scores

_TOKENS = (["ROW_REPEAT", "ROW_S1", "ROW_S2"]
           + [f"ITEM_{letter}" for letter in "ABCDEFGHIJKLMNOP"])
_ITEMS = {name[len("ITEM_"):]: index for index, name in enumerate(_TOKENS)
          if name.startswith("ITEM_")}
_ROWS = {letter: 1 for letter in "ABCDEFGH"} | {letter: 2 for letter in "IJKLMNOP"}


class FakeVocab:
    tokens = _TOKENS

    def id(self, name):
        return self.tokens.index(name)

    def item(self, article):
        return _ITEMS.get(article)

    def row_of(self, article):
        return _ROWS[article]


class ComposeBTest(unittest.TestCase):
    def setUp(self):
        self.vocab = FakeVocab()

    def test_rows_ordered_by_top_items_score_sum(self):
        scored = [("A", 0.5), ("B", 0.4), ("C", 0.9), ("I", 0.3), ("J", 0.2), ("K", 0.1)]
        rows = compose_b(scored, [], self.vocab, rows=6, items=2)
        # row1: C(0.9)+A(0.5)=1.4, row2: I(0.3)+J(0.2)=0.5 -> row1 이 먼저.
        self.assertEqual([row.row_token for row in rows], [1, 2])
        self.assertEqual(rows[0].items, ["C", "A"])
        self.assertEqual(rows[1].items, ["I", "J"])

    def test_history_items_go_to_repeat_row_and_each_product_once(self):
        scored = [("I", 0.95), ("A", 0.5), ("B", 0.4), ("C", 0.9), ("J", 0.3), ("K", 0.2)]
        rows = compose_b(scored, [("I")], self.vocab, rows=6, items=8)
        repeat = next(row for row in rows if row.row_token == self.vocab.id("ROW_REPEAT"))
        self.assertEqual(repeat.items, ["I"])
        # 다시 사기 행으로 간 상품은 자기 섹션 행에 다시 나오지 않는다.
        section2 = next(row for row in rows if row.row_token == 2)
        self.assertNotIn("I", section2.items)
        all_items = [article for row in rows for article in row.items]
        self.assertEqual(len(all_items), len(set(all_items)))

    def test_top_rows_limit(self):
        scored = [("A", 0.9), ("B", 0.8), ("C", 0.7), ("I", 0.6)]
        rows = compose_b(scored, [], self.vocab, rows=1, items=8)
        self.assertEqual([row.row_token for row in rows], [1])
        self.assertEqual(rows[0].items, ["A", "B", "C"])


class GRowInputsTest(unittest.TestCase):
    def setUp(self):
        self.vocab = FakeVocab()

    def test_allowed_rows_need_eight_items(self):
        scored = [(letter, 1.0 - index / 100) for index, letter in enumerate("ABCDEFGH")]
        scored += [(letter, 0.5 - index / 100) for index, letter in enumerate("IJKLMNO")]
        row_items, allowed_rows = g_row_inputs(scored, [], self.vocab)
        # row2 는 7개뿐이라 막히고 row1 만 남는다.
        self.assertEqual(allowed_rows, {1})
        self.assertEqual(row_items[1], list("ABCDEFGH"))

    def test_row_items_follow_score_order(self):
        scored = [("H", 0.9), ("A", 0.8), ("B", 0.7), ("C", 0.6),
                  ("D", 0.5), ("E", 0.4), ("F", 0.3), ("G", 0.2)]
        row_items, allowed_rows = g_row_inputs(scored, [], self.vocab)
        self.assertEqual(allowed_rows, {1})
        self.assertEqual(row_items[1], list("HABCDEFG"))

    def test_repeat_row_allowed_only_with_eight_history_items(self):
        history = list("IJKLMNOP")
        scored = [(letter, 1.0 - index / 100) for index, letter in enumerate("ABCDEFGH")]
        scored += [(letter, 0.5 - index / 100) for index, letter in enumerate("IJKLMNOP")]
        row_items, allowed_rows = g_row_inputs(scored, history, self.vocab)
        self.assertEqual(allowed_rows, {self.vocab.id("ROW_REPEAT"), 1})
        self.assertEqual(row_items[self.vocab.id("ROW_REPEAT")], list("IJKLMNOP"))


class GFullInputsTest(unittest.TestCase):
    def test_allowed_items_are_top_n_and_repeat_row_uses_recency(self):
        vocab = FakeVocab()
        history = ["D", "C", "A", "not-in-vocab"]
        scored = [("A", 0.9), ("B", 0.8), ("C", 0.7), ("I", 0.6), ("P", 0.5)]
        allowed_items, row_items = g_full_inputs(scored, history, vocab, top=3)
        self.assertEqual(allowed_items, {"A", "B", "C"})
        # 최근 산 순서(중복 없이, 어휘 밖 상품 제외).
        self.assertEqual(row_items[vocab.id("ROW_REPEAT")], ["D", "C", "A"])

    def test_repeat_row_items_keep_full_history_beyond_eight(self):
        vocab = FakeVocab()
        # 최근 순서로 12개. 8개로 자르면 뒤 4개가 사라져 모델이 그 자리를 채운다.
        history = list("ABCDEFGHIJKL")
        scored = [(letter, 0.5) for letter in "ABCDEFGHIJKL"]
        _, row_items = g_full_inputs(scored, history, vocab)
        self.assertEqual(row_items[vocab.id("ROW_REPEAT")], list("ABCDEFGHIJKL"))
        self.assertGreater(len(row_items[vocab.id("ROW_REPEAT")]), 8)


class RowScoreSumsTest(unittest.TestCase):
    def test_shares_the_sum_of_top_items_with_compose_b(self):
        vocab = FakeVocab()
        # row1(A..H) 합 6, row2(I..P) 합 8 → row2 가 먼저.
        scored = [("A", 3.0), ("B", 2.0), ("C", 1.0)] + [(letter, 0.0) for letter in "DEFGH"]
        scored += [(letter, 1.0) for letter in "IJKLMNOP"]
        rows = compose_b(scored, [], vocab, rows=6, items=8)
        self.assertEqual([row.row_token for row in rows], [2, 1])
        self.assertEqual(row_score_sums({1: [(3.0, "A"), (2.0, "B"), (1.0, "C")]}, items=8), {1: 6.0})


class HInputsTest(unittest.TestCase):
    def setUp(self):
        self.vocab = FakeVocab()

    def test_row_bias_is_lambda_times_standardized_row_scores(self):
        # row1(A..H) 합 6, row2(I..P) 합 8 → 평균 7, 표준편차 1 → z = {1: -1, 2: +1}.
        scored = [("A", 3.0), ("B", 2.0), ("C", 1.0)] + [(letter, 0.0) for letter in "DEFGH"]
        scored += [(letter, 1.0) for letter in "IJKLMNOP"]
        row_items, allowed_rows, row_bias = h_inputs(scored, [], self.vocab, 0.5)
        self.assertEqual(allowed_rows, {1, 2})
        self.assertAlmostEqual(row_bias[1], -0.5)
        self.assertAlmostEqual(row_bias[2], 0.5)
        # row_items · allowed_rows 는 G-row 와 같다.
        self.assertEqual((row_items, allowed_rows), g_row_inputs(scored, [], self.vocab))

    def test_single_allowed_row_bias_is_zero(self):
        # row1 만 8개, row2 는 7개라 허용 행이 하나다.
        scored = [(letter, 1.0 - index / 100) for index, letter in enumerate("ABCDEFGH")]
        scored += [(letter, 0.5) for letter in "IJKLMNO"]
        row_items, allowed_rows, row_bias = h_inputs(scored, [], self.vocab, 4.0)
        self.assertEqual(allowed_rows, {1})
        self.assertEqual(row_bias, {1: 0.0})

    def test_equal_row_scores_give_zero_bias(self):
        scored = [(letter, 1.0) for letter in "ABCDEFGH"] + [(letter, 1.0) for letter in "IJKLMNOP"]
        _, allowed_rows, row_bias = h_inputs(scored, [], self.vocab, 3.0)
        self.assertEqual(allowed_rows, {1, 2})
        self.assertEqual(row_bias, {1: 0.0, 2: 0.0})

    def test_no_allowed_rows_gives_empty_bias(self):
        _, allowed_rows, row_bias = h_inputs([("A", 1.0), ("I", 0.5)], [], self.vocab, 2.0)
        self.assertEqual(allowed_rows, set())
        self.assertEqual(row_bias, {})


class ThinInputsTest(unittest.TestCase):
    def setUp(self):
        self.vocab = FakeVocab()

    def test_allowed_rows_match_every_row_b_may_pick(self):
        # B 는 상품이 하나뿐인 행도 고를 수 있다: row1(A,B,C) 과 row2(I).
        scored = [("A", 0.9), ("B", 0.8), ("C", 0.7), ("I", 0.5)]
        b_rows = [row.row_token for row in compose_b(scored, [], self.vocab, rows=6, items=8)]
        allowed_items, row_items, allowed_rows = g_row_thin_inputs(scored, [], self.vocab)
        self.assertEqual(allowed_rows, set(b_rows))
        self.assertEqual(allowed_rows, set(_row_groups(scored, set(), self.vocab)))
        self.assertEqual(allowed_rows, {1, 2})
        # 8개 이상 규칙(D3 · D4)이면 이 표본에서 아무 행도 허용하지 않는다.
        self.assertEqual(g_row_inputs(scored, [], self.vocab)[1], set())
        # 행 안 상품은 그 행의 점수 순 최대 8개, 허용 상품은 상위 200개다.
        self.assertEqual(row_items[1], ["A", "B", "C"])
        self.assertEqual(row_items[2], ["I"])
        self.assertEqual(allowed_items, {"A", "B", "C", "I"})

    def test_row_items_are_capped_at_eight(self):
        scored = [(letter, 1.0 - index / 100) for index, letter in enumerate("ABCDEFGH")]
        _, row_items, allowed_rows = g_row_thin_inputs(scored, [], self.vocab)
        self.assertEqual(allowed_rows, {1})
        self.assertEqual(row_items[1], list("ABCDEFGH"))

    def test_history_items_stay_in_the_repeat_row(self):
        # 이력의 A 는 다시 사기 행으로 가고, 그 행도 허용 행이다(B 와 같다).
        scored = [("A", 0.9), ("B", 0.8), ("C", 0.7), ("I", 0.5)]
        allowed_items, row_items, allowed_rows = g_row_thin_inputs(scored, ["A"], self.vocab)
        self.assertEqual(allowed_rows, {self.vocab.id("ROW_REPEAT"), 1, 2})
        self.assertEqual(row_items[self.vocab.id("ROW_REPEAT")], ["A"])
        self.assertEqual(row_items[1], ["B", "C"])
        self.assertEqual(allowed_items, {"A", "B", "C", "I"})

    def test_h_thin_bias_standardizes_over_recall_rows(self):
        # row1 합 6, row2 합 8 → 평균 7, 표준편차 1 → z = {1: -1, 2: +1}.
        scored = [("A", 3.0), ("B", 2.0), ("C", 1.0)]
        scored += [(letter, 1.0) for letter in "IJKLMNOP"]
        allowed_items, row_items, allowed_rows, row_bias = h_thin_inputs(scored, [], self.vocab, 0.5)
        self.assertEqual(allowed_rows, {1, 2})
        self.assertAlmostEqual(row_bias[1], -0.5)
        self.assertAlmostEqual(row_bias[2], 0.5)
        self.assertEqual(row_items[1], ["A", "B", "C"])
        self.assertEqual(allowed_items, {"A", "B", "C", "I", "J", "K", "L", "M", "N", "O", "P"})

    def test_h_thin_single_allowed_row_bias_is_zero(self):
        scored = [("A", 0.9), ("B", 0.8), ("C", 0.7)]
        _, _, allowed_rows, row_bias = h_thin_inputs(scored, [], self.vocab, 4.0)
        self.assertEqual(allowed_rows, {1})
        self.assertEqual(row_bias, {1: 0.0})


_SMALL_TOKENS = (["PAD", "SEP_HISTORY", "SEP_PAGE", "ROW_REPEAT", "ROW_A", "ROW_B"]
                 + [f"ITEM_{letter}" for letter in "ABCDEFGHIJKLMNOP"])


class DecodeVocab:
    tokens = _SMALL_TOKENS
    item_ids = range(6, 22)
    row_ids = range(3, 6)
    article_of = {6 + index: letter for index, letter in enumerate("ABCDEFGHIJKLMNOP")}

    def id(self, name):
        return self.tokens.index(name)

    def item(self, article):
        return {letter: token for token, letter in self.article_of.items()}.get(article)

    def row_of(self, article):
        return 4 if article in "ABCDEFGH" else 5


class HPageTest(unittest.TestCase):
    """작은 실제 모델로 H(λ) 페이지를 G-row · 행 점수 순서와 맞춰 본다."""

    def setUp(self):
        self.vocab = DecodeVocab()
        cfg = ModelConfig(vocab_size=len(self.vocab.tokens), dim=8, layers=1, heads=2,
                          ffn=16, dropout=0.0, maxlen=64, content_dim=384)
        torch.manual_seed(29)
        model = GenPageV2(cfg, torch.zeros((16, 384)), tokens=self.vocab.tokens).eval()
        content_rows = {letter: index for index, letter in enumerate("ABCDEFGHIJKLMNOP")}
        self.decoder = PageDecoder(model, self.vocab, content_rows, "cpu")
        self.meta = pd.DataFrame({"customer_id": ["c1", "c2"], "history": [[], ["A"]]})
        self.archive = {"ctx_tokens": np.array([1, 2, 1, 2]),
                        "ctx_content": np.array([-1, -1, -1, -1]),
                        "ctx_offsets": np.array([0, 2, 4])}

    def _scores(self):
        # ROW_A(A..H) 가 ROW_B(I..P) 보다 행 점수가 훨씬 크다.
        scored = [(letter, float(20 - index)) for index, letter in enumerate("ABCDEFGH")]
        scored += [(letter, float(1.0 - index * 0.05)) for index, letter in enumerate("IJKLMNOP")]
        return {"c1": scored, "c2": scored}

    def test_lambda_zero_h_matches_g_row(self):
        pages_g, _ = build_pages("G-row", self.meta, self.archive, self._scores(),
                                 vocab=self.vocab, decoder=self.decoder)
        pages_h, _ = build_pages("H", self.meta, self.archive, self._scores(),
                                 vocab=self.vocab, decoder=self.decoder, row_lambda=0.0)
        self.assertEqual(pages_h, pages_g)
        self.assertTrue(pages_g["c1"])

    def test_large_lambda_follows_row_score_order(self):
        pages, _ = build_pages("H", self.meta, self.archive, self._scores(),
                               vocab=self.vocab, decoder=self.decoder, row_lambda=100.0)
        # c1 은 허용 행이 둘이고 ROW_A 점수가 더 크다 → 행 점수 순으로 골라진다.
        self.assertEqual([row.row_token for row in pages["c1"]], [4, 5])
        # c2 는 이력의 A 가 다시 사기 행으로 가 허용 행이 ROW_B 하나뿐이라 bias 가 0 이다.
        self.assertEqual([row.row_token for row in pages["c2"]], [5])


class ThinPageTest(unittest.TestCase):
    """G-row-thin · H-thin 페이지가 모델에게 채우게 하지 않고 row_items 로 끝나는지 본다."""

    def setUp(self):
        self.vocab = DecodeVocab()
        cfg = ModelConfig(vocab_size=len(self.vocab.tokens), dim=8, layers=1, heads=2,
                          ffn=16, dropout=0.0, maxlen=64, content_dim=384)
        torch.manual_seed(31)
        model = GenPageV2(cfg, torch.zeros((16, 384)), tokens=self.vocab.tokens).eval()
        content_rows = {letter: index for index, letter in enumerate("ABCDEFGHIJKLMNOP")}
        self.decoder = PageDecoder(model, self.vocab, content_rows, "cpu")
        self.meta = pd.DataFrame({"customer_id": ["c1", "c2"], "history": [[], ["A"]]})
        self.archive = {"ctx_tokens": np.array([1, 2, 1, 2]),
                        "ctx_content": np.array([-1, -1, -1, -1]),
                        "ctx_offsets": np.array([0, 2, 4])}

    def _scores(self):
        # ROW_A 는 A,B,C 세 개뿐이고, ROW_B 는 I..P 여덟 개다. c2 는 이력의 A 가
        # 다시 사기 행으로 가므로 짧은 다시 사기 행도 페이지에 오른다.
        scored = [("A", 0.9), ("B", 0.8), ("C", 0.7)]
        scored += [(letter, 0.1) for letter in "IJKLMNOP"]
        return {"c1": scored, "c2": scored}

    def test_thin_rows_use_row_items_and_stop_short(self):
        pages, violations = build_pages("G-row-thin", self.meta, self.archive, self._scores(),
                                        vocab=self.vocab, decoder=self.decoder)
        self.assertEqual(violations, {"c1": 0, "c2": 0})
        for customer, history in (("c1", []), ("c2", ["A"])):
            row_items = g_row_thin_inputs(self._scores()[customer], history, self.vocab)[1]
            for row in pages[customer]:
                # 모델이 채운 상품 0: 행 상품이 그 행의 row_items 와 정확히 같다.
                self.assertEqual(row.items, row_items[row.row_token])
            # 한 상품은 한 번만.
            flat = [article for row in pages[customer] for article in row.items]
            self.assertEqual(len(flat), len(set(flat)))
        # c1: ROW_A(짧은 행 3개) + ROW_B(8개). 이력이 없어 다시 사기 행은 없다.
        self.assertEqual({row.row_token for row in pages["c1"]}, {4, 5})
        short = next(row for row in pages["c1"] if row.row_token == 4)
        self.assertEqual(short.items, ["A", "B", "C"])
        self.assertEqual(len(short.items), 3)
        long_row = next(row for row in pages["c1"] if row.row_token == 5)
        self.assertEqual(len(long_row.items), 8)
        # c2: 다시 사기 행(짧은 1개)도 페이지에 오른다.
        repeat = next(row for row in pages["c2"] if row.row_token == 3)
        self.assertEqual(repeat.items, ["A"])

    def test_g_row_blocks_the_short_row(self):
        # D3 · D4 규칙(8개 이상)에서는 세 상품뿐인 ROW_A 가 막혀 ROW_B 만 남는다.
        pages, _ = build_pages("G-row", self.meta, self.archive, self._scores(),
                               vocab=self.vocab, decoder=self.decoder)
        self.assertEqual([row.row_token for row in pages["c1"]], [5])

    def test_large_lambda_h_thin_matches_b_rows_and_order(self):
        for customer, history in (("c1", []), ("c2", ["A"])):
            scores = self._scores()[customer]
            b_rows = compose_b(scores, history, self.vocab, rows=6, items=8)
            pages, violations = build_pages("H-thin", self.meta, self.archive, self._scores(),
                                            vocab=self.vocab, decoder=self.decoder, row_lambda=1e6)
            self.assertEqual(violations[customer], 0)
            thin = pages[customer]
            self.assertEqual([row.row_token for row in thin], [row.row_token for row in b_rows])
            self.assertEqual([row.items for row in thin], [row.items for row in b_rows])


class BuildPagesTest(unittest.TestCase):
    def test_b_build_pages_needs_no_decoder(self):
        vocab = FakeVocab()
        meta = pd.DataFrame({"customer_id": ["c1", "c2"], "history": [[], ["I"]]})
        scores = {"c1": [("A", 0.9), ("B", 0.8), ("C", 0.7)],
                  "c2": [("I", 0.95), ("J", 0.3), ("K", 0.2)]}
        pages, violations = build_pages("B", meta, None, scores, vocab=vocab)
        self.assertEqual(violations, {"c1": 0, "c2": 0})
        self.assertEqual([row.row_token for row in pages["c1"]], [1])
        self.assertEqual([row.row_token for row in pages["c2"]], [vocab.id("ROW_REPEAT"), 2])


class ScoresRoundTripTest(unittest.TestCase):
    def test_write_then_read_keeps_order(self):
        meta = pd.DataFrame({"customer_id": ["c2", "c1"]})
        scores = {"c1": [("A", 0.5), ("B", 0.4)], "c2": []}
        with tempfile.TemporaryDirectory() as temporary:
            path = write_scores(Path(temporary) / "scores.json.gz", meta, scores)
            loaded = read_scores(path)
        self.assertEqual(loaded["c1"], [("A", 0.5), ("B", 0.4)])
        self.assertEqual(loaded["c2"], [])


class RunReportTest(unittest.TestCase):
    def test_run_b_report_shape_is_readable_by_merge(self):
        vocab = FakeVocab()
        meta = pd.DataFrame({"customer_id": ["c1", "c2"], "truth": [["A"], ["I"]],
                             "history": [[], ["I"]]})
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            mode_dir = base / "hm" / "model" / "genpage2" / "validate"
            mode_dir.mkdir(parents=True)
            meta.to_parquet(mode_dir / "eval_meta.parquet")
            np.savez(mode_dir / "eval.npz", ctx_tokens=np.array([1, 1]), ctx_content=np.array([-1, -1]),
                     ctx_offsets=np.array([0, 1, 2]))
            scores = {"c1": [("A", 0.9), ("B", 0.8), ("C", 0.7)],
                      "c2": [("I", 0.95), ("J", 0.3)]}
            write_scores(base / "scores.json.gz", meta, scores)
            with patch("genpage2.page_compose.load_eval_assets", return_value=(vocab, None, None)):
                report = run(argparse.Namespace(
                    mode="validate", limit=None, scores=str(base / "scores.json.gz"), variant="B",
                    ckpt=None, shard=None, out=str(base / "b.json"), batch=256, device="cpu",
                    data_dir=str(base), threads=None,
                ))
        self.assertEqual(report["shard"], {"index": 1, "total": 1, "customers": 2})
        self.assertEqual([record["customer_id"] for record in report["pages"]], ["c1", "c2"])
        pages = _collect_pages([report], meta)
        self.assertIsInstance(pages["c1"][0], GeneratedRow)
        metrics = evaluate_pages(meta, pages, vocab=vocab)
        self.assertIn("page_ndcg", metrics)
        self.assertIn("row_hit", metrics)
        self.assertEqual(metrics["violations"], 0)


if __name__ == "__main__":
    unittest.main()
