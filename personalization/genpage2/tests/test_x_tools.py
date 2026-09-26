from __future__ import annotations

import argparse
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
import pandas as pd


ROOT = Path(__file__).resolve().parents[3]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.genpage2_x1_context import compact_json_bytes, make_level_request, mismatch_rate
from tools.genpage2_x4_budget import build_grid, percentile, summarize_server_responses


def _measurement_fixture(root: Path) -> tuple[Path, Path]:
    """Create one tiny, real GenPage data/checkpoint tree for end-to-end tests."""
    import torch

    from genpage2 import config
    from genpage2.content import save_content
    from genpage2.dataset import build_context
    from genpage2.model import GenPageV2, ModelConfig, save_checkpoint
    from genpage2.vocab import Vocab, content_rows

    mode_dir = root / "hm" / "model" / "genpage2" / "validate"
    content_dir = mode_dir.parent / "content"
    normalized = root / "hm" / "normalized"
    mode_dir.mkdir(parents=True)
    content_dir.mkdir(parents=True)
    normalized.mkdir(parents=True)
    articles = pd.DataFrame({
        "article_id": ["0000000001", "0000000002", "0000000003"],
        "section_no": [1, 1, 1],
    })
    customers = pd.DataFrame({
        "customer_id": ["customer-1"], "age": [30],
        "club_member_status": ["ACTIVE"], "fashion_news_frequency": ["Regularly"],
        "FN": [1.0], "Active": [1.0],
    })
    transactions = pd.DataFrame([
        {"t_dat": pd.Timestamp("2020-08-01") + pd.Timedelta(days=day),
         "customer_id": "customer-1", "article_id": article,
         "sales_channel_id": 2, "price": 0.01 + 0.01 * int(article[-1])}
        for day in range(10) for article in articles["article_id"]
    ])
    articles.to_parquet(normalized / "articles.parquet", index=False)
    customers.to_parquet(normalized / "customers.parquet", index=False)
    transactions.to_parquet(normalized / "transactions.parquet", index=False)
    rows = content_rows(articles)
    vocab = Vocab.build(transactions, articles)
    vocab.save(mode_dir / "vocab.json")
    vectors = np.zeros((len(articles), 384), dtype=np.float32)
    vectors[:, 0] = 1.0
    save_content(content_dir, vectors, articles["article_id"].tolist())
    profile = customers.iloc[0].to_dict()
    context, context_content = build_context(vocab, transactions, config.VALIDATE_REQUEST, profile, rows)
    history = transactions.sort_values("t_dat", kind="mergesort")["article_id"].tolist()[::-1]
    pd.DataFrame({"customer_id": ["customer-1"], "request_date": [config.VALIDATE_REQUEST],
                  "truth": [["0000000001"]], "history": [history]}).to_parquet(
        mode_dir / "eval_meta.parquet", index=False
    )
    np.savez(mode_dir / "eval.npz", ctx_tokens=np.asarray(context, dtype=np.int32),
             ctx_offsets=np.asarray([0, len(context)], dtype=np.int64),
             ctx_content=np.asarray(context_content, dtype=np.int32))
    cfg = ModelConfig(vocab_size=len(vocab.tokens), dim=16, layers=1, heads=2, ffn=32,
                      dropout=0.0, maxlen=128, content_dim=384)
    model = GenPageV2(cfg, content=torch.tensor(vectors), tokens=vocab.tokens)
    checkpoint = mode_dir / "ckpt" / "synthetic"
    save_checkpoint(checkpoint, model, cfg, {"context": "full"})
    return checkpoint, mode_dir


class X1ToolTest(unittest.TestCase):
    def setUp(self):
        self.transactions = pd.DataFrame({
            "t_dat": ["2020-09-01", "2020-09-02", "2020-09-03"],
            "article_id": ["1", "2", "1"],
            "sales_channel_id": [1, 2, 1],
            "price": [10.0, 20.0, 30.0],
        })

    def test_level_requests_only_add_the_documented_fields(self):
        history = ["1", "2"]  # application wire order: newest first
        request, events, profile, now = make_level_request("b", history, self.transactions, {"age": 30}, "2020-09-09")
        self.assertEqual(request, {"history": ["0000000001", "0000000002"]})
        self.assertEqual(events, [{"item": "0000000002"}, {"item": "0000000001"}])
        self.assertIsNone(profile)
        self.assertIsNone(now)

        _, events, profile, now = make_level_request("c", history, self.transactions, {"age": 30}, "2020-09-09")
        self.assertEqual(set(events[0]), {"item", "at"})
        self.assertIsNone(profile)
        self.assertIsNone(now)

        _, events, _, _ = make_level_request("d", history, self.transactions, {"age": 30}, "2020-09-09")
        self.assertEqual(set(events[0]), {"item", "at", "price", "action"})
        self.assertEqual(events[0]["action"], "ONLINE")

        request, events, profile, now = make_level_request("e", history, self.transactions, {"age": 30}, "2020-09-09")
        self.assertEqual(len(events), 3)
        self.assertEqual(profile, {"age": 30})
        self.assertEqual(now, "2020-09-09T00:00:00")
        self.assertEqual(request["events"], events)

    def test_mismatch_rate_counts_positions_and_length(self):
        self.assertEqual(mismatch_rate([1, 2, 3], [1, 9]), (2 / 3, 2))
        self.assertEqual(mismatch_rate([], []), (0.0, 0))
        self.assertEqual(compact_json_bytes({"history": ["가"]}), len('{"history":["가"]}'.encode("utf-8")))


class X4ToolTest(unittest.TestCase):
    def test_generate_batch_matches_repeated_generate(self):
        try:
            from genpage2.tests.test_decode import FakeModel, FakeVocab
            from genpage2.decode import PageDecoder
        except ModuleNotFoundError as exc:
            self.skipTest(f"decoder runtime unavailable: {exc}")
        vocab = FakeVocab()
        decoder = PageDecoder(FakeModel(), vocab, {article: index for index, article in enumerate("ABCDEF")}, "cpu")
        examples = [
            {"ctx_tokens": [1, 2], "ctx_content": [-1, -1], "history_articles": []},
            {"ctx_tokens": [1, 3, 4, 5, 6, 2], "ctx_content": [-1] * 6, "history_articles": ["A"]},
        ]
        kwargs = {"n_rows": 1, "items_per_row": 3, "prefix": 1}
        batched = decoder.generate_batch(examples, **kwargs)
        repeated = [decoder.generate(**example, **kwargs) for example in examples]
        self.assertEqual(batched, repeated)

    def test_grid_and_percentile(self):
        grid = build_grid()
        self.assertEqual(len(grid), 12)
        self.assertIn({"prefix": 8, "use_cache": False, "rows": 6}, grid)
        self.assertEqual(percentile([0, 10, 20], 95), 19.0)

    def test_server_aggregation_uses_fake_response_fields(self):
        report = summarize_server_responses([
            {"latency_ms": 10, "body": {"ms": 7, "queue_ms": 1}},
            {"latency_ms": 30, "body": {"ms": 11, "queue_ms": 5}},
        ], elapsed_seconds=2.0, errors=1)
        self.assertEqual(report["throughput"], 1.0)
        self.assertEqual(report["errors"], 1)
        self.assertEqual(report["wall"]["p50_ms"], 20.0)
        self.assertEqual(report["inference_ms"]["p95_ms"], 10.8)
        self.assertEqual(report["queue_ms"]["mean_ms"], 3.0)


class ToolRunIntegrationTest(unittest.TestCase):
    def test_x1_and_x4_run_end_to_end_without_server(self):
        try:
            import torch  # noqa: F401
        except ModuleNotFoundError as exc:
            self.skipTest(f"checkpoint runtime unavailable: {exc}")
        from tools.genpage2_x1_context import run as run_x1
        from tools.genpage2_x4_budget import run as run_x4

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            checkpoint, _ = _measurement_fixture(root)
            x1_out = root / "x1-out"
            x1 = run_x1(argparse.Namespace(
                mode="validate", ckpt=checkpoint, customers=1, device="cpu",
                out=x1_out, data_dir=root,
            ))
            self.assertTrue((x1_out / "x1.json").exists())
            self.assertFalse(x1["consistency_violation"])
            self.assertEqual(x1["levels"]["e"]["token_mismatch_rate"], 0.0)
            self.assertIn("total", x1["timings_ms"])
            self.assertIn("generation_total_ms", x1["levels"]["e"])

            x4_out = root / "x4-out"
            x4 = run_x4(argparse.Namespace(
                mode="validate", ckpt=checkpoint, customers=1, device="cpu",
                out=x4_out, server=None, server_seconds=30.0, data_dir=root,
            ))
            self.assertTrue((x4_out / "x4.json").exists())
            self.assertEqual(len(x4["direct"]), 12)
            self.assertIsNone(x4["server"])


if __name__ == "__main__":
    unittest.main()
