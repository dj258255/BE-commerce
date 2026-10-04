from __future__ import annotations

import json
import sys
import threading
import unittest
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pandas as pd


ROOT = Path(__file__).resolve().parents[3]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from genpage2 import config
from genpage2.simulate import (GeneratedRow, HttpSource, Impression, PageRequest, PriceIndex, ReactionModel,
                               SimUser, _page_prefix, build_eval_users, load_attributes, persona_of,
                               rule_policy_class, simulate)
from genpage2.vocab import Vocab, _article_id, content_rows
from tools.v2_virtual_ab import (METRICS, RecordingSource, _paired_table, arm_of, bootstrap_ci, build_parser,
                                 min_items_hit, notation, paired_bootstrap_ci, paired_kept, paired_metrics,
                                 run_paired_arms)


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


class ComposeNotationTest(unittest.TestCase):
    """compose 로 가른 A/B 는 모드 · 정책 표기가 compose 값을 따른다(S1 결함 수정)."""

    def test_compose_aa_and_ab(self):
        aa = {"A": ("v2", "u", "hybrid"), "B": ("v2", "u", "hybrid")}
        self.assertEqual(notation(aa, split=True, mode="aa"), ("aa", {"A": "hybrid", "B": "hybrid"}))
        ab = {"A": ("v2", "u", "rule"), "B": ("v2", "u", "hybrid")}
        self.assertEqual(notation(ab, split=True, mode="aa"), ("ab", {"A": "rule", "B": "hybrid"}))

    def test_without_compose_keeps_mode_and_kinds(self):
        kinds = {"A": ("v1", "u1", None), "B": ("v2", "u2", None)}
        self.assertEqual(notation(kinds, split=False, mode="ab"), ("ab", {"A": "v1", "B": "v2"}))


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


class PairedBootstrapTest(unittest.TestCase):
    """짝 부트스트랩 — 같은 정책 둘이면 차이 0 · 구간 [0, 0]. """

    def test_identical_arms_have_zero_difference_and_interval(self):
        values = [0.0, 1.0, 0.0, 0.25, 0.75]
        result = paired_bootstrap_ci(values, values, 2000, np.random.default_rng(7))
        self.assertEqual(result["diff"], 0.0)
        self.assertEqual(result["ci95"], [0.0, 0.0])

    def test_constant_difference_is_exact(self):
        result = paired_bootstrap_ci([0.0, 0.0, 0.0], [1.0, 1.0, 1.0], 100, np.random.default_rng(7))
        self.assertAlmostEqual(result["diff"], 1.0)
        self.assertEqual(result["ci95"], [1.0, 1.0])

    def test_length_mismatch_raises(self):
        with self.assertRaises(ValueError):
            paired_bootstrap_ci([0.0], [0.0, 1.0], 10, np.random.default_rng(7))


def _response(items=(1, 2, 3)):
    return {"rows": [{"row": "ROW_S1", "category": "S1", "title": "S1", "items": list(items)}],
            "violations": 0}


class PairedRunTest(unittest.TestCase):
    """짝 모드가 모든 사용자에게 A · B 두 정책을 돌린다(가짜 서버)."""

    def setUp(self):
        self.articles = _articles()
        self.vocab = _vocab(self.articles)
        self.attributes = load_attributes(self.articles)
        self.content = content_rows(self.articles)
        self.prices = PriceIndex(self.vocab)
        self.sections = {"0000000001": 1.0, "0000000002": 1.0, "0000000003": 5.0, "0000000004": 5.0}

    def _users(self):
        return [SimUser(f"c{index}", pd.Timestamp("2020-09-16"), [self.vocab.id("SEP_PAGE")], [-1], [],
                        [f"000000000{index}"], "mobile",
                        persona_of(f"c{index}", [f"000000000{index}"], self.attributes))
                for index in (1, 2, 3)]

    def _pair(self, compose_a, compose_b, response_a=None, response_b=None):
        users = self._users()
        server_a = _StubServer(response_a or _response())
        server_b = _StubServer(response_b or _response())
        try:
            a = RecordingSource(HttpSource(server_a.url, "v2", self.vocab, self.sections, compose=compose_a))
            b = RecordingSource(HttpSource(server_b.url, "v2", self.vocab, self.sections, compose=compose_b))
            pages_a, pages_b = run_paired_arms(users, a, b, vocab=self.vocab, attributes=self.attributes,
                                               content_map=self.content, prices=self.prices, chunk=2)
        finally:
            server_a.stop(), server_b.stop()
        return users, a, b, pages_a, pages_b, server_a, server_b

    def test_both_policies_run_for_every_user(self):
        users, _a, _b, pages_a, pages_b, server_a, server_b = self._pair("rule", "hybrid")
        wanted = sorted(user.customer_id for user in users)
        self.assertEqual(sorted({page.customer_id for page in pages_a}), wanted)
        self.assertEqual(sorted({page.customer_id for page in pages_b}), wanted)
        # 각 정책이 사용자마다 최소 한 번(1쪽) 요청을 받았고, 보낸 compose · customer 가 맞다.
        self.assertGreaterEqual(len(server_a.requests), len(users))
        self.assertGreaterEqual(len(server_b.requests), len(users))
        self.assertEqual(server_a.requests[0][3]["compose"], "rule")
        self.assertEqual(server_b.requests[0][3]["compose"], "hybrid")
        self.assertEqual(server_a.requests[0][3]["customer"], "c1")

    def test_same_policy_yields_zero_difference_and_interval(self):
        users, _a, _b, pages_a, pages_b, _sa, _sb = self._pair("rule", "rule")
        metrics, _frame = paired_metrics(users, pages_a, pages_b, draws=2000, seed=7)
        for name in list(METRICS) + ["purchase_hit_min_items"]:
            self.assertEqual(metrics[name]["diff"], 0.0)
            self.assertEqual(metrics[name]["ci95"], [0.0, 0.0])


class MinItemsTest(unittest.TestCase):
    """앱의 min-items 필터가 상품 3개 미만 행만 버린다."""

    def _impression(self, article, row_rank):
        return Impression("c1", pd.Timestamp("2020-09-16"), 1, row_rank, 1, 0, article, 1, "skip", 0.0, "mobile")

    def setUp(self):
        # 0행: 1개(짧은 행) · 1행: 3개.
        self.pages = [SimpleNamespace(impressions=[self._impression("0000000001", 0),
                                                   self._impression("0000000002", 1),
                                                   self._impression("0000000003", 1),
                                                   self._impression("0000000004", 1)])]

    def test_only_short_rows_are_dropped(self):
        self.assertEqual(min_items_hit(self.pages, ["0000000001"], 3), 0.0)   # 짧은 행에만 있다
        self.assertEqual(min_items_hit(self.pages, ["0000000002"], 3), 1.0)   # 3개 행에 있다
        self.assertEqual(min_items_hit(self.pages, ["0000000001"], 2), 0.0)   # 기준 2 도 버린다
        self.assertEqual(min_items_hit(self.pages, ["0000000001"], 1), 1.0)   # 안 버리면 있다


class _FailingSource:
    """정해진 고객의 요청에서 예외를 올리는 PageSource(실패 집계 확인용)."""

    name = "failing"

    def __init__(self, fails):
        self.fails = set(fails)
        self.seen = []

    def pages(self, examples):
        result = []
        for example in examples:
            self.seen.append(str(example.customer_id))
            if str(example.customer_id) in self.fails:
                raise RuntimeError("boom")
            result.append([GeneratedRow(1, ["0000000001", "0000000002", "0000000003"])])
        return result


class PairedFailureTest(unittest.TestCase):
    """실패한 사용자는 두 정책 모두에서 빠진다(짝 유지)."""

    def test_failed_user_is_dropped_from_both_policies(self):
        articles = _articles()
        vocab = _vocab(articles)
        attributes = load_attributes(articles)
        content = content_rows(articles)
        users = [SimUser(f"c{index}", pd.Timestamp("2020-09-16"), [vocab.id("SEP_PAGE")], [-1], [],
                         ["0000000001"], "mobile", persona_of(f"c{index}", ["0000000001"], attributes))
                 for index in (1, 2, 3)]
        a = RecordingSource(_FailingSource({"c2"}))
        b = RecordingSource(_FailingSource(set()))
        run_paired_arms(users, a, b, vocab=vocab, attributes=attributes, content_map=content,
                        prices=PriceIndex(vocab), chunk=2)
        kept, failed = paired_kept(users, a, b)
        self.assertEqual(failed, {"c2"})
        self.assertEqual([user.customer_id for user in kept], ["c1", "c3"])
        self.assertEqual(len(a.failed), 1)          # 실패는 세어 둔다
        self.assertEqual(len(b.failed), 0)
        metrics, _frame = paired_metrics(kept, [], [], draws=10, seed=7)
        self.assertIsNotNone(metrics["purchase_hit"]["diff"])


class S4AppConfigTest(unittest.TestCase):
    """S4(#467) 앱 구성 인자 — 기본값이면 지금과 같고, 주면 본문 · 표가 따라간다."""

    def test_parser_defaults_keep_current_shape(self):
        args = build_parser().parse_args(["--v2-url", "u", "--out", "o"])
        self.assertEqual(args.rows, config.MAX_ROWS)
        self.assertEqual(args.items_per_row, config.ITEMS_PER_ROW)
        self.assertEqual(args.primary, "raw")

    def test_app_config_flags_are_parsed(self):
        args = build_parser().parse_args(["--v2-url", "u", "--out", "o",
                                          "--rows", "3", "--primary", "min-items"])
        self.assertEqual(args.rows, 3)
        self.assertEqual(args.primary, "min-items")

    def test_rows_reach_the_request_body(self):
        vocab = _vocab(_articles())
        request = PageRequest("c1", pd.Timestamp("2020-09-16"), [1], [-1], [], [], [])
        default = HttpSource("http://127.0.0.1:1", "v2", vocab, {})
        body = default.request_body(request)
        self.assertEqual((body["rows"], body["items_per_row"]), (6, 8))
        narrowed = HttpSource("http://127.0.0.1:1", "v2", vocab, {}, rows=3)
        self.assertEqual(narrowed.request_body(request)["rows"], 3)

    def _report(self, primary):
        return {"mode": "paired-ab", "request_date": "2020-09-16", "customers_sampled": 1, "seed": 7,
                "holdout_buyers": 1, "users_paired": 1, "users": 1,
                "failures": {"A": 0, "B": 0, "dropped": 0},
                "bootstrap_draws": 2000, "min_items": 3, "rows": 3, "items_per_row": 8,
                "primary": primary, "policies": {"A": "rule", "B": "hybrid"},
                "compose": {"A": "rule", "B": "hybrid"}, "response_composition": {}, "response_fallback": {},
                "metrics": {name: {"a": 0.0, "b": 0.0, "diff": 0.0, "ci95": [0.0, 0.0]}
                            for name in list(METRICS) + ["purchase_hit_min_items"]}}

    def test_paired_table_marks_only_the_primary_row(self):
        marked = [line for line in _paired_table(self._report("min-items")).splitlines()
                  if "**(주 지표)**" in line]
        self.assertEqual(len(marked), 1)
        self.assertIn("3개 미만 행 제거", marked[0])
        raw = [line for line in _paired_table(self._report("raw")).splitlines()
               if "**(주 지표)**" in line]
        self.assertEqual(len(raw), 1)
        self.assertIn("실제 구매 적중", raw[0])
        self.assertNotIn("3개 미만", raw[0])


if __name__ == "__main__":
    unittest.main()
