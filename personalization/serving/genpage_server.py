"""GenPage 최소형 모델 서버(#238, ADR-053). 표준 라이브러리 HTTP 서버 + PyTorch(CPU).

    GENPAGE_DATA=personalization/data python personalization/serving/genpage_server.py [포트]

  POST /recommend {"history": [상품 id], "k": 12}
       → {"items": [...], "ms": ...}                       추천 행(ModelClient) 용 — 한 번의 순전파
  POST /page      {"history": [...], "exclude": [...], "exclude_categories": [...],
                   "rows": 3, "items_per_row": 8, "prefix": 2}
       → {"rows": [{"category", "items"}], "forward_passes", "violations", "ms"}

/page 가 GenPage 의 추론을 흉내 낸다:
  1. 행 고르기: 다음 상품 분포를 대분류별로 합쳐 가장 큰 대분류(아직 안 쓴 것)를 고른다
  2. 하이브리드 행 디코딩: 그 행의 앞 prefix 개는 한 칸씩 생성해 문맥에 붙이고, 나머지는 마지막 분포에서 한 번에 고른다
  3. 매 단계 마스크: 이미 나온 상품, 앞 쪽에서 보여 준 상품(exclude), 다른 대분류의 상품은 확률을 0 으로 만든다
품절은 여기서 모른다 — 모델은 교체 가능한 의존성이고 재고를 알 이유가 없다. 품절은 호출자가 생성 뒤에 거른다(ADR-050).

violations 는 서버가 스스로 센 규칙 위반 수다(중복·대분류 불일치·제외 상품). 마스크가 맞으면 늘 0 이다.
"""
from __future__ import annotations

import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pandas as pd
import torch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "pipeline"))
from promote_products import CATEGORY_NAMES  # noqa: E402  대분류 규칙은 카탈로그 적재와 같은 한 곳
from train_genpage import GenPageMini, MAXLEN  # noqa: E402

DATA = Path(os.environ.get("GENPAGE_DATA", ROOT / "data"))
MODEL_DIR = DATA / "hm" / "model" / "genpage"
torch.set_num_threads(int(os.environ.get("GENPAGE_THREADS", "2")))


class Engine:
    def __init__(self):
        vocab = json.loads((MODEL_DIR / "vocab.json").read_text())
        cfg = vocab["config"]
        self.items = vocab["items"]                                   # 토큰 i+1 = items[i](article_id 문자열)
        self.token_of = {int(a): i + 1 for i, a in enumerate(self.items)}   # 상품 id(앞자리 0 없는 정수) → 토큰
        self.product_of = torch.tensor([0] + [int(a) for a in self.items], dtype=torch.long)
        self.model = GenPageMini(len(self.items) + 1, cfg["dim"], cfg["layers"], cfg["heads"], cfg["maxlen"])
        self.model.load_state_dict(torch.load(MODEL_DIR / "model.pt", map_location="cpu"))
        self.model.eval()
        art = pd.read_parquet(DATA / "hm" / "normalized" / "articles.parquet", columns=["article_id", "index_group_name"])
        group = dict(zip(art["article_id"].astype(str), art["index_group_name"]))
        self.codes = sorted({v[0] for v in CATEGORY_NAMES.values()})
        cat = [-1] + [self.codes.index(CATEGORY_NAMES[group[a]][0]) if group.get(a) in CATEGORY_NAMES else -1
                      for a in self.items]
        self.category = torch.tensor(cat, dtype=torch.long)          # 토큰 → 대분류 번호(-1 = 모름)

    def _prompt(self, history):
        toks = [self.token_of[h] for h in reversed(history) if h in self.token_of]   # history 는 최근 것부터 온다
        return toks[-MAXLEN:] or [0]

    @torch.no_grad()
    def _logits(self, toks):
        x = torch.tensor([toks[-MAXLEN:]], dtype=torch.long)
        return self.model.next_logits(x, torch.tensor([len(toks[-MAXLEN:])]))[0]

    def recommend(self, history, k):
        logits = self._logits(self._prompt(history))
        return self.product_of[torch.topk(logits, k).indices].tolist()

    def page(self, history, exclude, exclude_categories, rows, items_per_row, prefix):
        toks = self._prompt(history)
        banned = torch.zeros(len(self.product_of), dtype=torch.bool)
        banned[0] = True
        for e in exclude:
            if e in self.token_of:
                banned[self.token_of[e]] = True
        used = {self.codes.index(c) for c in exclude_categories if c in self.codes}
        out, passes = [], 0
        for _ in range(rows):
            logits = self._logits(toks)
            passes += 1
            probs = torch.softmax(logits.masked_fill(banned, float("-inf")), 0)
            mass = torch.zeros(len(self.codes)).scatter_add_(0, self.category.clamp(min=0), probs * (self.category >= 0))
            for u in used:
                mass[u] = -1
            if mass.max() <= 0:
                break
            c = int(mass.argmax())
            used.add(c)
            in_row = (self.category == c) & ~banned
            chosen = []
            for _ in range(min(prefix, items_per_row)):              # 앞 prefix 개: 한 칸씩 생성해 문맥에 붙인다
                masked = logits.masked_fill(~in_row, float("-inf"))
                if torch.isinf(masked.max()):
                    break
                t = int(masked.argmax())
                chosen.append(t)
                in_row[t] = False
                banned[t] = True
                toks = toks + [t]
                logits = self._logits(toks)
                passes += 1
            rest = items_per_row - len(chosen)                        # 나머지: 마지막 분포에서 한 번에
            masked = logits.masked_fill(~in_row, float("-inf"))
            n = min(rest, int((~torch.isinf(masked)).sum()))
            if n > 0:
                for t in torch.topk(masked, n).indices.tolist():
                    chosen.append(t)
                    banned[t] = True
                toks = toks + chosen[len(chosen) - n:]
            out.append({"category": self.codes[c], "items": self.product_of[torch.tensor(chosen, dtype=torch.long)].tolist() if chosen else []})
        return out, passes, self._violations(out, exclude)

    def _violations(self, rows, exclude):
        seen, bad, excluded = set(), 0, set(exclude)
        for row in rows:
            for pid in row["items"]:
                t = self.token_of.get(pid)
                if pid in seen or pid in excluded or t is None or self.codes[int(self.category[t])] != row["category"]:
                    bad += 1
                seen.add(pid)
        return bad


ENGINE: Engine | None = None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _send(self, code, body):
        raw = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        self._send(200 if self.path == "/health" else 404, {"status": "UP", "vocab": len(ENGINE.items)})

    def _body(self):
        """본문을 읽는다. chunked 도 푼다(#254) — 예전에는 Content-Length 만 읽어 chunked 본문을 빈 것으로 봤다."""
        if "chunked" in self.headers.get("Transfer-Encoding", "").lower():
            data = b""
            while True:
                size = int(self.rfile.readline().strip().split(b";")[0] or b"0", 16)
                if size == 0:
                    self.rfile.readline()
                    break
                data += self.rfile.read(size)
                self.rfile.readline()
            return data
        return self.rfile.read(int(self.headers.get("Content-Length", 0)))

    def do_POST(self):
        req = json.loads(self._body() or b"{}")
        started = time.perf_counter()
        if self.path in ("/recommend", "/page") and "history" not in req:
            # 이력 키가 없으면 본문을 못 읽은 것이다. 빈 이력으로 조용히 생성하지 않는다
            self._send(400, {"error": "history 가 없다 — 본문을 읽지 못했을 수 있다"})
            return
        if self.path == "/recommend":
            items = ENGINE.recommend(req.get("history", []), int(req.get("k", 12)))
            self._send(200, {"items": items, "ms": (time.perf_counter() - started) * 1000})
        elif self.path == "/page":
            rows, passes, violations = ENGINE.page(req.get("history", []), req.get("exclude", []),
                                                   req.get("exclude_categories", []), int(req.get("rows", 3)),
                                                   int(req.get("items_per_row", 8)), int(req.get("prefix", 2)))
            self._send(200, {"rows": rows, "forward_passes": passes, "violations": violations,
                             "ms": (time.perf_counter() - started) * 1000})
        else:
            self._send(404, {"error": "no such path"})


def main():
    global ENGINE
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    t0 = time.time()
    ENGINE = Engine()
    print(f"GenPage 모델 서버 :{port} · 어휘 {len(ENGINE.items):,} · 적재 {time.time() - t0:.1f}s", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
