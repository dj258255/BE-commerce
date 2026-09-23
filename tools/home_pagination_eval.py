"""홈 페이지네이션 실측(#237). 판정 기준은 이슈에 측정 전에 적었다.

    python3 tools/home_pagination_eval.py BASE USERS WAIT_MS OUT.json

사용자마다:
  1) 가입·로그인 → 1쪽을 받는다(활동 없음)
  2) 기본 순서에서 가장 뒤에 올 대분류(인기 표에 가장 늦게 나오는 것)의 상품 하나를 본다
  3) WAIT_MS 만큼 기다린 뒤 커서로 2쪽을 받는다 → 첫 행이 그 대분류인가(세션 반영)
  4) 같은 2쪽을 "보여 준 상품을 뺀 커서"로도 받아 1쪽과 겹치는 상품 수를 센다(커서가 없었다면)
  5) nextCursor 가 없어질 때까지 넘기며 쪽 수·쪽별 지연·커서 길이를 적는다
"""
import base64
import json
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE, USERS, WAIT_MS, OUT = sys.argv[1].rstrip("/"), int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]


def call(method, path, token=None, body=None):
    req = urllib.request.Request(BASE + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json",
                                          **({"Authorization": f"Bearer {token}"} if token else {})})
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None), (time.perf_counter() - started) * 1000
    except urllib.error.HTTPError as e:
        return e.code, None, (time.perf_counter() - started) * 1000


def category_of_products():
    """대분류마다 상품 하나(재고 있는 것). 활동에 쓴다."""
    out = subprocess.run(["docker", "exec", "pay-mysql-1", "mysql", "-N", "-ubecommerce", "-pbecommerce", "becommerce", "-e",
                          "select p.category_code, min(p.product_id) from products p where p.category_code is not null group by p.category_code"],
                         capture_output=True, text=True, check=True).stdout
    return dict(line.split("\t") for line in out.strip().split("\n") if line)


def no_dedupe_cursor(cursor):
    """같은 쪽·같은 행 기록이되 보여 준 상품을 비운 커서 — '커서가 상품을 안 들고 다녔다면'의 대조군."""
    raw = base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)).decode()
    v, page, _ids, rows = raw.split(";")
    return base64.urlsafe_b64encode(f"{v};{page};;{rows}".encode()).decode().rstrip("=")


def ids(page):
    return [int(i["itemId"]) for r in page["rows"] for i in r["items"]]


def main():
    products = category_of_products()
    runs = []
    stamp = int(time.time())
    for u in range(USERS):
        email = f"page-{stamp}-{u}@load.test"
        call("POST", "/api/v1/members/signup", body={"email": email, "password": "page-load-only-1234"})
        _, login, _ = call("POST", "/api/v1/auth/login", body={"username": email, "password": "page-load-only-1234"})
        token = login["token"]

        _, p1, ms1 = call("GET", "/api/v1/personalization/homepage", token)
        # 세션 없이 2쪽이 고를 순서 — 가장 뒤의 대분류를 고른다(세션이 순서를 바꿨는지 가장 잘 보인다)
        _, default_p2, _ = call("GET", "/api/v1/personalization/homepage?cursor=" + p1["nextCursor"], token)
        default_order = [r["id"] for r in default_p2["rows"]]
        candidates = [c for c in products if f"cat:{c}" not in default_order[:1]]
        target = sorted(candidates)[u % len(candidates)]
        call("POST", "/api/v1/personalization/activity", token,
             {"itemId": int(products[target]), "type": "VIEW", "seq": 1})
        time.sleep(WAIT_MS / 1000)

        _, p2, ms2 = call("GET", "/api/v1/personalization/homepage?cursor=" + p1["nextCursor"], token)
        _, p2_nodedupe, _ = call("GET", "/api/v1/personalization/homepage?cursor=" + no_dedupe_cursor(p1["nextCursor"]), token)

        pages, lat, cur_len, page = [p1, p2], [ms1, ms2], [len(p1["nextCursor"])], p2
        while page.get("nextCursor") and len(pages) < 20:
            cur_len.append(len(page["nextCursor"]))
            _, page, ms = call("GET", "/api/v1/personalization/homepage?cursor=" + page["nextCursor"], token)
            pages.append(page)
            lat.append(ms)
        all_ids = [i for p in pages for i in ids(p)]
        runs.append({
            "target": f"cat:{target}",
            "p2_first_row": p2["rows"][0]["id"] if p2["rows"] else None,
            "p2_first_strategy": p2["rows"][0]["strategy"] if p2["rows"] else None,
            "session_hit": bool(p2["rows"]) and p2["rows"][0]["id"] == f"cat:{target}",
            "dup_with_cursor": len(set(ids(p1)) & set(ids(p2))),
            "dup_without_cursor": len(set(ids(p1)) & set(ids(p2_nodedupe))),
            "dup_across_all_pages": len(all_ids) - len(set(all_ids)),
            "pages": len(pages), "items": len(all_ids),
            "latency_ms": lat, "cursor_len": cur_len,
        })
    lat1 = [r["latency_ms"][0] for r in runs]
    latn = [x for r in runs for x in r["latency_ms"][1:]]
    summary = {
        "users": USERS, "wait_ms": WAIT_MS,
        "session_hit_rate": sum(r["session_hit"] for r in runs) / len(runs),
        "dup_with_cursor_total": sum(r["dup_with_cursor"] for r in runs),
        "dup_without_cursor_mean": statistics.mean(r["dup_without_cursor"] for r in runs),
        "dup_across_all_pages_total": sum(r["dup_across_all_pages"] for r in runs),
        "pages_mean": statistics.mean(r["pages"] for r in runs),
        "items_mean": statistics.mean(r["items"] for r in runs),
        "p1_p95_ms": sorted(lat1)[int(0.95 * (len(lat1) - 1))],
        "next_p95_ms": sorted(latn)[int(0.95 * (len(latn) - 1))] if latn else None,
        "cursor_len_by_page": [max(r["cursor_len"][i] for r in runs if len(r["cursor_len"]) > i)
                               for i in range(max(len(r["cursor_len"]) for r in runs))],
    }
    json.dump({"summary": summary, "runs": runs}, open(OUT, "w"), ensure_ascii=False, indent=1)
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
