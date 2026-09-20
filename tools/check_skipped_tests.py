#!/usr/bin/env python3
"""JUnit XML에서 건너뛴 테스트를 찾아 실패시킨다.

왜 필요한가
-----------
"테스트가 초록이다"는 검증이 아니다. 환경변수·키 파일·아티팩트가 없으면 테스트는 조용히
skip되고 빌드는 성공한다. 그러면 **검증하지 않은 기능이 초록불 아래로 나간다.**

DBTower에서 실제로 겪은 일이다 — SQL Server 대량 변경 테스트를 만들었지만 CI에 필요한
환경변수가 없어 테스트 전체가 skip되고도 빌드가 성공했다. 그래서 기준을
"테스트가 성공했는가"에서 **"검증하기로 한 테스트가 실제로 실행되고 성공했는가"**로 바꾼다.

사용
----
    python3 tools/check_skipped_tests.py build/test-results/test ci/allowed-skips.txt

허용목록 형식(한 줄에 하나):
    <패턴>    # 이유
패턴은 fnmatch 와일드카드를 쓸 수 있다(`*`). `#` 뒤는 주석으로 무시한다.
의도적으로 건너뛰는 테스트는 **이유를 반드시 적는다** — 이유 없는 예외는 다음 사람이 못 지운다.
"""
import fnmatch
import glob
import os
import sys
import xml.etree.ElementTree as ET


def load_allowlist(path):
    rules = []
    if not path or not os.path.exists(path):
        return rules
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            pattern, _, reason = line.partition("#")
            pattern = pattern.strip()
            if not pattern:
                continue
            rules.append((pattern, reason.strip() or "(이유 없음)"))
    return rules


def collect(results_dir):
    tests = 0
    skipped = []
    files = sorted(glob.glob(os.path.join(results_dir, "*.xml")))
    for path in files:
        root = ET.parse(path).getroot()
        tests += int(root.get("tests", 0))
        for tc in root.iter("testcase"):
            node = tc.find("skipped")
            if node is None:
                continue
            name = f"{tc.get('classname')}.{tc.get('name')}"
            message = (node.get("message") or "").strip().replace("\n", " ")
            skipped.append((name, message))
    return files, tests, skipped


def main(argv):
    results_dir = argv[1] if len(argv) > 1 else "build/test-results/test"
    allowlist_path = argv[2] if len(argv) > 2 else "ci/allowed-skips.txt"

    if not os.path.isdir(results_dir):
        print(f"::error::결과 디렉터리가 없습니다: {results_dir} — 테스트가 돌지 않았을 수 있습니다.", file=sys.stderr)
        return 1

    files, tests, skipped = collect(results_dir)

    # 가드 1: 테스트가 하나도 안 돌았으면 초록이 아니다.
    if tests == 0:
        print(f"::error::테스트가 0건입니다. 스위트가 통째로 건너뛰어졌는지 확인하세요 ({results_dir}).", file=sys.stderr)
        return 1

    rules = load_allowlist(allowlist_path)

    def allowed(name):
        return any(fnmatch.fnmatch(name, pattern) for pattern, _ in rules)

    unexpected = [(n, m) for n, m in skipped if not allowed(n)]

    print(f"결과 파일 {len(files)}개 · 테스트 {tests}건 · 건너뜀 {len(skipped)}건")
    for name, message in skipped:
        if allowed(name):
            reason = next(r for p, r in rules if fnmatch.fnmatch(name, p))
            print(f"  [허용] {name} — {reason}")
        else:
            print(f"  [실행 안 됨] {name}" + (f" — {message}" if message else ""))

    if unexpected:
        print("", file=sys.stderr)
        for name, _ in unexpected:
            print(f"::error::검증하기로 한 테스트가 실행되지 않았습니다: {name}", file=sys.stderr)
        print(
            "건너뛴 테스트는 통과가 아닙니다. 실행되게 고치거나, 의도적이면 "
            f"{allowlist_path} 에 이유와 함께 추가하세요.",
            file=sys.stderr,
        )
        return 1

    print("실행하기로 한 테스트가 모두 실제로 돌았습니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
