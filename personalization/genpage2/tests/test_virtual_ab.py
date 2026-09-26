from __future__ import annotations

import json
import sys
import threading
import unittest
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import numpy as np
import pandas as pd


ROOT = Path(__file__).resolve().parents[3]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from genpage2 import config
from genpage2.simulate import (HttpSource, PageRequest, PriceIndex, ReactionModel, SimUser, _page_prefix,
                               build_eval_users, load_attributes, persona_of, rule_policy_class, simulate)
from genpage2.vocab import Vocab, _article_id, content_rows
from tools.v2_virtual_ab import arm_of, bootstrap_ci


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


class _StubServer:
    """HttpSource 가 부르는 표준 라이브러리 HTTP 서버(정해진 응답을 돌려준다)."""

    def __init__(self, response, status=200):
        self.response = response
        self.status = status
        self.requests = []            # (Transfer-Encoding, Content-Length 헤더, 원문 바이트 길이, 본문)
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                length = int(self.headers.get("Content-Length", 0))
                raw = self.rfile.read(length)
                outer.requests.append((self.headers.get("Transfer-Encoding"),
                                       self.headers.get("Content-Length"), len(raw), json.loads(raw)))
                body = json.dumps(outer.response).encode("utf-8")
                self.send_response(outer.status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

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


class HttpSourceV1Test(unittest.TestCase):
    def setUp(self):
        self.articles = _articles()
        self.vocab = _vocab(self.articles)
        self.sections = {"0000000001": 1.0, "0000000002": 5.0, "0000000003": 9.0, "0000000004": 1.0}

    def _request(self):
        return PageRequest("c1", pd.Timestamp("2020-09-16"), [1, 2], [-1, -1],
                           ["0000000002", "0000000001"], [], ["0000000003"])

    def test_v1_body_is_length_framed_and_rows_use_section_mode(self):
        server = _StubServer({"rows": [
            {"category": "Ladieswear", "items": [1, 1, 2]},
            {"category": "Ladieswear", "items": [3]},
            {"category": "Ladieswear", "items": [9999999999]},
        ], "violations": 0, "forward_passes": 1})
        try:
            rows = HttpSource(server.url, "v1", self.vocab, self.sections).pages([self._request()])[0]
        finally:
            server.stop()
        transfer, length, raw_length, body = server.requests[0]
        self.assertIsNone(transfer)                       # chunked 금지
        self.assertEqual(int(length), raw_length)         # 길이가 붙은 본문
        self.assertEqual(body, {"history": [2, 1], "rows": 6, "items_per_row": 8, "prefix": 2})
        self.assertEqual([row.row_token for row in rows],
                         [self.vocab.id("ROW_S1"), self.vocab.id("ROW_FALLBACK"), self.vocab.id("ROW_FALLBACK")])
        self.assertEqual(rows[0].items, ["0000000001", "0000000001", "0000000002"])

    def test_violations_and_http_errors_raise(self):
        server = _StubServer({"rows": [], "violations": 2})
        try:
            with self.assertRaises(RuntimeError):
                HttpSource(server.url, "v1", self.vocab, self.sections).pages([self._request()])
        finally:
            server.stop()
        server = _StubServer({"error": "boom"}, status=500)
        try:
            with self.assertRaises(RuntimeError):
                HttpSource(server.url, "v1", self.vocab, self.sections).pages([self._request()])
        finally:
            server.stop()


class HttpSourceV2Test(unittest.TestCase):
    def setUp(self):
        self.articles = _articles()
        self.vocab = _vocab(self.articles)

    def test_v2_body_and_row_token(self):
        server = _StubServer({"rows": [
            {"row": "ROW_S1", "category": "S1", "title": "S1", "items": [1, 2]},
            {"row": "ROW_FALLBACK", "category": "기타", "title": "기타", "items": []},
        ], "violations": 0})
        events = [{"item": "0000000001", "at": "2020-08-01", "action": "STORE", "price": 10.0},
                  {"item": "0000000002", "at": "2020-09-01", "action": "ONLINE", "price": 20.0}]
        request = PageRequest("c1", pd.Timestamp("2020-09-16"), [1], [-1], ["0000000002"], [], [],
                              events, {"age": 30})
        try:
            rows = HttpSource(server.url, "v2", self.vocab, {}).pages([request])[0]
        finally:
            server.stop()
        body = server.requests[0][3]
        self.assertEqual(body["history"], [])
        self.assertEqual(body["events"], events)
        self.assertEqual(body["profile"], {"age": 30})
        self.assertEqual(body["now"], "2020-09-16T00:00:00")
        self.assertTrue(body["pin_repeat"])
        self.assertEqual((body["rows"], body["items_per_row"], body["prefix"]), (6, 8, 2))
        self.assertEqual([row.row_token for row in rows],
                         [self.vocab.id("ROW_S1"), self.vocab.id("ROW_FALLBACK")])
        self.assertEqual(rows[0].items, ["0000000001", "0000000002"])


class AllocationTest(unittest.TestCase):
    def test_assignment_is_deterministic_and_balanced(self):
        ids = [f"customer-{index}" for index in range(10000)]
        arms = [arm_of(customer) for customer in ids]
        self.assertEqual(arms, [arm_of(customer) for customer in ids])
        self.assertLess(abs(arms.count("A") / len(arms) - 0.5), 0.03)


class BootstrapTest(unittest.TestCase):
    def test_interval_contains_a_known_difference(self):
        result = bootstrap_ci([0.0] * 200, [1.0] * 200, 2000, np.random.default_rng(7))
        self.assertAlmostEqual(result["diff"], 1.0)
        self.assertLessEqual(result["ci95"][0], 1.0)
        self.assertGreaterEqual(result["ci95"][1], 1.0)

    def test_empty_arm_has_no_interval(self):
        result = bootstrap_ci([], [1.0], 100, np.random.default_rng(7))
        self.assertIsNone(result["diff"])
        self.assertEqual(result["ci95"], [None, None])


class EvalUserTest(unittest.TestCase):
    """평가 진입점이 요청 시각 이후 거래를 문맥 · 가격에 넣지 않는다."""

    def setUp(self):
        self.articles = _articles()
        self.vocab = _vocab(self.articles)
        self.content = content_rows(self.articles)
        self.attributes = load_attributes(self.articles)
        self.request = pd.Timestamp("2020-09-16")

    def _transactions(self):
        rows = [("2020-01-01", "v", article, 1, 10.0) for article in self.articles["article_id"]
                for _ in range(config.MIN_COUNT)]
        rows += [("2020-08-01", "c1", "0000000001", 1, 10.0),
                 ("2020-09-16", "c1", "0000000002", 2, 999.0)]
        frame = _frame(rows)
        frame["t_dat"] = pd.to_datetime(frame["t_dat"])
        return frame

    def _customers(self):
        return pd.DataFrame({"customer_id": ["c1"], "age": [30], "club_member_status": ["ACTIVE"],
                             "fashion_news_frequency": ["NONE"], "FN": [1.0], "Active": [1.0]})

    def test_wanted_is_the_window_and_context_prices_ignore_the_window(self):
        users, prices = build_eval_users(self.vocab, self._transactions(), self._customers(),
                                         self.attributes, self.content, request=self.request)
        self.assertEqual(len(users), 1)
        user = users[0]
        self.assertEqual(user.wanted, ["0000000002"])
        for event in user.events:
            self.assertLess(pd.Timestamp(event["at"]), self.request)
        self.assertIn(self.vocab.item("0000000001"), user.ctx_tokens)
        self.assertNotIn(self.vocab.item("0000000002"), user.ctx_tokens)
        self.assertEqual(prices.price_as_of("0000000002", self.request), 10.0)   # 999 가 아니라 r 이전 값
        self.assertEqual(prices.bucket("0000000002", self.request), prices.bucket("0000000001", self.request))


class ExcludeBodyTest(unittest.TestCase):
    """2쪽 요청에만 `exclude` 가 실리고, 그 집합이 1쪽 응답 상품과 같다."""

    def setUp(self):
        self.articles = _articles()
        self.vocab = _vocab(self.articles)
        self.sections = {"0000000001": 1.0, "0000000002": 1.0, "0000000003": 5.0, "0000000004": 5.0}

    def _first_request(self):
        return PageRequest("c1", pd.Timestamp("2020-09-16"), [1, 2], [-1, -1], ["0000000002"], [], [])

    def _response(self, kind):
        if kind == "v1":
            return {"rows": [{"category": "Ladieswear", "items": [1, 2]},
                             {"category": "Ladieswear", "items": [3, 4]}], "violations": 0}
        return {"rows": [{"row": "ROW_S1", "category": "S1", "title": "S1", "items": [1, 2]},
                         {"row": "ROW_S5", "category": "S5", "title": "S5", "items": [3, 4]}], "violations": 0}

    def _check(self, kind):
        server = _StubServer(self._response(kind))
        try:
            source = HttpSource(server.url, kind, self.vocab, self.sections)
            first = self._first_request()
            rows = source.pages([first])[0]
            source.pages([replace(first, prev_page=_page_prefix(self.vocab, rows))])
        finally:
            server.stop()
        page1_body, page2_body = server.requests[0][3], server.requests[1][3]
        self.assertNotIn("exclude", page1_body)
        shown = [article for row in rows for article in row.items]
        self.assertEqual(set(shown), {"0000000001", "0000000002", "0000000003", "0000000004"})
        expected = {int(article) for article in shown} if kind == "v1" else set(shown)
        self.assertEqual(set(page2_body["exclude"]), expected)
        self.assertEqual(len(page2_body["exclude"]), len(expected))
        if kind == "v1":
            self.assertTrue(all(isinstance(value, int) for value in page2_body["exclude"]))
        else:
            self.assertTrue(all(isinstance(value, str) for value in page2_body["exclude"]))

    def test_v1_excludes_the_previous_page(self):
        self._check("v1")

    def test_v2_excludes_the_previous_page(self):
        self._check("v2")


class _NoBuyReaction(ReactionModel):
    """1쪽에서 아무것도 사지 않게 해 2쪽까지 가는 길을 연다(하네스 점검용)."""

    def feedback(self, persona, item, rank, pos, rng):
        return "skip"


class _ExcludeServer:
    """`exclude` 를 받아 그 상품을 뺀 페이지를 돌려준다(실제 서버와 같은 규칙)."""

    def __init__(self, pool, kind, rows=1, items_per_row=2):
        self.pool = [str(article) for article in pool]
        self.kind = kind
        self.rows = rows
        self.items_per_row = items_per_row
        self.requests = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                length = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(length) or b"{}")
                outer.requests.append(body)
                excluded = {_article_id(str(value)) for value in body.get("exclude", [])}
                chosen = [article for article in outer.pool if article not in excluded]
                chosen = chosen[:outer.rows * outer.items_per_row]
                rows = []
                for index in range(0, len(chosen), outer.items_per_row):
                    items = [int(article) for article in chosen[index:index + outer.items_per_row]]
                    if outer.kind == "v1":
                        rows.append({"category": "Ladieswear", "items": items})
                    else:
                        rows.append({"row": "ROW_S1", "category": "S1", "title": "S1", "items": items})
                raw = json.dumps({"rows": rows, "violations": 0}).encode("utf-8")
                self.send_response(200)
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


class HttpSourceSimulateTest(unittest.TestCase):
    """`simulate` 를 `HttpSource` 로 돌리면 2쪽이 1쪽 상품을 다시 보여 주지 않는다."""

    def setUp(self):
        self.articles = _articles()
        self.vocab = _vocab(self.articles)
        self.attributes = load_attributes(self.articles)
        self.content = content_rows(self.articles)
        self.sections = {"0000000001": 1.0, "0000000002": 1.0, "0000000003": 5.0, "0000000004": 5.0}

    def _user(self):
        return SimUser("c1", pd.Timestamp("2020-09-16"), [self.vocab.id("SEP_PAGE")], [-1], [],
                       ["0000000001"], "mobile", persona_of("c1", ["0000000001"], self.attributes))

    def _check(self, kind):
        server = _ExcludeServer(list(self.sections), kind)
        policy = rule_policy_class()()
        policy.NEXT_PAGE = 1.0
        try:
            pages = simulate([self._user()], HttpSource(server.url, kind, self.vocab, self.sections),
                             vocab=self.vocab, attributes=self.attributes, content_rows_map=self.content,
                             reaction=_NoBuyReaction(policy), prices=PriceIndex(self.vocab))
        finally:
            server.stop()
        self.assertEqual([page.page_no for page in pages], [1, 2])
        first = {impression.article_id for impression in pages[0].impressions}
        second = {impression.article_id for impression in pages[1].impressions}
        self.assertEqual(first, {"0000000001", "0000000002"})
        self.assertEqual(second, {"0000000003", "0000000004"})
        self.assertFalse(first & second)
        self.assertEqual({_article_id(str(value)) for value in server.requests[1]["exclude"]}, first)

    def test_v1_second_page_skips_the_first_page_items(self):
        self._check("v1")

    def test_v2_second_page_skips_the_first_page_items(self):
        self._check("v2")


if __name__ == "__main__":
    unittest.main()
