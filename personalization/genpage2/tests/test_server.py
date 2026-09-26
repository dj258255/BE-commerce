from __future__ import annotations

import http.client
import json
import threading
import unittest

from serving import genpage2_server


class _Engine:
    class _Vocab:
        tokens = list(range(17))

    vocab = _Vocab()

    def page(self, request, *, recommend=False):
        if recommend:
            return {"items": [123], "context": {}}, 1.5, 0.2
        return {"rows": [], "forward_passes": 1, "violations": 0, "context": {}, "model": {}}, 1.5, 0.2


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.old = genpage2_server.ENGINE
        genpage2_server.ENGINE = _Engine()
        try:
            self.httpd = genpage2_server.ThreadingHTTPServer(("127.0.0.1", 0), genpage2_server.Handler)
        except PermissionError:
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

    def test_health_and_v1_routes(self):
        status, health = self.request("/health")
        self.assertEqual((status, health), (200, {"status": "UP", "vocab": 17}))
        status, page = self.request("/page", {"history": []})
        self.assertEqual(status, 200)
        self.assertEqual(page["violations"], 0)
        self.assertIn("ms", page)
        status, recommendation = self.request("/recommend", {"history": [], "k": 1})
        self.assertEqual((status, recommendation["items"]), (200, [123]))

    def test_errors(self):
        self.assertEqual(self.request("/page", {})[0], 400)
        self.assertEqual(self.request("/missing", {"history": []})[0], 404)


class PromptLogTest(unittest.TestCase):
    """X5(#328): the server writes what it parsed, one line per request."""

    def test_log_line_has_kind_events_and_token_names(self):
        import tempfile
        from pathlib import Path

        class _Vocab:
            tokens = ["BOS", "SEP_PAGE", "ITEM_FALLBACK"]

        engine = object.__new__(genpage2_server.Engine)
        engine.vocab = _Vocab()
        with tempfile.TemporaryDirectory() as tmp:
            engine.prompt_log = str(Path(tmp) / "prompts.jsonl")
            engine._log_prompt("page", {"now": "2026-09-26T03:00:00Z"},
                               [{"item": 12, "action": "CLICK", "at": "2026-09-26T02:00:00Z", "_inferred_price": 1.0}],
                               [0, 2, 1])
            engine._log_prompt("recommend", {}, [], [0, 1])
            lines = [json.loads(x) for x in Path(engine.prompt_log).read_text().splitlines()]
        self.assertEqual(len(lines), 2)
        self.assertEqual([x["kind"] for x in lines], ["page", "recommend"])
        self.assertEqual(lines[0]["events"], [{"item": "0000000012", "action": "CLICK", "at": "2026-09-26T02:00:00Z"}])
        self.assertEqual(lines[0]["tokens"], ["BOS", "ITEM_FALLBACK", "SEP_PAGE"])
        self.assertIsNone(lines[1]["now"])
        self.assertEqual(lines[1]["bytes"], len(b"{}"))


if __name__ == "__main__":
    unittest.main()
