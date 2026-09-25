#!/usr/bin/env python3
"""X3(#317) — 홈에 부하를 주면서 **응답 시점** 품절 노출률을 잰다.

    # 품절 후보를 만드는 워밍업 — 노출 빈도를 세어 상위 N 개를 파일로 남긴다(품절은 주입하지 않는다)
    python3 tools/x3_stock_race.py --name candidates --mode POST --duration 60 --warmup 8 \
        --candidates-out OUT/sellout-candidates.txt --candidates-limit 400 --out OUT/candidates.json

    # 방식별 측정 — 워밍업이 끝나면 품절 주입기를 직접 띄우고, 측정 뒤 멈춘다
    python3 tools/x3_stock_race.py --name POST-r3 --mode POST \
        --candidates OUT/sellout-candidates.txt \
        --sellout-rate 3 --sellout-products-file OUT/sellout-candidates.txt \
        --sellout-state raw/POST-r3/sellout-state.json \
        --users 8 --rate 20 --duration 60 --out raw/POST-r3/race.json

무엇을 재나
  응답 시점 품절 노출률  응답을 **받은 즉시** 응답에 담긴 상품의 재고를 DB 에서 읽어, 그 순간
                        quantity<=0 인 상품의 비율. 홈의 stats 는 "조립할 때 걸렀다"고 말하지만 그것이
                        **응답 시점의 사실**인지는 별개다 — 그 사이에 품절된 것이 이 값으로 드러난다.
                        재고 행이 없는 상품은 품절로 세지 않는다(홈의 화면용 규칙과 같은 방향 — fail-open).
  품절 후보 적중률        응답에 담긴 상품 중 품절 후보(--candidates)에 든 비율. 이것이 "품절 주입이 노출
                        상품에 닿았는가"다 — `NONE` · R>0 에서 0 이면 런이 무효(valid=false).
  홈 p95                1쪽 · 2쪽(커서) 요청의 지연(가장 가까운 위 순위 값)
  규칙 행 폴백 비율       2쪽에서 모델이 행을 못 만들어 규칙 행으로 물러선 비율(source != GENPAGE)
  재고 조회 · 뺀 수      응답의 stats(stockLookups · stockLookupMs · stockRemoved) 평균 — 방식별 대가

5번(품절 주입 시점): 워밍업이 끝난 **뒤** 측정과 함께 `x3_sellout.py` 를 직접 띄운다. 워밍업 전에 띄우면
높은 속도에서 측정이 시작하기도 전에 후보가 다 팔린다. 측정이 끝나면 SIGTERM 으로 멈추고(그때까지를 state 에
남긴다) state 를 읽어 60초 동안 팔린 비율을 summary 에 적는다. 복원은 하네스(쉘)가 맡는다.

--rate 는 초당 **반복**(1쪽 + 2쪽) 수다. 워커마다 간격을 workers/rate 로 잡아 전체가 rate 에 가깝게 돈다.
로그인 · 사용자는 tools/virtual_users/driver.py 의 방식을 따른다(회원 가입 → 로그인 → 토큰 재사용).
가입 · 로그인은 레이트리밋(5/s)에 걸리므로 **시작할 때 한 번만** 한다.
"""
import argparse
import json
import math
import pathlib
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

PASSWORD = "x3-load-only-1234"


def call(base, method, path, token=None, body=None, timeout=30):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(base + path, data=json.dumps(body).encode() if body is not None else None,
                                method=method, headers=headers)
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None), (time.time() - started) * 1000
    except urllib.error.HTTPError as e:
        return e.code, None, (time.time() - started) * 1000
    except Exception:
        return 0, None, (time.time() - started) * 1000


def db(args):
    import pymysql
    return pymysql.connect(host=args.host, port=args.port, user=args.user,
                           password=args.password, database=args.database, autocommit=True)


def load_candidates(path):
    """품절 후보 id 집합. 파일이 없으면 빈 집합(적중률이 0 으로 남는다)."""
    if not path:
        return set()
    text = pathlib.Path(path).read_text(encoding="utf-8")
    return {int(line.strip()) for line in text.splitlines() if line.strip()}


def signup_users(args):
    stamp = int(time.time())
    users = []
    for i in range(args.users):
        email = f"x3-{args.name}-{stamp}-{i}@load.test"
        status, body, _ = call(args.app, "POST", "/api/v1/members/signup", body={"email": email, "password": PASSWORD})
        if status != 201 or not body:
            raise SystemExit(f"가입 실패 {status} {email}")
        status, login, _ = call(args.app, "POST", "/api/v1/auth/login", body={"username": email, "password": PASSWORD})
        if status != 200 or not login:
            raise SystemExit(f"로그인 실패 {status} {email}")
        users.append({"email": email, "member": body["id"], "token": login["token"]})
        time.sleep(0.25)
    return users


def items_of(body):
    return [int(it["itemId"]) for row in body.get("rows", []) for it in row.get("items", [])]


class Recorder:
    def __init__(self):
        self.lock = threading.Lock()
        self.rows = []

    def add(self, row):
        with self.lock:
            self.rows.append(row)


class ExposureCounter:
    """응답에 담긴 상품의 **노출 빈도**를 센다 — 품절 후보를 고르는 데 쓴다(1번 요구)."""

    def __init__(self):
        self.lock = threading.Lock()
        self.counts = {}

    def add(self, ids):
        with self.lock:
            for pid in ids:
                self.counts[pid] = self.counts.get(pid, 0) + 1

    def top(self, limit):
        ranked = sorted(self.counts.items(), key=lambda kv: (-kv[1], kv[0]))
        return [pid for pid, _ in ranked[:limit]]

    def distinct(self):
        return len(self.counts)


def read_sold_out(conn, ids):
    """응답 직후에 읽는다 — 그 순간 quantity<=0 인 것만. 재고 행이 없으면 품절로 세지 않는다."""
    if not ids:
        return set()
    with conn.cursor() as cur:
        cur.execute("SELECT product_id FROM stock WHERE quantity <= 0 AND product_id IN "
                    f"({','.join(['%s'] * len(ids))})", ids)
        return {row[0] for row in cur.fetchall()}


def observe(conn, body, page, latency, rec, counter=None, candidates=None):
    ids = items_of(body)
    stats = body.get("stats") or {}
    if counter is not None:
        counter.add(ids)
    hit = sum(1 for pid in ids if candidates and pid in candidates)
    rec.add({"page": page, "latency": latency, "items": len(ids),
             "exposed": len(read_sold_out(conn, ids)), "candidates": hit, "source": body.get("source"),
             "stockLookups": stats.get("stockLookups", 0), "stockLookupMs": stats.get("stockLookupMs", 0),
             "stockRemoved": stats.get("stockRemoved", 0)})


def worker(args, users, index, deadline, rec, counter=None):
    conn = db(args)          # pymysql 커넥션은 스레드 안전하지 않다 — 워커마다 하나
    interval = args.workers / args.rate
    i = index
    try:
        while time.time() < deadline:
            user = users[i % len(users)]
            i += 1
            loop_started = time.time()
            status, body, latency = call(args.app, "GET", "/api/v1/personalization/homepage", user["token"])
            if status != 200 or not body:
                rec.add({"error": status})
            else:
                observe(conn, body, 1, latency, rec, counter, args.candidate_set)
                cursor = body.get("nextCursor")
                if cursor:
                    status2, body2, latency2 = call(
                        args.app, "GET", "/api/v1/personalization/homepage?cursor=" + cursor, user["token"])
                    if status2 == 200 and body2:
                        observe(conn, body2, 2, latency2, rec, counter, args.candidate_set)
                    else:
                        rec.add({"error": status2})
            slack = interval - (time.time() - loop_started)
            if slack > 0:
                time.sleep(slack)
    finally:
        conn.close()


def start_sellout(args):
    """워밍업이 끝난 뒤 측정과 함께 품절 주입을 시작한다(5번 요구). (proc, log) 또는 None."""
    if args.sellout_rate <= 0 or not args.sellout_products_file:
        return None
    state = pathlib.Path(args.sellout_state)
    log_path = state.with_name(state.name + ".log")
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log = open(log_path, "w", encoding="utf-8")
    proc = subprocess.Popen(
        [sys.executable, args.sellout_script, "--products-file", args.sellout_products_file,
         "--rate", str(args.sellout_rate), "--state", str(state)],
        stdout=log, stderr=subprocess.STDOUT)
    for _ in range(200):          # state 가 생겨야 복원할 수 있다 — 측정이 짧아도 보장한다
        if state.exists() and state.stat().st_size > 0:
            break
        if proc.poll() is not None:
            break
        time.sleep(0.05)
    print(f"품절 주입 시작 rate={args.sellout_rate}/s · state={state}", flush=True)
    return proc, log


def stop_sellout(handle):
    if not handle:
        return
    proc, log = handle
    if proc.poll() is None:
        proc.terminate()          # SIGTERM — 주입기가 state 를 남기고 끝난다
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)
    log.close()


def read_sellout_progress(args):
    """측정이 끝난 뒤 state 에서 실제로 품절된 수를 읽는다 — '60초 동안 얼마나 팔렸나'(3·6번 요구)."""
    candidate_total = len(args.candidate_set or ())
    path = pathlib.Path(args.sellout_state) if args.sellout_state else None
    if not path or not path.exists():
        return 0, candidate_total
    try:
        state = json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, OSError):
        return 0, candidate_total
    sold = state.get("sold_out")
    if sold is None:
        sold = sum(1 for e in state.get("events", []) if e.get("changed"))
    return int(sold), int(state.get("products_total") or candidate_total)


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    k = max(0, min(len(ordered) - 1, math.ceil(p / 100.0 * len(ordered)) - 1))
    return ordered[k]


def mean(values):
    values = [v for v in values if v is not None]
    return sum(values) / len(values) if values else None


def summarize(args, rec, started):
    ok = [r for r in rec.rows if "error" not in r]
    errors = [r for r in rec.rows if "error" in r]
    latencies = [r["latency"] for r in ok]
    items = sum(r["items"] for r in ok)
    exposed = sum(r["exposed"] for r in ok)
    hits = sum(r.get("candidates", 0) for r in ok)
    page2 = [r for r in ok if r["page"] == 2]
    genpage = [r for r in page2 if r["source"] == "GENPAGE"]
    hit_rate = (hits / items) if items else None
    sold_out, candidates_total = read_sellout_progress(args)
    return {
        "name": args.name, "mode": args.mode, "sellout_rate": args.sellout_rate,
        "app": args.app, "users": args.users, "rate": args.rate, "duration": args.duration,
        "requests": len(ok), "errors": len(errors),
        "exposure_rate": (exposed / items) if items else None,
        "exposed_items": exposed, "items": items,
        "candidate_hit_rate": hit_rate, "candidate_hits": hits, "candidates": len(args.candidate_set or ()),
        "candidates_total": candidates_total,
        "sold_out": sold_out,
        "sold_out_fraction": (sold_out / candidates_total) if candidates_total else None,
        "valid": not (args.mode == "NONE" and args.sellout_rate > 0 and (hit_rate or 0) == 0),
        "p50_ms": percentile(latencies, 50), "p95_ms": percentile(latencies, 95),
        "p95_page1_ms": percentile([r["latency"] for r in ok if r["page"] == 1], 95),
        "p95_page2_ms": percentile([r["latency"] for r in page2], 95),
        "rule_fallback_rate": (1 - len(genpage) / len(page2)) if page2 else None,
        "page2": len(page2), "page2_genpage": len(genpage),
        "stock_lookups": mean([r["stockLookups"] for r in ok]),
        "stock_lookup_ms": mean([r["stockLookupMs"] for r in ok]),
        "stock_removed": mean([r["stockRemoved"] for r in ok]),
        "wall_ms": (time.time() - started) * 1000,
    }


def run_load(args, users, rec, seconds, counter=None):
    deadline = time.time() + seconds
    threads = [threading.Thread(target=worker, args=(args, users, index, deadline, rec, counter), daemon=True)
               for index in range(args.workers)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()


def main():
    p = argparse.ArgumentParser(description="X3(#317) 응답 시점 품절 노출률 측정기")
    p.add_argument("--app", default="http://localhost:18080")
    p.add_argument("--name", required=True, help="런 이름(예: POST-r3)")
    p.add_argument("--mode", default=None, help="재고 확인 방식(NONE/POST/PRE/POST_FINAL) — 없으면 이름 앞부분")
    p.add_argument("--sellout-rate", type=float, default=0, help="워밍업 뒤 띄울 품절 주입 속도 R (/s). 0 이면 안 띄운다")
    p.add_argument("--candidates", default=None, help="품절 후보 id 파일(한 줄에 하나) — 적중률 · 무효 판정용")
    p.add_argument("--candidates-out", default=None, help="측정 뒤 노출 빈도 상위 N 개를 이 파일에 쓴다(품절 후보 만들기)")
    p.add_argument("--candidates-limit", type=int, default=400, help="--candidates-out 으로 남길 상위 상품 수")
    p.add_argument("--sellout-products-file", default=None, help="워밍업 뒤 띄울 품절 주입기의 상품 목록 파일")
    p.add_argument("--sellout-state", default=None, help="품절 주입기의 state 파일(원래 수량 · 기록)")
    p.add_argument("--sellout-script", default="tools/x3_sellout.py", help="품절 주입기 경로")
    p.add_argument("--users", type=int, default=8)
    p.add_argument("--rate", type=float, default=20, help="초당 홈 반복 수(전체, 1쪽+2쪽)")
    p.add_argument("--duration", type=int, default=60)
    p.add_argument("--warmup", type=int, default=8, help="부하 전 워밍업 초(측정에서 뺀다)")
    p.add_argument("--workers", type=int, default=4)
    p.add_argument("--out", default=None, help="결과 JSON 경로(기본 race-<name>.json)")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=3306)
    p.add_argument("--user", default="root")
    p.add_argument("--password", default="root")
    p.add_argument("--database", default="becommerce")
    args = p.parse_args()
    if args.mode is None:
        args.mode = args.name.split("-r")[0]
    if args.rate <= 0:
        raise SystemExit("--rate 는 0 보다 커야 한다")
    if args.workers < 1:
        raise SystemExit("--workers 는 1 이상이어야 한다")
    args.candidate_set = load_candidates(args.candidates)

    users = signup_users(args)
    print(f"사용자 {len(users)}명 · {args.app} · 홈 {args.rate}/s · {args.duration}초 · 모드 {args.mode}")

    if args.warmup:
        warm = Recorder()
        run_load(args, users, warm, args.warmup)
        print(f"워밍업 {args.warmup}초 — {len([r for r in warm.rows if 'error' not in r])}요청(측정에서 뺀다)")

    counter = ExposureCounter() if args.candidates_out else None
    sellout = start_sellout(args)          # 워밍업이 끝난 뒤, 측정과 함께
    rec = Recorder()
    started = time.time()
    try:
        run_load(args, users, rec, args.duration, counter)
    finally:
        stop_sellout(sellout)

    if args.candidates_out and counter is not None:
        top = counter.top(args.candidates_limit)
        path = pathlib.Path(args.candidates_out)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("".join(f"{pid}\n" for pid in top), encoding="utf-8")
        print(f"품절 후보 {len(top)}개를 {path} 에 남겼다(노출 상품 {counter.distinct()}종 중 빈도 상위)")

    summary = summarize(args, rec, started)
    out = pathlib.Path(args.out or f"race-{args.name}.json")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"summary": summary, "rows": rec.rows}, ensure_ascii=False), encoding="utf-8")
    s = summary
    print(f"응답 시점 품절 노출률 {(s['exposure_rate'] or 0) * 100:.2f}% ({s['exposed_items']}/{s['items']}) · "
          f"품절 후보 적중률 {(s['candidate_hit_rate'] or 0) * 100:.2f}% ({s['candidate_hits']}/{s['items']}) · "
          f"60초 품절 {(s['sold_out_fraction'] or 0) * 100:.2f}% ({s['sold_out']}/{s['candidates_total']})")
    print(f"홈 p95 {s['p95_ms'] or 0:.1f}ms · 규칙 행 폴백 {(s['rule_fallback_rate'] or 0) * 100:.2f}% · "
          f"조회 {s['stock_lookups'] or 0:.2f}회/{s['stock_lookup_ms'] or 0:.2f}ms · "
          f"뺀 항목 {s['stock_removed'] or 0:.2f}")
    if not s["valid"]:
        print("!! 무효 런 — 품절 후보가 응답에 닿지 않았다(NONE · R>0 · 적중률 0)")
    print(f"원자료: {out} (요청 {s['requests']} · 오류 {s['errors']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
