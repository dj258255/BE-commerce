from __future__ import annotations

import json
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pandas as pd


ROOT = Path(__file__).resolve().parents[3]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from genpage2 import config
from genpage2.simulate import HttpSource, PageRequest
from genpage2.vocab import Vocab
from tools.s1_serving_bench import closed_loop as s1_closed_loop
from tools.s1_serving_bench import summarize_observations
from tools.usl_bench import closed_loop as usl_closed_loop


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
    """정해진 응답을 돌려주는 표준 라이브러리 HTTP 서버(스레드). 받은 본문을 남긴다."""

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


def _page_response(composition="hybrid"):
    return {"rows": [], "violations": 0, "ms": 1.0, "composition": composition, "fallback": None}


def _requests(count):
    return [PageRequest(f"c{index}", pd.Timestamp("2020-09-16"), [1], [-1], [], [], [], [], {})
            for index in range(count)]


class UslClosedLoopTest(unittest.TestCase):
    """USL 벤치의 닫힌 루프가 포트를 번갈아 쓰고, 한 포트면 S1 과 같은 동작인가."""

    def setUp(self):
        self.vocab = _vocab(_articles())

    def _sources(self, servers):
        return [HttpSource(server.url, "v2", self.vocab, {}, compose="hybrid") for server in servers]

    def test_two_servers_split_requests_and_sum_throughput(self):
        servers = [_FakeServer(lambda body, index: _page_response()) for _ in range(2)]
        try:
            observations, errors, elapsed = usl_closed_loop(self._sources(servers), _requests(6), 2, 10.0)
        finally:
            for server in servers:
                server.stop()
        summary = summarize_observations(observations, errors, elapsed)
        received = [len(server.requests) for server in servers]
        customers = sorted(body["customer"] for server in servers for body in server.requests)
        self.assertEqual((summary["responses"], summary["errors"]), (6, 0))
        self.assertGreater(summary["throughput"], 0.0)
        # 포트를 번갈아 고르므로 요청 6건이 두 서버에 3 · 3 으로 갈린다.
        self.assertEqual(received, [3, 3])
        self.assertEqual(customers, ["c0", "c1", "c2", "c3", "c4", "c5"])
        self.assertEqual(summary["composition"], {"hybrid": 6})

    def test_two_servers_expose_errors_of_either_port(self):
        servers = [_FakeServer(lambda body, index: _page_response()), _FakeServer(status=500)]
        try:
            observations, errors, elapsed = usl_closed_loop(self._sources(servers), _requests(6), 2, 10.0)
        finally:
            for server in servers:
                server.stop()
        summary = summarize_observations(observations, errors, elapsed)
        # 한 포트가 전부 500 이면 그 포트 몫(3건)이 오류로 잡히고 나머지는 성공한다.
        self.assertEqual(summary["responses"], 3)
        self.assertEqual(summary["errors"], 3)

    def test_single_url_matches_s1_closed_loop(self):
        s1_server = _FakeServer(lambda body, index: _page_response())
        usl_server = _FakeServer(lambda body, index: _page_response())
        try:
            s1_source = self._sources([s1_server])[0]
            usl_source = self._sources([usl_server])[0]
            s1_observations, s1_errors, _ = s1_closed_loop(s1_source, _requests(6), 3, 10.0)
            usl_observations, usl_errors, _ = usl_closed_loop([usl_source], _requests(6), 3, 10.0)
            s1_customers = sorted(body["customer"] for body in s1_server.requests)
            usl_customers = sorted(body["customer"] for body in usl_server.requests)
        finally:
            s1_server.stop()
            usl_server.stop()
        # 같은 분할(요청[index::동시성]) · 같은 본문 → 같은 고객 집합, 같은 응답 수 · 오류.
        self.assertEqual(s1_customers, usl_customers)
        self.assertEqual(len(s1_server.requests), len(usl_server.requests))
        self.assertEqual(len(s1_observations), len(usl_observations))
        self.assertEqual(s1_errors, usl_errors)


if __name__ == "__main__":
    unittest.main()
