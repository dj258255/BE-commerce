from __future__ import annotations

import json
import random
import tempfile
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

from genpage2 import config
from genpage2.context import view as context_view
from genpage2.decode import GeneratedRow
from genpage2.simulate import (CkptSource, Impression, PageRequest, PriceIndex, ReactionModel, SimUser,
                               TruthShuffleSource, build_users, candidate_dates, device_of, item_view,
                               load_attributes, main, persona_of, rng_for, rule_policy_class, session_tokens,
                               simulate, stable_hash)
from genpage2.vocab import Vocab, content_rows


def _frame(rows):
    return pd.DataFrame(rows, columns=["t_dat", "customer_id", "article_id", "sales_channel_id", "price"])


def _articles():
    return pd.DataFrame({
        "article_id": [f"{index:010d}" for index in range(1, 13)],
        "section_no": [1.0] * 12,
        "product_type_name": ["Dress"] * 6 + ["Shirt"] * 6,
        "colour_group_name": ["Red", "Red", "Blue", "Blue", "Green", "Green"] * 2,
        "index_group_name": ["Ladieswear"] * 6 + ["Menswear"] * 6,
    })


def _vocab(articles):
    rows = []
    for article in articles["article_id"]:
        rows.extend([("2020-01-01", "v", article, 1, 10.0)] * config.MIN_COUNT)
    return Vocab.build(_frame(rows), articles)


class ReactionTest(unittest.TestCase):
    def test_attention_shrinks_with_row_and_position(self):
        reaction = ReactionModel()
        self.assertAlmostEqual(reaction.attention(0, 0), 1.0)
        self.assertGreater(reaction.attention(0, 0), reaction.attention(1, 0))
        self.assertGreater(reaction.attention(0, 0), reaction.attention(0, 4))
        self.assertGreater(reaction.attention(0, 0), reaction.attention(3, 7))
        policy = reaction.policy
        expected = 1.0 / (1.0 + policy.ROW_DECAY * 2) / (1.0 + policy.POS_DECAY * 3)
        self.assertAlmostEqual(reaction.attention(2, 3), expected)

    def test_same_seed_gives_same_feedback(self):
        reaction = ReactionModel()
        persona = persona_of("c", [], {})
        item = item_view("0000000001", {})
        first = [reaction.feedback(persona, item, 0, pos, random.Random(5)) for pos in range(24)]
        second = [reaction.feedback(persona, item, 0, pos, random.Random(5)) for pos in range(24)]
        self.assertEqual(first, second)
        self.assertEqual(rng_for("c", "2020-09-02").random(), rng_for("c", "2020-09-02").random())

    def test_wanted_items_click_and_buy_more_often(self):
        reaction = ReactionModel()
        attributes = {"0000000001": ("Dress", "Blue", "Ladieswear"),
                      "0000000009": ("Shirt", "Green", "Menswear")}
        persona = persona_of("c", ["0000000001"], attributes)

        def rates(item, trials=4000):
            clicks = buys = 0
            for index in range(trials):
                feedback = reaction.feedback(persona, item, 0, 0, random.Random(1000 + index))
                clicks += feedback in ("click", "buy")
                buys += feedback == "buy"
            return clicks / trials, buys / trials

        wanted_click, wanted_buy = rates(item_view("0000000001", attributes))
        other_click, other_buy = rates(item_view("0000000009", attributes))
        self.assertGreater(wanted_click, other_click + 0.3)
        self.assertGreater(wanted_buy, other_buy + 0.2)
        self.assertGreater(other_click, 0.0)

    def test_device_is_deterministic(self):
        self.assertEqual(device_of("c1"), device_of("c1"))
        self.assertIn(device_of("c1"), ("mobile", "pc"))
        self.assertNotEqual(stable_hash("left"), stable_hash("right"))


class PriceIndexTest(unittest.TestCase):
    def setUp(self):
        self.articles = _articles()
        self.vocab = _vocab(self.articles)

    def test_prices_use_only_transactions_before_request(self):
        transactions = _frame([("2020-01-01", "c1", "0000000001", 1, 10.0),
                               ("2020-08-01", "c1", "0000000001", 1, 100.0),
                               ("2020-08-15", "c2", "0000000002", 1, 50.0)])
        transactions["t_dat"] = pd.to_datetime(transactions["t_dat"])
        prices = PriceIndex.from_transactions(self.vocab, transactions, pd.Timestamp("2020-09-09"))
        self.assertEqual(prices.price_as_of("0000000001", pd.Timestamp("2020-07-01")), 10.0)
        self.assertEqual(prices.price_as_of("0000000001", pd.Timestamp("2020-09-01")), 100.0)
        # 그 r 이전에 그 상품 거래가 없으면 r 이전 전체 중앙값
        self.assertEqual(prices.price_as_of("0000000002", pd.Timestamp("2020-07-01")), 10.0)
        self.assertEqual(prices.price_as_of("0000000002", pd.Timestamp("2020-09-01")), 50.0)

    def test_reference_time_drops_later_transactions(self):
        transactions = _frame([("2020-01-01", "c1", "0000000001", 1, 10.0),
                               ("2020-08-15", "c1", "0000000001", 1, 100.0)])
        transactions["t_dat"] = pd.to_datetime(transactions["t_dat"])
        prices = PriceIndex.from_transactions(self.vocab, transactions, pd.Timestamp("2020-08-10"))
        self.assertEqual(prices.price_as_of("0000000001", pd.Timestamp("2020-09-01")), 10.0)


class UserBuildTest(unittest.TestCase):
    def setUp(self):
        self.articles = _articles()
        self.vocab = _vocab(self.articles)
        self.content = content_rows(self.articles)
        self.attributes = load_attributes(self.articles)

    def test_users_stay_inside_training_window(self):
        rows = [("2020-01-01", "v", article, 1, 10.0)
                for article in self.articles["article_id"] for _ in range(config.MIN_COUNT)]
        rows += [("2020-01-05", "c1", "0000000001", 1, 10.0),
                 ("2020-09-03", "c1", "0000000003", 1, 10.0),
                 ("2020-09-10", "c1", "0000000004", 1, 10.0)]
        transactions = _frame(rows)
        transactions["t_dat"] = pd.to_datetime(transactions["t_dat"])
        customers = pd.DataFrame({"customer_id": ["c1"], "age": [30]})
        reference = pd.Timestamp("2020-09-09")
        request_dates = candidate_dates(reference, 1)
        self.assertEqual(request_dates, [pd.Timestamp("2020-09-02")])
        users = build_users(self.vocab, transactions[transactions["t_dat"] < reference], customers,
                            self.attributes, self.content, request_dates, reference)
        self.assertEqual(len(users), 1)
        user = users[0]
        self.assertLessEqual(user.request_date + pd.Timedelta(days=config.TARGET_DAYS), reference)
        self.assertEqual(user.wanted, ["0000000003"])
        self.assertIn("0000000001", user.history)
        self.assertNotIn("0000000004", user.history)
        self.assertNotIn("0000000004", user.wanted)
        self.assertNotIn(self.vocab.item("0000000003"), user.ctx_tokens)

    def test_request_date_outside_training_period_is_rejected(self):
        with self.assertRaises(ValueError):
            build_users(self.vocab, _frame([]), pd.DataFrame({"customer_id": []}), self.attributes,
                        self.content, [pd.Timestamp("2020-09-09")], pd.Timestamp("2020-09-09"))


class PageTwoTest(unittest.TestCase):
    def setUp(self):
        self.articles = _articles()
        self.vocab = _vocab(self.articles)
        self.content = content_rows(self.articles)
        self.attributes = load_attributes(self.articles)

    def _forced_reaction(self):
        rule = rule_policy_class()

        class Forced(rule):
            CLICK_BIAS = 0.0
            BUY_BIAS = 100.0
            NEXT_PAGE = 1.0

            def score(self, persona, item):
                # ±50 이면 sigmoid(±50) 이 1.0 / 0 에 붙어 클릭 · 스킵이 결정적이다.
                # policies.sigmoid 는 math.exp 라서 1e6 같은 값은 넘친다.
                return 50.0 if int(item["id"]) % 2 == 0 else -50.0

        reaction = ReactionModel(policy=Forced())
        reaction.attention = lambda rank, pos: 1.0
        return reaction

    def _impression(self, article, feedback, pos):
        vocab = self.vocab
        return Impression("c1", pd.Timestamp("2020-09-02"), 1, 0, vocab.id("ROW_S1"), pos, article,
                          int(vocab.item(article)), feedback, 1.0, "pc")

    def test_session_tokens_keep_both_actions(self):
        tokens, content = session_tokens(
            [self._impression("0000000002", "click", 0),
             self._impression("0000000001", "skip", 1),
             self._impression("0000000003", "unseen", 2)],
            vocab=self.vocab, prices=PriceIndex(self.vocab), content_rows_map=self.content)
        self.assertEqual(len(tokens), 8)
        self.assertEqual(tokens[1], self.vocab.id("ACT_CLICK"))
        self.assertEqual(tokens[5], self.vocab.id("ACT_VIEW"))
        self.assertEqual(tokens[2], self.vocab.id("AGO_0-3"))
        self.assertIn(tokens[3], self.vocab.price_ids)
        self.assertEqual(content[0], self.content["0000000002"])
        self.assertEqual(content[4], self.content["0000000001"])

    def _full_context(self):
        vocab = self.vocab
        tokens = [vocab.id("BOS"), vocab.id("SEP_PROFILE"), vocab.id("AGE_30-39"),
                  vocab.id("CLUB_ACTIVE"), vocab.id("NEWS_NONE"), vocab.id("FN_NA"),
                  vocab.id("ACTIVE_NA"), vocab.id("SEP_REQUEST"), vocab.id("DOW_2"),
                  vocab.id("MONTH_9"), vocab.id("SEP_HISTORY"), vocab.id("SEP_PAGE")]
        return tokens, [-1] * len(tokens)

    def _two_pages(self):
        vocab = self.vocab
        ctx, ctx_content = self._full_context()
        user = SimUser("c1", pd.Timestamp("2020-09-02"), ctx, ctx_content, [],
                       ["0000000001", "0000000002", "0000000003", "0000000004"], "pc",
                       persona_of("c1", ["0000000001"], self.attributes))
        pages = simulate([user], TruthShuffleSource(vocab), vocab=vocab, attributes=self.attributes,
                         content_rows_map=self.content, reaction=self._forced_reaction(),
                         prices=PriceIndex(vocab))
        by_page = {page.page_no: page for page in pages}
        self.assertIn(2, by_page)
        return by_page[1], by_page[2]

    def test_page_two_places_actions_in_history_and_page_prefix_after_sep_page(self):
        vocab = self.vocab
        first, second = self._two_pages()
        self.assertEqual(len(first.impressions), 4)
        sep_history, sep_page = vocab.id("SEP_HISTORY"), vocab.id("SEP_PAGE")

        # 앞 쪽 행 · 상품 토큰은 prev_tokens 에만, EOS 없이 한 번만 들어간다.
        self.assertEqual(second.prev_tokens, first.page_tokens[:-1])
        self.assertNotIn(sep_page, second.prev_tokens)
        self.assertEqual(len(second.prev_tokens), len(set(second.prev_tokens)))

        # 세션 행동은 SEP_HISTORY ~ SEP_PAGE 사이 맨 뒤에 있고 SEP_PAGE 뒤에는 아무것도 없다.
        page_at = second.ctx_tokens.index(sep_page)
        self.assertEqual(page_at, len(second.ctx_tokens) - 1)
        hist_at = second.ctx_tokens.index(sep_history)
        self.assertIn(second.ctx_tokens[page_at - 4], vocab.item_ids)
        self.assertIn(second.ctx_tokens[page_at - 3], (vocab.id("ACT_CLICK"), vocab.id("ACT_VIEW")))
        self.assertEqual(second.ctx_tokens[page_at - 2], vocab.id("AGO_0-3"))
        self.assertIn(second.ctx_tokens[page_at - 1], vocab.price_ids)
        tail = second.ctx_tokens[hist_at + 1:page_at]
        for action in (vocab.id("ACT_CLICK"), vocab.id("ACT_VIEW")):
            self.assertIn(action, tail)

        # 앞 쪽 토큰이 ctx 에 다시 들어가지 않는다(한 번만).
        # 행 골격(ROW_)은 ctx 에 0번, 앞 쪽 상품(ITEM_)은 세션 이벤트로 1번까지 나온다.
        for token in second.prev_tokens:
            limit = 1 if token in vocab.item_ids else 0
            self.assertLessEqual(second.ctx_tokens.count(token), limit)

        # prev_tokens 에 ACT · AGO · PRICE 가 없다.
        for token in second.prev_tokens:
            self.assertNotIn(token, vocab.action_ids)
            self.assertNotIn(token, vocab.ago_ids)
            self.assertNotIn(token, vocab.price_ids)

    def test_page_two_items_view_keeps_session_event_items(self):
        vocab = self.vocab
        _, second = self._two_pages()
        session_items = [token for token in second.ctx_tokens if token in vocab.item_ids]
        self.assertTrue(session_items)
        tokens, content = context_view(second.ctx_tokens, second.ctx_content, vocab, "items")
        self.assertEqual(len(tokens), len(content))
        self.assertEqual(tokens[-1], vocab.id("SEP_PAGE"))
        for token in session_items:
            self.assertIn(token, tokens)


class CkptSourceTest(unittest.TestCase):
    def test_batches_and_requests_six_by_eight(self):
        class FakeDecoder:
            def __init__(self):
                self.calls = []

            def generate_batch(self, examples, **kwargs):
                self.calls.append((examples, kwargs))
                return [([GeneratedRow(1, ["0000000001"])], 0) for _ in examples]

        decoder = FakeDecoder()
        requests = [PageRequest(f"c{index}", pd.Timestamp("2020-09-02"), [1, 2], [-1, -1], [], [], [])
                    for index in range(5)]
        pages = CkptSource(decoder, batch=2).pages(requests)
        self.assertEqual(len(pages), 5)
        self.assertEqual(len(decoder.calls), 3)
        for examples, kwargs in decoder.calls:
            self.assertEqual(kwargs, {"n_rows": config.MAX_ROWS, "items_per_row": config.ITEMS_PER_ROW,
                                      "prefix": 2})
            self.assertIn("prev_page", examples[0])
            self.assertEqual(examples[0]["history_articles"], [])
        self.assertEqual(pages[0], [GeneratedRow(1, ["0000000001"])])


class FilesTest(unittest.TestCase):
    def _write_dataset(self, root):
        articles = _articles()
        normalized = root / "hm" / "normalized"
        normalized.mkdir(parents=True)
        articles.to_parquet(normalized / "articles.parquet", index=False)
        pd.DataFrame({"customer_id": ["c1", "c2"], "age": [30, 40],
                      "club_member_status": ["ACTIVE", "ACTIVE"],
                      "fashion_news_frequency": ["NONE", "NONE"],
                      "FN": [1.0, 0.0], "Active": [1.0, 1.0]}).to_parquet(
            normalized / "customers.parquet", index=False)
        rows = [("2020-01-01", "v", article, 1, 10.0)
                for article in articles["article_id"] for _ in range(config.MIN_COUNT)]
        rows += [("2020-01-02", "c1", "0000000001", 1, 10.0),
                 ("2020-09-03", "c1", "0000000003", 1, 10.0),
                 ("2020-09-03", "c1", "0000000004", 1, 10.0),
                 ("2020-01-03", "c2", "0000000002", 1, 10.0),
                 ("2020-09-04", "c2", "0000000005", 1, 10.0)]
        transactions = _frame(rows)
        transactions["t_dat"] = pd.to_datetime(transactions["t_dat"])
        transactions.to_parquet(normalized / "transactions.parquet", index=False)
        mode = root / "hm" / "model" / "genpage2" / "validate"
        mode.mkdir(parents=True)
        Vocab.build(transactions[transactions["t_dat"] < pd.Timestamp("2020-09-09")], articles).save(
            mode / "vocab.json")

    def test_truth_shuffle_writes_three_files_and_stats(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "data"
            out = Path(temporary) / "out"
            self._write_dataset(root)
            code = main(["--mode", "validate", "--source", "truth-shuffle", "--customers", "10",
                         "--dates", "1", "--name", "smoke", "--data-dir", str(root), "--out", str(out)])
            self.assertEqual(code, 0)
            directory = out / "validate" / "impressions" / "smoke"
            for name in ("impressions.parquet", "pages.npz", "stats.json"):
                self.assertTrue((directory / name).exists(), name)
            stats = json.loads((directory / "stats.json").read_text(encoding="utf-8"))
            self.assertEqual(stats["users"], 2)
            self.assertGreater(stats["impressions"], 0)
            for key in ("feedback_ratio", "by_row_rank", "by_pos", "page_reward", "page2_ratio", "device_ratio"):
                self.assertIn(key, stats)
            frame = pd.read_parquet(directory / "impressions.parquet")
            self.assertTrue(set(frame["feedback"]).issubset(set(config.MODES) | {"unseen", "skip", "click", "buy"}))
            self.assertTrue(set(frame["device"]).issubset({"mobile", "pc"}))
            with np.load(directory / "pages.npz") as archive:
                self.assertEqual(len(archive["token_reward"]), len(archive["page_tokens"]))
                self.assertEqual(len(archive["page_no"]), len(archive["page_offsets"]) - 1)
                self.assertEqual(len(archive["page_no"]), stats["pages"])
                self.assertEqual(len(archive["prev_offsets"]), stats["pages"] + 1)
                offsets = archive["prev_offsets"]
                for index, page_no in enumerate(archive["page_no"]):
                    if int(page_no) == 1:
                        self.assertEqual(int(offsets[index]), int(offsets[index + 1]))

    def test_token_reward_sums_item_rewards_into_row_tokens(self):
        articles = _articles()
        vocab = _vocab(articles)
        content = content_rows(articles)
        attributes = load_attributes(articles)
        user = SimUser("c1", pd.Timestamp("2020-09-02"), [vocab.id("BOS"), vocab.id("SEP_PAGE")], [-1, -1],
                       [], ["0000000001", "0000000002"], "pc", persona_of("c1", ["0000000001"], attributes))
        pages = simulate([user], TruthShuffleSource(vocab), vocab=vocab, attributes=attributes,
                         content_rows_map=content, reaction=ReactionModel(), prices=PriceIndex(vocab))
        page = pages[0]
        self.assertEqual(len(page.page_tokens), len(page.token_reward))
        self.assertEqual(page.page_tokens[-1], vocab.id("EOS"))
        self.assertEqual(page.token_reward[-1], 0.0)
        index = 0
        while index < len(page.page_tokens) - 1:
            self.assertIn(page.page_tokens[index], vocab.row_ids)
            row_start = index
            index += 1
            total = 0.0
            while index < len(page.page_tokens) - 1 and page.page_tokens[index] in vocab.item_ids:
                total += page.token_reward[index]
                index += 1
            self.assertEqual(page.token_reward[row_start], total)


if __name__ == "__main__":
    unittest.main()
