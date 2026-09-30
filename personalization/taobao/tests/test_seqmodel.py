"""G 의 마스크 · 한 번에 예측 · --no-unclicked 를 고정한다."""
from __future__ import annotations

import unittest

import numpy as np
import torch

from taobao import seqmodel
from taobao.seqmodel import UserSequence
from taobao.tests.helpers import synthetic_split


def _exposure(item, ts, click, cate=1):
    return (item, cate, cate * 10, cate * 100, cate * 1000, 3, ts, click)


def _sequence(exposures):
    split = synthetic_split("valid", [exposures])
    sequences = seqmodel.build_sequences(split)
    assert len(sequences) == 1
    return sequences[0], split


class SameSecondMaskTest(unittest.TestCase):
    """같은 초 동료의 클릭은 그 초의 질의에 닿지 않고, 더 이른 질의에도 닿지 않는다."""

    def setUp(self):
        torch.manual_seed(0)
        self.model = seqmodel.build_model(16, 2, heads=2)
        self.model.eval()

    def _logits(self, sequence, split):
        batch = seqmodel._collate(torch, [sequence], split.user_feats.astype(np.int64), False)
        with torch.no_grad():
            return self.model(batch, "cpu")[0, :len(sequence.q_pos)].numpy()

    def test_peer_click_only_changes_later_queries(self):
        base, split = _sequence([
            _exposure(1, 50, 1),
            _exposure(2, 100, 0),
            _exposure(3, 100, 1),
            _exposure(4, 200, 0),
        ])
        flipped = UserSequence(**{**base.__dict__, "click": base.click.copy()})
        flipped.click[1] = 1 - flipped.click[1]
        first = self._logits(base, split)
        second = self._logits(flipped, split)
        # 100초 질의(pos1 · pos2)와 그보다 이른 pos0 은 안 바뀐다.
        for row in (0, 1, 2):
            self.assertAlmostEqual(first[row], second[row], places=5)
        # 200초 질의(pos3)는 pos1 을 이력으로 보므로 바뀐다.
        self.assertNotAlmostEqual(first[3], second[3], places=5)


class OneShotEqualsPerQueryTest(unittest.TestCase):
    """한 번에 예측한 값이 위치마다 따로 예측한 값과 같다(마스크가 맞는지)."""

    def setUp(self):
        torch.manual_seed(1)
        self.model = seqmodel.build_model(16, 2, heads=2)
        self.model.eval()

    def _standalone(self, sequence, index):
        keep = [j for j in range(index) if sequence.ts[j] < sequence.ts[index]]
        if index > 0 and sequence.ts[index - 1] >= sequence.ts[index]:
            keep.append(index - 1)
        keep.append(index)
        keep = np.array(sorted(set(keep)), dtype=np.int64)
        position = int(np.flatnonzero(keep == index)[0])
        return UserSequence(
            item=sequence.item[keep], cate=sequence.cate[keep], brand=sequence.brand[keep],
            campaign=sequence.campaign[keep], price=sequence.price[keep], ts=sequence.ts[keep],
            click=sequence.click[keep], q_pos=np.array([position], dtype=np.int64),
            q_ctx=sequence.q_ctx[index:index + 1], q_label=sequence.q_label[index:index + 1],
            q_row=sequence.q_row[index:index + 1], user=sequence.user,
        )

    def test_matches(self):
        sequence, split = _sequence([
            _exposure(1, 50, 1),
            _exposure(2, 60, 0),
            _exposure(3, 100, 1),
            _exposure(4, 100, 0),
            _exposure(5, 200, 0),
        ])
        attributes = split.user_feats.astype(np.int64)
        batch = seqmodel._collate(torch, [sequence], attributes, False)
        with torch.no_grad():
            together = self.model(batch, "cpu")[0, :len(sequence.q_pos)].numpy()
        for index in range(len(sequence.q_pos)):
            single = self._standalone(sequence, index)
            single_batch = seqmodel._collate(torch, [single], attributes, False)
            with torch.no_grad():
                value = float(self.model(single_batch, "cpu")[0, 0])
            self.assertAlmostEqual(together[index], value, places=5)


class NoUnclickedTest(unittest.TestCase):
    """--no-unclicked 는 이력에서만 지우고 질의 · 라벨은 그대로 둔다."""

    def test_history_filtered_queries_kept(self):
        sequence, split = _sequence([
            _exposure(1, 10, 1),
            _exposure(2, 20, 0),
            _exposure(3, 30, 1),
            _exposure(4, 40, 0),
        ])
        attributes = split.user_feats.astype(np.int64)
        full = seqmodel._collate(torch, [sequence], attributes, False)
        filtered = seqmodel._collate(torch, [sequence], attributes, True)
        # 이력은 클릭한 노출 둘만 남는다.
        length = int((~filtered["hist_pad"][0]).sum())
        self.assertEqual(length, 2)
        self.assertTrue(bool(filtered["hist_click"][0, :length].all()))
        # 질의 · 라벨은 그대로다.
        count = len(sequence.q_pos)
        self.assertTrue(np.array_equal(filtered["q_label"][0, :count].numpy(), sequence.q_label))
        self.assertTrue(np.array_equal(filtered["q_item"][0, :count].numpy(), sequence.item[sequence.q_pos]))


if __name__ == "__main__":
    unittest.main()
