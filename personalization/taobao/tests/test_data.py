"""노출열 · 채점 행 · 채점 창의 기본 성질."""
from __future__ import annotations

import unittest

import numpy as np

from taobao import config
from taobao.data import select_users
from taobao.tests.helpers import synthetic_split


def _exposure(item, ts, click, cate=1):
    return (item, cate, cate * 10, cate * 100, cate * 1000, 3, ts, click)


class RowExposureTest(unittest.TestCase):
    def test_row_points_at_same_item(self):
        split = synthetic_split("valid", [[
            _exposure(10, 0, 1), _exposure(20, 1, 0), _exposure(10, 2, 0),
        ]])
        exposure = split.row_exposure()
        self.assertTrue(np.array_equal(split.item[exposure], split.row_item))
        self.assertTrue(np.array_equal(split.click[exposure], split.row_label))


class EvalMaskTest(unittest.TestCase):
    def test_long_history_keeps_only_last_window(self):
        exposures = [_exposure(index, index, 0) for index in range(config.MAX_HISTORY + 20)]
        split = synthetic_split("valid", [exposures])
        mask = split.eval_mask()
        self.assertEqual(int(mask.sum()), config.MAX_HISTORY)
        # 앞 20 개가 잘린다.
        self.assertFalse(mask[:20].any())
        self.assertTrue(mask[20:].all())

    def test_short_history_keeps_everything(self):
        split = synthetic_split("valid", [[_exposure(i, i, 0) for i in range(5)]])
        self.assertTrue(split.eval_mask().all())


class SelectUsersTest(unittest.TestCase):
    def test_deterministic_and_bounded(self):
        ids = np.arange(1000, 2000, dtype=np.int64)
        first = select_users(ids, 100)
        second = select_users(ids, 100)
        self.assertTrue(np.array_equal(first, second))
        self.assertEqual(int(first.sum()), 100)
        self.assertTrue(select_users(ids, None).all())


if __name__ == "__main__":
    unittest.main()
