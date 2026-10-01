"""G 의 통계 곁채널 — 이름 · 행 대응 · 표준화 · 누설 · 체크포인트.

옵션을 안 주면 지금까지와 같게 동작해야 한다(T1 재현성). 곁채널을 켰을 때는
질의의 특징 행이 그 질의의 채점 행(q_row)과 같아야 하고, 표준화 통계는 학습
분할에서만 나와야 한다.
"""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import numpy as np
import torch

from taobao import config, features, seqmodel
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


def _logits(model, sequence, split, side=None):
    batch = seqmodel._collate(torch, [sequence], split.user_feats.astype(np.int64), False, side)
    with torch.no_grad():
        return model(batch, "cpu")[0, :len(sequence.q_pos)].numpy()


class SideNumericNamesTest(unittest.TestCase):
    """곁채널 이름은 R 의 수치 27개(고객 이력 15 + 전날까지 전체 통계 12)다."""

    def test_names_are_the_numeric_partition(self):
        self.assertEqual(len(features.SIDE_NUMERIC), 27)
        self.assertEqual(set(features.SIDE_NUMERIC), set(features.NUMERIC))
        self.assertEqual(len(features.SIDE_COUNT) + len(features.SIDE_RATE) + len(features.SIDE_AGO), 27)
        self.assertEqual(len(features.SIDE_AGO), 4)

    def test_design_has_value_and_indicator_columns(self):
        sequence, split = _sequence_and_split()
        matrix, labels = features.side_design(split)
        self.assertEqual(matrix.shape, (split.n_rows, 31))
        self.assertEqual(len(labels), 31)
        for name in features.SIDE_AGO:
            self.assertIn(f"{name}_missing", labels)


class NoOptionUnchangedTest(unittest.TestCase):
    """--side-features 를 안 주면 지금까지와 완전히 같게 동작한다."""

    def setUp(self):
        torch.manual_seed(0)
        self.sequence, self.split = _sequence_and_split()

    def test_no_side_module(self):
        model = seqmodel.build_model(16, 2, heads=2)
        self.assertIsNone(model.side)
        self.assertFalse(any(key.startswith("side") for key in model.state_dict()))

    def test_collate_keys_without_side(self):
        batch = seqmodel._collate(torch, [self.sequence], self.split.user_feats.astype(np.int64), False)
        self.assertNotIn("q_side", batch)
        self.assertEqual(set(batch), {
            "hist_item", "hist_cate", "hist_brand", "hist_campaign", "hist_price", "hist_click",
            "hist_gap", "hist_ts", "hist_pad", "q_item", "q_cate", "q_brand", "q_campaign",
            "q_price", "q_gap", "q_ts", "q_attr", "q_ctx", "q_label", "q_valid",
        })

    def test_side_dim_keeps_base_initialization(self):
        torch.manual_seed(0)
        plain = seqmodel.build_model(16, 2, heads=2)
        torch.manual_seed(0)
        with_side = seqmodel.build_model(16, 2, heads=2, side_dim=31)
        plain_state = plain.state_dict()
        side_state = with_side.state_dict()
        for key, value in plain_state.items():
            self.assertTrue(torch.equal(value, side_state[key]), key)

    def test_same_seed_is_deterministic(self):
        torch.manual_seed(1)
        first = seqmodel.build_model(16, 2, heads=2)
        torch.manual_seed(1)
        second = seqmodel.build_model(16, 2, heads=2)
        first.eval()
        second.eval()
        self.assertTrue(np.allclose(_logits(first, self.sequence, self.split),
                                    _logits(second, self.sequence, self.split), atol=1e-6))


class QueryRowTest(unittest.TestCase):
    """질의의 곁채널 행은 그 질의의 채점 행(q_row)이다. 시작점이 0 이 아닌 고객 포함."""

    def test_query_side_uses_q_row(self):
        users = [
            [_exposure(1000 + index, index, index % 2) for index in range(300)],   # 창 256, start 44
            [_exposure(5000 + index, 90000 + index, index % 2) for index in range(4)],
        ]
        split = synthetic_split("valid", users)
        sequences = seqmodel.build_sequences(split)
        self.assertEqual(len(sequences), 2)
        # 행 번호를 그대로 값으로 둔 행렬로 대응을 확인한다(q_pos 를 쓰면 어긋난다).
        side = np.arange(split.n_rows, dtype=np.float32).reshape(-1, 1)
        batch = seqmodel._collate(torch, sequences, split.user_feats.astype(np.int64), False, side)
        for row, sequence in enumerate(sequences):
            self.assertFalse(np.array_equal(sequence.q_pos, sequence.q_row))
            count = len(sequence.q_pos)
            gathered = batch["q_side"][row, :count].numpy().reshape(-1)
            self.assertTrue(np.array_equal(gathered, sequence.q_row.astype(np.float32)))
            # 그 행의 아이템과도 맞는다.
            self.assertTrue(np.array_equal(split.row_item[sequence.q_row], sequence.item[sequence.q_pos]))


class StandardizationTest(unittest.TestCase):
    """표준화 통계는 학습 분할에서만 나오고, 검증 · 시험은 그 통계를 그대로 쓴다."""

    def setUp(self):
        self.train = synthetic_split("train", [[
            _exposure(1, 10, 1), _exposure(2, 20, 0), _exposure(3, 30, 1),
        ]])
        self.valid = synthetic_split("valid", [[
            _exposure(9, 100000, 1), _exposure(9, 200000, 0), _exposure(8, 300000, 1),
        ]])

    def test_stats_come_from_train_only(self):
        train_matrix = features.side_design(self.train)[0]
        valid_matrix = features.side_design(self.valid)[0]
        mean, std = seqmodel.side_statistics(train_matrix)
        raw_std = train_matrix.std(axis=0)
        self.assertTrue(np.allclose(mean, train_matrix.mean(axis=0), atol=1e-6))
        varying = raw_std >= 1e-6
        self.assertTrue(np.allclose(std[varying], raw_std[varying], atol=1e-6))
        self.assertTrue(np.allclose(std[~varying], 1.0))

        standardized = seqmodel.standardize_side(valid_matrix, mean, std)
        self.assertTrue(np.allclose(standardized, (valid_matrix - mean) / std, atol=1e-6))
        # 검증 통계를 섞지 않았으므로 검증을 표준화해도 평균이 0 으로 맞춰지지 않는다.
        self.assertFalse(np.allclose(standardized.mean(axis=0), 0.0, atol=1e-6))

    def test_constant_column_has_unit_std(self):
        matrix = np.ones((5, 2), dtype=np.float32)
        mean, std = seqmodel.side_statistics(matrix)
        self.assertTrue(np.allclose(std, 1.0))
        self.assertTrue(np.allclose(seqmodel.standardize_side(matrix, mean, std), 0.0))


class SideLeakageTest(unittest.TestCase):
    """같은 초 묶음 동료의 클릭은 곁채널을 켜도 그 초의 질의에 새지 않는다."""

    def setUp(self):
        torch.manual_seed(7)
        split = synthetic_split("valid", [[
            _exposure(1, 50, 1),
            _exposure(2, 100, 0),
            _exposure(3, 100, 1),
            _exposure(4, 200, 0),
        ]])
        self.side = features.side_design(split)[0]
        mean, std = seqmodel.side_statistics(self.side)
        self.side = seqmodel.standardize_side(self.side, mean, std)
        self.model = seqmodel.build_model(16, 2, heads=2, side_dim=self.side.shape[1])
        self.model.eval()
        self.sequence = seqmodel.build_sequences(split)[0]
        self.split = split

    def test_peer_click_only_changes_later_queries(self):
        flipped = seqmodel.UserSequence(**{**self.sequence.__dict__, "click": self.sequence.click.copy()})
        flipped.click[1] = 1 - flipped.click[1]
        first = _logits(self.model, self.sequence, self.split, self.side)
        second = _logits(self.model, flipped, self.split, self.side)
        for row in (0, 1, 2):
            self.assertAlmostEqual(first[row], second[row], places=5)
        self.assertNotAlmostEqual(first[3], second[3], places=5)


class SideCheckpointTest(unittest.TestCase):
    """곁채널 체크포인트 저장 · 복원 후 예측이 같고, 여부가 다르면 오류다."""

    def setUp(self):
        torch.manual_seed(3)
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "d64_l2_side.pt"
        self.sequence, self.split = _sequence_and_split()
        side = features.side_design(self.split)[0]
        self.mean, self.std = seqmodel.side_statistics(side)
        self.side = seqmodel.standardize_side(side, self.mean, self.std)
        self.stats = {"mean": self.mean.tolist(), "std": self.std.tolist(), "names": None}
        dim = config.SEQ_CONFIGS[CONFIG_NAME]["dim"]
        self.model = seqmodel.build_model(dim, config.SEQ_CONFIGS[CONFIG_NAME]["layers"],
                                          side_dim=self.side.shape[1])
        self.model.eval()

    def tearDown(self):
        self.directory.cleanup()

    def test_round_trip_predicts_the_same(self):
        before = _logits(self.model, self.sequence, self.split, self.side)
        seqmodel.save_checkpoint(self.path, self.model, config_name=CONFIG_NAME, no_unclicked=False,
                                 trained_epochs=1, best_epoch=0, best_valid_bundle_gauc=0.42,
                                 side=True, side_dim=self.side.shape[1], side_stats=self.stats)
        loaded, payload = seqmodel.load_checkpoint(self.path, CONFIG_NAME, False, device="cpu", side=True)
        after = _logits(loaded, self.sequence, self.split, self.side)
        self.assertTrue(np.allclose(before, after, atol=1e-6))
        self.assertTrue(payload["side"])
        self.assertEqual(payload["side_dim"], self.side.shape[1])

    def test_side_mismatch_raises(self):
        seqmodel.save_checkpoint(self.path, self.model, config_name=CONFIG_NAME, no_unclicked=False,
                                 trained_epochs=1, best_epoch=0, best_valid_bundle_gauc=0.42,
                                 side=True, side_dim=self.side.shape[1], side_stats=self.stats)
        with self.assertRaises(ValueError):
            seqmodel.load_checkpoint(self.path, CONFIG_NAME, False, device="cpu", side=False)

    def test_checkpoint_path_naming(self):
        self.assertEqual(seqmodel.checkpoint_path("d64_l2", False, True).name, "d64_l2_side.pt")
        self.assertEqual(seqmodel.checkpoint_path("d128_l2", True, True).name, "d128_l2_noclick_side.pt")
        self.assertEqual(seqmodel.checkpoint_path("d64_l2", False, False).name, "d64_l2.pt")


if __name__ == "__main__":
    unittest.main()
