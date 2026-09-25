"""X5(#328) 대조 규칙 — 기대 사건과 서버가 해석한 사건을 어떻게 가르는가."""
from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

from genpage2.dataset import ago_bucket
from genpage2.vocab import _article_id

_TOOL = Path(__file__).resolve().parents[3] / "tools" / "x5_prompt_replay.py"
_spec = importlib.util.spec_from_file_location("x5_prompt_replay", _TOOL)
replay = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(replay)

NOW = "2026-09-26T03:00:00Z"
TOKENS = ["BOS", "DOW_5", "MONTH_9", "ITEM_FALLBACK", "ACT_CLICK", "AGO_0-3", "PRICE_3", "SEP_PAGE"]


def norm(events):
    return replay.normalized(events, NOW, ago_bucket, _article_id)


class ReplayClassifyTest(unittest.TestCase):
    def test_rich_same_facts_is_exact(self):
        facts = [{"item": 1, "action": "CLICK", "at": "2026-09-26T02:00:00.000000Z"}]
        flags, diff, _ = replay.classify(norm(facts), norm(facts), TOKENS, TOKENS)
        self.assertTrue(flags["exact"])
        self.assertEqual(diff, 0)

    def test_off_ids_read_as_purchases_change_meaning(self):
        facts = [{"item": 1, "action": "VIEW", "at": "2026-09-20T02:00:00Z"}]
        off = [{"item": 1, "action": None, "at": None}]          # history 로 간 id — 행동 · 시각이 없다
        online_tokens = ["BOS", "DOW_1", "MONTH_9", "ITEM_FALLBACK", "ACT_ONLINE", "AGO_0-3", "PRICE_3", "SEP_PAGE"]
        flags, diff, _ = replay.classify(norm(facts), norm(off), TOKENS, online_tokens)
        self.assertTrue(flags["meaning"])
        self.assertTrue(flags["action"])
        self.assertTrue(flags["time"])       # 6일 전 조회가 요청일로 읽혔다
        self.assertTrue(flags["request"])    # 요일 토큰이 다르다
        self.assertFalse(flags["exact"])
        self.assertEqual(diff, 2)

    def test_missing_and_order_are_separate(self):
        a = {"item": 1, "action": "CLICK", "at": NOW}
        b = {"item": 2, "action": "CLICK", "at": NOW}
        flags, _, _ = replay.classify(norm([a, b]), norm([a]), TOKENS, TOKENS)
        self.assertTrue(flags["missing"])
        self.assertFalse(flags["order"])
        flags, _, _ = replay.classify(norm([a, b]), norm([b, a]), TOKENS, TOKENS)
        self.assertTrue(flags["order"])
        self.assertFalse(flags["missing"])

    def test_meaning_is_counted_even_when_items_are_missing(self):
        facts = [{"item": i, "action": "CLICK", "at": NOW} for i in (1, 2, 3)]
        off = [{"item": i, "action": None, "at": None} for i in (2, 3)]   # 창이 잘려 오래된 1 이 빠졌다
        flags, _, _ = replay.classify(norm(facts), norm(off), TOKENS, TOKENS)
        self.assertTrue(flags["missing"])
        self.assertTrue(flags["meaning"])

    def test_future_at_falls_in_request_day(self):
        self.assertEqual(norm([{"item": 1, "action": "CLICK", "at": "2026-09-27T01:00:00Z"}])[0][2], "AGO_0-3")


if __name__ == "__main__":
    unittest.main()
