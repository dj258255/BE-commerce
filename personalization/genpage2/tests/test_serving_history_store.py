"""S2(#455) 서버 이력 저장소(`--history-store`) — 계약 · 누설 · build_eval_users 일치.

작은 실제 모델 + 작은 합성 거래로 확인한다.

- 저장소를 끄면 지금 응답과 완전히 같다(`history_source` 가 없다)
- 저장소 이벤트는 ``build_eval_users(history_events=100)`` 가 그 고객에게 만드는 것과 같다
- 저장소에는 요청 시각 이후 거래가 들어가지 않는다(누설)
- 요청에 ``customer`` 가 있고 저장소에 있으면 ``history_source: store``, 없으면 ``request``
- 저장소 경로가 요청의 history 를 무시하고, 저장소 이벤트를 요청에 실은 것과 같은 페이지를 낸다
"""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import pandas as pd
import torch

from genpage2 import config
from genpage2.decode import PageDecoder
from genpage2.model import GenPageV2, ModelConfig
from genpage2.prompt import build_prompt
from genpage2.ranker import write_scores
from genpage2.simulate import build_eval_users, build_history_store
from genpage2.vocab import Vocab, content_rows
from serving import genpage2_server

_ARTICLES = [f"{index:010d}" for index in range(1, 17)]
_SECTIONS = {article: (1.0 if int(article) <= 8 else 5.0) for article in _ARTICLES}
_SCORED = [(article, float(100 - int(article))) for article in _ARTICLES]
_HISTORY = ["0000000001"]
# validate 모드 요청 시각(2020-09-09). 이 시각 이전 = 저장소, 이후 = 정답 창(누설 대상).
_REQUEST = config.request_of("validate")


def _articles_frame() -> pd.DataFrame:
    return pd.DataFrame({"article_id": _ARTICLES,
                         "section_no": [_SECTIONS[a] for a in _ARTICLES]})


def _vocab_frame() -> pd.DataFrame:
    """어휘를 만들 만큼(상품마다 MIN_COUNT) 거래가 있는 프레임(어휘용)."""
    rows = [(article, float(int(article))) for article in _ARTICLES
            for _ in range(config.MIN_COUNT)]
    return pd.DataFrame(rows, columns=["article_id", "price"])


def _store_frame() -> pd.DataFrame:
    """날짜 · 채널 · 가격이 있는 합성 거래. c1 은 요청 이전 3건 + 창 안 1건(누설 대상)."""
    rows = [
        ("2020-08-01", "c1", "0000000001", 1, 10.0),
        ("2020-08-15", "c1", "0000000002", 2, 20.0),
        ("2020-08-20", "c1", "0000000001", 1, 12.0),
        ("2020-09-10", "c1", "0000000003", 1, 30.0),  # [r, r+7) — 저장소에 없어야 한다
        ("2020-08-05", "c2", "0000000004", 1, 40.0),
        ("2020-09-11", "c2", "0000000005", 1, 50.0),  # 창 안
    ]
    return pd.DataFrame(rows, columns=["t_dat", "customer_id", "article_id", "sales_channel_id", "price"])


class HistoryStoreEventsTest(unittest.TestCase):
    """저장소 빌더가 `build_eval_users` 와 같은 이벤트를 만들고 누설이 없나."""

    def setUp(self):
        self.articles = _articles_frame()
        self.vocab = Vocab.build(_vocab_frame(), self.articles)
        self.content_rows_map = content_rows(self.articles)
        self.store_frame = _store_frame()
        self.customers = pd.DataFrame({"customer_id": ["c1", "c2"]})

    def test_store_events_match_build_eval_users(self):
        store = build_history_store(self.vocab, self.store_frame, request=_REQUEST)
        users, _ = build_eval_users(self.vocab, self.store_frame, self.customers, {},
                                    self.content_rows_map, request=_REQUEST, history_events=100)
        by_id = {user.customer_id: user.events for user in users}
        self.assertIn("c1", by_id)
        self.assertIn("c2", by_id)
        self.assertEqual(store["c1"], by_id["c1"])
        self.assertEqual(store["c2"], by_id["c2"])

    def test_store_has_no_future_transactions(self):
        store = build_history_store(self.vocab, self.store_frame, request=_REQUEST)
        events = store["c1"]
        self.assertTrue(events)
        for event in events:
            self.assertLess(pd.Timestamp(event["at"]), _REQUEST)
        # 창 안(요청 시각 이후)에 산 상품은 저장소에 없다.
        self.assertNotIn("0000000003", [event["item"] for event in events])

    def test_store_is_oldest_first_and_caps_at_100(self):
        rows = [("2020-01-01", "c9", f"{index:010d}", 1, float(index))
                for index in range(1, 121)]
        frame = pd.DataFrame(rows, columns=["t_dat", "customer_id", "article_id",
                                            "sales_channel_id", "price"])
        events = build_history_store(self.vocab, frame, request=_REQUEST)["c9"]
        self.assertEqual(len(events), 100)
        self.assertEqual(events[0]["item"], "0000000021")  # 가장 오래된 것이 먼저


class HistoryStoreEngineTest(unittest.TestCase):
    """서버 엔진이 저장소를 프롬프트 · 다시 사기 행 이력에 쓰고 `history_source` 를 남기나."""

    def setUp(self):
        self.articles = _articles_frame()
        self.vocab = Vocab.build(_vocab_frame(), self.articles)
        self.content_rows_map = content_rows(self.articles)
        self.store_frame = _store_frame()
        cfg = ModelConfig(vocab_size=len(self.vocab.tokens), dim=8, layers=1, heads=2,
                          ffn=16, dropout=0.0, maxlen=64, content_dim=384)
        torch.manual_seed(11)
        content = torch.zeros((len(self.content_rows_map), 384))
        self.model = GenPageV2(cfg, content, tokens=self.vocab.tokens).eval()
        self.decoder = PageDecoder(self.model, self.vocab, self.content_rows_map, "cpu")
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        meta = pd.DataFrame({"customer_id": ["c1"], "history": [_HISTORY]})
        self.scores_path = Path(self.tmp.name) / "scores.json.gz"
        write_scores(self.scores_path, meta, {"c1": list(_SCORED)})

    def _engine(self, *, history_store=False, transactions=None):
        with patch("serving.genpage2_server._load_decoder",
                   return_value=(self.decoder, self.vocab, None, self.content_rows_map, "cpu")):
            return genpage2_server.Engine(Path("ckpt"), "validate", "cpu", data=Path(self.tmp.name),
                                          scores=self.scores_path, hybrid_lambda=4.0,
                                          history_store=history_store, transactions=transactions)

    def test_store_off_is_unchanged(self):
        engine = self._engine()
        plain = engine._page_unlocked({"history": _HISTORY})
        self.assertNotIn("history_source", plain)
        with_compose = engine._page_unlocked({"history": _HISTORY, "compose": "generate"})
        self.assertNotIn("history_source", with_compose)

    def test_store_on_absent_customer_uses_request(self):
        on = self._engine(history_store=True, transactions=self.store_frame)._page_unlocked({"history": _HISTORY})
        off = self._engine()._page_unlocked({"history": _HISTORY})
        self.assertEqual(on["history_source"], "request")
        self.assertEqual(on["rows"], off["rows"])
        self.assertEqual(on["context"]["events"], off["context"]["events"])

    def test_history_source_store_and_request(self):
        engine = self._engine(history_store=True, transactions=self.store_frame)
        store = engine._page_unlocked({"history": [], "customer": "c1", "compose": "rule"})
        self.assertEqual(store["history_source"], "store")
        missing = engine._page_unlocked({"history": [], "customer": "zzz", "compose": "rule"})
        self.assertEqual(missing["history_source"], "request")

    def test_store_history_is_used_for_prompt_and_repeat_row(self):
        engine = self._engine(history_store=True, transactions=self.store_frame)
        body = engine._page_unlocked({"history": [], "customer": "c1", "compose": "rule"})
        # 요청 history(엉뚱한 상품)는 저장소가 있으면 무시된다.
        bogus = engine._page_unlocked({"history": ["0000000016"], "customer": "c1", "compose": "rule"})
        self.assertEqual(bogus["rows"], body["rows"])
        # 저장소 이벤트를 그대로 요청 이벤트로 실은 것과 같은 페이지가 나온다.
        plain = self._engine()
        replay = plain._page_unlocked({"events": engine.history_store["c1"],
                                       "now": _REQUEST.isoformat(),
                                       "customer": "c1", "compose": "rule"})
        self.assertEqual(body["rows"], replay["rows"])
        self.assertTrue(body["rows"])

    def test_store_ignores_request_now(self):
        engine = self._engine(history_store=True, transactions=self.store_frame)
        correct = engine._page_unlocked({"history": [], "customer": "c1", "compose": "rule"})
        bogus = engine._page_unlocked({"history": [], "customer": "c1", "compose": "rule",
                                       "now": "2026-01-01T00:00:00Z"})
        self.assertEqual(bogus["rows"], correct["rows"])
        self.assertEqual(bogus["context"]["missing"]["now"], correct["context"]["missing"]["now"])

    def test_store_keeps_request_session(self):
        engine = self._engine(history_store=True, transactions=self.store_frame)
        session = [{"item": "0000000009", "action": "CLICK"}]
        body = engine._page_unlocked({"history": [], "customer": "c1", "compose": "rule",
                                      "session": session})
        plain = self._engine()
        replay = plain._page_unlocked({"events": engine.history_store["c1"], "now": _REQUEST.isoformat(),
                                       "customer": "c1", "compose": "rule", "session": session})
        self.assertEqual(body["rows"], replay["rows"])

    def test_store_prompt_matches_build_prompt_on_store_events(self):
        engine = self._engine(history_store=True, transactions=self.store_frame)
        body = engine._page_unlocked({"history": [], "customer": "c1", "compose": "rule"})
        expected, _, _ = build_prompt(self.vocab, now=_REQUEST, profile=None,
                                      events=engine.history_store["c1"],
                                      content_rows=self.content_rows_map)
        self.assertEqual(int(body["context"]["tokens"]), len(expected))


if __name__ == "__main__":
    unittest.main()
