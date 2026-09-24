#!/usr/bin/env python3
"""PR 통합 테스트에 줄 Spring Modulith 인자를 정한다(#278).

    python3 tools/ci_modulith_scope.py BASE HEAD    # → gradle 인자 한 줄

Modulith 는 바뀐 클래스를 모듈에 대 보고 테스트를 고른다. 그런데 어느 모듈에도 속하지 않는 **테스트 도우미**
(`testsupport` 처럼 본 코드에 없는 패키지, 루트 패키지의 테스트 코드)가 바뀌면 그 변경을 어느 모듈에도 대지 못해
모든 모듈 테스트를 "영향 없음"으로 건너뛸 수 있다. 공용 컨테이너(SharedContainers)를 고친 PR 이 통합 테스트를
하나도 안 돌리고 초록이 되는 것을 막으려고, 그런 파일이 바뀌면 최적화를 끈다. 판단할 수 없으면 끈다.
"""
import subprocess
import sys

TEST_ROOT = "commerce/src/test/java/com/beomsu/becommerce/"
MAIN_ROOT = "commerce/src/main/java/com/beomsu/becommerce/"


def main():
    base, head = sys.argv[1], sys.argv[2]
    try:
        out = subprocess.run(["git", "diff", "--name-only", "-z", f"{base}...{head}"], capture_output=True, check=True).stdout
        tracked = subprocess.run(["git", "ls-files", "-z", MAIN_ROOT], capture_output=True, check=True).stdout
    except subprocess.CalledProcessError as e:
        print(f"변경 목록을 못 읽었다 — 전부 돌린다: {e}", file=sys.stderr)
        print("-Dspring.modulith.test.skip-optimizations=true")
        return
    files = [p for p in out.decode("utf-8").split("\0") if p]
    modules = {p[len(MAIN_ROOT):].split("/")[0] for p in tracked.decode("utf-8").split("\0") if p.startswith(MAIN_ROOT) and "/" in p[len(MAIN_ROOT):]}
    # 모듈 밖에 있는 테스트 클래스(*Test.java) 자신은 Modulith 가 늘 돌린다. 문제는 여러 테스트가 쓰는 도우미다
    shared = [p for p in files if p.startswith(TEST_ROOT) and not p.endswith("Test.java")
              and ("/" not in p[len(TEST_ROOT):] or p[len(TEST_ROOT):].split("/")[0] not in modules)]
    if shared:
        print(f"모듈 밖 테스트 코드가 바뀌었다 — 전부 돌린다: {shared[:5]}", file=sys.stderr)
        print("-Dspring.modulith.test.skip-optimizations=true")
    else:
        print(f"모듈 기준으로 고른다(base {base[:7]})", file=sys.stderr)
        print(f"-Dspring.modulith.test.reference-commit={base}")


if __name__ == "__main__":
    main()
