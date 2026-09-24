"""#256 표: 합성 사용자가 실제로 한 일(정답)과 분석 결과를 나란히 놓는다.

    python3 tools/ab_report.py OUT NAME...
"""
import json
import pathlib
import sys


def main(out, names):
    out = pathlib.Path(out)
    print("| 실행 | 사용자(대조/실험) | SRM p | 고정 배정 위반 | 클릭 사용자 차이 [95% 구간] · p | 주입 | 구매 사용자 차이 [95% 구간] · p | 주입 | 귀속 클릭 = 정답 | 귀속 구매 = 정답 | 안 셈(음성 대조) |")
    print("|---|---:|---:|---:|---|---:|---|---:|---|---|---|")
    for name in names:
        t = json.loads((out / f"truth-{name}.json").read_text())
        a = json.loads((out / f"analysis-{name}.json").read_text())
        per = a["per_variant"]
        sticky = sum(1 for u in t["users"] if len(set(u["variants"])) != 1) + a["sticky_conflicts"]
        truth_click = sum(u["shown_click"] for u in t["users"])
        truth_buy = sum(u["shown_buy"] for u in t["users"])
        noise_c = sum(u["noise_click"] for u in t["users"])
        noise_b = sum(u["noise_buy"] for u in t["users"])
        got_c = per["control"]["attributed_clicks"] + per["treatment"]["attributed_clicks"]
        got_b = per["control"]["attributed_purchases"] + per["treatment"]["attributed_purchases"]
        pc, pb = t["params"]["click"], t["params"]["buy"]

        def d(x):
            return f"{x['diff']:+.3f} [{x['ci95'][0]:+.3f}, {x['ci95'][1]:+.3f}] · {x['p']:.3f}"
        print(f"| {name} | {per['control']['users']}/{per['treatment']['users']} | {a['srm']['p']:.3f} | {sticky} "
              f"| {d(a['click_user_rate'])} | {pc['treatment'] - pc['control']:+.2f} "
              f"| {d(a['purchase_user_rate'])} | {pb['treatment'] - pb['control']:+.2f} "
              f"| {got_c} = {truth_click} | {got_b} = {truth_buy} "
              f"| 클릭 {a['unattributed']['clicks']}/{noise_c} · 구매 {a['unattributed']['purchases']}/{noise_b} |")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:])
