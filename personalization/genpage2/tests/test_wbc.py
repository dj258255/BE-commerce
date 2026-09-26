from __future__ import annotations

import argparse
import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
import pandas as pd
import torch

from genpage2.content import save_content
from genpage2.decode import GeneratedRow
from genpage2.evaluate import _load_decoder
from genpage2.model import GenPageV2, ModelConfig, save_checkpoint
from genpage2.vocab import AGE_BUCKETS, CLUB_VALUES, NEWS_VALUES, SPECIAL, Vocab
from genpage2.wbc import exposure_from_rows, make_wbc_batch, run, train, wbc_loss, weighted_auc


ARTICLES = [f"{index:010d}" for index in range(1, 6)]


def real_vocab() -> Vocab:
    profile = ([f"AGE_{value}" for value in AGE_BUCKETS]
               + [f"CLUB_{value}" for value in CLUB_VALUES]
               + [f"NEWS_{value}" for value in NEWS_VALUES]
               + ["FN_1", "FN_NA", "ACTIVE_1", "ACTIVE_NA"])
    request = [f"DOW_{value}" for value in range(7)] + [f"MONTH_{value}" for value in range(1, 13)]
    actions = ["ACT_STORE", "ACT_ONLINE", "ACT_VIEW", "ACT_CLICK"]
    ago = ["AGO_0-3", "AGO_4-7", "AGO_8-14", "AGO_15-30", "AGO_31-60", "AGO_61-120", "AGO_121-365", "AGO_366+"]
    prices = [f"PRICE_{value}" for value in range(8)]
    rows = ["ROW_REPEAT", "ROW_S1"]
    tokens = SPECIAL + profile + request + actions + ago + prices + rows + [f"ITEM_{article}" for article in ARTICLES]
    row_token = tokens.index("ROW_S1")
    article_rows = {article: row_token for article in ARTICLES}
    return Vocab(tokens, article_rows)


def base_context(vocab: Vocab) -> tuple[list[int], list[int]]:
    tokens = [vocab.id("BOS"), vocab.id("SEP_PROFILE"), vocab.id("AGE_NA"), vocab.id("CLUB_NA"),
              vocab.id("NEWS_NA"), vocab.id("FN_NA"), vocab.id("ACTIVE_NA"), vocab.id("SEP_REQUEST"),
              vocab.id("DOW_0"), vocab.id("MONTH_1"), vocab.id("SEP_HISTORY"), vocab.id("SEP_PAGE")]
    return tokens, [-1] * len(tokens)


class WbcTest(unittest.TestCase):
    def setUp(self):
        self.vocab = real_vocab()
        self.ctx, self.ctx_content = base_context(self.vocab)

    def test_item_and_row_labels_and_weights(self):
        rows = [GeneratedRow(self.vocab.id("ROW_S1"), ["0000000001", "0000000002", "0000000003"])]
        exposure = exposure_from_rows(rows, ["0000000001", "0000000004"], self.vocab,
                                      self.ctx, self.ctx_content, w_pos=2.0, w_neg=1.0)
        self.assertEqual(exposure.page_tokens,
                         [self.vocab.id("ROW_S1"), self.vocab.item("0000000001"),
                          self.vocab.item("0000000002"), self.vocab.item("0000000003")])
        # 행: 산 상품 1개 → 라벨 1, 가중치 1×2.0 / 상품: 1은 양성(2.0), 2·3은 음성(1.0)
        self.assertEqual(exposure.labels, [1.0, 1.0, 0.0, 0.0])
        self.assertEqual(exposure.weights, [2.0, 2.0, 1.0, 1.0])

    def test_row_weight_scales_with_purchased_items(self):
        rows = [GeneratedRow(self.vocab.id("ROW_REPEAT"), ["0000000001", "0000000002"])]
        exposure = exposure_from_rows(rows, ["0000000001", "0000000002"], self.vocab,
                                      self.ctx, self.ctx_content, w_pos=3.0, w_neg=1.0)
        self.assertEqual(exposure.labels, [1.0, 1.0, 1.0])
        self.assertEqual(exposure.weights, [6.0, 3.0, 3.0])

    def test_row_and_item_without_purchase_are_negative(self):
        rows = [GeneratedRow(self.vocab.id("ROW_S1"), ["0000000002", "0000000003"])]
        exposure = exposure_from_rows(rows, ["0000000001"], self.vocab,
                                      self.ctx, self.ctx_content, w_pos=5.0, w_neg=2.0)
        self.assertEqual(exposure.labels, [0.0, 0.0, 0.0])
        self.assertEqual(exposure.weights, [2.0, 2.0, 2.0])

    def test_loss_mask_covers_only_generated_token_positions(self):
        rows = [GeneratedRow(self.vocab.id("ROW_S1"), ["0000000001", "0000000002", "0000000003"])]
        exposure = exposure_from_rows(rows, ["0000000001"], self.vocab, self.ctx, self.ctx_content)
        batch = make_wbc_batch([exposure], vocab=self.vocab, level="full", maxlen=64, device="cpu")
        self.assertEqual(int(batch["mask"].sum()), len(exposure.page_tokens))
        positions = torch.nonzero(batch["mask"][0]).flatten().tolist()
        self.assertEqual(positions, list(range(len(self.ctx) - 1,
                                               len(self.ctx) - 1 + len(exposure.page_tokens))))
        self.assertEqual(batch["target"][0][batch["mask"][0]].tolist(), exposure.page_tokens)

    def test_training_makes_positive_item_logits_higher(self):
        torch.manual_seed(0)
        rows = [GeneratedRow(self.vocab.id("ROW_S1"), ["0000000001", "0000000002"])]
        exposure = exposure_from_rows(rows, ["0000000001"], self.vocab, self.ctx, self.ctx_content)
        exposures = [exposure for _ in range(64)]
        cfg = ModelConfig(vocab_size=len(self.vocab.tokens), dim=16, layers=1, heads=2, ffn=32,
                          dropout=0.0, maxlen=64, content_dim=384)
        model = GenPageV2(cfg, tokens=self.vocab.tokens)
        train(model, exposures, vocab=self.vocab, level="full", maxlen=64, device="cpu",
              epochs=40, batch=16, lr=0.1, warmup=5, eval_every=0)
        batch = make_wbc_batch([exposure], vocab=self.vocab, level="full", maxlen=64, device="cpu")
        model.eval()
        with torch.no_grad():
            _, logits, labels, _ = wbc_loss(model, batch)
            item = batch["is_item"][batch["mask"]]
            positive = float(logits[item & (labels > 0.5)].mean())
            negative = float(logits[item & (labels <= 0.5)].mean())
        self.assertGreater(positive, negative)

    def test_weighted_auc_ranks_and_ties(self):
        self.assertAlmostEqual(weighted_auc([2.0, 1.0], [1.0, 0.0], [1.0, 1.0]), 1.0)
        self.assertAlmostEqual(weighted_auc([1.0, 2.0], [1.0, 0.0], [1.0, 1.0]), 0.0)
        self.assertAlmostEqual(weighted_auc([1.0, 1.0], [1.0, 0.0], [1.0, 1.0]), 0.5)
        self.assertIsNone(weighted_auc([1.0, 1.0], [1.0, 1.0], [1.0, 1.0]))

    def test_run_saves_checkpoint_readable_by_evaluate_decoder(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            mode_dir = base / "hm" / "model" / "genpage2" / "validate"
            content_dir = base / "hm" / "model" / "genpage2" / "content"
            mode_dir.mkdir(parents=True)
            content_dir.mkdir(parents=True)
            vocab = real_vocab()
            vocab.save(mode_dir / "vocab.json")
            save_content(content_dir, np.zeros((len(ARTICLES), 384), dtype=np.float32), ARTICLES)
            context, content = base_context(vocab)
            truth = ["0000000001", "0000000004"]
            history = ["0000000001", "0000000002", "0000000003"]
            self._write_split(mode_dir, "train", [context] * 4, [content] * 4, [history] * 4, [truth] * 4)
            self._write_split(mode_dir, "eval", [context] * 2, [content] * 2, [history] * 2, [truth] * 2)

            cfg = ModelConfig(vocab_size=len(vocab.tokens), dim=16, layers=1, heads=2, ffn=32,
                              dropout=0.0, maxlen=64, content_dim=384)
            torch.manual_seed(0)
            init = GenPageV2(cfg, torch.zeros((len(ARTICLES), 384)), tokens=vocab.tokens)
            init_dir = base / "init"
            save_checkpoint(init_dir, init, cfg, {"context": "full"})

            output = base / "wbc-ckpt"
            report = run(argparse.Namespace(
                mode="validate", init=str(init_dir), examples=4, pin_repeat=True, epochs=1,
                batch=2, lr=1e-4, warmup=1, w_pos=1.0, w_neg=1.0, name="wbc-smoke", device="cpu",
                gen_batch=2, data_dir=str(base), out=str(output), eval_every=1, eval_examples=2,
                log_every=1, seed=7,
            ))
            self.assertEqual(report["violations"], 0)
            saved = json.loads((output / "config.json").read_text(encoding="utf-8"))["extra"]
            for key in ("init", "examples", "pin_repeat", "positive_ratio", "loss_curve", "context",
                        "generation_seconds", "train_seconds"):
                self.assertIn(key, saved)
            self.assertEqual(saved["context"], "full")
            self.assertTrue(saved["examples"] > 0)

            decoder, _, _, _, _ = _load_decoder(mode_dir, output, "cpu")
            examples = [{"ctx_tokens": context, "ctx_content": content, "history_articles": []}
                        for _ in range(2)]
            pages = decoder.generate_batch(examples, n_rows=6, items_per_row=8, prefix=2)
            self.assertTrue(all(bad == 0 for _, bad in pages))
            self.assertTrue(all(rows for rows, _ in pages))

    def _write_split(self, mode_dir: Path, name: str, contexts, contents, histories, truths) -> None:
        tokens = np.concatenate([np.asarray(value, dtype=np.int32) for value in contexts])
        rows = np.concatenate([np.asarray(value, dtype=np.int32) for value in contents])
        offsets = np.zeros(len(contexts) + 1, dtype=np.int64)
        offsets[1:] = np.cumsum([len(value) for value in contexts])
        page = np.concatenate([np.asarray([self.vocab.id("EOS")], dtype=np.int32) for _ in contexts])
        page_offsets = np.arange(0, len(contexts) + 1, dtype=np.int64)
        np.savez(mode_dir / f"{name}.npz", ctx_tokens=tokens, ctx_offsets=offsets, ctx_content=rows,
                 page_tokens=page, page_offsets=page_offsets)
        pd.DataFrame({"customer_id": [f"c{index}" for index in range(len(contexts))],
                      "request_date": [pd.Timestamp("2020-09-09")] * len(contexts),
                      "truth": truths, "history": histories}).to_parquet(mode_dir / f"{name}_meta.parquet")


if __name__ == "__main__":
    unittest.main()
