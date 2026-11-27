#!/usr/bin/env python3
"""두 커밋 사이에 문서가 아닌 파일이 바뀌었는지 GitHub Actions 출력 형식으로 적는다(#274).

    python3 tools/ci_changed_code.py BASE HEAD      # → code=true | code=false

문서 = docs/** · personalization/docs/** · 확장자 .md. 그 밖의 파일이 하나라도 바뀌면 code=true 다.
data/** 는 자바 빌드와 무관한 데이터 실험이라 문서처럼 본다. 다만 원본 스키마 사본(data/compose/init/mysql/**)은
DataSchemaContractMySqlTest 가 마이그레이션과 대조하므로 코드로 본다.
판단할 수 없으면(커밋을 못 찾는 등) code=true 로 둔다 — 건너뛰는 쪽으로 틀리면 검사 없이 머지된다.
"""
import fnmatch
import subprocess
import sys

DOCS = ("docs/*", "personalization/docs/*", "*.md", "data/*")
CODE = ("data/compose/init/mysql/*",)   # DOCS 에 걸려도 코드로 본다


def changed(base, head):
    out = subprocess.run(["git", "diff", "--name-only", "-z", f"{base}...{head}"], capture_output=True, check=True).stdout
    return [p for p in out.decode("utf-8").split("\0") if p]   # -z: 한글 이름을 이스케이프하지 않는다


def main():
    try:
        files = changed(sys.argv[1], sys.argv[2])
    except (IndexError, subprocess.CalledProcessError) as e:
        print(f"변경 목록을 못 읽었다 — 통합 테스트를 돌린다: {e}", file=sys.stderr)
        print("code=true")
        return
    code = [f for f in files
            if any(fnmatch.fnmatch(f, c) for c in CODE) or not any(fnmatch.fnmatch(f, d) for d in DOCS)]
    print(f"바뀐 파일 {len(files)}개 · 문서가 아닌 것 {len(code)}개 {code[:5]}", file=sys.stderr)
    print(f"code={'true' if code or not files else 'false'}")


if __name__ == "__main__":
    main()
