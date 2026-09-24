import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
import torch

from genpage2.model import GenPageV2, ModelConfig, load_checkpoint, save_checkpoint
from genpage2.train_pretrain import (
    NpzExamples,
    add_page_content,
    batch_loss,
    history_only_context,
    make_batch,
    replace_item_inputs,
    truncate_context_page,
)


class FakeVocab:
    tokens = ["PAD", "BOS", "EOS", "SEP_PROFILE", "SEP_REQUEST", "SEP_HISTORY", "SEP_PAGE",
              "ITEM_FALLBACK", "AGE_20-24", "DOW_3", "ACT_ONLINE", "AGO_4-7", "ROW_S1",
              "ITEM_A", "ITEM_B"]
    item_ids = range(13, 15)
    article_of = {13: "a", 14: "b"}

    def id(self, name):
        return self.tokens.index(name)

    @classmethod
    def load(cls, path):
        payload = json.loads(Path(path).read_text(encoding="utf-8"))
        vocab = cls()
        vocab.tokens = payload["tokens"]
        start, stop = payload["item_range"]
        vocab.item_ids = range(start, stop)
        vocab.article_of = {int(token): article for token, article in payload["article_of"].items()}
        return vocab


class PretrainTest(unittest.TestCase):
    def setUp(self):
        self.vocab = FakeVocab()
        self.context = np.array([1, 3, 8, 4, 9, 5, 13, 10, 11, 14, 10, 11, 6])
        self.content = np.array([-1, -1, -1, -1, -1, -1, 0, -1, -1, 1, -1, -1, -1])
        self.page = np.array([12, 13, 2])

    def test_truncation_drops_oldest_complete_history_events(self):
        ctx, rows, page, removed = truncate_context_page(self.context, self.content, self.page, maxlen=10,
                                                           sep_history=5, sep_page=6)
        self.assertEqual(removed, 6)
        self.assertEqual(ctx.tolist(), [1, 3, 8, 4, 9, 5, 6])
        self.assertEqual(rows.tolist(), [-1] * 7)
        self.assertEqual(page.tolist(), [12, 13, 2])

    def test_loss_only_targets_page_tokens(self):
        batch = make_batch([(self.context, self.content, self.page)], vocab=self.vocab, maxlen=32)
        # SEP_PAGE predicts ROW, ROW predicts ITEM, ITEM predicts EOS.
        self.assertEqual(batch["loss_mask"][0, :].tolist(), [False] * 12 + [True, True, True])

    def test_fallback_replaces_inputs_but_not_targets(self):
        original = torch.tensor([[1, 13, 10, 14, 2]])
        replaced = replace_item_inputs(original, self.vocab.item_ids, 7, 1.0)
        self.assertEqual(replaced.tolist(), [[1, 7, 10, 7, 2]])
        self.assertEqual(original.tolist(), [[1, 13, 10, 14, 2]])

    def test_batch_loss_projects_only_masked_positions(self):
        cfg = ModelConfig(vocab_size=len(self.vocab.tokens), dim=8, layers=1, heads=2, ffn=16,
                          dropout=0, maxlen=8, content_dim=2)
        model = GenPageV2(cfg, tokens=self.vocab.tokens)
        batch = make_batch([(np.array([1, 5, 6]), np.array([-1, -1, -1]), np.array([12, 13, 2]))],
                           vocab=self.vocab, maxlen=8)
        loss, logits = batch_loss(model, batch, item_range=self.vocab.item_ids, fallback_id=7, fallback_prob=0)
        self.assertEqual(tuple(logits.shape), (int(batch["loss_mask"].sum()), len(self.vocab.tokens)))
        self.assertTrue(torch.isfinite(loss))

    def test_history_context_keeps_only_item_tokens(self):
        tokens, rows = history_only_context(self.context, self.content, self.vocab)
        self.assertEqual(tokens.tolist(), [1, 5, 13, 14, 6])
        self.assertEqual(rows.tolist(), [-1, -1, 0, 1, -1])

    def test_history_truncation_removes_one_token_events_and_keeps_page(self):
        long_ctx = np.array([1, 3, 8, 4, 9, 5, 13, 10, 11, 14, 10, 11,
                             13, 10, 11, 14, 10, 11, 13, 10, 11, 6])
        long_content = np.full(len(long_ctx), -1, dtype=np.int64)
        long_content[[6, 9, 12, 15, 18]] = [0, 1, 0, 1, 0]
        batch = make_batch([(long_ctx, long_content, self.page)], vocab=self.vocab,
                           maxlen=6, context="history")
        self.assertEqual(batch["tokens"].shape[1], 6)
        self.assertEqual(batch["tokens"][0, -3:].tolist(), self.page.tolist())
        self.assertEqual(batch["loss_mask"][0, -3:].tolist(), [True, True, True])

    def test_npz_examples_slice_by_offsets(self):
        with tempfile.TemporaryDirectory() as directory:
            path = f"{directory}/train.npz"
            np.savez(path, ctx_tokens=np.array([1, 6, 1, 5, 6]), ctx_offsets=np.array([0, 2, 5]),
                     ctx_content=np.array([-1, -1, -1, -1, -1]), page_tokens=np.array([12, 2, 12, 13, 2]),
                     page_offsets=np.array([0, 2, 5]))
            data = NpzExamples(path)
            self.assertEqual(len(data), 2)
            self.assertEqual(data[1][0].tolist(), [1, 5, 6])
            self.assertEqual(data[1][2].tolist(), [12, 13, 2])

    def test_small_rule_dataset_learns_and_roundtrips(self):
        torch.manual_seed(4)
        cfg = ModelConfig(vocab_size=len(self.vocab.tokens), dim=16, layers=1, heads=2, ffn=32,
                          dropout=0, maxlen=8, content_dim=2, fallback_prob=0)
        model = GenPageV2(cfg, tokens=self.vocab.tokens)
        # Every prompt has the same deterministic page: ROW_S1, ITEM_A, EOS.
        examples = [(np.array([1, 5, 6]), np.array([-1, -1, -1]), np.array([12, 13, 2])) for _ in range(16)]
        batch = make_batch(examples, vocab=self.vocab, maxlen=8)
        optimizer = torch.optim.AdamW(model.parameters(), lr=0.03)
        first = None
        for _ in range(100):
            optimizer.zero_grad()
            loss, _ = batch_loss(model, batch, item_range=self.vocab.item_ids, fallback_id=7, fallback_prob=0)
            if first is None:
                first = float(loss.detach())
            loss.backward()
            optimizer.step()
        self.assertLess(float(loss.detach()), first / 2)
        with tempfile.TemporaryDirectory() as directory:
            save_checkpoint(directory, model, cfg, {"final_loss": float(loss.detach())})
            restored, _, _ = load_checkpoint(directory)
            self.assertTrue(torch.allclose(model.logits(model(batch["tokens"])),
                                           restored.logits(restored(batch["tokens"]))))

    def test_train_loop_with_injected_vocab_loader(self):
        from genpage2.train_pretrain import parse_args, train

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data_dir = root / "validate"
            content_dir = root / "content"
            data_dir.mkdir()
            content_dir.mkdir()
            (data_dir / "vocab.json").write_text(json.dumps({
                "tokens": self.vocab.tokens, "item_range": [13, 15],
                "article_of": {"13": "a", "14": "b"},
            }), encoding="utf-8")
            np.save(content_dir / "content_e5.npy", np.zeros((2, 384), dtype=np.float16))
            (content_dir / "articles.json").write_text(json.dumps(["a", "b"]), encoding="utf-8")

            contexts = np.tile(self.context, 10)
            contents = np.tile(self.content, 10)
            pages = np.tile(self.page, 10)
            offsets = np.arange(0, 11 * len(self.context), len(self.context), dtype=np.int64)
            page_offsets = np.arange(0, 11 * len(self.page), len(self.page), dtype=np.int64)
            np.savez(data_dir / "train.npz", ctx_tokens=contexts, ctx_offsets=offsets,
                     ctx_content=contents, page_tokens=pages, page_offsets=page_offsets)
            np.savez(data_dir / "eval.npz", ctx_tokens=self.context, ctx_offsets=np.array([0, len(self.context)]),
                     ctx_content=self.content, page_tokens=self.page, page_offsets=np.array([0, len(self.page)]))

            output = root / "checkpoint"
            args = parse_args(["--mode", "validate", "--preset", "small", "--context", "full",
                               "--epochs", "1", "--max-steps", "5", "--batch", "2", "--warmup", "1",
                               "--eval-every", "2", "--eval-examples", "1", "--log-every", "1",
                               "--fallback-prob", "0.5", "--data-dir", str(data_dir), "--out", str(output),
                               "--device", "cpu", "--maxlen", "16"])
            result = train(args, vocab_loader=FakeVocab.load)
            self.assertEqual(result["steps"], 5)
            self.assertTrue((output / "model.pt").exists())
            self.assertTrue((output / "config.json").exists())
            records = [json.loads(line) for line in (output / "train_log.jsonl").read_text().splitlines()]
            self.assertTrue(any("eval" in record for record in records))


if __name__ == "__main__":
    unittest.main()
