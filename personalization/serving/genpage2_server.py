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
from typing import Any, Sequence

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from genpage2 import config  # noqa: E402
from genpage2.evaluate import _load_decoder  # noqa: E402
from genpage2.page_compose import (THIN_DECODE_KWARGS, compose_b, h_thin_example,  # noqa: E402
                                    read_page_store, read_scores)
from genpage2.prompt import build_prompt  # noqa: E402
from genpage2.simulate import (HISTORY_STORE_EVENTS, TRANSACTION_COLUMNS,  # noqa: E402
                               build_history_store, eval_transactions)
from genpage2.vocab import _article_id  # noqa: E402


def _int_article(value: str) -> int:
    """v1 sends article ids as JSON integers; preserve that response contract."""
    return int(value) if value.isdigit() else value  # type: ignore[return-value]


class Engine:
    def __init__(self, ckpt: Path, mode: str, device: str = "cpu", data: Path | None = None, *,
                 scores: Path | None = None, page_stores: Sequence[Path] = (),
                 hybrid_lambda: float = 4.0, history_store: bool = False,
                 transactions: pd.DataFrame | None = None):
        self.mode = mode
        self.data = data or config.data_dir()
        self.mode_dir = self.data / "hm" / "model" / "genpage2" / mode
        self.decoder, self.vocab, _, self.content_rows, self.device = _load_decoder(self.mode_dir, ckpt, device)
        self.ckpt = Path(ckpt).name
        self.lock = threading.Lock()
        self.price_by_article, self.default_price = self._prices()
        # S1(#454): 순위 모델 점수(상위 200)와 미리 계산한 H'(4) 페이지 저장소.
        # ``--scores`` · ``--page-store`` 가 없으면 rule · hybrid 는 대체 경로로 간다.
        self.scores = read_scores(scores) if scores else {}
        self.page_store: dict[str, Any] = {}
        for path in page_stores:
            self.page_store.update(read_page_store(path))
        self.hybrid_lambda = float(hybrid_lambda)
        # S2(#455): `--history-store`. 켜면 고객 이력을 H&M 거래(모드 요청 시각 이전 최근 100건)
        # 에서 읽어 둔다. `/page` 에 `customer` 가 있고 그 고객이 있으면 요청의 history · events
        # 대신 이벤트를 프롬프트와 다시 사기 행 이력에 쓴다. 끄면(기본) 지금과 완전히 같다.
        # 서버가 아는 고객(점수 키)만 담아 메모리를 묶는다 — H&M 고객 137만 명 전부는 담을 수 없다.
        self.history_store: dict[str, list[dict[str, Any]]] | None = None
        if history_store:
            self.history_store = self._build_history_store(transactions)
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

    def _build_history_store(self, transactions: pd.DataFrame | None) -> dict[str, list[dict[str, Any]]]:
        """S2(#455): 요청 시각 이전 고객별 최근 100건 구매 이벤트(``build_history_store``).

        테스트는 ``transactions`` 를 직접 준다. 실제 기동에서는 서버가 아는 고객(점수 키)의
        거래만 읽어 담는다 — ``build_eval_users`` 와 같은 함수를 쓰므로 이벤트가 같고,
        고객 단위 그룹화라 부분집합으로 줄여도 그 고객의 이벤트는 전체 표와 같다.
        """
        request = config.request_of(self.mode)
        if transactions is not None:
            frame = transactions
        elif self.scores:
            frame = eval_transactions(self.data / "hm" / "normalized" / "transactions.parquet",
                                      sorted(self.scores), request)
        else:
            frame = pd.read_parquet(self.data / "hm" / "normalized" / "transactions.parquet",
                                    columns=list(TRANSACTION_COLUMNS))
        return build_history_store(self.vocab, frame, request=request,
                                   history_events=HISTORY_STORE_EVENTS)

    def _request_events(self, request: dict[str, Any]) -> list[dict[str, Any]]:
        if "events" in request:
            raw = request.get("events") or []
            if not isinstance(raw, list):
                raise ValueError("events 는 배열이어야 합니다")
            return [dict(x) for x in raw]
        history = request.get("history", [])
        if not isinstance(history, list):
            raise ValueError("history 는 배열이어야 합니다")
        return [{"item": item} for item in reversed(history)]

    def _events(self, request: dict[str, Any]) -> tuple[list[dict[str, Any]], str]:
        """(이벤트, history_source). S2(#455): 저장소가 있고 그 고객이 있으면 저장소 이벤트."""
        customer = request.get("customer")
        store = self.history_store
        if store is not None and customer is not None and str(customer) in store:
            events = [dict(x) for x in store[str(customer)]]
            source = "store"
        else:
            events = self._request_events(request)
            source = "request"
        session = request.get("session", [])
        if session:
            if not isinstance(session, list):
                raise ValueError("session 은 배열이어야 합니다")
            events.extend(dict(x) for x in session)
        for event in events:
            article = _article_id(event.get("item", ""))
            if event.get("price") is None:
                event["_inferred_price"] = self.price_by_article.get(article, self.default_price)
        return events, source

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

    def _prompt(self, request: dict[str, Any], kind: str = "page"
                ) -> tuple[list[int], list[int], dict[str, Any], list[str], str]:
        events, history_source = self._events(request)
        # S2(#455): 저장소 이벤트는 모드 요청 시각 기준이라 요청의 `now` 를 쓰지 않는다.
        now = config.request_of(self.mode) if history_source == "store" else request.get("now")
        tokens, content, report = build_prompt(self.vocab,
                                               now=now or config.request_of(self.mode), profile=request.get("profile"),
                                               events=events, content_rows=self.content_rows)
        report["missing"]["now"] = now is None
        report["level"] = self.decoder.level
        if self.prompt_log:
            self._log_prompt(kind, request, events, tokens)
        history = [_article_id(x["item"]) for x in events
                   if str(x.get("action") or "ONLINE").upper() in ("STORE", "ONLINE") and "item" in x]
        return tokens, content, report, list(reversed(history)), history_source

    def _log_prompt(self, kind: str, request: dict[str, Any], events: list[dict[str, Any]], tokens: list[int]) -> None:
        """Append the parsed prompt. Called under ``self.lock``, so lines never interleave."""
        # `bytes` is the request re-serialized compactly — the same form the app's Jackson writes.
        line = {"t": time.time(), "kind": kind, "now": request.get("now"),
                "bytes": len(json.dumps(request, ensure_ascii=False, separators=(",", ":")).encode("utf-8")),
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
        tokens, content, report, history, history_source = self._prompt(request, "recommend" if recommend else "page")
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
            body = {"items": [_int_article(item) for row in rows for item in row.items][:k],
                    "violations": violations, "context": report}
        else:
            compose = request.get("compose")
            if compose is None:
                body = self._generate_body(request, tokens, content, report, history,
                                           exclude, excluded_rows, prev_page, allowed_items)
            else:
                body = self._composed(request, compose, tokens, content, report, history,
                                      exclude, excluded_rows, prev_page, allowed_items)
        # S2(#455): 저장소를 켰을 때만 응답에 이력 출처를 남긴다(끄면 지금과 완전히 같다).
        if self.history_store is not None:
            body["history_source"] = history_source
        return body

    def _generate_body(self, request: dict[str, Any], tokens: list[int], content: list[int], report: dict[str, Any],
                       history: list[str], exclude: set[str], excluded_rows: set[int], prev_page: list[int],
                       allowed_items: set[str] | None) -> dict[str, Any]:
        """지금까지의 생성 응답. ``compose`` 가 없으면 이 모양 그대로 돌려준다."""
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

    def _rows_body(self, rows: list[Any], report: dict[str, Any], *, composition: str, fallback: str | None,
                   violations: int, forward_passes: int) -> dict[str, Any]:
        """rule · hybrid 의 행 응답(기존 필드 모양 유지, composition · fallback 추가)."""
        return {"rows": [{"category": self.row_names.get(row.row_token, self.vocab.tokens[row.row_token]),
                           "row": self.vocab.tokens[row.row_token],
                           "title": self.row_titles.get(row.row_token, self.vocab.tokens[row.row_token]),
                           "items": [_int_article(item) for item in row.items]} for row in rows],
                "forward_passes": forward_passes,
                "violations": violations, "context": report,
                "model": {"ckpt": self.ckpt, "level": self.decoder.level},
                "composition": composition, "fallback": fallback}

    def _composed(self, request: dict[str, Any], compose: str, tokens: list[int], content: list[int],
                  report: dict[str, Any], history: list[str], exclude: set[str], excluded_rows: set[int],
                  prev_page: list[int], allowed_items: set[str] | None) -> dict[str, Any]:
        """``compose`` 요청을 계약대로 처리한다(BACKEND "S1" 절)."""
        if compose not in ("generate", "rule", "hybrid", "hybrid-cached"):
            raise ValueError(f"compose 는 generate · rule · hybrid · hybrid-cached 중 하나: {compose}")
        if compose == "generate":
            body = self._generate_body(request, tokens, content, report, history,
                                       exclude, excluded_rows, prev_page, allowed_items)
            body["composition"], body["fallback"] = "generate", None
            return body
        customer = request.get("customer")
        scored = self.scores.get(str(customer)) if customer is not None else None
        if not scored:
            # 점수가 없으면 지금 방식(generate)으로 만들고 이유를 남긴다.
            body = self._generate_body(request, tokens, content, report, history,
                                       exclude, excluded_rows, prev_page, allowed_items)
            body["composition"], body["fallback"] = "generate", "no_scores"
            return body
        # exclude(앞 쪽에서 보여 준 상품)는 상위 200개에서 먼저 뺀 뒤 줄을 구성한다.
        scored = [(article, score) for article, score in scored if article not in exclude]
        # 앱 2쪽은 rows 3 · items_per_row 8 로 요청한다. 요청 값을 무시하고 항상 6행 × 8개를 만들면
        # 결합 방식만 행이 두 배가 되어 앱 지연 비교가 불공정하다(S2 M2). 요청에 없으면 지금과 같은
        # 6 · 8 이라 S1 결과는 그대로다(계약: 요청 값 우선).
        n_rows = int(request.get("rows", config.MAX_ROWS))
        items_per_row = int(request.get("items_per_row", config.ITEMS_PER_ROW))
        if compose == "rule":
            rows = compose_b(scored, history, self.vocab, rows=n_rows, items=items_per_row)
            return self._rows_body(rows, report, composition="rule", fallback=None,
                                   violations=0, forward_passes=0)
        composition, fallback = compose, None
        if compose == "hybrid-cached":
            cached = self.page_store.get(str(customer))
            if cached is not None and not exclude:
                return self._rows_body(cached, report, composition="hybrid-cached", fallback=None,
                                       violations=0, forward_passes=0)
            composition = "hybrid"
            fallback = "exclude" if cached is not None else "not_in_store"
        # hybrid: 오프라인 build_pages 의 H-thin 분기와 같은 함수 · 같은 인자. 다만 행 수 ·
        # 행 안 개수는 요청 값을 쓴다(위 주석) — 요청에 없으면 THIN_DECODE_KWARGS 의 6 · 8 그대로다.
        example = h_thin_example(tokens, content, scored, history, self.vocab, self.hybrid_lambda)
        decode_kwargs = dict(THIN_DECODE_KWARGS)
        decode_kwargs["n_rows"] = n_rows
        decode_kwargs["items_per_row"] = items_per_row
        rows, violations = self.decoder.generate_batch([example], **decode_kwargs)[0]
        forward_passes = 1 + sum(1 + len(row.items) for row in rows)
        return self._rows_body(rows, report, composition=composition, fallback=fallback,
                               violations=violations, forward_passes=forward_passes)

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
            engine = ENGINE  # type: ignore[assignment]
            self._send(200, {"status": "UP", "vocab": len(engine.vocab.tokens),
                             "scores": len(getattr(engine, "scores", {})),
                             "page_store": len(getattr(engine, "page_store", {}))})
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
    # S1(#454): 순위 모델 점수(상위 200)와 미리 계산한 H'(4) 페이지 저장소. 없으면 rule · hybrid 는 대체 경로.
    parser.add_argument("--scores", type=Path, help="page_compose.read_scores 형식(scores.json.gz)")
    parser.add_argument("--page-store", type=Path, nargs="+", default=[],
                        help="page_compose 조각(pages[]) — 여러 개")
    parser.add_argument("--hybrid-lambda", type=float, default=4.0)
    # S2(#455): 고객 이력을 H&M 거래(모드 요청 시각 이전 최근 100건)에서 읽어 둔다.
    parser.add_argument("--history-store", action="store_true",
                        help="요청 customer 의 이력을 서버가 만든 저장소에서 읽는다(기본: 요청 이력)")
    args = parser.parse_args()
    import torch
    torch.set_num_threads(args.threads)
    began = time.perf_counter()
    ENGINE = Engine(args.ckpt, args.mode, args.device, scores=args.scores, page_stores=args.page_store,
                    hybrid_lambda=args.hybrid_lambda, history_store=args.history_store)
    history_store = getattr(ENGINE, "history_store", None)
    print(f"GenPage v2 모델 서버 :{args.port} · 어휘 {len(ENGINE.vocab.tokens):,} "
          f"· 점수 {len(ENGINE.scores):,} · 페이지 저장소 {len(ENGINE.page_store):,} "
          f"· 이력 저장소 {'끔' if history_store is None else f'{len(history_store):,}'} "
          f"· 기동 {time.perf_counter() - began:.1f}s", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
