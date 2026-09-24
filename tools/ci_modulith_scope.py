#!/usr/bin/env python3
"""PR 통합 테스트에 줄 Spring Modulith 인자를 정한다(#278).

    python3 tools/ci_modulith_scope.py BASE HEAD    # → gradle 인자 한 줄

Modulith 는 바뀐 **제품** 클래스를 모듈에 대 보고 테스트를 고른다. 테스트 코드 변경은 모듈 변경으로 치지 않는다 —
바뀐 테스트 클래스 자신만 돌린다(1.3.12 `TestExecutionCondition`, #289 에서 확인). 그래서 **테스트 도우미**(이름이
`Test` 로 끝나지 않는 픽스처 · 지원 클래스)가 바뀌면, 모듈 안이든 밖이든 그 도우미를 쓰는 테스트가 하나도 안 돈 채
초록이 될 수 있다. 공용 컨테이너(SharedContainers)를 고친 PR 이 그렇게 될 뻔했다(#278, #290). 그런 파일이 바뀌면
최적화를 끈다. 판단할 수 없으면 끈다.
"""
import subprocess
import sys

TEST_ROOT = "commerce/src/test/java/com/beomsu/becommerce/"


def helpers(files):
    """바뀐 테스트 도우미 — 테스트 트리의 자바 파일 중 이름이 Test.java 로 끝나지 않는 것. 테스트 클래스 자신은 Modulith 가 돌린다."""
    return [p for p in files if p.startswith(TEST_ROOT) and p.endswith(".java") and not p.endswith("Test.java")]


def main():
    base, head = sys.argv[1], sys.argv[2]
    try:
        out = subprocess.run(["git", "diff", "--name-only", "-z", f"{base}...{head}"], capture_output=True, check=True).stdout
    except subprocess.CalledProcessError as e:
        print(f"변경 목록을 못 읽었다 — 전부 돌린다: {e}", file=sys.stderr)
        print("-Dspring.modulith.test.skip-optimizations=true")
        return
    files = [p for p in out.decode("utf-8").split("\0") if p]
    shared = helpers(files)
    if shared:
        print(f"테스트 도우미가 바뀌었다 — 전부 돌린다: {shared[:5]}", file=sys.stderr)
        print("-Dspring.modulith.test.skip-optimizations=true")
    else:
        print(f"모듈 기준으로 고른다(base {base[:7]})", file=sys.stderr)
        print(f"-Dspring.modulith.test.reference-commit={base}")


if __name__ == "__main__":
    main()
