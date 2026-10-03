#!/usr/bin/env python3
"""S2(#455) 결합 방식을 앱 홈 2쪽에 기능 플래그로 붙였을 때의 검증 · 지연 측정.

설계 · 판정은 `personalization/docs/genpage-v2/BACKEND.md` "S2" 절에 측정 전에 고정했다.
이 도구는 그 절의 M1 ~ M4 하네스다. 실행 순서는 `tools/run-s2.sh` 가 잡는다.

- M1 앱 = 서버: 매핑 계정의 앱 홈 2쪽(`HYBRID`) 행 · 상품이, 같은 고객 · 같은 exclude 로
  모델 서버 `/page` 에 직접 보낸 `compose: hybrid` 응답과 같은가. 1쪽 상품이 2쪽에 없는가.
  대조라 타임아웃 대체가 섞이면 안 되므로 앱에 긴 모델 타임아웃을 주고 그 사실을 표에 남긴다
- M2 앱 지연: 같은 계정의 2쪽 왕복 p50 · p95 · p99 와 GENPAGE 행 비율 · 규칙 행으로 물러선
  비율, 설정(OFF · RULE · HYBRID)마다. 측정은 **앱 기본 설정**(타임아웃 300ms · page-capacity
  shared)으로 한다. 앱은 설정마다 다시 띄운다(설정은 기동 설정이다 — run-inference-overload.sh 와 같은 이유)
- M3 대체 경로: (a) 매핑 없음 → `fallback=no_mapping` (b) 점수 없음 → 서버 `generate` 대체,
  `fallback=no_scores` (c) 모델 서버를 멈춘 상태 → 규칙 행. 셋 다 응답 200 이고 지표가 맞게 센다
- M4 OFF 회귀: 플래그 `OFF` 의 앱 홈 2쪽이 main 으로 만든 앱과 같은 계정 · 같은 활동에서 같은 행

계정은 앱의 기존 API(회원가입 → 로그인)로 만들고, H&M 고객 매핑은 `personalization_user_map` 에
직접 넣는다(도커 MySQL, 기존 실행기 `genpage_purchase_eval.db` 와 같은 접속). 매핑 고객의 이력은
앱 DB 가 아니라 H&M 거래에 있으므로(서버 `--history-store`), 앱 주문을 심지 않는다.

    $PY tools/s2_app_bench.py prepare  --data-dir ... --scores ... --out OUT
    $PY tools/s2_app_bench.py signup   --app-url http://localhost:18090 --out OUT
    $PY tools/s2_app_bench.py map      --out OUT
    $PY tools/s2_app_bench.py m1       --app-url ... --model-url ... --out OUT
    $PY tools/s2_app_bench.py m2       --app-url ... --setting HYBRID --out OUT
    $PY tools/s2_app_bench.py m3       --app-url ... --case a --out OUT
    $PY tools/s2_app_bench.py m4-capture --app-url ... --label branch --out OUT
    $PY tools/s2_app_bench.py m4-report  --out OUT
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path
from typing import Any, Sequence

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
PERSONALIZATION = ROOT / "personalization"
for _path in (str(ROOT), str(PERSONALIZATION)):
    if _path not in sys.path:
        sys.path.insert(0, _path)

from genpage2 import config  # noqa: E402
from genpage2.page_compose import read_scores  # noqa: E402
from genpage2.simulate import eval_buyers  # noqa: E402
from tools.genpage2_x4_budget import latency_summary  # noqa: E402


PAGE_ROWS, ITEM_CAP, PREFIX = 3, 8, 2
APP_PASSWORD = "s2-app-only-1234"
SETTINGS = ("OFF", "RULE", "HYBRID")
COMPOSE_OF = {"OFF": None, "RULE": "rule", "HYBRID": "hybrid"}
HOME_PATH = "/api/v1/personalization/homepage"
M4_ACCOUNTS = 20
M3_MAPPED = 20


# ---------------------------------------------------------------------------
# HTTP · 앱 API
# ---------------------------------------------------------------------------

def http_call(url: str, method: str = "GET", *, token: str | None = None,
              body: Any | None = None, timeout: float = 30.0) -> tuple[int, Any, float]:
    """HTTP 한 번. 오류도 (status, None, ms)로 돌려준다 — 폴백도 설계된 응답이라 예외가 아니다."""
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    began = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if raw else None), (time.perf_counter() - began) * 1000
    except urllib.error.HTTPError as exc:
        return exc.code, None, (time.perf_counter() - began) * 1000


def signup(base: str, email: str) -> int:
    status, body, _ = http_call(base + "/api/v1/members/signup", "POST",
                                body={"email": email, "password": APP_PASSWORD})
    if status != 201:
        raise RuntimeError(f"가입 실패 {status} {email}")
    return int(body["id"])


def login(base: str, email: str) -> str:
    status, body, _ = http_call(base + "/api/v1/auth/login", "POST",
                                body={"username": email, "password": APP_PASSWORD})
    if status != 200:
        raise RuntimeError(f"로그인 실패 {status} {email}")
    return str(body["token"])


def home(base: str, token: str, cursor: str | None = None, timeout: float = 30.0) -> tuple[int, Any, float]:
    path = HOME_PATH + ("?cursor=" + urllib.parse.quote(cursor) if cursor else "")
    return http_call(base + path, "GET", token=token, timeout=timeout)


def page_items(page: dict[str, Any]) -> list[int]:
    """홈 응답의 상품 id(행 순서, 행 안 순서)."""
    return [int(item["itemId"]) for row in page.get("rows", []) for item in row.get("items", [])]


def page_rows(page: dict[str, Any]) -> list[tuple[str, list[int], str]]:
    """홈 응답 행을 (카테고리, 상품 id, 전략)으로. GENPAGE 행의 id 는 `cat:<서버 행 이름>`이다."""
    rows: list[tuple[str, list[int], str]] = []
    for row in page.get("rows", []):
        row_id = str(row.get("id", ""))
        category = row_id[4:] if row_id.startswith("cat:") else row_id
        rows.append((category, [int(item["itemId"]) for item in row.get("items", [])], str(row.get("strategy"))))
    return rows


def all_genpage(page: dict[str, Any]) -> bool:
    rows = page.get("rows", [])
    return bool(rows) and all(str(row.get("strategy")) == "GENPAGE" for row in rows)


# ---------------------------------------------------------------------------
# 서버 `/page`
# ---------------------------------------------------------------------------

def server_hybrid_body(p1_items: Sequence[int], customer: str) -> dict[str, Any]:
    """앱 2쪽이 보내는 것과 같은 본문(같은 고객 · 같은 exclude · 같은 rows · items_per_row).

    앱은 `generateComposed` 로 `compose` · `customer` 를 싣고, 서버가 `--history-store` 면 요청
    이력을 무시하므로 `history` 는 빈 목록이면 된다. 앱 본문과 맞추려면 서버 프롬프트 로그
    (`GENPAGE2_PROMPT_LOG`)를 써도 되지만, 여기서는 계약 필드를 그대로 만든다.
    """
    return {"history": [], "exclude": [int(value) for value in p1_items],
            "rows": PAGE_ROWS, "items_per_row": ITEM_CAP, "prefix": PREFIX,
            "compose": "hybrid", "customer": str(customer)}


def server_page(model_url: str, body: dict[str, Any], timeout: float = 60.0) -> tuple[dict[str, Any], float]:
    status, payload, ms = http_call(model_url.rstrip("/") + "/page", "POST", body=body, timeout=timeout)
    if status != 200 or payload is None:
        raise RuntimeError(f"모델 서버 /page 실패 {status}")
    if int(payload.get("violations", 0)) != 0:
        raise RuntimeError(f"모델 서버 규칙 위반 {payload['violations']}건")
    return payload, ms


def _server_rows(payload: dict[str, Any]) -> list[tuple[str, list[int]]]:
    return [(str(row.get("category")), [int(value) for value in row.get("items", [])])
            for row in payload.get("rows", [])]


def _app_rows(page: dict[str, Any]) -> list[tuple[str, list[int]]]:
    return [(category, items) for category, items, _ in page_rows(page)]


def first_difference(left: Sequence[Any], right: Sequence[Any]) -> dict[str, Any] | None:
    for index in range(max(len(left), len(right))):
        a = list(left[index]) if index < len(left) else None
        b = list(right[index]) if index < len(right) else None
        if a != b:
            return {"index": index, "app": a, "server": b}
    return None


def compare_rows(app_page: dict[str, Any], server_payload: dict[str, Any],
                 min_items: int = 1) -> dict[str, Any]:
    """앱 2쪽 행 · 상품과 서버 `hybrid` 응답이 같은가(카테고리 · 상품 id 순서).

    앱의 `composeGenerated` 는 항목이 `app.home.min-items`(기본 3) 미만인 행을 버린다 — 그래서
    서버 행을 같은 기준으로 거른 뒤 비교한다. 빼면 앱이 정상적으로 버린 짧은 행이 불일치로 잡힌다.
    """
    app = _app_rows(app_page)
    server = [row for row in _server_rows(server_payload) if len(row[1]) >= int(min_items)]
    if app == server:
        return {"matched": True, "app_rows": len(app), "server_rows": len(server), "first_diff": None}
    return {"matched": False, "app_rows": len(app), "server_rows": len(server),
            "first_diff": first_difference(app, server)}


def leaked_items(p1_page: dict[str, Any], p2_page: dict[str, Any]) -> list[int]:
    """1쪽에서 보여 준 상품이 2쪽에 다시 나온 것(중복)."""
    first = set(page_items(p1_page))
    return sorted(first & set(page_items(p2_page)))


# ---------------------------------------------------------------------------
# 지표 (`/actuator/prometheus`)
# ---------------------------------------------------------------------------

_PROM_LINE = re.compile(r"^(?P<name>[a-zA-Z_:][a-zA-Z0-9_:]*)"
                        r"(?:\{(?P<tags>[^}]*)\})?\s+(?P<value>\S+)$")


def parse_prometheus(text: str) -> dict[tuple[str, tuple[tuple[str, str], ...]], float]:
    """Prometheus 텍스트를 `{(이름, 태그): 값}` 으로. 주석 · 히스토그램 버킷도 그대로 담는다."""
    out: dict[tuple[str, tuple[tuple[str, str], ...]], float] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        match = _PROM_LINE.match(line)
        if match is None:
            continue
        tags: list[tuple[str, str]] = []
        raw = match.group("tags")
        if raw:
            for part in raw.split(","):
                part = part.strip()
                if not part:
                    continue
                key, _, value = part.partition("=")
                tags.append((key.strip(), value.strip().strip('"')))
        try:
            out[(match.group("name"), tuple(sorted(tags)))] = float(match.group("value"))
        except ValueError:
            continue
    return out


def metric_value(snapshot: dict[tuple[str, tuple[tuple[str, str], ...]], float],
                 name: str, **tags: str) -> float:
    return float(snapshot.get((name, tuple(sorted(tags.items()))), 0.0))


def metric_delta(before: dict[Any, float], after: dict[Any, float]) -> dict[Any, float]:
    keys = set(before) | set(after)
    return {key: float(after.get(key, 0.0)) - float(before.get(key, 0.0)) for key in keys}


def prometheus(base: str, timeout: float = 30.0) -> dict[Any, float]:
    """`/actuator/prometheus` 텍스트를 읽는다 — JSON 이 아니라 `http_call` 을 쓰지 않는다."""
    request = urllib.request.Request(base + "/actuator/prometheus")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise RuntimeError(f"/actuator/prometheus 응답 {response.status}")
        return parse_prometheus(response.read().decode("utf-8"))


# ---------------------------------------------------------------------------
# 계정 · 매핑
# ---------------------------------------------------------------------------

def _choice(values: Sequence[str], size: int, seed: int) -> list[str]:
    taken = min(int(size), len(values))
    if taken == 0:
        raise ValueError("표집할 고객이 없다")
    rng = np.random.default_rng(int(seed))
    return sorted(str(value) for value in
                  rng.choice(np.asarray(list(values)), size=taken, replace=False).tolist())


def build_sample(data_dir: Path, scores: Path, mapped: int, unmapped: int, no_scores: int,
                 seed: int) -> dict[str, Any]:
    """계정 표본 — 매핑 계정(홀드아웃 구매) · 매핑 없음 · 점수 없는 고객.

    홀드아웃 구매 고객은 `simulate.eval_buyers`(S1 과 같은 정의), 점수 없는 고객은 검증 주
    구매 고객 중 홀드아웃 점수(`scores.json.gz`)에 없는 사람이다.
    """
    transactions = data_dir / "hm" / "normalized" / "transactions.parquet"
    scores_customers = read_scores(scores)
    final_buyers = eval_buyers(transactions, config.request_of("final"))
    validate_buyers = eval_buyers(transactions, config.request_of("validate"))
    no_score_pool = [customer for customer in validate_buyers if str(customer) not in scores_customers]
    return {"seed": int(seed),
            "mapped": _choice(final_buyers, mapped, seed),
            "no_scores": _choice(no_score_pool, no_scores, seed + 1),
            "unmapped": [i for i in range(int(unmapped))],
            "holdout_buyers": len(final_buyers), "validate_buyers": len(validate_buyers),
            "scores_customers": len(scores_customers)}


def signup_accounts(base: str, sample: dict[str, Any], stamp: int) -> dict[str, Any]:
    """표본마다 회원가입 → 로그인. 토큰은 단계마다 다시 받으므로 email · user_id 만 남긴다."""
    groups = {"mapped": sample["mapped"], "no_scores": sample["no_scores"], "unmapped": sample["unmapped"]}
    accounts: dict[str, list[dict[str, Any]]] = {"mapped": [], "no_scores": [], "unmapped": []}
    for group, customers in groups.items():
        for index, customer in enumerate(customers):
            email = f"s2-{stamp}-{group}-{index}@load.test"
            account: dict[str, Any] = {"i": index, "email": email, "user_id": signup(base, email)}
            if group != "unmapped":
                account["hm_customer_id"] = str(customer)
            accounts[group].append(account)
    return accounts


def map_accounts(accounts: dict[str, Any], connection: Any) -> dict[str, Any]:
    """`personalization_user_map` 에 매핑 계정 · 점수 없는 고객 계정을 넣고, 매핑 없음 계정은 지운다.

    재실행이 멱등이 되게 그 회원들의 행을 먼저 지운다(유니크가 user_id 에 걸려 있다, V66).
    """
    mapped = [(a["user_id"], a["hm_customer_id"]) for group in ("mapped", "no_scores") for a in accounts[group]]
    unmapped = [a["user_id"] for a in accounts["unmapped"]]
    stale = [user_id for user_id, _ in mapped] + unmapped
    with connection.cursor() as cursor:
        for index in range(0, len(stale), 500):
            chunk = stale[index:index + 500]
            cursor.execute(f"DELETE FROM personalization_user_map WHERE user_id IN ({','.join(['%s'] * len(chunk))})",
                           chunk)
        for index in range(0, len(mapped), 500):
            cursor.executemany("INSERT INTO personalization_user_map (user_id, hm_customer_id) VALUES (%s, %s)",
                               mapped[index:index + 500])
    connection.commit()
    return {"mapped": len(mapped), "unmapped": len(unmapped)}


# ---------------------------------------------------------------------------
# M1
# ---------------------------------------------------------------------------

def run_m1(base: str, model_url: str, accounts: Sequence[dict[str, Any]], limit: int,
           timeout: float, min_items: int = 3, app_timeout: str = "") -> dict[str, Any]:
    """매핑 계정마다 앱 2쪽과 서버 직접 `hybrid` 를 비교한다(M1).

    M1 은 앱=서버 대조라 타임아웃 대체가 섞이면 안 된다 — `app_timeout` 에 앱에 준 모델 타임아웃을
    적어 표에 남긴다(M2·M3·M4 는 앱 기본 300ms 로 잰다).
    """
    chosen = list(accounts)[:int(limit)] if int(limit) > 0 else list(accounts)
    records: list[dict[str, Any]] = []
    for account in chosen:
        token = login(base, account["email"])
        _, p1, _ = home(base, token, timeout=timeout)
        if not p1 or not p1.get("nextCursor"):
            records.append({"i": account["i"], "error": "1쪽에 nextCursor 가 없다"})
            continue
        _, p2, ms2 = home(base, token, cursor=p1["nextCursor"], timeout=timeout)
        composition = None
        try:
            payload, _ = server_page(model_url, server_hybrid_body(page_items(p1), account["hm_customer_id"]))
            composition = payload.get("composition")
            comparison = compare_rows(p2, payload, min_items=min_items)
        except RuntimeError as exc:
            comparison = {"matched": False, "error": str(exc)}
        records.append({"i": account["i"], "hm_customer_id": account["hm_customer_id"],
                        "app_ms": ms2, "leaked": leaked_items(p1, p2),
                        "composition": composition, **comparison})
    matched = sum(1 for r in records if r.get("matched"))
    error_count = sum(1 for r in records if "error" in r)
    leaked = [r["i"] for r in records if r.get("leaked")]
    report = {"stage": "m1", "app_url": base, "model_url": model_url, "accounts": len(records),
              "matched": matched, "mismatched": len(records) - matched - error_count,
              "errors": error_count, "leaked_accounts": len(leaked), "app_timeout": app_timeout,
              "records": records}
    report["all_matched"] = matched == len(records) and len(records) > 0
    return report


def table_m1(report: dict[str, Any]) -> str:
    lines = ["# S2 M1 — 앱 2쪽 = 서버 hybrid", "",
             f"- 앱 `{report['app_url']}` · 모델 `{report['model_url']}` · 계정 {report['accounts']}개"]
    if report.get("app_timeout"):
        lines.append(f"- 앱 모델 타임아웃 `{report['app_timeout']}` — M1 은 앱=서버 대조라 타임아웃 "
                     f"대체가 섞이지 않게 앱 기본(300ms)보다 길게 잰다(M2·M3·M4 는 앱 기본 설정)")
    lines += [
             f"- 일치 {report['matched']} · 불일치 {report['mismatched']} · 오류 {report['errors']} · "
             f"1쪽 중복 계정 {report['leaked_accounts']}", "",
             "| 계정 | 일치 | 앱행 | 서버행 | 첫 차이 | 1쪽 중복 |", "|---:|:--:|---:|---:|---|---:|"]
    for record in report["records"]:
        if "error" in record:
            lines.append(f"| {record['i']} | 오류 | - | - | `{record['error']}` | - |")
            continue
        lines.append(f"| {record['i']} | {'예' if record['matched'] else '아니오'} | {record['app_rows']} | "
                     f"{record['server_rows']} | `{json.dumps(record['first_diff'], ensure_ascii=False)}` | "
                     f"{len(record.get('leaked') or [])} |")
    lines.append("")
    lines.append(f"전체 일치: **{report['all_matched']}**")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# M2 — 닫힌 루프 2쪽 지연
# ---------------------------------------------------------------------------

def prepare_cursors(base: str, accounts: Sequence[dict[str, Any]], limit: int,
                    timeout: float) -> list[dict[str, Any]]:
    """계정마다 로그인해 1쪽 → 커서를 얻는다(2쪽 반복에 같은 커서를 쓴다, k6 와 같다)."""
    chosen = list(accounts)[:int(limit)] if int(limit) > 0 else list(accounts)
    sessions: list[dict[str, Any]] = []
    for account in chosen:
        token = login(base, account["email"])
        _, p1, _ = home(base, token, timeout=timeout)
        if p1 and p1.get("nextCursor"):
            sessions.append({"token": token, "cursor": p1["nextCursor"]})
    return sessions


def closed_loop(base: str, sessions: Sequence[dict[str, Any]], concurrency: int,
                timeout: float) -> tuple[list[dict[str, Any]], int, float]:
    """2쪽 커서를 동시성 `concurrency` 의 닫힌 루프로 한 번씩 보낸다(M2)."""
    if concurrency < 1:
        raise ValueError("concurrency 는 1 이상")
    shares = [list(sessions[index::concurrency]) for index in range(concurrency)]
    observations: list[dict[str, Any]] = []
    errors = [0]
    lock = threading.Lock()

    def worker(share: list[dict[str, Any]]) -> None:
        local: list[dict[str, Any]] = []
        bad = 0
        for session in share:
            status, body, ms = home(base, session["token"], cursor=session["cursor"], timeout=timeout)
            if status != 200 or body is None:
                bad += 1
                continue
            local.append({"latency_ms": ms, "genpage": all_genpage(body),
                          "strategies": [strategy for _, _, strategy in page_rows(body)]})
        with lock:
            observations.extend(local)
            errors[0] += bad

    began = time.perf_counter()
    threads = [threading.Thread(target=worker, args=(share,)) for share in shares]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return observations, errors[0], time.perf_counter() - began


def summarize_page_latencies(observations: Sequence[dict[str, Any]], errors: int,
                             elapsed: float) -> dict[str, Any]:
    latency = [float(value["latency_ms"]) for value in observations]
    strategies: Counter[str] = Counter(strategy for value in observations
                                       for strategy in value.get("strategies", []))
    generated = sum(1 for value in observations if value.get("genpage"))
    # 규칙 행으로 물러선 응답 — 모든 행이 GENPAGE 가 아니고, GENPAGE 행이 하나도 없는 쪽(타임아웃 ·
    # 실패 · 자리 없음). 그 밖(빈 응답 · 섞인 응답)은 other 다(S2 M2 표기).
    ruled = sum(1 for value in observations if value.get("strategies")
                and all(strategy != "GENPAGE" for strategy in value["strategies"]))
    other = len(observations) - generated - ruled
    total = len(observations)
    return {"responses": total, "errors": int(errors),
            "throughput": total / elapsed if elapsed > 0 else 0.0,
            "wall": latency_summary(latency),
            "genpage_rate": (generated / total if total else 0.0),
            "rule_rate": (ruled / total if total else 0.0),
            "other_rate": (other / total if total else 0.0),
            "strategies": dict(strategies)}


def run_m2(base: str, accounts: Sequence[dict[str, Any]], setting: str, limit: int,
           concurrency: int, timeout: float, settings_note: str = "") -> dict[str, Any]:
    sessions = prepare_cursors(base, accounts, limit, timeout)
    observations, errors, elapsed = closed_loop(base, sessions, int(concurrency), timeout)
    summary = summarize_page_latencies(observations, errors, elapsed)
    summary.update({"stage": "m2", "app_url": base, "setting": setting, "compose": COMPOSE_OF.get(setting),
                    "accounts": len(sessions), "concurrency": int(concurrency), "app_settings": settings_note})
    return summary


def table_m2(report: dict[str, Any]) -> str:
    wall = report["wall"]
    lines = [f"# S2 M2 — 앱 2쪽 지연 · {report['setting']} (compose `{report['compose']}`)", ""]
    if report.get("app_settings"):
        lines.append(f"- 앱 설정: {report['app_settings']}")
    lines += [
        f"- 앱 `{report['app_url']}` · 계정 {report['accounts']}개 · 동시성 {report['concurrency']} 닫힌 루프",
        f"- 응답 {report['responses']} · 오류 {report['errors']} · 처리량 {report['throughput']:.2f}/s",
        f"- 왕복 p50 {wall['p50_ms']:.1f} · p95 {wall['p95_ms']:.1f} · p99 {wall['p99_ms']:.1f} ms",
        f"- GENPAGE 행 비율 {report['genpage_rate']:.1%} · 규칙 행으로 물러선 비율 {report['rule_rate']:.1%} · "
        f"그 외 {report['other_rate']:.1%} (전략 `{report['strategies']}`)", ""]
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# M3 — 대체 경로
# ---------------------------------------------------------------------------

def m3_case_accounts(accounts: dict[str, Any], case: str) -> list[dict[str, Any]]:
    """M3 한 경우가 도는 계정 — 계획이 고정한다. (a) 매핑 없음 전부 (b) 점수 없음 전부
    (c) 매핑 계정 앞 `M3_MAPPED`개(모델 서버 정지 상태)."""
    if case == "a":
        return list(accounts["unmapped"])
    if case == "b":
        return list(accounts["no_scores"])
    if case == "c":
        return list(accounts["mapped"])[:M3_MAPPED]
    raise ValueError(f"case 는 a · b · c: {case}")


def run_m3(base: str, accounts: dict[str, Any], case: str, timeout: float,
           limit: int = 0) -> dict[str, Any]:
    """M3 한 경우를 그 경우의 계정 전부로 잰다 — 계정마다 상태 · 행 수 · 전략 · 왕복 ms 를 남기고,
    지표 델타는 전체 전후로 잰다. 한 건이 아니라 전부를 돌아야 "오류 0" 이 강해진다."""
    chosen = m3_case_accounts(accounts, case)
    if int(limit) > 0:
        chosen = chosen[:int(limit)]
    before = prometheus(base, timeout=timeout)
    records: list[dict[str, Any]] = []
    for account in chosen:
        token = login(base, account["email"])
        _, p1, _ = home(base, token, timeout=timeout)
        if not p1 or not p1.get("nextCursor"):
            records.append({"i": account["i"], "status": 0, "ms": None, "rows": 0,
                            "strategies": [], "all_genpage": False, "error": "1쪽에 nextCursor 가 없다"})
            continue
        status, page, ms = home(base, token, cursor=p1["nextCursor"], timeout=timeout)
        records.append({"i": account["i"], "status": status, "ms": ms,
                        "rows": len((page or {}).get("rows", [])),
                        "strategies": sorted({strategy for _, _, strategy in page_rows(page or {})}),
                        "all_genpage": all_genpage(page or {})})
    after = prometheus(base, timeout=timeout)
    delta = metric_delta(before, after)
    latencies = [float(record["ms"]) for record in records if record["ms"] is not None]
    return {"stage": "m3", "case": case, "app_url": base,
            "accounts": len(chosen), "responses": len(records),
            "errors": sum(1 for record in records if record["status"] != 200),
            "all_200": bool(records) and all(record["status"] == 200 for record in records),
            "all_rule": bool(records) and all("GENPAGE" not in record["strategies"] for record in records),
            "wall": latency_summary(latencies),
            "compose_delta": _tag_delta(delta, "recommendation_genpage_compose_total",
                                        ("composition", "fallback")),
            "page_delta": _tag_delta(delta, "recommendation_genpage_page_total", ("result",)),
            "records": records}


def _tag_delta(delta: dict[Any, float], name: str, tags: Sequence[str]) -> dict[str, float]:
    """지표 이름의 델타를 `composition/fallback` 같은 태그 키로. 공통 태그(`application` 등)는 뺀다.

    Spring Boot 이 모든 미터에 `application` 태그를 붙이므로 태그 전체를 키로 쓰면 못 찾는다.
    """
    out: dict[str, float] = {}
    for key, value in delta.items():
        if key[0] != name or not value:
            continue
        values = dict(key[1])
        out["/".join(str(values.get(tag)) for tag in tags)] = value
    return out


def m3_passed(report: dict[str, Any]) -> bool:
    """모든 응답 200 · 오류 0 이고, 폴백 지표가 계정 수만큼 맞게 셌는가(판정 1).

    (a) `none/no_mapping` 델타 = 계정 수 (b) `generate/no_scores` 델타 = 계정 수
    (c) 모든 페이지가 규칙 행(`GENPAGE` 없음)이고 `failed` · `busy` 델타 합 = 계정 수.
    """
    if not report.get("all_200") or report.get("errors"):
        return False
    count = int(report.get("accounts", 0))
    if count <= 0:
        return False
    compose = report.get("compose_delta", {})
    if report.get("case") == "a":
        return compose.get("none/no_mapping", 0.0) == count
    if report.get("case") == "b":
        return compose.get("generate/no_scores", 0.0) == count
    if any("GENPAGE" in record["strategies"] for record in report.get("records", [])):
        return False
    page = report.get("page_delta", {})
    return page.get("failed", 0.0) + page.get("busy", 0.0) == count


def table_m3(reports: Sequence[dict[str, Any]]) -> str:
    """경우마다 요약 한 줄 + 계정별 표."""
    labels = {"a": "매핑 없음 → no_mapping", "b": "점수 없음 → no_scores", "c": "모델 정지 → 규칙 행"}
    lines = ["# S2 M3 — 대체 경로", ""]
    for report in reports:
        wall = report["wall"]
        lines += [f"## 경우 {report['case']} — {labels[report['case']]}", "",
                  f"- 계정 {report['accounts']}개 · 응답 {report['responses']} · 오류 {report['errors']} · "
                  f"상태 200 {'전부' if report['all_200'] else '아님'}",
                  f"- 왕복 p50 {wall['p50_ms']:.1f} · p95 {wall['p95_ms']:.1f} ms",
                  f"- 지표 compose `{json.dumps(report['compose_delta'], ensure_ascii=False)}` · "
                  f"page `{json.dumps(report['page_delta'], ensure_ascii=False)}`",
                  f"- 판정: **{'통과' if m3_passed(report) else '실패'}**", "",
                  "| 계정 | 상태 | 행 | 전략 | 왕복 ms |", "|---:|---:|---:|---|---:|"]
        for record in report["records"]:
            ms = "-" if record["ms"] is None else f"{record['ms']:.1f}"
            lines.append(f"| {record['i']} | {record['status']} | {record['rows']} | "
                         f"`{record['strategies']}` | {ms} |")
        lines.append("")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# M4 — OFF 회귀
# ---------------------------------------------------------------------------

def capture_rows(base: str, accounts: Sequence[dict[str, Any]], limit: int,
                 timeout: float) -> dict[str, Any]:
    """계정마다 1쪽 → 2쪽 행을 남긴다(설정 · 앱이 달라도 같은 회원 · 같은 커서)."""
    chosen = list(accounts)[:int(limit)] if int(limit) > 0 else list(accounts)
    captures: list[dict[str, Any]] = []
    for account in chosen:
        token = login(base, account["email"])
        _, p1, _ = home(base, token, timeout=timeout)
        if not p1 or not p1.get("nextCursor"):
            captures.append({"email": account["email"], "rows": None})
            continue
        _, p2, _ = home(base, token, cursor=p1["nextCursor"], timeout=timeout)
        captures.append({"email": account["email"],
                         "p1": page_items(p1),
                         "rows": [{"category": category, "items": items, "strategy": strategy}
                                  for category, items, strategy in page_rows(p2)]})
    return {"accounts": captures}


def compare_captures(main: dict[str, Any], branch: dict[str, Any]) -> dict[str, Any]:
    by_email = {capture["email"]: capture for capture in main["accounts"]}
    right = {capture["email"]: capture for capture in branch["accounts"]}
    same = compared = 0
    differences: list[dict[str, Any]] = []
    for email, left in by_email.items():
        other = right.get(email)
        if other is None:
            continue
        compared += 1
        if left.get("rows") == other.get("rows") and left.get("p1") == other.get("p1"):
            same += 1
        elif len(differences) < 5:
            differences.append({"email": email, "main": left.get("rows"), "branch": other.get("rows")})
    return {"stage": "m4", "accounts": compared, "same": same, "all_same": compared > 0 and same == compared,
            "differences": differences}


def table_m4(report: dict[str, Any]) -> str:
    lines = ["# S2 M4 — OFF 회귀 (main ↔ 이 브랜치)", "",
             f"- 계정 {report['accounts']}개 · 같음 {report['same']}개", "",
             f"전체 같음: **{report['all_same']}**", ""]
    for difference in report["differences"]:
        lines.append(f"- `{difference['email']}`: main `{json.dumps(difference['main'], ensure_ascii=False)}`")
        lines.append(f"  브랜치 `{json.dumps(difference['branch'], ensure_ascii=False)}`")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# 파일 · CLI
# ---------------------------------------------------------------------------

def write_report(out: Path, name: str, report: dict[str, Any], table: str) -> None:
    out.mkdir(parents=True, exist_ok=True)
    (out / f"{name}.json").write_text(json.dumps(report, ensure_ascii=False, indent=2, default=float) + "\n",
                                      encoding="utf-8")
    (out / f"{name}.md").write_text(table, encoding="utf-8")
    print(table, end="")


def load_json(out: Path, name: str) -> Any:
    return json.loads((out / name).read_text(encoding="utf-8"))


def db_connect(args: argparse.Namespace) -> Any:
    import pymysql
    return pymysql.connect(host=args.db_host, port=args.db_port, user=args.db_user,
                           password=args.db_password, database=args.db_name, autocommit=False)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "signup", "map", "m1", "m2", "m3",
                                            "m4-capture", "m4-report", "report"))
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--app-url", default="http://localhost:18090")
    parser.add_argument("--model-url", default="http://127.0.0.1:18868")
    parser.add_argument("--data-dir", type=Path)
    parser.add_argument("--scores", type=Path)
    parser.add_argument("--mapped", type=int, default=200)
    parser.add_argument("--unmapped", type=int, default=20)
    parser.add_argument("--no-scores", type=int, default=20)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--limit", type=int, default=0, help="0(기본)이면 전원")
    parser.add_argument("--min-items", type=int, default=3,
                        help="앱이 2쪽에서 버리는 행의 최소 항목 수(app.home.min-items). M1 비교가 서버 행을 이 값으로 거른다")
    parser.add_argument("--setting", choices=SETTINGS, default="HYBRID")
    parser.add_argument("--app-timeout", default="", help="M1 표에 적을 앱 모델 타임아웃(대조용 긴 값)")
    parser.add_argument("--app-settings", default="", help="M2 표에 적을 앱 설정 설명")
    parser.add_argument("--case", choices=("a", "b", "c"), default="a")
    parser.add_argument("--label", default="branch")
    parser.add_argument("--concurrency", type=int, default=1)
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--db-host", default="127.0.0.1")
    parser.add_argument("--db-port", type=int, default=3306)
    parser.add_argument("--db-user", default="root")
    parser.add_argument("--db-password", default="root")
    parser.add_argument("--db-name", default="becommerce")
    args = parser.parse_args(argv)

    out = Path(args.out)
    data_dir = args.data_dir or config.data_dir()
    if args.command == "prepare":
        if args.scores is None:
            raise SystemExit("prepare 에는 --scores 가 필요하다")
        sample = build_sample(data_dir, args.scores, args.mapped, args.unmapped, args.no_scores, args.seed)
        out.mkdir(parents=True, exist_ok=True)
        (out / "sample.json").write_text(json.dumps(sample, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({key: (len(value) if isinstance(value, list) else value)
                          for key, value in sample.items()}, ensure_ascii=False, indent=2))
    elif args.command == "signup":
        if (out / "accounts.json").exists():
            print("accounts.json 있음 — 건너뜀")
        else:
            sample = load_json(out, "sample.json")
            accounts = signup_accounts(args.app_url, sample, int(time.time()))
            (out / "accounts.json").write_text(json.dumps(accounts, ensure_ascii=False, indent=2) + "\n",
                                               encoding="utf-8")
            print({group: len(value) for group, value in accounts.items()})
    elif args.command == "map":
        accounts = load_json(out, "accounts.json")
        connection = db_connect(args)
        try:
            print(map_accounts(accounts, connection))
        finally:
            connection.close()
    elif args.command == "m1":
        accounts = load_json(out, "accounts.json")["mapped"]
        report = run_m1(args.app_url, args.model_url, accounts, args.limit, args.timeout,
                        args.min_items, args.app_timeout)
        write_report(out, "m1", report, table_m1(report))
    elif args.command == "m2":
        accounts = load_json(out, "accounts.json")["mapped"]
        report = run_m2(args.app_url, accounts, args.setting, args.limit, args.concurrency, args.timeout,
                        args.app_settings)
        write_report(out, f"m2-{args.setting.lower()}", report, table_m2(report))
    elif args.command == "m3":
        accounts = load_json(out, "accounts.json")
        report = run_m3(args.app_url, accounts, args.case, args.timeout, args.limit)
        write_report(out, f"m3-{args.case}", report, table_m3([report]))
    elif args.command == "m4-capture":
        accounts = load_json(out, "accounts.json")["mapped"][:M4_ACCOUNTS]
        capture = capture_rows(args.app_url, accounts, args.limit, args.timeout)
        (out / f"m4-{args.label}.json").write_text(json.dumps(capture, ensure_ascii=False, indent=2) + "\n",
                                                  encoding="utf-8")
        print(f"m4-{args.label}.json — 계정 {len(capture['accounts'])}개")
    elif args.command == "m4-report":
        report = compare_captures(load_json(out, "m4-main.json"), load_json(out, "m4-branch.json"))
        write_report(out, "m4", report, table_m4(report))
    else:  # report
        print(f"산출물: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
