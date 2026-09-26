"""홈 커서가 몇 쪽에서 상한을 넘는가, 그 위에서는 무엇이 먼저 막는가(#348, ADR-052). 판정 기준은 이슈에 측정 전에 적었다.

    python3 tools/home_cursor_growth.py BASE IDS.txt OUT.json

1) 가입 · 로그인 뒤 1쪽부터 nextCursor 가 없어질 때까지 넘기며 쪽마다 커서 길이 · 실린 상품 수 · 행 수를 적는다
2) 2쪽부터의 쪽당 증가(상품 수 · 행 수)를 그대로 이어 붙여 40쪽까지 커서 길이를 계산한다. 상품 id 는 IDS.txt(카탈로그의 실제 id,
   한 줄에 하나)에서 아직 안 실은 것을 차례로 쓴다. 인코딩은 HomeCursor.encode() 와 같다(36진수 · 쉼표 · Base64 URL, 패딩 없음)
3) 길이가 다른 커서로 다음 쪽을 실제로 불러 응답 코드를 적는다(앱 상한 4,096자 · 톰캣 요청 헤더 한도)
"""
import base64
import json
import sys
import time
import urllib.error
import urllib.request

LIMIT = 4096


def call(base, method, path, token=None, body=None):
    req = urllib.request.Request(base + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, None


def b36(n):
    digits = "0123456789abcdefghijklmnopqrstuvwxyz"
    out = ""
    while True:
        n, r = divmod(n, 36)
        out = digits[r] + out
        if n == 0:
            return out


def encode(page, ids, rows):
    raw = "v1;%d;%s;%s" % (page, ",".join(b36(i) for i in sorted(ids)), ",".join(sorted(rows)))
    return base64.urlsafe_b64encode(raw.encode()).decode().rstrip("=")


def decode(cursor):
    raw = base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)).decode()
    _, page, ids, rows = raw.split(";")
    return int(page), [int(x, 36) for x in ids.split(",") if x], [r for r in rows.split(",") if r]


def main(base, ids_path, out):
    base = base.rstrip("/")
    email = f"cursor-{int(time.time())}@load.test"
    call(base, "POST", "/api/v1/members/signup", body={"email": email, "password": "cursor-only-1234"})
    _, login = call(base, "POST", "/api/v1/auth/login", body={"username": email, "password": "cursor-only-1234"})
    token = login["token"]
    pages, cursor, page = [], None, 1
    while True:
        path = "/api/v1/personalization/homepage" + (f"?cursor={cursor}" if cursor else "")
        status, body = call(base, "GET", path, token)
        nxt = (body or {}).get("nextCursor")
        row = {"page": page, "status": status}
        if nxt:
            p, ids, rows = decode(nxt)
            row.update({"nextCursorLength": len(nxt), "idsCarried": len(ids), "rowsCarried": len(rows),
                        "reencodedMatches": encode(p, ids, rows) == nxt})
        pages.append(row)
        if not nxt or page > 20:
            break
        cursor, page = nxt, page + 1
    print(json.dumps(pages, ensure_ascii=False))

    # 2쪽부터의 쪽당 증가를 이어 붙인다
    carried = [p for p in pages if "idsCarried" in p]
    last_ids, last_rows = decode(cursor)[1:] if cursor else ([], [])
    per_ids = (carried[-1]["idsCarried"] - carried[0]["idsCarried"]) / max(len(carried) - 1, 1)
    per_rows = (carried[-1]["rowsCarried"] - carried[0]["rowsCarried"]) / max(len(carried) - 1, 1)
    pool = [int(x) for x in open(ids_path).read().split() if x.strip()]
    pool = [i for i in pool if i not in set(last_ids)]
    ids, rows = list(last_ids), list(last_rows)
    projected, cross = [], None
    for pg in range(carried[-1]["page"] + 1, 41):
        need_ids = round(carried[0]["idsCarried"] + per_ids * (pg - carried[0]["page"])) - len(ids)
        ids += pool[:need_ids]
        pool = pool[need_ids:]
        need_rows = round(carried[0]["rowsCarried"] + per_rows * (pg - carried[0]["page"])) - len(rows)
        rows += [f"cat:mid{pg:03d}{k}" for k in range(need_rows)]
        length = len(encode(pg + 1, ids, rows))
        projected.append({"afterPage": pg, "cursorLength": length, "ids": len(ids), "rows": len(rows)})
        if cross is None and length > LIMIT:
            cross = pg
    print("쪽당 증가: 상품 %.1f · 행 %.1f, 4,096자를 넘는 쪽: %s" % (per_ids, per_rows, cross))

    # 긴 커서로 실제 호출
    probes = []
    for target in (4096, 4097, 8000, 8100, 9000):
        pad_ids, pg = list(last_ids), 3
        k = 0
        while len(encode(pg, pad_ids, last_rows)) < target:
            pad_ids.append(900_000_000 + k)
            k += 1
        if target == LIMIT and len(encode(pg, pad_ids, last_rows)) > LIMIT:
            pad_ids.pop()                                   # 상한 안에서 가장 긴 올바른 커서
        c = encode(pg, pad_ids, last_rows)                  # 자르지 않는다(잘린 Base64 는 다른 이유로 400 이다)
        status, _ = call(base, "GET", f"/api/v1/personalization/homepage?cursor={c}", token)
        probes.append({"cursorLength": len(c), "status": status})
    print(json.dumps(probes))
    json.dump({"pages": pages, "perPageIds": per_ids, "perPageRows": per_rows, "firstPageOverLimit": cross,
               "projected": projected, "probes": probes}, open(out, "w"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])
