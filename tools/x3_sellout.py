#!/usr/bin/env python3
"""X3(#317) — 노출 후보 상품을 초당 R 개씩 품절시킨다("생성하는 동안 누군가 마지막 재고를 샀다").

    python3 tools/x3_sellout.py --products 101,102,103 --rate 5 [--duration 300] [--state OUT.json]
    python3 tools/x3_sellout.py --from-stock 400 --rate 5 --state OUT.json
    python3 tools/x3_sellout.py --restore --state OUT.json
    python3 tools/x3_sellout.py --restore --verify --state OUT.json

왜 원래 수량을 파일로 떠 두나: `UPDATE stock SET quantity=0` 은 되돌릴 수 없다. 시작 **전에** 상품마다
원래 수량을 state 파일에 적어 두고, `--restore` 가 그 값으로 되돌린다(재고 행이 없던 상품은 다시 만든다).
바꾼 시각과 상품도 같은 파일에 남는다 — "언제 무엇이 품절됐는가"가 있어야 응답 시점 노출률을 해석한다.

SIGTERM 을 받으면 그때까지의 기록을 state 파일에 남기고 끝난다(하네스가 측정이 끝나며 죽인다).
`--verify` 는 복원 **뒤에** DB 를 다시 읽어 원래 수량과 같은지 확인한다 — 다음 조건이 이미 품절된 상태로
시작하지 않게 하려는 것이다(1차 수정 4번 요구).

DB 는 compose 의 mysql 이다(root/root, 127.0.0.1:3306, becommerce). 다른 곳은 --host/--port/--user/--password 로.
"""
import argparse
import json
import pathlib
import signal
import sys
import time

DEFAULT_STATE = "x3-sellout-state.json"


def db(args):
    import pymysql
    return pymysql.connect(host=args.host, port=args.port, user=args.user,
                           password=args.password, database=args.database, autocommit=True)


def product_ids(args):
    if args.products:
        return [int(x) for x in args.products.replace(" ", "").split(",") if x]
    if args.products_file:
        text = pathlib.Path(args.products_file).read_text(encoding="utf-8")
        return [int(line.strip()) for line in text.splitlines() if line.strip()]
    if args.from_stock:
        conn = db(args)
        with conn.cursor() as cur:
            cur.execute("SELECT product_id FROM stock WHERE quantity > 0 ORDER BY product_id LIMIT %s",
                        (args.from_stock,))
            ids = [row[0] for row in cur.fetchall()]
        conn.close()
        return ids
    return []


def save_state(path, state):
    pathlib.Path(path).write_text(json.dumps(state, ensure_ascii=False, indent=1), encoding="utf-8")


def restore(args):
    path = pathlib.Path(args.state)
    if not path.exists():
        print(f"복원할 상태 파일이 없다: {path}", file=sys.stderr)
        return 1
    state = json.loads(path.read_text(encoding="utf-8"))
    original = state.get("original", {})
    conn = db(args)
    restored = 0
    with conn.cursor() as cur:
        for pid, qty in original.items():
            cur.execute("UPDATE stock SET quantity=%s WHERE product_id=%s", (qty, int(pid)))
            if cur.rowcount == 0:
                cur.execute("INSERT INTO stock (product_id, quantity, version) VALUES (%s, %s, 0)", (int(pid), qty))
            restored += 1
    mismatched = []
    if args.verify:
        with conn.cursor() as cur:
            for pid, qty in original.items():
                cur.execute("SELECT quantity FROM stock WHERE product_id=%s", (int(pid),))
                row = cur.fetchone()
                if row is None or row[0] != qty:
                    mismatched.append((int(pid), qty, None if row is None else row[0]))
    conn.close()
    print(f"복원 {restored}개 — {state.get('started_at', '?')} 에 시작한 런의 원래 수량")
    if args.verify:
        if mismatched:
            print(f"복원 검증 실패 {len(mismatched)}개 (상품, 기대, 실제): {mismatched[:10]}", file=sys.stderr)
            return 1
        print(f"복원 검증 통과 — {restored}개 모두 원래 수량")
    return 0


def sellout(args):
    ids = product_ids(args)
    if not ids:
        print("상품 목록이 비었다 — --products · --products-file · --from-stock 중 하나가 필요하다", file=sys.stderr)
        return 2
    if args.rate <= 0:
        print("rate 는 1 이상이어야 한다(0 이면 하네스가 이 도구를 아예 부르지 않는다)", file=sys.stderr)
        return 2

    conn = db(args)
    original = {}
    with conn.cursor() as cur:
        for k in range(0, len(ids), 1000):
            chunk = ids[k:k + 1000]
            cur.execute("SELECT product_id, quantity FROM stock WHERE product_id IN "
                        f"({','.join(['%s'] * len(chunk))})", chunk)
            for pid, qty in cur.fetchall():
                original[str(pid)] = qty
    state = {"started_at": time.strftime("%Y-%m-%dT%H:%M:%S"), "rate": args.rate,
             "products": ids, "products_total": len(ids), "original": original, "events": []}
    save_state(args.state, state)          # 품절을 만들기 **전에** 떠 둔다 — 중간에 죽어도 복원할 수 있다
    print(f"상품 {len(ids)}개 · 원래 수량 {len(original)}개를 {args.state} 에 떴다", flush=True)

    interval = 1.0 / args.rate
    deadline = time.time() + args.duration if args.duration else None
    stop = {"now": False}

    def _on_term(signum, frame):
        stop["now"] = True

    signal.signal(signal.SIGTERM, _on_term)   # 하네스가 죽이면 기록을 남기고 끝난다

    changed = 0
    try:
        for pid in ids:
            if stop["now"] or (deadline and time.time() >= deadline):
                break
            at = time.time()
            with conn.cursor() as cur:
                cur.execute("UPDATE stock SET quantity=0 WHERE product_id=%s AND quantity>0", (pid,))
                n = cur.rowcount
            state["events"].append({"at": round(at, 3), "product_id": pid, "changed": n})
            changed += n
            if n:
                print(f"  {time.strftime('%H:%M:%S')} 품절 {pid} (누적 {changed})", flush=True)
            slack = interval - (time.time() - at)
            if slack > 0:
                time.sleep(slack)          # 처리 시간을 빼서 실제로 **초당 R 개**를 지킨다
            if (len(state["events"]) % 50) == 0:
                save_state(args.state, state)
    except KeyboardInterrupt:
        print("\n중단 — 지금까지 바꾼 것을 state 파일에 남긴다")
    finally:
        state["sold_out"] = changed
        state["tried"] = len(state["events"])
        save_state(args.state, state)
        conn.close()
    print(f"품절 {changed}개 · 시도 {len(state['events'])}회")
    print(f"복원: python3 tools/x3_sellout.py --restore --state {args.state}")
    return 0


def main():
    p = argparse.ArgumentParser(description="X3(#317) 품절 주입기 — stock 을 초당 R 개 0 으로 만든다")
    p.add_argument("--products", help="쉼표로 구분한 상품 id 목록")
    p.add_argument("--products-file", help="한 줄에 상품 id 하나인 파일")
    p.add_argument("--from-stock", type=int, default=0,
                   help="목록을 주지 않으면 재고가 있는 상품을 여기서 N 개 골라 쓴다")
    p.add_argument("--rate", type=float, default=5, help="초당 품절시키는 상품 수(R)")
    p.add_argument("--duration", type=int, default=0, help="최대 실행 초(0 = 목록을 다 쓸 때까지)")
    p.add_argument("--state", default=DEFAULT_STATE, help="원래 수량 · 바꾼 기록을 남길 파일")
    p.add_argument("--restore", action="store_true", help="state 파일의 원래 수량으로 되돌린다")
    p.add_argument("--verify", action="store_true", help="--restore 뒤 DB 를 다시 읽어 원래 수량과 같은지 확인한다")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=3306)
    p.add_argument("--user", default="root")
    p.add_argument("--password", default="root")
    p.add_argument("--database", default="becommerce")
    args = p.parse_args()
    return restore(args) if args.restore else sellout(args)


if __name__ == "__main__":
    sys.exit(main())
