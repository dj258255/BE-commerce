"""노출 배열의 고객 구간 — 여러 고객 데이터에서 위치를 제대로 읽는지 고정한다.

`SplitData` 의 노출 배열은 고객 순서로 이어 붙어 있다. 고객 u 의 노출은
`offsets[u]` 부터이므로, 시퀀스를 만들 때 시작점을 더하지 않으면 모든 고객이
배열 앞쪽(다른 고객)의 노출을 읽는다. 고객 하나짜리 데이터로는 못 잡는다.
"""
from __future__ import annotations

import copy
import unittest

import numpy as np
import torch

from taobao import config, seqmodel
from taobao.seqmodel import UserSequence
from taobao.tests.helpers import synthetic_split


def _exposure(item, ts, click, cate=1):
    return (item, cate, cate * 10, cate * 100, cate * 1000, 3, ts, click)


# 고객마다 광고 · 시각이 다르다. 시작점을 안 더하면 서로 뒤섞인다.
USERS = [
    [_exposure(10, 10, 1), _exposure(11, 20, 0), _exposure(12, 30, 0)],
    [_exposure(20, 110, 0), _exposure(21, 120, 1), _exposure(22, 130, 0)],
    [_exposure(30, 210, 1), _exposure(31, 220, 0), _exposure(32, 230, 1)],
]


def _long_user(exposures: int, base_item: int, base_ts: int):
    return [_exposure(base_item + index, base_ts + index, index % 2) for index in range(exposures)]


class CustomerSliceTest(unittest.TestCase):
    def test_every_query_matches_its_row(self):
        split = synthetic_split("valid", USERS)
        row_ts = split.ts[split.row_exposure()]
        sequences = seqmodel.build_sequences(split)
        self.assertEqual(len(sequences), 3)
        for sequence in sequences:
            self.assertGreater(len(sequence.q_pos), 0)
            for position, row in zip(sequence.q_pos, sequence.q_row):
                self.assertEqual(int(sequence.item[position]), int(split.row_item[row]))
                self.assertEqual(int(sequence.ts[position]), int(row_ts[row]))

    def test_window_is_the_customer_slice(self):
        split = synthetic_split("valid", USERS)
        for sequence in seqmodel.build_sequences(split):
            user = sequence.user
            left = int(split.offsets[user])
            positions = np.sort(split.row_pos[split.row_user == user])
            end = int(positions.max()) + 1
            start = max(end - config.MAX_HISTORY, 0)
            self.assertTrue(np.array_equal(sequence.item, split.item[left + start:left + end]))
            self.assertTrue(np.array_equal(sequence.ts, split.ts[left + start:left + end]))
            self.assertTrue(np.array_equal(sequence.click, split.click[left + start:left + end]))

    def test_long_history_window_uses_offset_and_cap(self):
        # 한 고객만 300개(창 256) — start 가 0 이 아니어도 그 고객 구간을 읽는다.
        users = [_long_user(300, 1000, 0), _long_user(4, 5000, 90000)]
        split = synthetic_split("valid", users)
        sequences = {sequence.user: sequence for sequence in seqmodel.build_sequences(split)}
        self.assertEqual(len(sequences[0].item), config.MAX_HISTORY)
        left = int(split.offsets[0])
        end = 300
        start = end - config.MAX_HISTORY
        self.assertTrue(np.array_equal(sequences[0].item, split.item[left + start:left + end]))
        # 두 번째 고객도 자기 구간을 읽는다.
        left1 = int(split.offsets[1])
        self.assertTrue(np.array_equal(sequences[1].item, split.item[left1:left1 + 4]))


class MultiCustomerLeakageTest(unittest.TestCase):
    """같은 초 묶음 누설 테스트를 고객 여러 명 데이터로도 돌린다."""

    def setUp(self):
        torch.manual_seed(5)
        self.model = seqmodel.build_model(16, 2, heads=2)
        self.model.eval()
        self.split = synthetic_split("valid", [
            [_exposure(1, 50, 1), _exposure(2, 100, 0), _exposure(3, 100, 1), _exposure(4, 200, 0)],
            [_exposure(5, 60, 0), _exposure(6, 300, 1), _exposure(7, 310, 0)],
        ])
        self.attributes = self.split.user_feats.astype(np.int64)

    def _logits(self, sequence):
        batch = seqmodel._collate(torch, [sequence], self.attributes, False)
        with torch.no_grad():
            return self.model(batch, "cpu")[0, :len(sequence.q_pos)].numpy()

    def test_peer_click_only_changes_later_queries_of_same_customer(self):
        sequences = seqmodel.build_sequences(self.split)
        before = [self._logits(sequence) for sequence in sequences]
        flipped = copy.deepcopy(sequences)
        flipped[0].click[1] = 1 - flipped[0].click[1]
        after = [self._logits(sequence) for sequence in flipped]
        # 고객 0: 100초 질의(pos1 · pos2)와 그보다 이른 pos0 은 그대로, 200초 질의(pos3)만 바뀐다.
        for row in (0, 1, 2):
            self.assertAlmostEqual(before[0][row], after[0][row], places=5)
        self.assertNotAlmostEqual(before[0][3], after[0][3], places=5)
        # 고객 1 은 다른 고객의 이력을 보지 않으므로 전부 그대로다.
        self.assertTrue(np.allclose(before[1], after[1], atol=1e-6))

    def test_one_shot_equals_per_query(self):
        for sequence in seqmodel.build_sequences(self.split):
            batch = seqmodel._collate(torch, [sequence], self.attributes, False)
            with torch.no_grad():
                together = self.model(batch, "cpu")[0, :len(sequence.q_pos)].numpy()
            for index in range(len(sequence.q_pos)):
                single = _standalone(sequence, index)
                single_batch = seqmodel._collate(torch, [single], self.attributes, False)
                with torch.no_grad():
                    value = float(self.model(single_batch, "cpu")[0, 0])
                self.assertAlmostEqual(together[index], value, places=5)


def _standalone(sequence: UserSequence, index: int) -> UserSequence:
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


if __name__ == "__main__":
    unittest.main()
