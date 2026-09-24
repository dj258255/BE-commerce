"""A/B 분석(#256): 노출을 변형별로 나누고, 노출된 상품에 한해 클릭·구매를 귀속한다.

    uv run --with pymysql python3 tools/ab_analysis.py EXPERIMENT SINCE_ISO [TREATMENT_PERCENT] > out.json

- 노출: `home_impressions`(experiment 가 이 실험인 행). 사용자의 변형은 노출에 적힌 값이다
- 클릭: `user_activities` 의 CLICK. 같은 사용자의 노출 뒤 **30분 안**이고 그 노출의 `item_ids` 에 있는 상품이면 귀속한다
- 구매: 결제 완료 주문의 항목. 노출 뒤 **24시간 안**이고 노출된 상품이면 귀속한다
- 귀속되지 않은 클릭·구매는 따로 센다(노출되지 않은 상품, 창 밖)
- 비율 불일치(SRM): 노출 사용자 수가 설정 비율과 맞는지 χ² 검정(자유도 1)
- 차이: 실험군 − 대조군, 두 비율의 정규 근사 95% 구간과 양측 p 값
"""
import collections
import datetime as dt
import json
import math
import sys

CLICK_WINDOW = dt.timedelta(minutes=30)
PURCHASE_WINDOW = dt.timedelta(hours=24)


def db():
    import pymysql
    return pymysql.connect(host="127.0.0.1", port=3306, user="root", password="root", database="becommerce")


def phi(z):
    return 0.5 * (1 + math.erf(z / math.sqrt(2)))


def diff(x1, n1, x0, n0):
    """실험군 비율 − 대조군 비율, 95% 구간(비합동 분산), 양측 p(합동 분산 z 검정)."""
    p1, p0 = x1 / n1, x0 / n0
    se = math.sqrt(p1 * (1 - p1) / n1 + p0 * (1 - p0) / n0)
    pooled = (x1 + x0) / (n1 + n0)
    se0 = math.sqrt(pooled * (1 - pooled) * (1 / n1 + 1 / n0)) or 1e-12
    z = (p1 - p0) / se0
    return {"treatment": p1, "control": p0, "diff": p1 - p0, "ci95": [p1 - p0 - 1.96 * se, p1 - p0 + 1.96 * se],
            "p": 2 * (1 - phi(abs(z)))}


def srm(n1, n0, treatment_share):
    n = n1 + n0
    e1, e0 = n * treatment_share, n * (1 - treatment_share)
    chi2 = (n1 - e1) ** 2 / e1 + (n0 - e0) ** 2 / e0
    return {"chi2": chi2, "p": math.erfc(math.sqrt(chi2 / 2))}      # 자유도 1 의 생존 함수


def analyze(experiment, since, treatment_percent=50):
    conn = db()
    c = conn.cursor()
    c.execute("SELECT user_id, variant, created_at, item_ids FROM home_impressions WHERE experiment = %s AND created_at >= %s",
              (experiment, since))
    imps = collections.defaultdict(list)
    variant_of, conflicts = {}, 0
    for user, variant, at, items in c.fetchall():
        if user in variant_of and variant_of[user] != variant:
            conflicts += 1                                    # 고정 배정이 깨진 사용자
        variant_of.setdefault(user, variant)
        imps[user].append((at, set(int(x) for x in items.split(",") if x)))
    users = list(variant_of)

    def attribute(rows, window):
        got, missed = collections.defaultdict(set), 0
        for user, item, at in rows:
            if any(t <= at <= t + window and item in shown for t, shown in imps.get(user, [])):
                got[user].add(item)
            else:
                missed += 1
        return got, missed

    clicks, purchases = [], []
    for i in range(0, len(users), 1000):
        chunk = users[i:i + 1000]
        marks = ",".join(["%s"] * len(chunk))
        c.execute(f"SELECT user_id, item_id, occurred_at FROM user_activities WHERE activity_type = 'CLICK' "
                  f"AND occurred_at >= %s AND user_id IN ({marks})", (since, *chunk))
        clicks += c.fetchall()
        c.execute(f"SELECT o.user_id, i.product_id, o.created_at FROM orders o JOIN order_items i ON i.order_id = o.id "
                  f"WHERE o.status = 'PAID' AND o.created_at >= %s AND o.user_id IN ({marks})", (since, *chunk))
        purchases += c.fetchall()
    conn.close()
    clicked, click_missed = attribute(clicks, CLICK_WINDOW)
    bought, buy_missed = attribute(purchases, PURCHASE_WINDOW)

    n = collections.Counter(variant_of.values())
    per = {}
    for v in ("control", "treatment"):
        vu = [u for u in users if variant_of[u] == v]
        per[v] = {"users": len(vu), "impressions": sum(len(imps[u]) for u in vu),
                  "click_users": sum(1 for u in vu if clicked.get(u)), "purchase_users": sum(1 for u in vu if bought.get(u)),
                  "attributed_clicks": sum(len(clicked.get(u, ())) for u in vu),
                  "attributed_purchases": sum(len(bought.get(u, ())) for u in vu)}
    t, k = per["treatment"], per["control"]
    return {"experiment": experiment, "since": str(since), "per_variant": per,
            "srm": srm(n["treatment"], n["control"], treatment_percent / 100), "sticky_conflicts": conflicts,
            "click_user_rate": diff(t["click_users"], t["users"], k["click_users"], k["users"]),
            "purchase_user_rate": diff(t["purchase_users"], t["users"], k["purchase_users"], k["users"]),
            "unattributed": {"clicks": click_missed, "purchases": buy_missed},
            "windows": {"click_minutes": CLICK_WINDOW.total_seconds() / 60, "purchase_hours": PURCHASE_WINDOW.total_seconds() / 3600}}


if __name__ == "__main__":
    since = dt.datetime.fromisoformat(sys.argv[2])
    pct = int(sys.argv[3]) if len(sys.argv) > 3 else 50
    print(json.dumps(analyze(sys.argv[1], since, pct), indent=1, default=str))
