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
from genpage2.evaluate import run
from genpage2.train_pretrain import train
from genpage2.vocab import Vocab, content_rows


class RealPipelineIntegrationTest(unittest.TestCase):
    def test_dataset_content_train_and_evaluate(self):
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
                                       "article_id": article, "sales_channel_id": 2})
            # One training target week and one validation target week.
            for date in ("2020-09-03", "2020-09-10"):
                for article in articles.article_id:
                    purchases.append({"t_dat": date, "customer_id": "customer-1",
                                       "article_id": article, "sales_channel_id": 2})
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
            write_examples(mode_dir, "train", train_examples)
            write_examples(mode_dir, "eval", eval_examples)

            train_args = argparse.Namespace(
                mode="validate", preset="small", context="full", epochs=1, max_steps=3,
                batch=1, lr=3e-4, warmup=0, name="smoke-small-full", data_dir=str(mode_dir),
                out=str(mode_dir / "ckpt" / "smoke-small-full"), device="cpu", eval_every=1000,
                eval_examples=10, log_every=3, seed=7, maxlen=256, fallback_prob=0.0,
            )
            with patch.dict(os.environ, {"GENPAGE_DATA": str(root)}):
                train(train_args)
                report = run(argparse.Namespace(
                    mode="validate", ckpt=str(mode_dir / "ckpt" / "smoke-small-full"),
                    limit=1, data_dir=str(root), out=None, batch=1, device="cpu", baselines_only=False,
                ))
            self.assertIn("model", report["results"])
            self.assertEqual(report["results"]["model"]["violations"], 0)
