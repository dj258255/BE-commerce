from __future__ import annotations

import unittest

from genpage2.reward import FEEDBACKS, RewardConfig, item_reward, page_reward, row_reward


class RewardTest(unittest.TestCase):
    def test_default_values(self):
        # 기본값은 고른 설정값이다(실제 행동을 흉내 낸다는 주장이 아니다).
        self.assertEqual(RewardConfig().buy, 3.0)
        self.assertEqual(RewardConfig().click, 1.0)
        self.assertEqual(RewardConfig().skip, -0.2)
        self.assertEqual(RewardConfig().unseen, 0.0)

    def test_item_reward_by_feedback(self):
        cfg = RewardConfig()
        self.assertEqual([item_reward(name, cfg) for name in FEEDBACKS], [0.0, -0.2, 1.0, 3.0])
        self.assertEqual(item_reward("buy"), 3.0)

    def test_item_reward_uses_given_config(self):
        cfg = RewardConfig(buy=5.0, click=2.0, skip=-1.0, unseen=0.25)
        self.assertEqual(item_reward("buy", cfg), 5.0)
        self.assertEqual(item_reward("click", cfg), 2.0)
        self.assertEqual(item_reward("skip", cfg), -1.0)
        self.assertEqual(item_reward("unseen", cfg), 0.25)

    def test_sums(self):
        self.assertAlmostEqual(row_reward([3.0, -0.2, 1.0]), 3.8)
        self.assertAlmostEqual(row_reward([0.0, 0.0]), 0.0)
        self.assertAlmostEqual(page_reward([1.0, 3.0, -0.2, 0.0]), 3.8)
        self.assertEqual(row_reward([]), 0.0)
        self.assertEqual(page_reward([]), 0.0)

    def test_unknown_feedback_is_rejected(self):
        with self.assertRaises(ValueError):
            item_reward("abandon")
        with self.assertRaises(ValueError):
            item_reward("unseen ")


if __name__ == "__main__":
    unittest.main()
