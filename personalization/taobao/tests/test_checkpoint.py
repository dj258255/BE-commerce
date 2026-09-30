"""체크포인트 저장 · 재사용 — 저장 전후 예측이 같고, 설정이 다르면 멈춘다."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import numpy as np
import torch

from taobao import config, seqmodel
from taobao.tests.helpers import synthetic_split

CONFIG_NAME = "d64_l2"


def _exposure(item, ts, click, cate=1):
    return (item, cate, cate * 10, cate * 100, cate * 1000, 3, ts, click)


def _sequence_and_split():
    split = synthetic_split("valid", [[
        _exposure(1, 10, 1),
        _exposure(2, 20, 0),
        _exposure(3, 30, 1),
        _exposure(4, 40, 0),
    ]])
    return seqmodel.build_sequences(split)[0], split


class CheckpointRoundTripTest(unittest.TestCase):
    def setUp(self):
        torch.manual_seed(3)
        self.model = seqmodel.build_model(config.SEQ_CONFIGS[CONFIG_NAME]["dim"],
                                          config.SEQ_CONFIGS[CONFIG_NAME]["layers"])
        self.model.eval()

    def _logits(self, model):
        sequence, split = _sequence_and_split()
        batch = seqmodel._collate(torch, [sequence], split.user_feats.astype(np.int64), False)
        with torch.no_grad():
            return model(batch, "cpu")[0, :len(sequence.q_pos)].numpy()

    def test_saved_and_loaded_model_predict_the_same(self):
        before = self._logits(self.model)
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "d64_l2.pt"
            seqmodel.save_checkpoint(path, self.model, config_name=CONFIG_NAME, no_unclicked=False,
                                     trained_epochs=1, best_epoch=0, best_valid_bundle_gauc=0.42)
            loaded, payload = seqmodel.load_checkpoint(path, CONFIG_NAME, False, device="cpu")
        after = self._logits(loaded)
        self.assertTrue(np.allclose(before, after, atol=1e-6))
        # 복원에 필요한 기록이 남는다.
        self.assertEqual(payload["config"], CONFIG_NAME)
        self.assertEqual(payload["trained_epochs"], 1)
        self.assertEqual(payload["best_epoch"], 0)
        self.assertAlmostEqual(payload["best_valid_bundle_gauc"], 0.42)


class CheckpointMismatchTest(unittest.TestCase):
    def setUp(self):
        torch.manual_seed(4)
        self.model = seqmodel.build_model(config.SEQ_CONFIGS[CONFIG_NAME]["dim"],
                                          config.SEQ_CONFIGS[CONFIG_NAME]["layers"])
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "d64_l2.pt"
        seqmodel.save_checkpoint(self.path, self.model, config_name=CONFIG_NAME, no_unclicked=False,
                                 trained_epochs=1, best_epoch=0, best_valid_bundle_gauc=0.42)

    def tearDown(self):
        self.directory.cleanup()

    def test_config_mismatch_raises(self):
        with self.assertRaises(ValueError):
            seqmodel.load_checkpoint(self.path, "d128_l2", False, device="cpu")

    def test_no_unclicked_mismatch_raises(self):
        with self.assertRaises(ValueError):
            seqmodel.load_checkpoint(self.path, CONFIG_NAME, True, device="cpu")

    def test_missing_file_raises(self):
        with self.assertRaises(FileNotFoundError):
            seqmodel.load_checkpoint("없는파일.pt", CONFIG_NAME, False, device="cpu")


class CheckpointPathTest(unittest.TestCase):
    def test_naming(self):
        self.assertEqual(seqmodel.checkpoint_path("d64_l2", False).name, "d64_l2.pt")
        self.assertEqual(seqmodel.checkpoint_path("d128_l2", True).name, "d128_l2_noclick.pt")


if __name__ == "__main__":
    unittest.main()
