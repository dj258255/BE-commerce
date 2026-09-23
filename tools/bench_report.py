"""k6 요약 JSON 들을 사람이 읽을 표로 바꾼다.

수치만 찍지 않고 <어떤 환경에서 쟀는지>를 항상 같이 낸다.
맥북에서 잰 처리량을 운영 기본값의 근거로 쓴 적이 있어서, 그 실수를 구조적으로 막는다.
"""
import json
import pathlib
import sys


def num(v):
    """k6 요약은 버전에 따라 값이 dict 안에 들어있기도 하다."""
    if isinstance(v, dict):
        return v.get('value', v.get('count'))
    return v


def metric(summary, name, field):
    m = summary.get('metrics', {}).get(name)
    if not isinstance(m, dict):
        return None
    return num(m.get(field))


def fmt(v, unit='', digits=1):
    if v is None:
        return '—'
    if isinstance(v, (int, float)):
        return f"{v:,.{digits}f}{unit}"
    return str(v)


def main(run_dir):
    d = pathlib.Path(run_dir)
    env_file = d / 'environment.txt'

    print(f"# 성능 실측 — {d.name}\n")

    if env_file.exists():
        print("## 측정 환경\n")
        print("```")
        print(env_file.read_text(encoding='utf-8').rstrip())
        print("```\n")
        print("> 이 수치는 위 환경의 값이다. 다른 환경 수치와 직접 비교하지 말고,")
        print("> 같은 스크립트(`./gradlew -p commerce bench`)로 그 환경에서 다시 재라.\n")

    summaries = sorted(d.glob('*-summary.json'))
    if not summaries:
        print("_k6 요약 파일이 없습니다._")
        return

    print("## 결과\n")
    print("| 프로파일 | 요청 | 실패율 | 처리량(req/s) | p95 | p99 | 최대 |")
    print("|---|---:|---:|---:|---:|---:|---:|")

    for s in summaries:
        name = s.name.replace('-summary.json', '')
        try:
            data = json.loads(s.read_text(encoding='utf-8'))
        except (json.JSONDecodeError, OSError) as e:
            print(f"| {name} | 읽기 실패: {e} | | | | | |")
            continue

        reqs = metric(data, 'http_reqs', 'count')
        rate = metric(data, 'http_reqs', 'rate')
        failed = metric(data, 'http_req_failed', 'value')
        p95 = metric(data, 'http_req_duration', 'p(95)')
        p99 = metric(data, 'http_req_duration', 'p(99)')
        mx = metric(data, 'http_req_duration', 'max')

        print(f"| {name} "
              f"| {fmt(reqs, digits=0)} "
              f"| {fmt(failed * 100 if failed is not None else None, '%', 2)} "
              f"| {fmt(rate)} "
              f"| {fmt(p95, 'ms')} "
              f"| {fmt(p99, 'ms')} "
              f"| {fmt(mx, 'ms')} |")

    print()
    print("## 판독 주의\n")
    print("- **처리량이 안 오르는데 p95만 오르기 시작하는 지점**이 무릎이다. 그 지점이 아니라 *그 전*을 본다.")
    print("- 5xx 가 보이면 이미 무릎을 한참 지난 것이다.")
    print("- 실패한 요청은 응답 시간 집계에서 빠지므로, **실패율이 오르면 p95 는 오히려 좋아 보인다.**")
    print("  실패율을 먼저 보고 그 다음에 지연을 봐라.")
    print("\n단계별 상세는 같은 폴더의 `*-k6.log` 에 있다.")


if __name__ == '__main__':
    if len(sys.argv) < 2:
        sys.exit("usage: bench_report.py <run-dir>")
    main(sys.argv[1])
