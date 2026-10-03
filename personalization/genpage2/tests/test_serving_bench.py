from __future__ import annotations

import gzip
import json
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pandas as pd
import numpy as np


ROOT = Path(__file__).resolve().parents[3]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from genpage2 import config
from genpage2.simulate import HttpSource, PageRequest, _events_of
from genpage2.vocab import Vocab
from tools.s1_serving_bench import (closed_loop, compare_against_source, expand_paths, load_pages,
                                    summarize_observations)


def _frame(rows):
    return pd.DataFrame(rows, columns=["t_dat", "customer_id", "article_id", "sales_channel_id", "price"])


def _articles():
    return pd.DataFrame({
        "article_id": [f"{index:010d}" for index in range(1, 5)],
        "section_no": [1.0, 1.0, 5.0, 5.0],
        "product_type_name": ["Dress"] * 4,
        "colour_group_name": ["Red"] * 4,
        "index_group_name": ["Ladieswear"] * 4,
    })


def _vocab(articles):
    rows = [("2020-01-01", "v", article, 1, 10.0) for article in articles["article_id"]
            for _ in range(config.MIN_COUNT)]
    return Vocab.build(_frame(rows), articles)


class _FakeServer:
    """정해진 응답을 돌려주는 표준 라이브러리 HTTP 서버(스레드)."""

    def __init__(self, responder=None, status=200):
        self.responder = responder or (lambda body, index: {"rows": [], "violations": 0})
        self.status = status
        self.requests = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                length = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(length) or b"{}")
                outer.requests.append(body)
                if outer.status != 200:
                    raw = json.dumps({"error": "boom"}).encode("utf-8")
                else:
                    raw = json.dumps(outer.responder(body, len(outer.requests) - 1)).encode("utf-8")
                self.send_response(outer.status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return f"http://127.0.0.1:{self.httpd.server_address[1]}"

    def stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=5)


def _page_response(items=("0000000001", "0000000002"), row="ROW_S1", ms=1.5,
                   composition="generate", fallback=None):
    body = {"rows": [{"row": row, "category": row, "title": row,
                      "items": [int(value) for value in items]}],
            "violations": 0, "ms": ms}
    if composition is not None:
        body["composition"] = composition
    if fallback is not None or composition is not None:
        body["fallback"] = fallback
    return body


class ComposeBodyTest(unittest.TestCase):
    """compose · customer 를 안 주면 본문이 지금과 같다(계약 필드)."""

    def setUp(self):
        self.vocab = _vocab(_articles())

    def _request(self):
        events = [{"item": "0000000001", "at": "2020-08-01", "action": "STORE", "price": 10.0}]
        return PageRequest("c1", pd.Timestamp("2020-09-16"), [1], [-1], ["0000000002"], [], [], events, {"age": 30})

    def test_without_compose_body_is_unchanged(self):
        server = _FakeServer()
        try:
            source = HttpSource(server.url, "v2", self.vocab, {})
            body = source.request_body(self._request())
        finally:
            server.stop()
        self.assertEqual(body, {
            "history": [], "events": [{"item": "0000000001", "at": "2020-08-01", "action": "STORE", "price": 10.0}],
            "profile": {"age": 30}, "now": "2020-09-16T00:00:00", "pin_repeat": True,
            "rows": 6, "items_per_row": 8, "prefix": 2})
        self.assertNotIn("compose", body)
        self.assertNotIn("customer", body)

    def test_with_compose_adds_contract_fields(self):
        server = _FakeServer()
        try:
            source = HttpSource(server.url, "v2", self.vocab, {}, compose="hybrid")
            body = source.request_body(self._request())
        finally:
            server.stop()
        self.assertEqual(body["compose"], "hybrid")
        self.assertEqual(body["customer"], "c1")

    def test_compose_is_v2_only_and_known(self):
        server = _FakeServer()
        try:
            with self.assertRaises(ValueError):
                HttpSource(server.url, "v1", self.vocab, {}, compose="rule")
            with self.assertRaises(ValueError):
                HttpSource(server.url, "v2", self.vocab, {}, compose="nope")
        finally:
            server.stop()


class L1ComparisonTest(unittest.TestCase):
    """L1 비교가 행 토큰 · 상품 순서를 보고 불일치 예시를 남긴다."""

    def setUp(self):
        self.vocab = _vocab(_articles())
        self.request = PageRequest("c1", pd.Timestamp("2020-09-16"), [1], [-1], [], [], [], [], {})

    def _offline(self, items=("0000000001", "0000000002"), row="ROW_S1"):
        return {"c1": [{"row_token": self.vocab.id(row),
                        "items": [str(value) for value in items]}]}

    def test_match(self):
        server = _FakeServer(lambda body, index: _page_response())
        try:
            source = HttpSource(server.url, "v2", self.vocab, {}, compose="rule")
            result = compare_against_source(source, [self.request], self._offline())
        finally:
            server.stop()
        self.assertEqual((result["matched"], result["mismatched"], result["errors"]), (1, 0, 0))
        self.assertEqual(source.composition_counts["generate"], 1)

    def test_mismatch_records_first_difference(self):
        server = _FakeServer(lambda body, index: _page_response(items=("0000000002", "0000000001")))
        try:
            source = HttpSource(server.url, "v2", self.vocab, {}, compose="rule")
            result = compare_against_source(source, [self.request], self._offline())
        finally:
            server.stop()
        self.assertEqual((result["matched"], result["mismatched"]), (0, 1))
        self.assertEqual(result["examples"][0]["first_diff"]["index"], 0)

    def test_missing_customer_is_counted(self):
        server = _FakeServer(lambda body, index: _page_response())
        try:
            source = HttpSource(server.url, "v2", self.vocab, {}, compose="rule")
            result = compare_against_source(source, [self.request], {})
        finally:
            server.stop()
        self.assertEqual((result["matched"], result["missing"]), (0, 1))


class L2SummaryTest(unittest.TestCase):
    """닫힌 루프가 고객 전원을 한 번씩 보내고 응답 수 · 오류 · composition · fallback 을 맞게 센다."""

    def setUp(self):
        self.vocab = _vocab(_articles())

    def _requests(self, count):
        return [PageRequest(f"c{index}", pd.Timestamp("2020-09-16"), [1], [-1], [], [], [], [], {})
                for index in range(count)]

    def test_every_customer_sent_exactly_once(self):
        server = _FakeServer(lambda body, index: _page_response(composition="hybrid"))
        try:
            source = HttpSource(server.url, "v2", self.vocab, {}, compose="hybrid")
            observations, errors, elapsed = closed_loop(source, self._requests(5), 2, 10.0)
            customers = sorted(str(body["customer"]) for body in server.requests)
        finally:
            server.stop()
        summary = summarize_observations(observations, errors, elapsed)
        self.assertEqual((summary["responses"], summary["errors"]), (5, 0))
        self.assertEqual(customers, ["c0", "c1", "c2", "c3", "c4"])
        self.assertEqual(summary["composition"], {"hybrid": 5})
        self.assertEqual(summary["fallback"], {"null": 5})
        self.assertGreater(summary["throughput"], 0.0)

    def test_http_error_is_counted(self):
        server = _FakeServer(status=500)
        try:
            source = HttpSource(server.url, "v2", self.vocab, {}, compose="rule")
            observations, errors, elapsed = closed_loop(source, self._requests(4), 2, 10.0)
        finally:
            server.stop()
        summary = summarize_observations(observations, errors, elapsed)
        self.assertEqual(summary["responses"], 0)
        self.assertEqual(summary["errors"], 4)


class FallbackAggregationTest(unittest.TestCase):
    """fallback 사유를 세고 대체 비율을 낼 수 있다(L3)."""

    def setUp(self):
        self.vocab = _vocab(_articles())
        self.request = PageRequest("c1", pd.Timestamp("2020-09-16"), [1], [-1], [], [], [], [], {})

    def test_fallback_reason_is_counted(self):
        server = _FakeServer(lambda body, index: _page_response(composition="generate", fallback="no_scores"))
        try:
            source = HttpSource(server.url, "v2", self.vocab, {}, compose="hybrid")
            source.pages([self.request])
            source.pages([self.request])
        finally:
            server.stop()
        self.assertEqual(source.fallback_counts, {"no_scores": 2})
        self.assertEqual(source.composition_counts, {"generate": 2})
        summary = summarize_observations(source.observations, 0, 1.0)
        self.assertEqual(summary["fallback"], {"no_scores": 2})
        self.assertEqual(source.observations[0]["fallback"], "no_scores")


class HistoryEventsTest(unittest.TestCase):
    """이력 이벤트 수를 100 으로 넓혀도 기본값(프롬프트가 쓰는 60)의 본문은 지금과 같다."""

    def setUp(self):
        self.vocab = _vocab(_articles())

    def _request(self, count):
        events = [{"item": "0000000001", "at": "2020-08-01", "action": "STORE", "price": 10.0}
                  for _ in range(count)]
        return PageRequest("c1", pd.Timestamp("2020-09-16"), [1], [-1], [], [], [], events, {})

    def test_events_of_default_is_the_prompt_cap_and_100_widens_it(self):
        events = {"dates": np.arange(150), "articles": np.array([f"a{index}" for index in range(150)]),
                  "channels": np.ones(150, dtype=np.int64), "prices": np.ones(150)}
        self.assertEqual(len(_events_of(events, 150)), config.HISTORY_EVENTS)
        self.assertEqual(len(_events_of(events, 150, 100)), 100)

    def test_body_default_keeps_current_events(self):
        server = _FakeServer()
        try:
            body = HttpSource(server.url, "v2", self.vocab, {}).request_body(self._request(3))
        finally:
            server.stop()
        self.assertEqual(len(body["events"]), 3)

    def test_body_100_sends_at_most_100_recent_events(self):
        server = _FakeServer()
        try:
            default_body = HttpSource(server.url, "v2", self.vocab, {}).request_body(self._request(120))
            wide_body = HttpSource(server.url, "v2", self.vocab, {},
                                   history_events=100).request_body(self._request(120))
        finally:
            server.stop()
        self.assertEqual(len(default_body["events"]), config.HISTORY_EVENTS)
        self.assertEqual(len(wide_body["events"]), 100)


class LoadPagesTest(unittest.TestCase):
    """오프라인 조각(.json · .json.gz)을 고객별로 합친다."""

    def test_loads_gzip_shard_and_expands_glob(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "B-1.json.gz"
            report = {"mode": "final", "options": {"compose": "B"}, "pages": [
                {"customer_id": "c1", "rows": [{"row_token": 75, "items": ["0000000001"]}]}]}
            with gzip.open(path, "wt", encoding="utf-8") as handle:
                json.dump(report, handle)
            pages = load_pages([str(Path(directory) / "B-*.json.gz")])
            expanded = expand_paths([str(Path(directory) / "*.json.gz")])
        self.assertEqual(pages, {"c1": [{"row_token": 75, "items": ["0000000001"]}]})
        self.assertEqual(len(expanded), 1)

    def test_duplicate_customer_raises(self):
        with tempfile.TemporaryDirectory() as directory:
            first = Path(directory) / "a.json"
            second = Path(directory) / "b.json"
            shard = {"pages": [{"customer_id": "c1", "rows": []}]}
            first.write_text(json.dumps(shard), encoding="utf-8")
            second.write_text(json.dumps(shard), encoding="utf-8")
            with self.assertRaises(ValueError):
                load_pages([str(first), str(second)])


if __name__ == "__main__":
    unittest.main()
