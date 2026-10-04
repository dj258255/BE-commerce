from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import numpy as np
import torch

from genpage2.row_side import HeadExample, RowHead, head_bias, load_head, save_head, train_head


def _synthetic(seed: int = 0, count: int = 200):
    """정답 행만 특징 첫 값이 +1 인 아주 배우기 쉬운 합성 데이터."""
    rng = np.random.default_rng(seed)
    rows = [4, 5]
    examples: list[HeadExample] = []
    steps = []
    for index in range(count):
        target = int(rng.integers(0, 2))
        features = {row: [1.0 if row == rows[target] else -1.0, 0.0, 0.0, 0.0, 0.0, 0.0]
                    for row in rows}
        examples.append(HeadExample(f"c{index}", [1, 2], [-1, -1], set(rows), features, [rows[target]]))
        steps.append([(rows, np.zeros(2, dtype=np.float32), rows[target])])
    return examples, steps


class TrainHeadTest(unittest.TestCase):
    def test_head_reduces_loss_on_synthetic_data(self):
        examples, steps = _synthetic()
        head, history = train_head(examples, steps, epochs=10, lr=1e-2, seed=7, device="cpu")
        self.assertGreaterEqual(len(history), 1)
        first = history[0]["train_loss"]
        best = min(record["train_loss"] for record in history)
        self.assertLess(best, first)
        # 시드가 같으면 같은 궤적이 나온다.
        _again, history_again = train_head(examples, steps, epochs=10, lr=1e-2, seed=7, device="cpu")
        self.assertEqual(history, history_again)

    def test_more_epochs_lower_final_train_loss(self):
        examples, steps = _synthetic(seed=1)
        _head, short = train_head(examples, steps, epochs=1, lr=1e-2, seed=7, device="cpu")
        _head2, long = train_head(examples, steps, epochs=5, lr=1e-2, seed=7, device="cpu")
        self.assertLessEqual(min(r["train_loss"] for r in long), min(r["train_loss"] for r in short))


class HeadIoTest(unittest.TestCase):
    def _head(self) -> RowHead:
        torch.manual_seed(3)
        head = RowHead()
        head.net[0].weight.data.normal_(0, 0.3)
        head.net[0].bias.data.normal_(0, 0.3)
        head.net[2].weight.data.normal_(0, 0.3)
        head.net[2].bias.data.normal_(0, 0.3)
        return head

    def test_save_load_roundtrip_keeps_bias(self):
        head = self._head()
        features = {4: [0.1, -0.2, 0.3, 0.4, 0.5, 0.6], 5: [-0.6, -0.5, 0.4, 0.3, 0.2, 0.1]}
        with tempfile.TemporaryDirectory() as temporary:
            path = save_head(Path(temporary) / "head.pt", head, {"mode": "validate"})
            loaded = load_head(path)
        before, after = head_bias(head, features), head_bias(loaded, features)
        self.assertEqual(set(before), set(after))
        for row in before:
            self.assertAlmostEqual(before[row], after[row], places=6)

    def test_zero_head_returns_zero_bias(self):
        head = RowHead()
        for parameter in head.parameters():
            parameter.data.zero_()
        features = {4: [5.0, 4.0, 3.0, 2.0, 1.0, 0.0], 5: [-1.0, -2.0, -3.0, -4.0, -5.0, -6.0]}
        self.assertEqual(head_bias(head, features), {4: 0.0, 5: 0.0})
        self.assertEqual(head_bias(head, {}), {})


if __name__ == "__main__":
    unittest.main()
