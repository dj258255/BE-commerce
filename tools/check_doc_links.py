#!/usr/bin/env python3
"""마크다운의 저장소 내부 링크가 실제 파일을 가리키는지 검사한다.

왜 필요한가
-----------
디렉터리를 옮기면 링크는 **조용히** 깨진다. 빌드도 테스트도 안 잡는다. 읽는 사람만
404 를 만나고, 그건 보통 문서를 안 믿게 되는 시작점이다.

`commerce/` 재배치(#142)에서 실제로 겪었다 — `docs/adr/ADR-006` 의 `../../src/...` 처럼
깊이를 세어 둔 링크가 한 단계 어긋났다. 사람이 훑어서 찾을 양이 아니라 검사로 고정한다.

무엇을 보나
-----------
- 마크다운 인라인 링크 `[텍스트](경로)` 와 이미지 `![...](경로)`
- **저장소 안을 가리키는 것만.** `http(s)://`·`mailto:`·`#앵커`·`<...>` 자리표시자는 건너뛴다
- `#섹션` 조각은 떼고 파일 존재만 본다 — 앵커까지 보려면 제목 파싱이 필요한데,
  깨진 앵커는 404 가 아니라 '페이지 맨 위로' 이므로 피해가 다르다

사용
----
    python3 tools/check_doc_links.py            # 저장소 전체
    python3 tools/check_doc_links.py docs       # 일부만

깨진 링크가 하나라도 있으면 종료 코드 1.
"""
import os
import re
import subprocess
import sys
from urllib.parse import unquote

# [텍스트](대상) — 대상에 괄호가 없는 흔한 형태만 본다. 중첩 괄호까지 보려면 파서가 필요하고,
# 그건 이 검사가 잡으려는 문제(경로 이동)와 무관하다.
LINK = re.compile(r"!?\[[^\]]*\]\(([^()\s]+)(?:\s+\"[^\"]*\")?\)")

SKIP_PREFIX = ("http://", "https://", "mailto:", "#", "tel:", "data:")


def tracked_markdown(roots):
    """git 이 아는 .md 만 본다 — build/ 나 node_modules 의 사본을 세지 않으려는 것이다."""
    cmd = ["git", "ls-files", "-z"] + list(roots)
    out = subprocess.run(cmd, capture_output=True, check=True).stdout
    # -z 를 쓰는 이유: 한글 파일명을 git 이 따옴표로 감싸 이스케이프해 내보내기 때문이다.
    # 그걸 공백으로 쪼개면 한글 이름 문서가 통째로 검사에서 빠진다.
    return [p.decode("utf-8") for p in out.split(b"\0") if p.endswith(b".md")]


def check(path):
    broken = []
    with open(path, encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            for target in LINK.findall(line):
                if target.startswith(SKIP_PREFIX) or target.startswith("<"):
                    continue
                ref = unquote(target.split("#", 1)[0])
                if not ref:
                    continue          # 같은 문서 안 앵커
                base = os.path.dirname(path) if not ref.startswith("/") else "."
                resolved = os.path.normpath(os.path.join(base, ref.lstrip("/")))
                if not os.path.exists(resolved):
                    broken.append((lineno, target, resolved))
    return broken


def main(argv):
    roots = argv[1:] or ["."]
    files = tracked_markdown(roots)
    total = 0
    for f in files:
        for lineno, target, resolved in check(f):
            total += 1
            print(f"{f}:{lineno}: 깨진 링크 {target}  →  {resolved}")
    print(f"문서 {len(files)}개 · 깨진 링크 {total}건")
    return 1 if total else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
