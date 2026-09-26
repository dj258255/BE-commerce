"""PF-3(#358): 하트비트 설정 · keep-alive 를 바꾼 커넥터 넷을 동시에 띄우고, DB 가 조용할 때 하트비트 토픽 레코드 간격을 잰다."""
import json, subprocess, sys, time, urllib.request
CONNECT = "http://localhost:18083"
BASE = json.load(open(sys.argv[1]))["config"]
OUT = sys.argv[2]
CONDS = [("pf3a", 10000, None), ("pf3b", 3000, None), ("pf3c", 10000, 15000), ("pf3d", 10000, 30000)]

def req(method, path, body=None):
    r = urllib.request.Request(CONNECT + path, method=method, data=json.dumps(body).encode() if body else None,
                               headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r, timeout=10) as res:
            return res.status, res.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()

for i, (name, hb, ka) in enumerate(CONDS):
    req("DELETE", f"/connectors/{name}")
    cfg = dict(BASE)
    cfg.update({"database.server.id": str(190001 + i), "topic.prefix": name, "heartbeat.interval.ms": str(hb),
                "schema.history.internal.kafka.topic": f"{name}.schema-history"})
    if ka:
        cfg["connect.keep.alive.interval.ms"] = str(ka)
    print(name, req("POST", "/connectors", {"name": name, "config": cfg})[0])
for _ in range(60):
    states = [json.loads(req("GET", f"/connectors/{n}/status")[1]) for n, _, _ in CONDS]
    if all(s.get("tasks") and s["tasks"][0]["state"] == "RUNNING" for s in states):
        break
    time.sleep(2)
print("모두 RUNNING", time.strftime("%T"))
time.sleep(60)
start = int(time.time() * 1000)
print("관측 시작", time.strftime("%T"))
time.sleep(200)
end = int(time.time() * 1000)
result = {"start_ms": start, "end_ms": end, "conditions": {}}
for name, hb, ka in CONDS:
    out = subprocess.run(["docker", "exec", "cdcexp-kafka-1", "/opt/kafka/bin/kafka-console-consumer.sh", "--bootstrap-server",
                          "localhost:19092", "--topic", f"__debezium-heartbeat.{name}", "--from-beginning", "--timeout-ms", "8000",
                          "--property", "print.timestamp=true"], capture_output=True, text=True).stdout
    ts = sorted(int(l.split("CreateTime:")[1].split()[0]) for l in out.splitlines() if "CreateTime:" in l)
    win = [t for t in ts if start <= t <= end]
    gaps = [round((b - a) / 1000, 1) for a, b in zip(win, win[1:])]
    result["conditions"][name] = {"heartbeat_interval_ms": hb, "keep_alive_ms": ka or 60000, "records": len(win), "gaps_s": gaps}
    print(name, "hb", hb, "keep-alive", ka or "기본 60000", "간격(초)", gaps)
json.dump(result, open(OUT, "w"), indent=1)
for name, _, _ in CONDS:
    req("DELETE", f"/connectors/{name}")
