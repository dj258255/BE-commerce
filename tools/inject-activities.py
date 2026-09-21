#!/usr/bin/env python3
"""E2 주입 — 통제된 활동 로그를 온라인 경로에 흘린다.

E2는 부하 실험이 아니라 **통제된 주입 + 대조**다. 도착률보다 **순서와 개수**를 통제한다.
그래서 k6가 아니라 스크립트로 만들었다(순서 역전을 일부러 만드는 일은 부하 도구의 일이 아니다).

원인을 하나씩 재현할 수 있게 손잡이를 둔다:
  --disorder-ratio   인접 쌍을 뒤집어 보낸다 → 낮은 seq가 나중에 도착해 온라인에서 버려진다
  --duplicate-ratio  같은 이벤트를 두 번 보낸다 → DB 유니크가 409로 막는지 확인한다
  --long-users N     일부 사용자에게 max-items 보다 많은 이벤트를 보낸다 → 목록이 잘린다
  (컨슈머 지연과 TTL은 앱 기동 설정이라 러너가 정한다)

산출물은 **매니페스트**다 — 어떤 사용자에게 무엇을 보냈고 무엇이 201/409였는지.
대조 도구(compare-contexts.py)가 이 목록으로 비교 대상을 정한다.

사용:
  python3 tools/inject-activities.py --users 20 --events 12 --long-users 5 --long-events 30 \
      --disorder-ratio 0.2 --out /tmp/e2-manifest.json
"""
import argparse
import json
import random
import ssl
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone


def call(base, method, path, body=None, token=None, timeout=10):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout,
                                    context=ssl._create_unverified_context()) as res:
            return res.status, json.loads(res.read().decode() or "null")
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw or "null")
        except json.JSONDecodeError:
            return e.code, raw


def signup_and_login(base, email, password):
    call(base, "POST", "/api/v1/members/signup", {"email": email, "password": password})
    status, body = call(base, "POST", "/api/v1/auth/login", {"username": email, "password": password})
    if status != 200 or not isinstance(body, dict) or "token" not in body:
        raise RuntimeError(f"로그인 실패({status}): {email} → {body}")
    return body["token"]


def seq_order(count, disorder_ratio, rng):
    """보낼 순서를 만든다. 순서 정상이면 1..count, 아니면 인접 쌍 일부를 뒤집는다.

    뒤집기가 곧 재현 장치다 — 온라인 적용은 `seq`가 클 때만 반영하므로, 낮은 seq가 나중에
    도착하면 **영영 버려진다.** 로그에는 남지만 컨텍스트에는 없다.
    """
    order = list(range(1, count + 1))
    if disorder_ratio <= 0:
        return order
    i = 0
    while i < len(order) - 1:
        if rng.random() < disorder_ratio:
            order[i], order[i + 1] = order[i + 1], order[i]
            i += 2
        else:
            i += 1
    return order


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:18080")
    ap.add_argument("--users", type=int, default=20)
    ap.add_argument("--events", type=int, default=12, help="보통 사용자의 이벤트 수(max-items 이하)")
    ap.add_argument("--long-users", type=int, default=5, help="max-items 초과로 보낼 사용자 수")
    ap.add_argument("--long-events", type=int, default=30, help="그 사용자들의 이벤트 수")
    ap.add_argument("--disorder-ratio", type=float, default=0.0)
    ap.add_argument("--duplicate-ratio", type=float, default=0.0)
    ap.add_argument("--signup-delay", type=float, default=0.35, help="가입 IP 제한 회피")
    ap.add_argument("--inter-event-delay", type=float, default=0.0)
    ap.add_argument("--max-items", type=int, default=20, help="앱 설정과 같아야 한다")
    ap.add_argument("--seed", type=int, default=20260921)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    rng = random.Random(args.seed)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    users = []
    accepted = rejected = 0

    for i in range(args.users):
        long_user = i < args.long_users
        count = args.long_events if long_user else args.events
        email = f"e2-{stamp}-{i}@load.test"
        password = "k6-load-only-1234"
        token = signup_and_login(args.base, email, password)
        # userId는 JWT sub(=principal name)다. 매니페스트에 남겨 대조 도구가 그대로 쓴다.
        import base64
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        user_id = int(json.loads(base64.urlsafe_b64decode(payload))["sub"])

        sent = []
        for seq in seq_order(count, args.disorder_ratio, rng):
            item_id = user_id * 1000 + seq
            status, _ = call(args.base, "POST", "/api/v1/personalization/activity",
                             {"itemId": item_id, "type": "CLICK", "seq": seq}, token)
            sent.append({"seq": seq, "itemId": item_id, "status": status})
            accepted += 1 if status == 201 else 0
            rejected += 0 if status == 201 else 1
            if rng.random() < args.duplicate_ratio:
                dup_status, _ = call(args.base, "POST", "/api/v1/personalization/activity",
                                     {"itemId": item_id, "type": "CLICK", "seq": seq}, token)
                sent.append({"seq": seq, "itemId": item_id, "status": dup_status, "duplicate": True})
                accepted += 1 if dup_status == 201 else 0
                rejected += 0 if dup_status == 201 else 1
            if args.inter_event_delay:
                time.sleep(args.inter_event_delay)
        users.append({"userId": user_id, "email": email, "long": long_user,
                      "sentCount": count, "sent": sent})
        time.sleep(args.signup_delay)
        print(f"  user {user_id}: {count}건 (긴 사용자={long_user})", flush=True)

    manifest = {
        "base": args.base,
        "injected_at": datetime.now(timezone.utc).isoformat(),
        "maxItems": args.max_items,
        "disorderRatio": args.disorder_ratio,
        "duplicateRatio": args.duplicate_ratio,
        "accepted": accepted,
        "rejected": rejected,
        "users": users,
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    print(f"\n주입 완료 — 사용자 {len(users)}명 · 201 {accepted}건 · 그 외 {rejected}건")
    print(f"매니페스트: {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
