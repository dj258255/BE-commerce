"""지표를 손으로 계산한 값과 맞춘다."""
from __future__ import annotations

import unittest

import numpy as np

from taobao import metrics


class AucTest(unittest.TestCase):
    def test_perfect_and_tied(self):
        self.assertAlmostEqual(metrics.auc(np.array([0, 0, 1, 1]), np.array([1.0, 2, 3, 4])), 1.0)
        # 동점 두 쌍: 양성의 평균 순위가 같아 AUC 0.5.
        self.assertAlmostEqual(metrics.auc(np.array([0, 1, 0, 1]), np.array([1.0, 1, 2, 2])), 0.5)

    def test_single_class_is_nan(self):
        self.assertTrue(np.isnan(metrics.auc(np.array([1, 1]), np.array([0.1, 0.2]))))


class GroupedAucTest(unittest.TestCase):
    def test_hand_calculated_gauc(self):
        user = np.array([0, 0, 1, 1])
        label = np.array([1, 0, 0, 1])
        score = np.array([0.9, 0.1, 0.9, 0.1])
        result = metrics.grouped_auc(user, label, score)
        # 고객 0 AUC 1, 고객 1 AUC 0, 노출 수 가중 -> 0.5.
        self.assertAlmostEqual(result["gauc"], 0.5)
        self.assertEqual(list(result["users"]), [0, 1])
        self.assertEqual(list(result["weights"]), [2, 2])

    def test_users_with_one_class_are_dropped(self):
        user = np.array([0, 0, 1, 1, 1])
        label = np.array([1, 0, 1, 1, 1])
        score = np.array([0.9, 0.1, 0.9, 0.5, 0.1])
        result = metrics.grouped_auc(user, label, score)
        self.assertEqual(list(result["users"]), [0])


class BundledNdcgTest(unittest.TestCase):
    def test_click_first_is_one(self):
        group = np.array([0, 0, 0])
        label = np.array([1, 0, 0])
        score = np.array([0.9, 0.5, 0.1])
        self.assertAlmostEqual(metrics.bundled_ndcg(group, label, score)["ndcg"], 1.0)

    def test_click_last_matches_hand_value(self):
        group = np.array([0, 0])
        label = np.array([1, 0])
        score = np.array([0.1, 0.9])
        expected = (1 / np.log2(3)) / 1.0
        self.assertAlmostEqual(metrics.bundled_ndcg(group, label, score)["ndcg"], expected)

    def test_bundles_without_click_are_skipped(self):
        group = np.array([0, 0, 1, 1])
        label = np.array([0, 0, 1, 0])
        score = np.array([0.1, 0.9, 0.9, 0.1])
        result = metrics.bundled_ndcg(group, label, score)
        self.assertEqual(result["bundles"], 1)


class PairedBootstrapTest(unittest.TestCase):
    def test_constant_difference_collapses(self):
        users = np.array([0, 1, 2])
        a = np.array([0.5, 0.6, 0.5])
        b = np.array([0.7, 0.8, 0.7])
        weights = np.array([1, 2, 3])
        result = metrics.paired_bootstrap(users, a, users, b, weights, bootstrap=200, seed=7)
        self.assertAlmostEqual(result["difference"], 0.2)
        self.assertAlmostEqual(result["low"], 0.2)
        self.assertAlmostEqual(result["high"], 0.2)
        self.assertEqual(result["verdict"], "이겼다")

    def test_mismatched_users_raise(self):
        with self.assertRaises(ValueError):
            metrics.paired_bootstrap(np.array([0]), np.array([0.5]), np.array([1]), np.array([0.6]),
                                     np.array([1]))

    def test_interval_can_include_zero(self):
        rng = np.random.default_rng(0)
        users = np.arange(400)
        a = rng.uniform(0.4, 0.6, size=400)
        b = a + rng.normal(0, 0.05, size=400)
        result = metrics.paired_bootstrap(users, a, users, b, np.ones(400), bootstrap=500, seed=7)
        self.assertLessEqual(result["low"], 0.0)
        self.assertGreaterEqual(result["high"], 0.0)


class LoglossTest(unittest.TestCase):
    def test_perfect_is_zero(self):
        self.assertAlmostEqual(metrics.logloss(np.array([1, 0]), np.array([1.0, 0.0])), 0.0)


class BundledAucTest(unittest.TestCase):
    def test_hand_calculated_bundle_gauc(self):
        user = np.array([0, 0, 0, 1])
        group = np.array([0, 0, 1, 1])
        label = np.array([1, 0, 0, 1])
        score = np.array([0.9, 0.1, 0.9, 0.1])
        result = metrics.bundled_auc(user, group, label, score)
        # 묶음 0 AUC 1, 묶음 1 AUC 0, 크기 가중 -> 0.5.
        self.assertAlmostEqual(result["gauc"], 0.5)
        self.assertEqual(list(result["sizes"]), [2, 2])

    def test_bundles_with_one_class_are_dropped(self):
        user = np.array([0, 0, 0])
        group = np.array([0, 0, 1])
        label = np.array([0, 0, 1])
        score = np.array([0.1, 0.9, 0.5])
        result = metrics.bundled_auc(user, group, label, score)
        self.assertEqual(len(result["sizes"]), 0)
        self.assertTrue(np.isnan(result["gauc"]))

    def test_cumulative_click_score_is_half_within_bundles(self):
        """시간이 지날수록 커지는 점수(누적 클릭 수)는 묶음 안에서 상수라 0.5 가 된다."""
        # 묶음마다 점수가 같다: 0초=0, 1초=1, 2초=2 (그 초에 클릭이 하나씩 늘어난 값).
        user = np.array([0, 0, 0, 0, 0, 0])
        group = np.array([0, 0, 1, 1, 2, 2])
        label = np.array([0, 0, 0, 1, 0, 1])
        score = np.array([0.0, 0.0, 1.0, 1.0, 2.0, 2.0])
        bundle = metrics.bundled_auc(user, group, label, score)
        customer = metrics.grouped_auc(user, label, score)
        # 같은 초 안에서는 모두 같은 점수라 묶음 AUC 는 0.5, 고객 GAUC 는 0.5 가 아니다.
        self.assertAlmostEqual(bundle["gauc"], 0.5)
        self.assertGreater(customer["gauc"], 0.5)

    def test_bootstrap_draws_whole_customers(self):
        """부트스트랩은 고객을 뽑고 그 고객의 묶음을 모두 넣는다."""
        user = np.array([0, 0, 0, 0, 1, 1, 1, 1])
        group = np.array([0, 0, 1, 1, 2, 2, 3, 3])
        label = np.array([1, 0, 1, 0, 0, 1, 0, 1])
        score = np.array([0.9, 0.1, 0.9, 0.1, 0.9, 0.1, 0.9, 0.1])
        users, value_a, value_b, weights = metrics.bundle_user_totals(user, group, label, score, score)
        # 고객 0 은 묶음 둘 다 AUC 1, 고객 1 은 둘 다 AUC 0 -> 고객 값 1 과 0.
        self.assertEqual(list(users), [0, 1])
        self.assertAlmostEqual(value_a[0], 1.0)
        self.assertAlmostEqual(value_a[1], 0.0)
        # 가중치는 그 고객의 묶음 크기 합(2+2).
        self.assertEqual(list(weights), [4, 4])
        # 같은 점수끼리는 차이 0.
        interval = metrics.paired_bootstrap(users, value_a, users, value_b, weights, bootstrap=200, seed=7)
        self.assertAlmostEqual(interval["difference"], 0.0)
        self.assertAlmostEqual(interval["low"], 0.0)
        self.assertAlmostEqual(interval["high"], 0.0)


class EvaluateKeysTest(unittest.TestCase):
    def test_primary_key_is_bundle_gauc(self):
        user = np.array([0, 0, 0, 1])
        group = np.array([0, 0, 1, 1])
        label = np.array([1, 0, 0, 1])
        score = np.array([0.9, 0.1, 0.9, 0.1])
        summary = metrics.evaluate(user, label, score, group=group)
        self.assertIn("bundle_gauc", summary)
        self.assertIn("user_gauc", summary)
        self.assertNotIn("gauc", summary)
        self.assertAlmostEqual(summary["bundle_gauc"], 0.5)


if __name__ == "__main__":
    unittest.main()
