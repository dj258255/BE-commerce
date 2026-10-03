"""S1(#454) 서버의 `compose` · `customer` 처리 — 계약과 오프라인 일치.

작은 실제 모델 + 작은 점수 · 저장소 파일로 서버의 `rule` · `hybrid` · `hybrid-cached`
가 `genpage2.page_compose` 의 오프라인 결과(compose_b · build_pages H-thin)와 같은지
본다. HTTP 핸들러도 실제 스레드로 한 번 확인한다.
"""
from __future__ import annotations

import gzip
import http.client
import json
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np
import pandas as pd
import torch

from genpage2 import config
from genpage2.decode import PageDecoder
from genpage2.model import GenPageV2, ModelConfig
from genpage2.page_compose import build_pages, compose_b, read_scores
from genpage2.prompt import build_prompt
from genpage2.ranker import write_scores
from genpage2.vocab import Vocab, _article_id, content_rows
from serving import genpage2_server

_ARTICLES = [f"{index:010d}" for index in range(1, 17)]
_SECTIONS = {article: (1.0 if int(article) <= 8 else 5.0) for article in _ARTICLES}
_HISTORY = ["0000000001"]
_SCORED = [(article, float(100 - int(article))) for article in _ARTICLES]


def _articles_frame() -> pd.DataFrame:
    return pd.DataFrame({"article_id": _ARTICLES,
                         "section_no": [_SECTIONS[a] for a in _ARTICLES]})


def _transactions_frame() -> pd.DataFrame:
    rows = [(article, float(int(article))) for article in _ARTICLES
            for _ in range(config.MIN_COUNT)]
    return pd.DataFrame(rows, columns=["article_id", "price"])


def _normalize(rows) -> list[tuple[int, tuple[str, ...]]]:
    """오프라인 ``GeneratedRow`` 행을 (행 토큰, 10자리 상품)으로 맞춘다."""
    return [(int(row.row_token), tuple(_article_id(item) for item in row.items)) for row in rows]


def _response_rows(vocab: Vocab, body) -> list[tuple[int, tuple[str, ...]]]:
    """서버 응답 행을 (행 토큰, 10자리 상품)으로 맞춘다. `row` 는 어휘 토큰 이름이다."""
    return [(vocab.id(str(row["row"])), tuple(_article_id(item) for item in row["items"]))
            for row in body["rows"]]


class ServingComposeTest(unittest.TestCase):
    def setUp(self):
        self.articles = _articles_frame()
        self.vocab = Vocab.build(_transactions_frame(), self.articles)
        self.content_rows_map = content_rows(self.articles)
        cfg = ModelConfig(vocab_size=len(self.vocab.tokens), dim=8, layers=1, heads=2,
                          ffn=16, dropout=0.0, maxlen=64, content_dim=384)
        torch.manual_seed(11)
        content = torch.zeros((len(self.content_rows_map), 384))
        self.model = GenPageV2(cfg, content, tokens=self.vocab.tokens).eval()
        self.decoder = PageDecoder(self.model, self.vocab, self.content_rows_map, "cpu")
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.meta = pd.DataFrame({"customer_id": ["c1", "c2"], "history": [_HISTORY, []]})
        self.scores = {"c1": list(_SCORED), "c2": list(_SCORED)}
        self.scores_path = Path(self.tmp.name) / "scores.json.gz"
        write_scores(self.scores_path, self.meta, self.scores)
        self.store_path = Path(self.tmp.name) / "store.json.gz"
        self.cached_rows = [{"row_token": self.vocab.id("ROW_S1"),
                             "items": ["0000000002", "0000000003"]}]
        with gzip.open(self.store_path, "wt", encoding="utf-8") as handle:
            json.dump({"pages": [{"customer_id": "c2", "rows": self.cached_rows}]}, handle)

    def _engine(self, *, scores=None, page_stores=()):
        with patch("serving.genpage2_server._load_decoder",
                   return_value=(self.decoder, self.vocab, None, self.content_rows_map, "cpu")):
            return genpage2_server.Engine(Path("ckpt"), "validate", "cpu", data=Path(self.tmp.name),
                                          scores=scores, page_stores=page_stores, hybrid_lambda=4.0)

    def _offline_h_thin(self, history):
        """서버가 만드는 것과 같은 ctx 로 오프라인 H-thin 페이지를 만든다."""
        events = [{"item": article} for article in reversed(history)]
        ctx_tokens, ctx_content, _ = build_prompt(self.vocab, now=config.request_of("validate"),
                                                  profile=None, events=events, content_rows=self.content_rows_map)
        archive = {"ctx_tokens": np.asarray(ctx_tokens, dtype=np.int64),
                   "ctx_content": np.asarray(ctx_content, dtype=np.int64),
                   "ctx_offsets": np.asarray([0, len(ctx_tokens)], dtype=np.int64)}
        meta = pd.DataFrame({"customer_id": ["c1"], "history": [history]})
        pages, _ = build_pages("H-thin", meta, archive, {"c1": list(_SCORED)}, vocab=self.vocab,
                               decoder=self.decoder, row_lambda=4.0)
        return pages["c1"]

    def test_compose_absent_is_the_plain_response(self):
        engine = self._engine(scores=self.scores_path, page_stores=[self.store_path])
        plain = engine._page_unlocked({"history": _HISTORY})
        with_compose = engine._page_unlocked({"history": _HISTORY, "compose": "generate"})
        self.assertEqual(set(plain), {"rows", "forward_passes", "violations", "context", "model"})
        self.assertNotIn("composition", plain)
        self.assertNotIn("fallback", plain)
        self.assertEqual(with_compose["rows"], plain["rows"])
        self.assertEqual(with_compose["composition"], "generate")
        self.assertIsNone(with_compose["fallback"])

    def test_rule_rows_match_compose_b(self):
        engine = self._engine(scores=self.scores_path)
        body = engine._page_unlocked({"history": _HISTORY, "customer": "c1", "compose": "rule"})
        self.assertEqual(body["composition"], "rule")
        self.assertIsNone(body["fallback"])
        self.assertEqual(_response_rows(self.vocab, body),
                         _normalize(compose_b(list(_SCORED), _HISTORY, self.vocab)))

    def test_hybrid_rows_match_offline_h_thin(self):
        engine = self._engine(scores=self.scores_path)
        body = engine._page_unlocked({"history": _HISTORY, "customer": "c1", "compose": "hybrid"})
        self.assertEqual(body["composition"], "hybrid")
        self.assertIsNone(body["fallback"])
        self.assertEqual(_response_rows(self.vocab, body), _normalize(self._offline_h_thin(_HISTORY)))
        self.assertTrue(body["rows"])

    def test_exclude_products_are_not_shown(self):
        engine = self._engine(scores=self.scores_path)
        exclude = ["0000000002", "0000000003"]
        for compose in ("rule", "hybrid"):
            body = engine._page_unlocked({"history": _HISTORY, "customer": "c1",
                                          "compose": compose, "exclude": exclude})
            shown = {_article_id(item) for row in body["rows"] for item in row["items"]}
            self.assertEqual(shown & set(exclude), set())
            self.assertTrue(shown)

    def test_no_scores_falls_back_to_generate(self):
        engine = self._engine(scores=self.scores_path)
        for compose in ("rule", "hybrid", "hybrid-cached"):
            body = engine._page_unlocked({"history": _HISTORY, "customer": "missing", "compose": compose})
            self.assertEqual(body["composition"], "generate")
            self.assertEqual(body["fallback"], "no_scores")

    def test_hybrid_cached_missing_store_falls_back_to_hybrid(self):
        engine = self._engine(scores=self.scores_path, page_stores=[self.store_path])
        body = engine._page_unlocked({"history": _HISTORY, "customer": "c1", "compose": "hybrid-cached"})
        self.assertEqual(body["composition"], "hybrid")
        self.assertEqual(body["fallback"], "not_in_store")
        self.assertEqual(_response_rows(self.vocab, body), _normalize(self._offline_h_thin(_HISTORY)))

    def test_hybrid_cached_returns_the_store_page(self):
        engine = self._engine(scores=self.scores_path, page_stores=[self.store_path])
        body = engine._page_unlocked({"history": [], "customer": "c2", "compose": "hybrid-cached"})
        self.assertEqual(body["composition"], "hybrid-cached")
        self.assertIsNone(body["fallback"])
        self.assertEqual(_response_rows(self.vocab, body),
                         [(int(self.cached_rows[0]["row_token"]),
                           tuple(self.cached_rows[0]["items"]))])

    def test_hybrid_cached_with_exclude_uses_hybrid(self):
        engine = self._engine(scores=self.scores_path, page_stores=[self.store_path])
        body = engine._page_unlocked({"history": [], "customer": "c2", "compose": "hybrid-cached",
                                      "exclude": ["0000000002"]})
        self.assertEqual(body["composition"], "hybrid")
        self.assertEqual(body["fallback"], "exclude")

    def test_scores_round_trip_loader(self):
        loaded = read_scores(self.scores_path)
        self.assertEqual(loaded["c1"], list(_SCORED))


class ServingHttpTest(unittest.TestCase):
    """HTTP 핸들러가 `compose` · `customer` 를 그대로 받는다(실제 서버 스레드)."""

    def setUp(self):
        self.articles = _articles_frame()
        self.vocab = Vocab.build(_transactions_frame(), self.articles)
        self.content_rows_map = content_rows(self.articles)
        cfg = ModelConfig(vocab_size=len(self.vocab.tokens), dim=8, layers=1, heads=2,
                          ffn=16, dropout=0.0, maxlen=64, content_dim=384)
        torch.manual_seed(13)
        content = torch.zeros((len(self.content_rows_map), 384))
        self.model = GenPageV2(cfg, content, tokens=self.vocab.tokens).eval()
        self.decoder = PageDecoder(self.model, self.vocab, self.content_rows_map, "cpu")
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        meta = pd.DataFrame({"customer_id": ["c1"], "history": [_HISTORY]})
        self.scores_path = Path(self.tmp.name) / "scores.json.gz"
        write_scores(self.scores_path, meta, {"c1": list(_SCORED)})
        with patch("serving.genpage2_server._load_decoder",
                   return_value=(self.decoder, self.vocab, None, self.content_rows_map, "cpu")):
            self.engine = genpage2_server.Engine(Path("ckpt"), "validate", "cpu", data=Path(self.tmp.name),
                                                 scores=self.scores_path, hybrid_lambda=4.0)
        self.old = genpage2_server.ENGINE
        genpage2_server.ENGINE = self.engine
        try:
            self.httpd = genpage2_server.ThreadingHTTPServer(("127.0.0.1", 0), genpage2_server.Handler)
        except PermissionError:
            genpage2_server.ENGINE = self.old
            self.skipTest("sandbox does not permit loopback sockets")
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.thread.join()
        self.httpd.server_close()
        genpage2_server.ENGINE = self.old

    def request(self, path, body=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.httpd.server_port)
        connection.request("POST" if body is not None else "GET", path,
                           body=json.dumps(body) if body is not None else None,
                           headers={"Content-Type": "application/json"} if body is not None else {})
        response = connection.getresponse()
        result = json.loads(response.read())
        connection.close()
        return response.status, result

    def test_health_reports_score_and_store_counts(self):
        status, health = self.request("/health")
        self.assertEqual(status, 200)
        self.assertEqual(health["scores"], 1)
        self.assertEqual(health["page_store"], 0)

    def test_page_rule_over_http(self):
        status, body = self.request("/page", {"history": _HISTORY, "customer": "c1", "compose": "rule"})
        self.assertEqual(status, 200)
        self.assertEqual(body["composition"], "rule")
        self.assertEqual(_response_rows(self.vocab, body),
                         _normalize(compose_b(list(_SCORED), _HISTORY, self.vocab)))
        self.assertIn("ms", body)


if __name__ == "__main__":
    unittest.main()
