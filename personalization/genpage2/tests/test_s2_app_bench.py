from __future__ import annotations

import json
import sys
import threading
import unittest
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.s2_app_bench import (all_genpage, closed_loop, compare_rows, compare_captures, leaked_items,
                                metric_delta, metric_value, m3_passed, parse_prometheus, page_items,
                                server_hybrid_body, summarize_page_latencies, run_m1, run_m2, run_m3)


class _FakeApp:
    """앱의 홈 · 로그인 · 회원가입 · 지표 엔드포인트를 흉내 내는 표준 라이브러리 서버(스레드)."""

    def __init__(self, p1, p2, compose_key=("none", "no_mapping")):
        self.p1 = p1
        self.p2 = p2
        self.compose_key = compose_key
        self.signups = 0
        self.compose_counts: Counter[tuple[str, str]] = Counter()
        self.failed = 0
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def _write(self, code, raw, content_type):
                self.send_response(code)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def _json(self, code, body):
                self._write(code, json.dumps(body).encode("utf-8"), "application/json")

            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length", 0)))
                if self.path == "/api/v1/members/signup":
                    outer.signups += 1
                    self._json(201, {"id": outer.signups})
                elif self.path == "/api/v1/auth/login":
                    self._json(200, {"token": f"t{outer.signups or 1}"})
                else:
                    self._json(404, {})

            def do_GET(self):
                if self.path.startswith("/actuator/prometheus"):
                    self._write(200, outer.prometheus_text().encode("utf-8"), "text/plain")
                elif self.path.startswith("/api/v1/personalization/homepage"):
                    if "cursor=" in self.path:
                        outer.compose_counts[outer.compose_key] += 1
                        outer.failed += 1
                        self._json(200, outer.p2)
                    else:
                        self._json(200, outer.p1)
                else:
                    self._json(404, {})

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return f"http://127.0.0.1:{self.httpd.server_address[1]}"

    def prometheus_text(self):
        lines = [f'recommendation_genpage_compose_total{{composition="{comp}",fallback="{fb}",}} {float(n)}'
                 for (comp, fb), n in self.compose_counts.items()]
        lines.append(f'recommendation_genpage_page_total{{result="failed",}} {float(self.failed)}')
        return "\n".join(lines) + "\n"

    def stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=5)


class _FakeModel:
    """모델 서버 `/page` 를 흉내 내는 서버. 보낸 본문을 남긴다."""

    def __init__(self, rows):
        self.rows = rows
        self.requests = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                outer.requests.append(json.loads(raw or b"{}"))
                body = json.dumps({"rows": outer.rows, "violations": 0, "composition": "hybrid",
                                   "fallback": None}).encode("utf-8")
                self.send_response(200)
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


def _app_row(category, items, strategy="GENPAGE"):
    return {"id": f"cat:{category}", "title": category, "strategy": strategy,
            "items": [{"itemId": str(item)} for item in items]}


def _p1(items=(11, 12), categories=("recent",)):
    return {"rows": [_app_row(category, items, "RECENT_VIEW") for category in categories],
            "nextCursor": "C1"}


def _p2(category="Womens Everyday Basics", items=(100, 101, 102), strategy="GENPAGE"):
    return {"rows": [_app_row(category, items, strategy)]}


class CompareRowsTest(unittest.TestCase):
    def test_match_and_mismatch(self):
        server = {"rows": [{"category": "Womens Everyday Basics", "items": [100, 101, 102]}]}
        self.assertTrue(compare_rows(_p2(), server)["matched"])
        self.assertEqual(compare_rows(_p2(), server)["app_rows"], 1)
        mismatch = compare_rows(_p2(items=(101, 100, 102)), server)
        self.assertFalse(mismatch["matched"])
        self.assertEqual(mismatch["first_diff"]["index"], 0)

    def test_short_server_rows_are_filtered_by_min_items(self):
        """앱이 minItems 미만 행을 버리므로 서버 행을 같은 기준으로 거른 뒤 비교한다."""
        server = {"rows": [{"category": "A", "items": [1, 2, 3]}, {"category": "B", "items": [4, 5]},
                           {"category": "C", "items": [6, 7, 8]}]}
        app = {"rows": [_app_row("A", (1, 2, 3)), _app_row("C", (6, 7, 8))]}
        self.assertTrue(compare_rows(app, server, min_items=3)["matched"])
        self.assertFalse(compare_rows(app, server, min_items=1)["matched"])

    def test_leaked_items_detects_first_page_items(self):
        first = _p1(items=(11, 12))
        self.assertEqual(leaked_items(first, _p2(items=(100, 102))), [])
        self.assertEqual(leaked_items(first, _p2(items=(12, 100))), [12])


class ServerBodyTest(unittest.TestCase):
    def test_hybrid_body_has_contract_fields(self):
        body = server_hybrid_body([11, 12], "hm-1")
        self.assertEqual(body["compose"], "hybrid")
        self.assertEqual(body["customer"], "hm-1")
        self.assertEqual(body["exclude"], [11, 12])
        self.assertEqual((body["rows"], body["items_per_row"], body["prefix"]), (3, 8, 2))


class PrometheusTest(unittest.TestCase):
    def test_parses_tags_and_values(self):
        text = ('# HELP x\nrecommendation_genpage_compose_total{composition="none",fallback="no_mapping",} 3.0\n'
                'recommendation_genpage_page_total{result="failed",} 2.0\n')
        snapshot = parse_prometheus(text)
        self.assertEqual(metric_value(snapshot, "recommendation_genpage_compose_total",
                                      composition="none", fallback="no_mapping"), 3.0)
        delta = metric_delta({"a": 1.0}, {"a": 4.0, "b": 2.0})
        self.assertEqual(delta["a"], 3.0)
        self.assertEqual(delta["b"], 2.0)

    def test_m3_passed_reads_each_case(self):
        self.assertTrue(m3_passed({"status": 200, "case": "a", "all_genpage": True, "rows": 1,
                                   "strategies": ["GENPAGE"], "compose_delta": {"none/no_mapping": 1.0},
                                   "page_delta": {}}))
        self.assertTrue(m3_passed({"status": 200, "case": "b", "all_genpage": False, "rows": 1,
                                   "strategies": ["GENPAGE"], "compose_delta": {"generate/no_scores": 1.0},
                                   "page_delta": {}}))
        self.assertTrue(m3_passed({"status": 200, "case": "c", "all_genpage": False, "rows": 1,
                                   "strategies": ["CATEGORY_POPULAR"], "compose_delta": {},
                                   "page_delta": {"failed": 1.0}}))
        self.assertFalse(m3_passed({"status": 500, "case": "a", "all_genpage": False, "rows": 0,
                                    "strategies": [], "compose_delta": {}, "page_delta": {}}))


class LatencyTest(unittest.TestCase):
    def test_summary_counts_errors_genpage_and_rule_fallback(self):
        observations = [{"latency_ms": 10.0, "genpage": True, "strategies": ["GENPAGE"]},
                        {"latency_ms": 20.0, "genpage": True, "strategies": ["GENPAGE"]},
                        {"latency_ms": 30.0, "genpage": False, "strategies": ["CATEGORY_POPULAR"]}]
        summary = summarize_page_latencies(observations, 1, 3.0)
        self.assertEqual((summary["responses"], summary["errors"]), (3, 1))
        self.assertEqual(summary["wall"]["p50_ms"], 20.0)
        self.assertAlmostEqual(summary["genpage_rate"], 2 / 3)
        self.assertAlmostEqual(summary["rule_rate"], 1 / 3)
        self.assertAlmostEqual(summary["other_rate"], 0.0)
        self.assertEqual(summary["strategies"], {"GENPAGE": 2, "CATEGORY_POPULAR": 1})

    def test_closed_loop_sends_each_session_once(self):
        app = _FakeApp(_p1(), _p2())
        try:
            sessions = [{"token": "t", "cursor": "C1"} for _ in range(4)]
            observations, errors, elapsed = closed_loop(app.url, sessions, 1, 10.0)
        finally:
            app.stop()
        self.assertEqual((len(observations), errors), (4, 0))
        self.assertEqual(len(app.compose_counts), 1)   # 네 번 다 2쪽으로 갔다
        self.assertGreaterEqual(elapsed, 0.0)


class RunStagesTest(unittest.TestCase):
    def test_run_m1_matches_app_and_server(self):
        app = _FakeApp(_p1(items=(11, 12)), _p2(items=(100, 101, 102)))
        model = _FakeModel([{"category": "Womens Everyday Basics", "items": [100, 101, 102]}])
        try:
            accounts = [{"i": 0, "email": "a@load.test", "hm_customer_id": "hm-1"}]
            report = run_m1(app.url, model.url, accounts, 0, 10.0)
        finally:
            app.stop()
            model.stop()
        self.assertTrue(report["all_matched"])
        self.assertEqual(report["leaked_accounts"], 0)
        self.assertEqual(model.requests[0]["customer"], "hm-1")
        self.assertEqual(model.requests[0]["exclude"], [11, 12])

    def test_run_m2_reports_setting_and_latency(self):
        app = _FakeApp(_p1(), _p2())
        try:
            accounts = [{"i": 0, "email": "a@load.test", "hm_customer_id": "hm-1"}]
            report = run_m2(app.url, accounts, "HYBRID", 0, 1, 10.0, "앱 기본 설정")
        finally:
            app.stop()
        self.assertEqual(report["setting"], "HYBRID")
        self.assertEqual(report["compose"], "hybrid")
        self.assertEqual(report["app_settings"], "앱 기본 설정")
        self.assertEqual(report["responses"], 1)
        self.assertEqual(report["genpage_rate"], 1.0)
        self.assertEqual(report["rule_rate"], 0.0)
        self.assertEqual(report["strategies"], {"GENPAGE": 1})

    def test_run_m3_cases(self):
        accounts = {"unmapped": [{"i": 0, "email": "u@load.test"}],
                    "no_scores": [{"i": 0, "email": "n@load.test", "hm_customer_id": "hm-n"}],
                    "mapped": [{"i": 0, "email": "m@load.test", "hm_customer_id": "hm-m"}]}
        app_a = _FakeApp(_p1(), _p2(), compose_key=("none", "no_mapping"))
        app_b = _FakeApp(_p1(), _p2(), compose_key=("generate", "no_scores"))
        app_c = _FakeApp(_p1(), _p2(strategy="CATEGORY_POPULAR"), compose_key=("none", "none"))
        try:
            report_a = run_m3(app_a.url, accounts, "a", 10.0)
            report_b = run_m3(app_b.url, accounts, "b", 10.0)
            report_c = run_m3(app_c.url, accounts, "c", 10.0)
        finally:
            for app in (app_a, app_b, app_c):
                app.stop()
        self.assertTrue(m3_passed(report_a))
        self.assertTrue(m3_passed(report_b))
        self.assertTrue(m3_passed(report_c))
        self.assertFalse(all_genpage(_p2(strategy="CATEGORY_POPULAR")))
        self.assertEqual(page_items(_p1(items=(11, 12))), [11, 12])


class M4CompareTest(unittest.TestCase):
    def test_compare_captures(self):
        main = {"accounts": [{"email": "a@load.test", "p1": [1], "rows": [{"category": "c", "items": [1]}]}]}
        branch = {"accounts": [{"email": "a@load.test", "p1": [1], "rows": [{"category": "c", "items": [1]}]}]}
        report = compare_captures(main, branch)
        self.assertTrue(report["all_same"])
        branch["accounts"][0]["rows"][0]["items"] = [2]
        self.assertFalse(compare_captures(main, branch)["all_same"])


if __name__ == "__main__":
    unittest.main()
