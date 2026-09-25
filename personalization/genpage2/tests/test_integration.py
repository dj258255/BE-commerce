from __future__ import annotations

import argparse
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np
import pandas as pd

from genpage2 import config
from genpage2.content import save_content
from genpage2.dataset import generate_examples, write_examples
from genpage2.decode import PageDecoder
from genpage2.evaluate import run
from genpage2.train_pretrain import train
from genpage2.vocab import Vocab, content_rows


class RealPipelineIntegrationTest(unittest.TestCase):
    def test_four_field_dataset_trains_and_evaluates_items_and_full_contexts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            mode_dir = root / "hm" / "model" / "genpage2" / "validate"
            content_dir = mode_dir.parent / "content"
            mode_dir.mkdir(parents=True)
            content_dir.mkdir(parents=True)
            articles = pd.DataFrame({
                "article_id": ["0000000001", "0000000002", "0000000003"],
                "section_no": [1, 1, 1],
            })
            customers = pd.DataFrame({
                "customer_id": ["customer-1"], "age": [30],
                "club_member_status": ["ACTIVE"], "fashion_news_frequency": ["Regularly"],
                "FN": [1.0], "Active": [1.0],
            })
            purchases = []
            for day in range(1, 11):
                for article in articles.article_id:
                    purchases.append({"t_dat": f"2020-08-{day:02d}", "customer_id": "customer-1",
                                       "article_id": article, "sales_channel_id": 2,
                                       "price": 0.01 + 0.01 * int(article[-1])})
            # One training target week and one validation target week.
            for date in ("2020-09-03", "2020-09-10"):
                for article in articles.article_id:
                    purchases.append({"t_dat": date, "customer_id": "customer-1",
                                       "article_id": article, "sales_channel_id": 2,
                                       "price": 0.01 + 0.01 * int(article[-1])})
            transactions = pd.DataFrame(purchases)
            transactions["t_dat"] = pd.to_datetime(transactions["t_dat"])
            normalized = root / "hm" / "normalized"
            normalized.mkdir(parents=True)
            articles.to_parquet(normalized / "articles.parquet", index=False)
            customers.to_parquet(normalized / "customers.parquet", index=False)
            transactions.to_parquet(normalized / "transactions.parquet", index=False)

            rows = content_rows(articles)
            vocab = Vocab.build(transactions[transactions.t_dat < config.VALIDATE_REQUEST], articles)
            vocab.save(mode_dir / "vocab.json")
            vectors = np.zeros((len(articles), 384), dtype=np.float32)
            vectors[:, 0] = 1.0
            save_content(content_dir, vectors, articles.article_id.tolist())
            train_examples, eval_examples = generate_examples(
                vocab, transactions, customers, config.VALIDATE_REQUEST, rows, max_train=10,
            )
            self.assertTrue(train_examples)
            self.assertTrue(eval_examples)
            price_ids = set(vocab.price_ids)
            item_ids = set(vocab.item_ids)
            history_id, page_id = vocab.id("SEP_HISTORY"), vocab.id("SEP_PAGE")
            for example in train_examples + eval_examples:
                tokens = [int(token) for token in example.ctx_tokens]
                history_at = tokens.index(history_id)
                page_at = tokens.index(page_id, history_at + 1)
                event = tokens[history_at + 1:page_at]
                # A cold-start prompt has no history event and so no price.
                # Every real event must carry exactly one PRICE_ token.
                self.assertEqual(sum(token in price_ids for token in event),
                                 sum(token in item_ids for token in event))
            write_examples(mode_dir, "train", train_examples)
            write_examples(mode_dir, "eval", eval_examples)

            with patch.dict(os.environ, {"GENPAGE_DATA": str(root)}):
                for context in ("items", "full"):
                    checkpoint = mode_dir / "ckpt" / f"smoke-small-{context}"
                    train_args = argparse.Namespace(
                        mode="validate", preset="small", context=context, epochs=1, max_steps=3,
                        batch=1, lr=3e-4, warmup=0, name=checkpoint.name, data_dir=str(mode_dir),
                        out=str(checkpoint), device="cpu", eval_every=1000,
                        eval_examples=10, log_every=3, seed=7, maxlen=256, fallback_prob=0.0,
                    )
                    train(train_args)
                    prompts = []
                    original_next_batch = PageDecoder._next_logits_batch

                    def record_decoder_prompt(decoder, sequences, caches=None, *, use_cache=True):
                        prompts.append([(list(tokens), list(content)) for tokens, content in sequences])
                        return original_next_batch(decoder, sequences, caches, use_cache=use_cache)

                    # This wraps the real evaluation decoder: captured sequences
                    # are after checkpoint extra.context has selected its view.
                    with patch.object(PageDecoder, "_next_logits_batch", new=record_decoder_prompt):
                        report = run(argparse.Namespace(
                            mode="validate", ckpt=str(checkpoint), limit=1, data_dir=str(root), out=None,
                            batch=1, device="cpu", baselines_only=False,
                        ))
                    self.assertIn("model", report["results"])
                    self.assertEqual(report["results"]["model"]["violations"], 0)
                    prompt_tokens = prompts[0][0][0]
                    if context == "items":
                        forbidden = ("SEP_PROFILE", "SEP_REQUEST", "ACT_", "AGO_", "PRICE_")
                        self.assertFalse(any(vocab.tokens[token] == name or vocab.tokens[token].startswith(name)
                                             for token in prompt_tokens for name in forbidden))
                    else:
                        self.assertTrue(any(vocab.tokens[token].startswith("PRICE_") for token in prompt_tokens))
