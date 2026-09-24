import tempfile
import unittest

import numpy as np
import torch

from genpage2.model import (
    GenPageV2,
    ModelConfig,
    TOKEN_TYPE_ACTION,
    TOKEN_TYPE_ITEM,
    TOKEN_TYPE_PROFILE,
    TOKEN_TYPE_REQUEST,
    TOKEN_TYPE_ROW,
    TOKEN_TYPE_SPECIAL,
    TOKEN_TYPE_TIME,
    load_checkpoint,
    save_checkpoint,
    token_types,
)


class ModelTest(unittest.TestCase):
    def setUp(self):
        torch.manual_seed(3)
        self.names = ["PAD", "BOS", "EOS", "SEP_PAGE", "ITEM_FALLBACK", "AGE_20-24",
                      "DOW_3", "ACT_ONLINE", "AGO_4-7", "ROW_S1", "ITEM_010"]
        self.cfg = ModelConfig(vocab_size=len(self.names), dim=16, layers=1, heads=2, ffn=32,
                               dropout=0, maxlen=12, content_dim=3)

    def test_input_and_output_weights_are_not_tied(self):
        model = GenPageV2(self.cfg, tokens=self.names)
        self.assertNotEqual(model.token_embedding.weight.data_ptr(), model.output_projection.weight.data_ptr())

    def test_causal_hidden_states_ignore_future_tokens(self):
        model = GenPageV2(self.cfg, tokens=self.names).eval()
        first = torch.tensor([[1, 5, 6, 7]])
        changed = torch.tensor([[1, 5, 9, 10]])
        self.assertTrue(torch.allclose(model(first)[:, :2], model(changed)[:, :2], atol=1e-6))

    def test_content_is_added_only_for_valid_rows(self):
        content = torch.tensor([[1.0, 2.0, 3.0]])
        model = GenPageV2(self.cfg, content=content, tokens=self.names)
        tokens = torch.tensor([[10, 10]])
        no_content = model.input_embeddings(tokens, torch.tensor([[-1, -1]]))
        fused = model.input_embeddings(tokens, torch.tensor([[0, -1]]))
        self.assertFalse(torch.allclose(no_content[:, 0], fused[:, 0]))
        self.assertTrue(torch.allclose(no_content[:, 1], fused[:, 1]))
        disabled = GenPageV2(self.cfg, content=None, tokens=self.names)
        self.assertTrue(torch.allclose(disabled.input_embeddings(tokens, torch.tensor([[0, -1]])),
                                       disabled.input_embeddings(tokens, torch.tensor([[-1, -1]]))))

    def test_token_type_prefixes(self):
        got = token_types(self.names)
        self.assertEqual(got.tolist(), [TOKEN_TYPE_SPECIAL, TOKEN_TYPE_SPECIAL, TOKEN_TYPE_SPECIAL,
                                         TOKEN_TYPE_SPECIAL, TOKEN_TYPE_SPECIAL, TOKEN_TYPE_PROFILE,
                                         TOKEN_TYPE_REQUEST, TOKEN_TYPE_ACTION, TOKEN_TYPE_TIME,
                                         TOKEN_TYPE_ROW, TOKEN_TYPE_ITEM])

    def test_checkpoint_roundtrip(self):
        model = GenPageV2(self.cfg, tokens=self.names).eval()
        inputs = torch.tensor([[1, 5, 10]])
        with tempfile.TemporaryDirectory() as directory:
            save_checkpoint(directory, model, self.cfg, {"answer": 42})
            restored, cfg, extra = load_checkpoint(directory)
            restored.eval()
            self.assertEqual(cfg, self.cfg)
            self.assertEqual(extra["answer"], 42)
            self.assertTrue(torch.equal(model.logits(model(inputs)), restored.logits(restored(inputs))))

    def test_content_checkpoint_requires_matching_matrix(self):
        content = torch.ones(2, 3)
        model = GenPageV2(self.cfg, content=content, tokens=self.names)
        with tempfile.TemporaryDirectory() as directory:
            save_checkpoint(directory, model, self.cfg)
            with self.assertRaises(ValueError):
                load_checkpoint(directory)
            with self.assertRaises(ValueError):
                load_checkpoint(directory, torch.ones(1, 3))
            restored, _, _ = load_checkpoint(directory, content.clone())
            self.assertEqual(list(restored.content.shape), [2, 3])
            model.eval()
            restored.eval()
            tokens = torch.tensor([[1, 5, 10]])
            content_idx = torch.tensor([[-1, 0, 1]])
            self.assertTrue(torch.allclose(model.logits(model(tokens, content_idx)),
                                           restored.logits(restored(tokens, content_idx))))


if __name__ == "__main__":
    unittest.main()
