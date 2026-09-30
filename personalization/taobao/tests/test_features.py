"""R 특징의 누설 방지 — 같은 초 묶음 동료와 그날 통계를 보지 않는다."""
from __future__ import annotations

import unittest

import numpy as np

from taobao import features
from taobao.tests.helpers import synthetic_split


def _exposure(item, ts, click, cate=1):
    return (item, cate, cate * 10, cate * 100, cate * 1000, 3, ts, click)


class SameSecondLeakageTest(unittest.TestCase):
    """같은 초 묶음 동료의 클릭이 그 행의 특징에 새지 않는다."""

    def test_peer_click_not_in_history_features(self):
        # pos0 은 50초(클릭 1), pos1 · pos2 는 100초(같은 화면).
        split = synthetic_split("valid", [[
            _exposure(10, 50, 1),
            _exposure(10, 100, 0),
            _exposure(20, 100, 1),
        ]])
        matrix, _, names = features.build_design(split)
        column = {name: index for index, name in enumerate(names)}
        # pos2(행 2)는 같은 초 동료 pos1 을 보면 안 된다. 이력은 50초 하나뿐이다.
        for row in (1, 2):
            self.assertAlmostEqual(matrix[row, column["cust_exp"]], 1.0)
            self.assertAlmostEqual(matrix[row, column["cust_click"]], 1.0)
            self.assertAlmostEqual(matrix[row, column["cate_exp"]], 1.0)
            self.assertAlmostEqual(matrix[row, column["cate_click"]], 1.0)
        # pos2 의 광고(20)는 이력에 없으므로 0 이다(pos1 의 광고 10 은 같은 초라 안 센다).
        self.assertAlmostEqual(matrix[2, column["ad_exp"]], 0.0)
        # 이력이 하나도 없는 pos0 은 전부 0 이다.
        self.assertAlmostEqual(matrix[0, column["cust_exp"]], 0.0)
        self.assertAlmostEqual(matrix[0, column["cust_ctr"]], 0.0)


class GlobalStatsDayTest(unittest.TestCase):
    """전체 통계는 그 노출의 전날까지만 쓴다(그날 것을 쓰지 않는다)."""

    def test_same_day_not_counted(self):
        day = 86400
        split = synthetic_split("valid", [[
            _exposure(10, 0, 0),        # 전날: 노출 1, 클릭 0
            _exposure(10, day, 1),      # 그날: 노출 1, 클릭 1
        ]])
        matrix, _, names = features.build_design(split)
        column = {name: index for index, name in enumerate(names)}
        # 그날 행(행 1)의 전체 통계에는 전날 노출 1 · 클릭 0 만 들어간다.
        self.assertAlmostEqual(matrix[1, column["g_ad_exp"]], 1.0)
        self.assertAlmostEqual(matrix[1, column["g_ad_click"]], 0.0)
        self.assertAlmostEqual(matrix[1, column["g_ad_ctr"]], 0.0)
        # 전날 행(행 0)은 이전이 없어 0 이다.
        self.assertAlmostEqual(matrix[0, column["g_ad_exp"]], 0.0)


if __name__ == "__main__":
    unittest.main()
