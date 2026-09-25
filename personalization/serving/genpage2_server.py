"""HTTP serving adapter for a GenPage v2 checkpoint.

The public routes intentionally keep the v1 wire contract so the consumer
application can switch only its endpoint/model-version setting.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from genpage2 import config  # noqa: E402
from genpage2.evaluate import _load_decoder  # noqa: E402
from genpage2.prompt import build_prompt  # noqa: E402
from genpage2.vocab import _article_id  # noqa: E402


def _int_article(value: str) -> int:
    """v1 sends article ids as JSON integers; preserve that response contract."""
    return int(value) if value.isdigit() else value  # type: ignore[return-value]


class Engine:
    def __init__(self, ckpt: Path, mode: str, device: str = "cpu", data: Path | None = None):
        self.mode = mode
        self.data = data or config.data_dir()
        self.mode_dir = self.data / "hm" / "model" / "genpage2" / mode
        self.decoder, self.vocab, _, self.content_rows, self.device = _load_decoder(self.mode_dir, ckpt, device)
        self.ckpt = Path(ckpt).name
        self.lock = threading.Lock()
        self.price_by_article, self.default_price = self._prices()
        # X5(#328): one JSON line per request with what the server understood — the
        # events it parsed and the prompt token names. Off unless the variable is set.
        self.prompt_log = os.environ.get("GENPAGE2_PROMPT_LOG") or None
        self.row_names = {self.vocab.id("ROW_REPEAT"): "REPEAT"}
        self.row_titles = {self.vocab.id("ROW_REPEAT"): "다시 사기"}
        self._load_sections()

    def _prices(self) -> tuple[dict[str, float], float]:
        fallback = float(self.vocab.price_edges[len(self.vocab.price_edges) // 2])
        try:
            tx = pd.read_parquet(self.data / "hm" / "normalized" / "transactions.parquet",
                                 columns=["t_dat", "article_id", "price"])
            tx = tx[pd.to_datetime(tx["t_dat"]) < config.request_of(self.mode)]
            values = pd.to_numeric(tx["price"], errors="coerce")
            if values.notna().any():
                fallback = float(values.median())
            tx = tx.assign(article_id=tx["article_id"].map(_article_id), price=values)
            return {str(k): float(v) for k, v in tx.groupby("article_id")["price"].median().dropna().items()}, fallback
        except (FileNotFoundError, ImportError, ValueError):
            return {}, fallback

    def _load_sections(self) -> None:
        try:
            art = pd.read_parquet(self.data / "hm" / "normalized" / "articles.parquet",
                                  columns=["section_no", "section_name"])
        except (FileNotFoundError, ImportError, ValueError):
            return
        for row in art.dropna(subset=["section_no"]).drop_duplicates("section_no").itertuples(index=False):
            number = str(int(float(row.section_no)))
            try:
                token = self.vocab.id(f"ROW_S{number}")
            except KeyError:
                continue
            name = str(row.section_name)
            self.row_names[token] = name
            self.row_titles[token] = name

    def _events(self, request: dict[str, Any]) -> list[dict[str, Any]]:
        if "events" in request:
            raw = request.get("events") or []
            if not isinstance(raw, list):
                raise ValueError("events 는 배열이어야 합니다")
            events = [dict(x) for x in raw]
        else:
            history = request.get("history", [])
            if not isinstance(history, list):
                raise ValueError("history 는 배열이어야 합니다")
            events = [{"item": item} for item in reversed(history)]
        session = request.get("session", [])
        if session:
            if not isinstance(session, list):
                raise ValueError("session 은 배열이어야 합니다")
            events.extend(dict(x) for x in session)
        for event in events:
            article = _article_id(event.get("item", ""))
            if event.get("price") is None:
                event["_inferred_price"] = self.price_by_article.get(article, self.default_price)
        return events

    def _prev_page(self, value: Any, report: dict[str, Any]) -> list[int]:
        tokens: list[int] = []
        for row in value or []:
            if not isinstance(row, dict):
                continue
            try:
                token = self.vocab.id(str(row.get("row")))
            except KeyError:
                report.setdefault("unknown_prev_rows", []).append(row.get("row"))
                continue
            tokens.append(token)
            for item in row.get("items", []):
                item_token = self.vocab.item(_article_id(item))
                if item_token is not None:
                    tokens.append(item_token)
        return tokens

    def _prompt(self, request: dict[str, Any], kind: str = "page") -> tuple[list[int], list[int], dict[str, Any], list[str]]:
        events = self._events(request)
        tokens, content, report = build_prompt(self.vocab,
                                               now=request.get("now") or config.request_of(self.mode), profile=request.get("profile"),
                                               events=events, content_rows=self.content_rows)
        report["missing"]["now"] = request.get("now") is None
        report["level"] = self.decoder.level
        if self.prompt_log:
            self._log_prompt(kind, request, events, tokens)
        history = [_article_id(x["item"]) for x in events
                   if str(x.get("action") or "ONLINE").upper() in ("STORE", "ONLINE") and "item" in x]
        return tokens, content, report, list(reversed(history))

    def _log_prompt(self, kind: str, request: dict[str, Any], events: list[dict[str, Any]], tokens: list[int]) -> None:
        """Append the parsed prompt. Called under ``self.lock``, so lines never interleave."""
        line = {"t": time.time(), "kind": kind, "now": request.get("now"),
                "events": [{"item": _article_id(e.get("item", "")), "action": e.get("action"), "at": e.get("at")}
                           for e in events],
                "tokens": [self.vocab.tokens[t] for t in tokens]}
        with open(self.prompt_log, "a", encoding="utf-8") as f:
            f.write(json.dumps(line, ensure_ascii=False) + "\n")

    def _excluded_rows(self, values: Any, report: dict[str, Any]) -> set[int]:
        wanted = set(values or [])
        rows = {token for token, name in self.row_names.items() if name in wanted}
        unknown = sorted(str(x) for x in wanted - set(self.row_names.values()))
        if unknown:
            report["unknown_exclude_categories"] = unknown
        return rows

    def _page_unlocked(self, request: dict[str, Any], *, recommend: bool = False) -> dict[str, Any]:
        tokens, content, report, history = self._prompt(request, "recommend" if recommend else "page")
        exclude = {_article_id(x) for x in request.get("exclude", [])}
        excluded_rows = self._excluded_rows(request.get("exclude_categories", []), report)
        prev_page = self._prev_page(request.get("prev_rows", []), report)
        allowed = request.get("candidates")
        allowed_items = {_article_id(x) for x in allowed} if allowed is not None else None
        if allowed is not None and not isinstance(allowed, list):
            raise ValueError("candidates 는 배열이어야 합니다")
        if recommend:
            k = max(0, int(request.get("k", 12)))
            rows, violations = self.decoder.generate(tokens, content, history_articles=history,
                                                      exclude_items=exclude, exclude_rows=excluded_rows,
                                                      prev_page=prev_page, allowed_items=allowed_items,
                                                      n_rows=min(config.MAX_ROWS, max(1, math.ceil(k / config.ITEMS_PER_ROW))),
                                                      items_per_row=config.ITEMS_PER_ROW, prefix=2)
            return {"items": [_int_article(item) for row in rows for item in row.items][:k],
                    "violations": violations, "context": report}
        n_rows, items = int(request.get("rows", 3)), int(request.get("items_per_row", 8))
        prefix = int(request.get("prefix", 2))
        pinned = {0: self.vocab.id("ROW_REPEAT")} if request.get("pin_repeat") else None
        rows, violations = self.decoder.generate(tokens, content, history_articles=history, prev_page=prev_page,
                                                  exclude_items=exclude, exclude_rows=excluded_rows, pinned=pinned,
                                                  allowed_items=allowed_items, n_rows=n_rows, items_per_row=items,
                                                  prefix=prefix)
        return {"rows": [{"category": self.row_names.get(row.row_token, self.vocab.tokens[row.row_token]),
                           "row": self.vocab.tokens[row.row_token],
                           "title": self.row_titles.get(row.row_token, self.vocab.tokens[row.row_token]),
                           "items": [_int_article(item) for item in row.items]} for row in rows],
                # Initial prompt + each row token + sequential prefix tokens +
                # one cached bulk chunk when a row has items after its prefix.
                "forward_passes": (1 + len(rows)
                                   + sum(min(max(0, prefix), len(row.items)) for row in rows)
                                   + sum(len(row.items) > max(0, prefix) for row in rows)),
                "violations": violations, "context": report,
                "model": {"ckpt": self.ckpt, "level": self.decoder.level}}

    def page(self, request: dict[str, Any], *, recommend: bool = False) -> tuple[dict[str, Any], float, float]:
        waiting = time.perf_counter()
        with self.lock:
            queue_ms = (time.perf_counter() - waiting) * 1000
            began = time.perf_counter()
            body = self._page_unlocked(request, recommend=recommend)
            return body, (time.perf_counter() - began) * 1000, queue_ms


ENGINE: Engine | None = None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_: Any) -> None:
        pass

    def _send(self, code: int, body: dict[str, Any]) -> None:
        raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _body(self) -> bytes:
        if "chunked" in self.headers.get("Transfer-Encoding", "").lower():
            chunks: list[bytes] = []
            while True:
                size = int(self.rfile.readline().strip().split(b";", 1)[0] or b"0", 16)
                if size == 0:
                    self.rfile.readline()
                    return b"".join(chunks)
                chunks.append(self.rfile.read(size))
                self.rfile.readline()
        return self.rfile.read(int(self.headers.get("Content-Length", "0")))

    def do_GET(self) -> None:
        if self.path == "/health":
            self._send(200, {"status": "UP", "vocab": len(ENGINE.vocab.tokens)})  # type: ignore[union-attr]
        else:
            self._send(404, {"error": "no such path"})

    def do_POST(self) -> None:
        try:
            request = json.loads(self._body() or b"{}")
            if not isinstance(request, dict):
                raise ValueError("JSON 본문은 객체여야 합니다")
        except (json.JSONDecodeError, UnicodeDecodeError, ValueError) as exc:
            self._send(400, {"error": str(exc)})
            return
        if self.path not in ("/recommend", "/page"):
            self._send(404, {"error": "no such path"})
            return
        if "history" not in request:
            self._send(400, {"error": "history 가 없다 — 본문을 읽지 못했을 수 있다"})
            return
        try:
            body, ms, queue_ms = ENGINE.page(request, recommend=self.path == "/recommend")  # type: ignore[union-attr]
            body["ms"], body["queue_ms"] = ms, queue_ms
            self._send(200, body)
        except Exception as exc:  # The HTTP contract intentionally returns model failures as JSON.
            self._send(500, {"error": str(exc)})


def main() -> None:
    global ENGINE
    parser = argparse.ArgumentParser()
    parser.add_argument("--ckpt", required=True, type=Path)
    parser.add_argument("--mode", choices=("validate", "final"), required=True)
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--device", choices=("cpu", "mps"), default="cpu")
    parser.add_argument("--threads", type=int, default=int(os.environ.get("GENPAGE_THREADS", "2")))
    args = parser.parse_args()
    import torch
    torch.set_num_threads(args.threads)
    ENGINE = Engine(args.ckpt, args.mode, args.device)
    print(f"GenPage v2 모델 서버 :{args.port} · 어휘 {len(ENGINE.vocab.tokens):,}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
