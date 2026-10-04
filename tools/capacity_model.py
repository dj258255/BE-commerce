#!/usr/bin/env python3
"""용량 모델 — docs/40-capacity-model.md 의 유도 계산을 그대로 코드로 옮긴다.

    python3 tools/capacity_model.py           # (b)(c)(d)(f) 계산 결과를 표로 출력
    python3 tools/capacity_model.py --check   # 문서에 적은 기대값과 대조한다. 하나라도 어긋나면 종료 코드 1

문서를 파싱하지 않는다. 기대값을 이 파일 안 표에 두고 계산 결과와 비교한다.
계산에 쓰는 입력(가정 · 공급 실측 · 검증 재계산 입력)도 모두 여기 상수로 박아 둔다.
"""
from __future__ import annotations

import argparse
import sys

# ── 수요 가정 (docs/40 수요 가정과 유도 체인) ────────────────────────────────
MAU_STAGES = (100_000, 300_000, 1_000_000)
DAU_PER_MAU = 0.25
SESSIONS_PER_USER = 1.5
PAGES_PER_SESSION = 8
APIS_PER_PAGE = 3
PEAK_MULTIPLIER = 8
SECONDS_PER_DAY = 86_400
REQUESTS_PER_USER_DAY = SESSIONS_PER_USER * PAGES_PER_SESSION * APIS_PER_PAGE  # 36

# ── API 믹스 (docs/40 API 믹스, 합 100%) ──────────────────────────────────────
API_MIX = {
    "홈 1쪽": 0.10,
    "홈 2쪽": 0.05,
    "목록·검색": 0.35,
    "상품 상세": 0.26,
    "패싯": 0.06,
    "카테고리": 0.04,
    "찜": 0.03,
    "주문 목록": 0.05,
    "주문 생성": 0.02,
    "결제 승인": 0.01,
    "기타": 0.03,
}
HOME_SHARE = API_MIX["홈 1쪽"] + API_MIX["홈 2쪽"]            # 0.15
HOME2_SHARE = API_MIX["홈 2쪽"]                              # 0.05
WRITE_SHARE = API_MIX["주문 생성"] + API_MIX["결제 승인"]      # 0.03
READ_SHARE = 1.0 - WRITE_SHARE                               # 0.97

# ── 공급 실측 (docs/40 공급 인벤토리) ─────────────────────────────────────────
# 모델 서버 1대 상한은 모델 서버 직접 실측이다(runs/20261004-s1-serving/l2, /page hybrid):
# 동시성 4 = 9.34/s(사용), 동시성 1 = 9.0/s. 앱 홈 2쪽 경로의 종단 처리량 10.2/s 는
# 모델 서버가 아니라 앱 경로 실측(runs/20261004-s2-app/m2-hybrid)이라 따로 둔다.
MODEL_SUPPLY = 9.34          # 모델 서버 1대 /page hybrid, 동시성 4 (동시성 1 은 9.0/s)
APP_HOME2_SUPPLY = 10.2      # 앱 홈 2쪽 경로 HYBRID 종단 처리량 (동시성 1, 닫힌 루프)
DB_READ_KNEE = 1_800.0       # docs/24  (열린 루프 읽기 무릎)
DB_WRITE_KNEE = 120.0        # docs/24  (열린 루프 쓰기 무릎)
# PG 는 실 응답시간 분포가 없어 처리율로 환산하지 않는다. 실측값은 내부 동시 호출 상한 40 뿐이다
# (ADR-022 는 "지금 40 은 워커 수에서 나온 값이지 PG 에서 나온 값이 아니다"라고 명시한다).
PG_CONCURRENCY = 40          # ADR-022 내부 동시 호출 상한(처리율 환산 불가)

# ── 검증 재계산 입력 (docs/40 기존 데이터로 모델 검증) ────────────────────────
S_HYBRID_MS = 107.0                       # s1 serving l2 hybrid 평균 추론
X_C1 = 9.034493143767998                  # l2 hybrid 동시성 1 처리량
X_C4 = 9.33766867173462                   # l2 hybrid 동시성 4 처리량
W_MEASURED_MS = 420.7540414936375         # l2 hybrid 동시성 4 왕복 p50
X6_C = 22.9                               # v2 /recommend 용량 (BACKEND X2)
X6_CP = 16.5                              # v2 /page 용량 (BACKEND X2)
X6_RECOMMEND = 6.0                        # 추천 행 부하 (0.25C)
X6_PAGE_LOADS = (8.0, 16.0, 25.0)         # 2쪽 부하 0.5 · 1.0 · 1.5 Cp

# ── 문서에 적힌 기대값 (이 표가 유일한 판정 기준) ─────────────────────────────
EXPECTED_CHAIN = {
    100_000: {"dau": 25_000, "daily": 900_000, "avg_rps": 10.42, "peak_rps": 83.33},
    300_000: {"dau": 75_000, "daily": 2_700_000, "avg_rps": 31.25, "peak_rps": 250.00},
    1_000_000: {"dau": 250_000, "daily": 9_000_000, "avg_rps": 104.17, "peak_rps": 833.33},
}
EXPECTED_FORCED = {
    100_000: {"model": 12.50, "pg": 0.83, "db_read": 80.83},
    300_000: {"model": 37.50, "pg": 2.50, "db_read": 242.50},
    1_000_000: {"model": 125.00, "pg": 8.33, "db_read": 808.33},
}
EXPECTED_RATIO = {
    100_000: {"model": 1.34, "app_home2": 0.41, "db_read": 0.04, "db_write": 0.02},
    300_000: {"model": 4.01, "app_home2": 1.23, "db_read": 0.13, "db_write": 0.06},
    1_000_000: {"model": 13.38, "app_home2": 4.08, "db_read": 0.45, "db_write": 0.21},
}
EXPECTED_CROSS_MAU = {"model_home": 74_720, "model_home2": 224_160, "app_home2": 244_800}
EXPECTED_VERIFY = {
    "serial_pred": 9.35,
    "serial_c1": 9.03,
    "serial_c4": 9.34,
    "serial_err_c1": 3.45,
    "serial_err_c4": 0.09,
    "little_pred_ms": 428.37,
    "little_meas_ms": 420.75,
    "little_err": 1.81,
    "x6_half": 0.75,
    "x6_one": 1.23,
    "x6_one_and_half": 1.78,
    "x6_collapse": 12.18,
}


def peak_rps(mau: float) -> float:
    """MAU → peak RPS. 유도 체인 MAU → DAU → 일 요청 → 평균 RPS → peak."""
    dau = mau * DAU_PER_MAU
    daily = dau * REQUESTS_PER_USER_DAY
    average = daily / SECONDS_PER_DAY
    return average * PEAK_MULTIPLIER


def chain(mau: int) -> dict:
    dau = mau * DAU_PER_MAU
    daily = dau * REQUESTS_PER_USER_DAY
    average = daily / SECONDS_PER_DAY
    return {"dau": dau, "daily": daily, "avg_rps": average, "peak_rps": average * PEAK_MULTIPLIER}


def forced_flow(peak: float) -> dict:
    """peak RPS → 자원별 peak 수요 (docs/40 Forced Flow)."""
    return {
        "model": peak * HOME_SHARE,
        "pg": peak * API_MIX["결제 승인"],
        "db_read": peak * READ_SHARE,
        "db_write": peak * WRITE_SHARE,
    }


def ratios(peak: float) -> dict:
    demand = forced_flow(peak)
    return {
        "model": demand["model"] / MODEL_SUPPLY,
        "app_home2": (peak * HOME2_SHARE) / APP_HOME2_SUPPLY,
        "db_read": demand["db_read"] / DB_READ_KNEE,
        "db_write": demand["db_write"] / DB_WRITE_KNEE,
    }


def crossing_mau(share: float, supply: float = MODEL_SUPPLY) -> float:
    """공급 상한에 수요가 닿는 MAU. peak = MAU/1200 이므로 MAU = peak × 1200."""
    peak = supply / share
    return peak * (SECONDS_PER_DAY / (DAU_PER_MAU * REQUESTS_PER_USER_DAY * PEAK_MULTIPLIER))


def verify_serial() -> dict:
    predicted = 1.0 / (S_HYBRID_MS / 1000.0)
    return {
        "pred": predicted,
        "c1": X_C1,
        "c4": X_C4,
        "err_c1": abs(predicted - X_C1) / X_C1 * 100.0,
        "err_c4": abs(predicted - X_C4) / X_C4 * 100.0,
    }


def verify_littles() -> dict:
    predicted_ms = 4.0 / X_C4 * 1000.0
    return {
        "pred_ms": predicted_ms,
        "meas_ms": W_MEASURED_MS,
        "err": abs(predicted_ms - W_MEASURED_MS) / W_MEASURED_MS * 100.0,
    }


def verify_x6() -> dict:
    def occupancy(page_load: float) -> float:
        return X6_RECOMMEND / X6_C + page_load / X6_CP

    collapse = (1.0 - X6_RECOMMEND / X6_C) * X6_CP
    return {
        "half": occupancy(X6_PAGE_LOADS[0]),
        "one": occupancy(X6_PAGE_LOADS[1]),
        "one_and_half": occupancy(X6_PAGE_LOADS[2]),
        "collapse": collapse,
    }


def _close(computed: float, expected: float, decimals: int = 2) -> bool:
    return round(computed, decimals) == round(expected, decimals)


class Checker:
    def __init__(self) -> None:
        self.failures = 0
        self.checks = 0

    def equal(self, label: str, computed: float, expected: float, decimals: int = 2) -> None:
        self.checks += 1
        if _close(computed, expected, decimals):
            print(f"  PASS  {label}: {round(computed, decimals)} == {round(expected, decimals)}")
        else:
            self.failures += 1
            print(f"  FAIL  {label}: 계산 {round(computed, decimals)} != 기대 {round(expected, decimals)}")


def run_check() -> int:
    check = Checker()
    print("[b] 수요 가정과 유도 체인")
    for mau in MAU_STAGES:
        row = chain(mau)
        expected = EXPECTED_CHAIN[mau]
        check.equal(f"MAU {mau} DAU", row["dau"], expected["dau"], 0)
        check.equal(f"MAU {mau} 일 요청", row["daily"], expected["daily"], 0)
        check.equal(f"MAU {mau} 평균 RPS", row["avg_rps"], expected["avg_rps"])
        check.equal(f"MAU {mau} peak RPS", row["peak_rps"], expected["peak_rps"])

    print("[d] Forced Flow")
    for mau in MAU_STAGES:
        demand = forced_flow(peak_rps(mau))
        expected = EXPECTED_FORCED[mau]
        check.equal(f"MAU {mau} 모델 RPS", demand["model"], expected["model"])
        check.equal(f"MAU {mau} PG 호출률", demand["pg"], expected["pg"])
        check.equal(f"MAU {mau} DB 읽기 QPS", demand["db_read"], expected["db_read"])

    print("[f] 대조")
    for mau in MAU_STAGES:
        ratio = ratios(peak_rps(mau))
        expected = EXPECTED_RATIO[mau]
        check.equal(f"MAU {mau} 모델 서버 수요/공급", ratio["model"], expected["model"])
        check.equal(f"MAU {mau} 앱 홈 2쪽 경로 수요/공급", ratio["app_home2"], expected["app_home2"])
        check.equal(f"MAU {mau} DB 읽기 수요/공급", ratio["db_read"], expected["db_read"])
        check.equal(f"MAU {mau} DB 쓰기 수요/공급", ratio["db_write"], expected["db_write"])
    check.equal("모델 상한 도달 MAU(홈 전체)",
                crossing_mau(HOME_SHARE, MODEL_SUPPLY), EXPECTED_CROSS_MAU["model_home"], 0)
    check.equal("모델 상한 도달 MAU(홈 2쪽)",
                crossing_mau(HOME2_SHARE, MODEL_SUPPLY), EXPECTED_CROSS_MAU["model_home2"], 0)
    check.equal("앱 경로 상한 도달 MAU(홈 2쪽)",
                crossing_mau(HOME2_SHARE, APP_HOME2_SUPPLY), EXPECTED_CROSS_MAU["app_home2"], 0)

    print("[g] 기존 데이터로 모델 검증")
    serial = verify_serial()
    check.equal("잠금 직렬 X 예측", serial["pred"], EXPECTED_VERIFY["serial_pred"])
    check.equal("잠금 직렬 실측(동시성1)", serial["c1"], EXPECTED_VERIFY["serial_c1"])
    check.equal("잠금 직렬 실측(동시성4)", serial["c4"], EXPECTED_VERIFY["serial_c4"])
    check.equal("잠금 직렬 오차(동시성1)", serial["err_c1"], EXPECTED_VERIFY["serial_err_c1"])
    check.equal("잠금 직렬 오차(동시성4)", serial["err_c4"], EXPECTED_VERIFY["serial_err_c4"])
    little = verify_littles()
    check.equal("Little's Law W 예측(ms)", little["pred_ms"], EXPECTED_VERIFY["little_pred_ms"])
    check.equal("Little's Law 실측(ms)", little["meas_ms"], EXPECTED_VERIFY["little_meas_ms"])
    check.equal("Little's Law 오차(%)", little["err"], EXPECTED_VERIFY["little_err"])
    x6 = verify_x6()
    check.equal("X6 점유율 0.5Cp", x6["half"], EXPECTED_VERIFY["x6_half"])
    check.equal("X6 점유율 1.0Cp", x6["one"], EXPECTED_VERIFY["x6_one"])
    check.equal("X6 점유율 1.5Cp", x6["one_and_half"], EXPECTED_VERIFY["x6_one_and_half"])
    check.equal("X6 붕괴 2쪽 부하", x6["collapse"], EXPECTED_VERIFY["x6_collapse"])

    print()
    if check.failures:
        print(f"FAIL: {check.failures}/{check.checks} 항목이 문서 값과 어긋난다")
        return 1
    print(f"PASS: {check.checks}/{check.checks} 항목이 문서 값과 일치한다")
    return 0


def print_tables() -> None:
    print("[b] 수요 가정과 유도 체인")
    for mau in MAU_STAGES:
        row = chain(mau)
        print(f"  MAU {mau:>9,}: DAU {row['dau']:>10,.0f}  일 요청 {row['daily']:>12,.0f}"
              f"  평균 {row['avg_rps']:>7.2f}/s  peak {row['peak_rps']:>8.2f}/s")

    print("[c] API 믹스 (peak RPS)")
    mix_total = sum(API_MIX.values())
    print(f"  합계 {mix_total * 100:.0f}%")
    peaks = {mau: peak_rps(mau) for mau in MAU_STAGES}
    for name, share in API_MIX.items():
        cells = "  ".join(f"{peaks[mau] * share:>8.2f}" for mau in MAU_STAGES)
        print(f"  {name:<10} {share * 100:>4.0f}%   {cells}   (10만 / 30만 / 100만)")

    print("[d] Forced Flow (peak 수요)")
    for mau in MAU_STAGES:
        demand = forced_flow(peaks[mau])
        print(f"  MAU {mau:>9,}: 모델 {demand['model']:>8.2f}/s  PG {demand['pg']:>6.2f}/s"
              f"  DB 읽기 {demand['db_read']:>8.2f}/s  DB 쓰기 {demand['db_write']:>6.2f}/s")

    print("[f] 대조 (수요/공급, 1 초과면 모자람)")
    print(f"  공급: 모델 서버 1대 {MODEL_SUPPLY}/s · 앱 홈 2쪽 경로 {APP_HOME2_SUPPLY}/s"
          f" · DB 읽기 {DB_READ_KNEE:.0f}/s · DB 쓰기 {DB_WRITE_KNEE:.0f}/s · PG 미측정(환산 불가)")
    for mau in MAU_STAGES:
        ratio = ratios(peaks[mau])
        print(f"  MAU {mau:>9,}: 모델 {ratio['model']:>6.2f}  앱경로 {ratio['app_home2']:>5.2f}"
              f"  DB읽기 {ratio['db_read']:>5.2f}  DB쓰기 {ratio['db_write']:>5.2f}")
    print(f"  모델 상한 도달 MAU 홈 전체 {crossing_mau(HOME_SHARE, MODEL_SUPPLY):,.0f}"
          f" · 홈 2쪽 {crossing_mau(HOME2_SHARE, MODEL_SUPPLY):,.0f}"
          f" · 앱 경로 홈 2쪽 {crossing_mau(HOME2_SHARE, APP_HOME2_SUPPLY):,.0f}")

    print("[g] 기존 데이터로 모델 검증")
    serial = verify_serial()
    print(f"  잠금 직렬: 예측 {serial['pred']:.2f}/s  실측 {serial['c1']:.2f}(c1) · {serial['c4']:.2f}(c4)"
          f"  오차 {serial['err_c1']:.2f}% · {serial['err_c4']:.2f}%")
    little = verify_littles()
    print(f"  Little's Law: 예측 {little['pred_ms']:.2f}ms  실측 {little['meas_ms']:.2f}ms"
          f"  오차 {little['err']:.2f}%")
    x6 = verify_x6()
    print(f"  X6 점유율: {x6['half']:.2f} · {x6['one']:.2f} · {x6['one_and_half']:.2f}"
          f"  붕괴 2쪽 부하 {x6['collapse']:.2f}/s")


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true",
                        help="docs/40 에 적은 기대값과 계산 결과를 대조한다(하나라도 어긋나면 종료 코드 1)")
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    if args.check:
        return run_check()
    print_tables()
    return 0


if __name__ == "__main__":
    sys.exit(main())
