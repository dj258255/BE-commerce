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


if __name__ == "__main__":
    unittest.main()
