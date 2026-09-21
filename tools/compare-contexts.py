#!/usr/bin/env python3
"""E2 대조 — 같은 로그의 **offline 재계산**과 **online 서빙**을 비교하고 원인을 분류한다.

두 값의 출처:
  online  = Redis `ctx:{userId}` — 요청 시 서빙이 실제로 보는 값
  offline = `user_activities` 로그를 seq 기준으로 다시 계산한 값 (마지막 max-items개)

**둘은 같은 이벤트에서 나온다.** 갈라지면 그건 저장소나 집계 방식이 원인이다.

원인 분류(한 사용자당 **독립 플래그** — 겹칠 수 있다):
  absent     컨텍스트 키가 없다. **TTL 만료와 미적용을 밖에서 구분할 수 없다** —
             TTL 조건에서 지배적 설명이 만료이고, 지연 조건에서는 아직 적용 전이다
  behind     온라인 seq < 로그 max seq — 소비가 못 따라왔다(late event)
  dropped    로그에는 있는 항목이 온라인에 없다 — 낮은 seq가 나중에 도착해 버려졌거나
             read-modify-write 경합에서 덮였다(순서 역전·동시 적용)
  truncated  로그가 max-items를 넘고 온라인 목록이 꽉 찼다 — **창 집계가 잘린다**

두 가지 일치율을 따로 낸다 — 처방이 다르기 때문이다:
  컨텍스트 일치율 = 항목 목록이 같은 비율
  창 집계 일치율 = 개수까지 같은 비율

사용:
  python3 tools/compare-contexts.py --manifest /tmp/e2-manifest.json --out /tmp/e2-compare.json
"""
import argparse
import json
import subprocess
import sys
from collections import Counter
from datetime import datetime, timezone


def read_contexts(host, user_ids):
    """user_id → 온라인 컨텍스트 JSON(or None).

    **키 형식을 가정하지 않는다.** `ctx:*` 를 훑어 가운데 조각을 userId 로 읽는다 —
    키에 값 판이 붙어도(`ctx:1:v2`) 대조 도구가 따라 깨지지 않게 하려는 것이다.
    (실제로 키에 판을 붙였을 때 이 도구가 옛 형식을 봐서 전부 `absent` 로 나온 적이 있다.)
    """
    wanted = set(user_ids)
    scan = subprocess.run(["redis-cli", "-h", host, "--scan", "--pattern", "ctx:*"],
                          capture_output=True, text=True, check=True).stdout
    keys = []
    owner = {}
    for key in scan.split():
        parts = key.split(":")
        if len(parts) < 2:
            continue
        try:
            user_id = int(parts[1])
        except ValueError:
            continue
        if user_id in wanted:
            keys.append(key)
            owner[key] = user_id

    values = {user_id: None for user_id in user_ids}
    if not keys:
        return values
    out = subprocess.run(["redis-cli", "-h", host, "--raw", "MGET", *keys],
                         capture_output=True, text=True, check=True).stdout
    lines = out.split("\n")
    for i, key in enumerate(keys):
        raw = lines[i] if i < len(lines) else ""
        if raw:
            values[owner[key]] = raw
    return values


def read_log(container, user_ids):
    """로그 전체를 한 번에 읽는다 — offline 재계산의 입력이다."""
    ids = ",".join(str(u) for u in user_ids)
    sql = ("select user_id, seq, item_id from user_activities "
           f"where user_id in ({ids}) order by user_id, seq")
    out = subprocess.run(
        ["docker", "exec", container, "mysql", "-N", "-B", "-h127.0.0.1", "--protocol=TCP",
         "-ubecommerce", "-pbecommerce", "becommerce", "-e", sql],
        capture_output=True, text=True, check=True).stdout
    log = {}
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        user_id, seq, item_id = int(parts[0]), int(parts[1]), int(parts[2])
        log.setdefault(user_id, []).append({"seq": seq, "itemId": item_id})
    return log


def offline_context(rows, max_items):
    """로그에서 컨텍스트를 다시 계산한다 — **seq 내림차순 마지막 max_items개**(온라인과 같은 규칙)."""
    ordered = sorted(rows, key=lambda r: r["seq"], reverse=True)
    return ordered[:max_items]


def classify(online, offline_rows, log_rows, max_items):
    """원인을 **독립 플래그**로 판정한다 — 한 사용자가 여러 원인을 동시에 가질 수 있다.

    단일 라벨로 접으면 "순서 역전이면서 잘림"인 사용자가 한쪽으로만 세어져 분포가 왜곡된다.
    (스모크에서 실제로 그랬다 — 긴 사용자가 disorder 때문에 dropped 로만 분류됐다.)
    """
    if online is None:
        return {"absent": True, "behind": False, "dropped": False, "truncated": False}, None, None

    online_items = [i["itemId"] for i in online.get("items", [])]
    offline_items = [r["itemId"] for r in offline_rows]
    online_seq = online.get("seq", 0)
    log_max_seq = max((r["seq"] for r in log_rows), default=0)

    flags = {
        # 온라인이 로그보다 뒤처졌다 — 아직 적용되지 않은 이벤트가 있다(late event)
        "behind": online_seq < log_max_seq,
        # 항목이 다르다 — 낮은 seq가 나중에 도착해 **영구히 버려졌다**(순서 역전)
        "dropped": online_items != offline_items,
        # 로그가 max-items를 넘고 온라인 목록이 꽉 찼다 — **창 집계가 잘린다**
        "truncated": len(log_rows) > max_items and len(online_items) >= max_items,
        "absent": False,
    }
    return flags, online_items, offline_items


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--condition", default="unknown", help="이 대조가 어떤 조건인지(리포트 라벨)")
    ap.add_argument("--redis-host", default="127.0.0.1")
    ap.add_argument("--mysql-container", default="pay-mysql-1")
    ap.add_argument("--max-items", type=int, default=20)
    ap.add_argument("--samples", type=int, default=3, help="불일치 대표 사례 몇 건을 남길지")
    args = ap.parse_args()

    with open(args.manifest, encoding="utf-8") as f:
        manifest = json.load(f)
    user_ids = [u["userId"] for u in manifest["users"]]
    emails = {u["userId"]: u["email"] for u in manifest["users"]}

    online_by_user = read_contexts(args.redis_host, user_ids)
    log = read_log(args.mysql_container, user_ids)

    rows = []
    flag_counts = Counter()
    list_ok = count_ok = 0
    samples = []
    causes = ["absent", "behind", "dropped", "truncated"]

    for user_id in user_ids:
        value = online_by_user.get(user_id)
        online = json.loads(value) if value else None
        log_rows = log.get(user_id, [])
        offline_rows = offline_context(log_rows, args.max_items)
        flags, online_items, offline_items = classify(online, offline_rows, log_rows, args.max_items)
        for name in causes:
            if flags[name]:
                flag_counts[name] += 1

        list_match = online_items is not None and online_items == offline_items
        count_match = online_items is not None and len(online_items) == len(log_rows)
        list_ok += 1 if list_match else 0
        count_ok += 1 if count_match else 0

        fired = [c for c in causes if flags[c]]
        rows.append({
            "userId": user_id, "email": emails.get(user_id),
            "causes": fired, "matched": not fired,
            "onlineSeq": online.get("seq") if online else None,
            "logMaxSeq": max((r["seq"] for r in log_rows), default=0),
            "onlineCount": len(online_items or []), "logCount": len(log_rows),
            "onlineItems": online_items, "offlineItems": offline_items,
            "listMatch": list_match, "countMatch": count_match,
        })
        if fired and len(samples) < args.samples:
            samples.append(rows[-1])

    total = len(rows)
    result = {
        "condition": args.condition,
        "measuredAt": datetime.now(timezone.utc).isoformat(),
        "manifest": args.manifest,
        "users": total,
        "contextMatchPct": round(100.0 * list_ok / total, 1) if total else None,
        "windowCountMatchPct": round(100.0 * count_ok / total, 1) if total else None,
        "causes": [{"cause": c, "users": flag_counts[c],
                    "sharePct": round(100.0 * flag_counts[c] / total, 1) if total else 0.0}
                   for c in causes],
        "matchedUsers": total - sum(1 for r in rows if r["causes"]),
        "samples": samples,
        "rows": rows,
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    print(f"[{args.condition}] 사용자 {total}명 · 컨텍스트 일치 {result['contextMatchPct']}% · "
          f"창 집계 일치 {result['windowCountMatchPct']}%")
    for c in result["causes"]:
        if c["users"]:
            print(f"    {c['cause']:10s} {c['users']:3d}명 ({c['sharePct']}%)  ← 원인은 겹칠 수 있다")
    return 0


if __name__ == "__main__":
    sys.exit(main())
