"""CDC poll.interval.ms 의 비용을 잰다(#252). 판정 기준은 이슈에 측정 전에 고정했다.

    uv run --with pymysql --with confluent-kafka python3 tools/cdc_poll_cost.py run OUT POLL [IDLE_S LOAD_S RATE]
    python3 tools/cdc_poll_cost.py report OUT

`catalog-cdc` 커넥터가 떠 있고 poll.interval.ms 가 POLL 로 설정돼 있어야 한다(run-cdc-poll-cost.sh 가 한다).
조용한 구간과 변경 구간(초당 RATE 건)에서 Connect·MySQL 컨테이너 CPU 를 2초마다 찍고, 변경 구간에서는
커밋 → 토픽 레코드 지연을 잰다. 레코드 시각은 Connect 프로듀서가 붙인 CreateTime 이다(컨슈머 지연을 빼려는 것이다).
"""
import json
import os
import pathlib
import statistics
import subprocess
import sys
import threading
import time

# 일회용 스택에서 돌릴 때는 환경 변수로 바꾼다(#360). 기본은 공유 compose 이름이다
CONTAINERS = (os.environ.get("CONNECT_CONTAINER", "pay-debezium-1"), os.environ.get("MYSQL_CONTAINER", "pay-mysql-1"))
DB_PORT = int(os.environ.get("DB_PORT", "3306"))
KAFKA_BOOTSTRAP = os.environ.get("KAFKA_BOOTSTRAP", "localhost:9092")
ACTIVITY_RATE = float(os.environ.get("ACTIVITY_RATE", "0"))   # 0 보다 크면 변경 구간에 사용자 활동도 초당 이만큼 넣는다(커넥터 둘, #360)
TAG = os.environ.get("TAG", "")
PRICE_BASE = 3_000_000


def sample_cpu(stop, phase, out):
    while not stop.is_set():
        r = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}},{{.CPUPerc}}", *CONTAINERS],
                           capture_output=True, text=True)
        t = time.time()
        for line in r.stdout.strip().splitlines():
            name, cpu = line.split(",")
            out.append({"t": t, "phase": phase[0], "name": name, "cpu": float(cpu.rstrip("%"))})


def run(out, poll, idle_s=60, load_s=60, rate=50):
    import pymysql
    from confluent_kafka import Consumer, TopicPartition, OFFSET_END
    out = pathlib.Path(out)
    out.mkdir(parents=True, exist_ok=True)
    conn = pymysql.connect(host="127.0.0.1", port=DB_PORT, user="root", password="root", database="becommerce",
                           autocommit=True)
    c = conn.cursor()
    c.execute("SELECT product_id, price FROM products ORDER BY product_id LIMIT 200")
    targets = list(c.fetchall())

    consumer = Consumer({"bootstrap.servers": KAFKA_BOOTSTRAP, "group.id": f"poll-cost-{poll}-{time.time()}",
                         "enable.auto.commit": False})
    md = consumer.list_topics("catalog.change", timeout=10)
    consumer.assign([TopicPartition("catalog.change", p, OFFSET_END) for p in md.topics["catalog.change"].partitions])
    commits, lags, stop_consume = {}, [], threading.Event()

    def consume():
        while not stop_consume.is_set():
            m = consumer.poll(0.2)
            if m is None or m.error():
                continue
            try:
                price = json.loads(m.value())["price"]
            except Exception:
                continue
            if price in commits:
                lags.append(m.timestamp()[1] - commits[price])
    ct = threading.Thread(target=consume)
    ct.start()

    samples, phase, stop = [], ["idle"], threading.Event()
    st = threading.Thread(target=sample_cpu, args=(stop, phase, samples))
    st.start()
    time.sleep(idle_s)
    phase[0] = "load"
    started = time.time()
    activity = {"n": 0}
    stop_activity = threading.Event()

    def insert_activity():
        ac = pymysql.connect(host="127.0.0.1", port=DB_PORT, user="root", password="root", database="becommerce", autocommit=True)
        cur = ac.cursor()
        base = int(time.time() * 1000)
        while not stop_activity.is_set() and time.time() - started < load_s:
            time.sleep(max(0.0, started + activity["n"] / ACTIVITY_RATE - time.time()))
            n = activity["n"]
            cur.execute("INSERT INTO user_activities(user_id, item_id, activity_type, seq, source, occurred_at) "
                        "VALUES (%s, %s, 'VIEW', %s, 'WEB', NOW(6))", (1 + n % 1000, targets[n % len(targets)][0], base + n))
            activity["n"] += 1
        ac.close()
    at = threading.Thread(target=insert_activity) if ACTIVITY_RATE > 0 else None
    if at:
        at.start()
    seq = 0
    try:
        while time.time() - started < load_s:
            time.sleep(max(0.0, started + seq / rate - time.time()))
            pid, _ = targets[seq % len(targets)]
            price = PRICE_BASE + seq
            c.execute("UPDATE products SET price = %s WHERE product_id = %s", (price, pid))
            commits[price] = int(time.time() * 1000)
            seq += 1
        phase[0] = "drain"
        if at:
            at.join()
        time.sleep(5)
    finally:
        stop_activity.set()
        stop.set()
        st.join()
        stop_consume.set()
        ct.join()
        consumer.close()
        for pid, price in targets:
            c.execute("UPDATE products SET price = %s WHERE product_id = %s", (price, pid))
        conn.close()

    def avg(phase_, name):
        v = [s["cpu"] for s in samples if s["phase"] == phase_ and s["name"] == name]
        return round(statistics.mean(v), 2) if v else None

    def pct(xs, p):
        xs = sorted(xs)
        return xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))] if xs else None

    doc = {"poll_ms": poll, "tag": TAG, "rate": rate, "activity_rate": ACTIVITY_RATE, "activity_sent": activity["n"],
           "containers": list(CONTAINERS), "sent": seq, "received": len(lags),
           "lag_p50_ms": pct(lags, 50), "lag_p95_ms": pct(lags, 95), "lag_max_ms": max(lags) if lags else None,
           "cpu": {f"{ph}.{n}": avg(ph, n) for ph in ("idle", "load") for n in CONTAINERS},
           "samples": samples}
    (out / f"poll-{poll}{TAG}.json").write_text(json.dumps(doc, indent=1))
    print({k: v for k, v in doc.items() if k != "samples"})


def report(out):
    out = pathlib.Path(out)
    print("| 실행 | poll.interval.ms | 활동 부하 | Connect CPU 조용 | Connect CPU 변경 중 | MySQL CPU 조용 | MySQL CPU 변경 중 | 보냄/받음 | 지연 p50 | **지연 p95** | 최대 |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for p in sorted(out.glob("poll-*.json")):
        d = json.loads(p.read_text())
        cpu = d["cpu"]
        connect, mysql = d.get("containers", ["pay-debezium-1", "pay-mysql-1"])
        f = lambda v: "—" if v is None else f"{v:.1f}%"
        ms = lambda v: "—" if v is None else f"{v:,}ms"
        print(f"| `{p.stem}` | {d['poll_ms']} | {d.get('activity_rate', 0):g}/s | {f(cpu[f'idle.{connect}'])} | {f(cpu[f'load.{connect}'])} "
              f"| {f(cpu[f'idle.{mysql}'])} | {f(cpu[f'load.{mysql}'])} | {d['sent']}/{d['received']} | {ms(d['lag_p50_ms'])} "
              f"| **{ms(d['lag_p95_ms'])}** | {ms(d['lag_max_ms'])} |")
    print("\nCPU 는 docker stats 의 컨테이너 CPU(100% = 한 코어) 평균이다.")


if __name__ == "__main__":
    if sys.argv[1] == "run":
        a = sys.argv[2:]
        run(a[0], int(a[1]), *(int(x) for x in a[2:]))
    else:
        report(sys.argv[2])
