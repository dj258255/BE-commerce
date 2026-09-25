#!/usr/bin/env python3
"""X5(#328) — 문맥 저장소에 있는 행동이 v2 모델 입력까지 잃지 않고 가는가.

E2 가 저장소와 로그를 대조했다면, 이 도구는 **로그와 모델이 실제로 받은 프롬프트**를 대조한다.

  1. 주입 매니페스트(tools/inject-activities.py)의 사용자마다 홈 1쪽 → 2쪽을 차례로 부른다.
     2쪽이 v2 서버의 /page 를 부르고, 서버는 GENPAGE2_PROMPT_LOG 에 해석한 사건과 토큰을 한 줄 남긴다
  2. 사용자마다 호출 구간 [시작, 끝] 에 찍힌 kind=page 줄을 그 사용자의 **온라인 프롬프트**로 잡는다.
     호출을 한 명씩 차례로 하므로 구간이 겹치지 않는다(요청 모양을 바꿔 사용자 id 를 싣지 않아도 된다)
  3. 로그(user_activities)에서 seq 내림차순 max-items 개를 오래된 것부터 놓아 **사실 그대로의 사건**을 만들고,
     서버와 같은 build_prompt 로 **기대 프롬프트**를 만든다. 요청 시각은 온라인 줄의 now(없으면 그 줄의 시각)다
  4. 사건(상품 · 행동 · 시각 구간)과 토큰을 둘 다 대조한다. 주입 상품은 어휘에 없어 토큰이 전부
     ITEM_FALLBACK 이다 — 토큰만 보면 상품이 바뀌어도 같게 나온다

기대 쪽은 "저장소가 로그를 잃지 않았고 앱이 그대로 넘겼다면" 모델이 받았어야 할 입력이다. OFF(id 만)는 설계상
행동 · 시각을 잃으므로, OFF 의 불일치는 결함의 크기이고 RICH 의 기준 조건 불일치는 버그다.

사용(개인화 가상환경 — pandas · torch 가 필요하다):
  $PY tools/x5_prompt_replay.py --manifest raw/baseline/manifest.json --prompt-log raw/baseline/prompts.jsonl \\
      --mode-dir $GENPAGE_DATA/hm/model/genpage2/validate --session RICH --out raw/baseline/replay.json
"""
from __future__ import annotations

import argparse
import json
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "personalization"
sys.path.insert(0, str(ROOT))

PASSWORD = "k6-load-only-1234"   # inject-activities.py 가 만든 부하 전용 계정의 고정 비밀번호
HOME = "/api/v1/personalization/homepage"
SESSION_ACTIONS = {"CLICK", "VIEW"}


def call(base, method, path, body=None, token=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return res.status, json.loads(res.read().decode() or "null")
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw or "null")
        except json.JSONDecodeError:
            return e.code, raw


def drive(base, users):
    """사용자마다 1쪽 → 2쪽. 2쪽 호출 구간과 지연을 돌려준다."""
    out = {}
    for user in users:
        status, body = call(base, "POST", "/api/v1/auth/login", {"username": user["email"], "password": PASSWORD})
        if status != 200:
            out[user["userId"]] = {"error": f"login {status}"}
            continue
        token = body["token"]
        status, first = call(base, "GET", HOME, token=token)
        cursor = first.get("nextCursor") if status == 200 and isinstance(first, dict) else None
        if not cursor:
            out[user["userId"]] = {"error": f"1쪽 {status} · 다음 커서 없음"}
            continue
        began = time.time()
        t0 = time.perf_counter()
        status, second = call(base, "GET", HOME + "?cursor=" + urllib.parse.quote(cursor), token=token)
        ms = (time.perf_counter() - t0) * 1000
        ended = time.time()
        sources = sorted({r.get("source") for r in (second or {}).get("rows", [])} - {None}) if status == 200 else []
        out[user["userId"]] = {"start": began, "end": ended, "ms": ms, "status": status, "rowSources": sources}
        time.sleep(0.05)   # 다음 사용자의 구간과 로그 시각이 붙지 않게
    return out


def read_log(container, user_ids):
    ids = ",".join(str(u) for u in user_ids)
    sql = ("select user_id, seq, item_id, activity_type, "
           "date_format(occurred_at, '%Y-%m-%dT%H:%i:%s.%fZ') from user_activities "
           f"where user_id in ({ids}) order by user_id, seq")
    raw = subprocess.run(["docker", "exec", container, "mysql", "-N", "-B", "-h127.0.0.1", "--protocol=TCP",
                          "-ubecommerce", "-pbecommerce", "becommerce", "-e", sql],
                         capture_output=True, text=True, check=True).stdout
    log = {}
    for line in raw.splitlines():
        parts = line.split("\t")
        if len(parts) != 5:
            continue
        log.setdefault(int(parts[0]), []).append(
            {"seq": int(parts[1]), "item": int(parts[2]), "action": parts[3], "at": parts[4]})
    return log


def prompt_lines(path):
    lines = []
    for raw in Path(path).read_text(encoding="utf-8").splitlines():
        line = json.loads(raw)
        if line.get("kind") == "page":
            lines.append(line)
    return lines


def normalized(events, now, ago_bucket, article_id):
    """(상품, 행동, 시각 구간). 서버의 해석 규칙 그대로 — 행동이 없으면 ONLINE, 시각이 없거나 미래면 요청일."""
    import pandas as pd

    request = _naive(pd.Timestamp(now))
    out = []
    for e in events:
        at = request if e.get("at") is None else min(_naive(pd.Timestamp(e["at"])).normalize(), request.normalize())
        out.append((article_id(e["item"]), str(e.get("action") or "ONLINE").upper(), ago_bucket(request, at)))
    return out


def _naive(ts):
    return ts.tz_convert("UTC").tz_localize(None) if ts.tzinfo is not None else ts


def classify(expected, online, exp_tokens, online_tokens):
    """불일치를 독립 플래그로 센다(E2 와 같은 이유 — 한 사용자가 여러 원인을 가질 수 있다)."""
    exp_items = [e[0] for e in expected]
    on_items = [e[0] for e in online]
    same_multiset = Counter(exp_items) == Counter(on_items)
    flags = {
        "missing": bool(Counter(exp_items) - Counter(on_items)),
        "extra": bool(Counter(on_items) - Counter(exp_items)),
        "order": same_multiset and exp_items != on_items,
        "action": False, "meaning": False, "time": False,
        "request": [t for t in exp_tokens if t.startswith(("DOW_", "MONTH_"))]
                   != [t for t in online_tokens if t.startswith(("DOW_", "MONTH_"))],
    }
    # 행동 · 시각은 **양쪽에 다 있는 사건끼리** 비교한다. 목록이 다르다고 건너뛰면 빠짐이 뜻 바뀜을 가린다
    # (스모크에서 그랬다 — OFF 가 8건만 보내 빠짐으로만 세어졌다). 같은 상품이 여러 번이면 나온 순서대로 짝짓는다.
    pending = {}
    for item, action, bucket in online:
        pending.setdefault(item, []).append((action, bucket))
    for item, ea, eb in expected:
        if not pending.get(item):
            continue
        oa, ob = pending[item].pop(0)
        flags["action"] |= ea != oa
        # 뜻이 바뀜: 조회 · 클릭이 구매(ONLINE · STORE)로 읽혔다
        flags["meaning"] |= ea in SESSION_ACTIONS and oa not in SESSION_ACTIONS
        flags["time"] |= eb != ob
    diff = sum(a != b for a, b in zip(exp_tokens, online_tokens)) + abs(len(exp_tokens) - len(online_tokens))
    flags["exact"] = expected == online and exp_tokens == online_tokens
    return flags, diff, max(len(exp_tokens), len(online_tokens), 1)


def p95(values):
    if not values:
        return None
    values = sorted(values)
    return values[min(len(values) - 1, int(round(0.95 * (len(values) - 1))))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--prompt-log", required=True)
    ap.add_argument("--mode-dir", required=True, type=Path, help="$GENPAGE_DATA/hm/model/genpage2/<mode>")
    ap.add_argument("--session", choices=("OFF", "RICH"), required=True)
    ap.add_argument("--condition", default="")
    ap.add_argument("--mysql-container", default="pay-mysql-1")
    ap.add_argument("--skip-drive", action="store_true", help="이미 부른 결과(--drive-out)로 대조만 다시 한다")
    ap.add_argument("--drive-out")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    users = manifest["users"]
    max_items = int(manifest.get("maxItems", 20))
    drive_out = Path(args.drive_out or Path(args.out).with_name("drive.json"))
    if args.skip_drive:
        calls = {int(k): v for k, v in json.loads(drive_out.read_text()).items()}
    else:
        calls = drive(manifest["base"], users)
        drive_out.write_text(json.dumps(calls, ensure_ascii=False, indent=2))

    import pandas as pd
    from genpage2 import config
    from genpage2.dataset import ago_bucket
    from genpage2.prompt import build_prompt
    from genpage2.vocab import Vocab, _article_id
    from serving import genpage2_server

    vocab = Vocab.load(args.mode_dir / "vocab.json")
    # 가격은 저장소의 사실이 아니라 카탈로그의 사실이다 — 서버와 같은 표를 쓴다
    prices = object.__new__(genpage2_server.Engine)
    prices.vocab, prices.data, prices.mode = vocab, args.mode_dir.parents[3], args.mode_dir.name
    price_by_article, default_price = prices._prices()

    lines = prompt_lines(args.prompt_log)
    log = read_log(args.mysql_container, [u["userId"] for u in users])
    rows, tally, token_diff, token_total, sizes, lat = [], Counter(), 0, 0, [], []
    for user in users:
        uid = user["userId"]
        c = calls.get(uid, {})
        if "start" not in c:
            tally["not_called"] += 1
            rows.append({"userId": uid, "error": c.get("error", "호출 안 됨")})
            continue
        lat.append(c["ms"])
        mine = [x for x in lines if c["start"] - 0.01 <= x["t"] <= c["end"] + 0.01]
        if len(mine) != 1:
            tally["no_prompt" if not mine else "ambiguous"] += 1
            rows.append({"userId": uid, "error": f"구간 안 프롬프트 줄 {len(mine)}개", "rowSources": c["rowSources"]})
            continue
        line = mine[0]
        sizes.append(line.get("bytes", 0))
        now = line.get("now") or datetime.fromtimestamp(line["t"], timezone.utc).isoformat()
        facts = sorted(log.get(uid, []), key=lambda r: r["seq"], reverse=True)[:max_items][::-1]
        truth = [{"item": r["item"], "action": r["action"], "at": r["at"],
                  "_inferred_price": price_by_article.get(_article_id(r["item"]), default_price)} for r in facts]
        # 주입 사용자는 구매가 없다 — history 는 비고 사실은 전부 세션이다
        exp_tokens, _, _ = build_prompt(vocab, now=now, profile=None, events=truth)
        exp_names = [vocab.tokens[t] for t in exp_tokens]
        expected = normalized(truth, now, ago_bucket, _article_id)
        online = normalized(line["events"], now, ago_bucket, _article_id)
        flags, diff, total = classify(expected, online, exp_names, line["tokens"])
        token_diff += diff
        token_total += total
        for k, v in flags.items():
            tally[k] += int(v)
        tally["compared"] += 1
        rows.append({"userId": uid, "flags": flags, "tokenDiff": diff, "tokens": total,
                     "expected": [list(e) for e in expected], "online": [list(e) for e in online],
                     "now": line.get("now"), "bytes": line.get("bytes"), "ms": c["ms"], "rowSources": c["rowSources"]})

    compared = tally["compared"]
    summary = {
        "condition": args.condition, "session": args.session, "users": len(users), "compared": compared,
        "exact": tally["exact"], "exactRate": tally["exact"] / compared if compared else None,
        "tokenMismatchRate": token_diff / token_total if token_total else None,
        "flags": {k: tally[k] for k in ("missing", "extra", "order", "action", "meaning", "time", "request")},
        "notCalled": tally["not_called"], "noPrompt": tally["no_prompt"], "ambiguous": tally["ambiguous"],
        "requestBytesMedian": statistics.median(sizes) if sizes else None,
        "page2MsP50": statistics.median(lat) if lat else None, "page2MsP95": p95(lat),
        "config": {"requestOf": str(config.request_of(args.mode_dir.name)), "maxItems": max_items},
    }
    Path(args.out).write_text(json.dumps({"summary": summary, "users": rows}, ensure_ascii=False, indent=2))
    f = summary["flags"]
    print(f"{args.condition or '-'} [{args.session}] 대조 {compared}/{len(users)} · 완전 일치 {tally['exact']} "
          f"· 토큰 불일치 {summary['tokenMismatchRate'] if token_total else float('nan'):.3f} "
          f"· 빠짐 {f['missing']} 더함 {f['extra']} 순서 {f['order']} 행동 {f['action']} 뜻 {f['meaning']} "
          f"시각 {f['time']} 요청 {f['request']} · 본문 {summary['requestBytesMedian']}B "
          f"· 2쪽 p95 {summary['page2MsP95'] and round(summary['page2MsP95'])}ms "
          f"· 호출 안 됨 {tally['not_called']} 프롬프트 없음 {tally['no_prompt']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
