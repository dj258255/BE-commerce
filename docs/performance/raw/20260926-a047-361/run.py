"""A-047(#361): 저장소의 user-activity 커넥터(키 = userId)와 키 변환을 뺀 대조를 4파티션 토픽 둘로 보내고,
사용자마다 몇 개 파티션에 흩어졌는지 · 파티션 안 seq 역전을 센다. 일회용 CDC 스택(cdcexp)."""
import json, random, subprocess, sys, time, urllib.request
from collections import defaultdict
import pymysql
from confluent_kafka import Consumer, TopicPartition

CONNECT = "http://localhost:18083"
BASE = json.load(open(sys.argv[1]))["config"]
OUT = sys.argv[2]
USERS, PER_USER = 50, 40
UID_BASE = 7_000_000     # 다른 실험의 활동과 겹치지 않는 사용자 id

def req(method, path, body=None):
    r = urllib.request.Request(CONNECT + path, method=method, data=json.dumps(body).encode() if body else None,
                               headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r, timeout=10) as res:
            return res.status, res.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()

def kafka(*args):
    return subprocess.run(["docker", "exec", "cdcexp-kafka-1", "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server", "localhost:19092", *args],
                          capture_output=True, text=True).stdout

variants = {
    "keyed": ("a047-keyed", "user.activity.p4.keyed", 190101, dict(BASE)),
    "nokey": ("a047-nokey", "user.activity.p4.nokey", 190102, dict(BASE)),
}
nk = variants["nokey"][3]
nk["transforms"] = ",".join(t for t in nk["transforms"].split(",") if t not in ("keyFromValue", "keyField"))
for k in [k for k in nk if k.startswith("transforms.keyFromValue") or k.startswith("transforms.keyField")]:
    del nk[k]

for name, topic, sid, cfg in variants.values():
    kafka("--create", "--if-not-exists", "--topic", topic, "--partitions", "4", "--replication-factor", "1")
    cfg.update({"transforms.route.replacement": topic, "database.server.id": str(sid), "topic.prefix": name,
                "schema.history.internal.kafka.topic": f"{name}.schema-history",
                # 조건식 패턴이 저장소 설정의 prefix(pay-cdc)에 묶여 있어 prefix 를 바꾸면 변환이 조용히 빠진다(첫 두 시도가 0건이었던 이유)
                "predicates.isData.pattern": name.replace("-", "\\-") + "\\.becommerce\\..*"})
    req("DELETE", f"/connectors/{name}")
    print(name, req("POST", "/connectors", {"name": name, "config": cfg})[0])
for _ in range(60):
    st = [json.loads(req("GET", f"/connectors/{n}/status")[1]) for n, _, _, _ in variants.values()]
    if all(s.get("tasks") and s["tasks"][0]["state"] == "RUNNING" for s in st):
        break
    time.sleep(2)
time.sleep(10)

conn = pymysql.connect(host="127.0.0.1", port=13326, user="root", password="root", database="becommerce", autocommit=True)
c = conn.cursor()
c.execute("DELETE FROM user_activities WHERE user_id >= %s AND user_id < %s", (UID_BASE, UID_BASE + USERS))
rows = [(UID_BASE + u, seq) for seq in range(1, PER_USER + 1) for u in range(USERS)]
random.Random(7).shuffle(rows)                   # 사용자를 섞되 사용자마다 seq 는 오름차순이 되게 다시 정렬한다
next_seq = defaultdict(int)
ordered = []
for uid, _ in rows:
    next_seq[uid] += 1
    ordered.append((uid, next_seq[uid]))
for uid, seq in ordered:
    c.execute("INSERT INTO user_activities(user_id, item_id, activity_type, seq, source, occurred_at) VALUES (%s, 108775015, 'VIEW', %s, 'WEB', NOW(6))",
              (uid, seq))
print("넣음", len(ordered), time.strftime("%T"))
time.sleep(15)

result = {}
for key, (name, topic, _, _) in variants.items():
    cons = Consumer({"bootstrap.servers": "localhost:19092", "group.id": f"a047-{key}-{time.time()}", "auto.offset.reset": "earliest",
                     "enable.auto.commit": False})
    cons.assign([TopicPartition(topic, p, 0) for p in range(4)])
    parts = defaultdict(set)
    last = {}
    inversions, n, keys = 0, 0, defaultdict(set)
    started = time.time()          # 스트리밍이 스냅숏 뒤에 시작하므로 다 올 때까지(최대 180초) 읽는다. 0 건을 결과로 쓰지 않는다
    while n < USERS * PER_USER and time.time() - started < 180:
        m = cons.poll(0.5)
        if m is None or m.error():
            continue
        v = json.loads(m.value())
        uid, seq = v.get("userId"), v.get("seq")
        if uid is None or not (UID_BASE <= uid < UID_BASE + USERS):
            continue
        n += 1
        parts[uid].add(m.partition())
        keys[uid].add(m.key().decode() if m.key() else None)
        k = (uid, m.partition())
        if k in last and seq < last[k]:
            inversions += 1
        last[k] = seq
    cons.close()
    spread = [len(p) for p in parts.values()]
    result[key] = {"records": n, "expected": USERS * PER_USER, "read_seconds": round(time.time() - started, 1), "users": len(parts), "users_in_one_partition": sum(1 for s in spread if s == 1),
                   "partitions_per_user_hist": {str(k): spread.count(k) for k in sorted(set(spread))},
                   "inversions_within_partition": inversions, "sample_keys": sorted({str(x) for s in list(keys.values())[:3] for x in s})[:6]}
    print(key, result[key])
json.dump(result, open(OUT, "w"), indent=1, ensure_ascii=False)
for name, _, _, _ in variants.values():
    req("DELETE", f"/connectors/{name}")
c.execute("DELETE FROM user_activities WHERE user_id >= %s AND user_id < %s", (UID_BASE, UID_BASE + USERS))
