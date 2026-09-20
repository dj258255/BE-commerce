#!/usr/bin/env python3
"""H&M 상품 이미지를 Kaggle에서 받는다.

기본은 **크기 확인만**(dry-run)이다 — 30GB를 받기 전에 실제 용량을 먼저 본다.
키는 `~/.kaggle/kaggle.json`에서 읽고 **화면에 출력하지 않는다**.

전제: Kaggle에서 H&M 컴페티션의 **Rules를 수락**해야 한다(안 하면 403).

사용:
    python3 personalization/pipeline/fetch_hm_images.py            # 크기만 확인
    python3 personalization/pipeline/fetch_hm_images.py --download # 실제 다운로드
"""
from __future__ import annotations

import argparse
import base64
import json
import sys
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

COMPETITION = "h-and-m-personalized-fashion-recommendations"
KEY_PATH = Path.home() / ".kaggle" / "kaggle.json"
API = "https://www.kaggle.com/api/v1"


def load_auth() -> str:
    """Basic 인증 헤더를 만든다. 키 값은 절대 출력하지 않는다."""
    if not KEY_PATH.exists():
        print(f"키가 없다: {KEY_PATH}", file=sys.stderr)
        print("  Kaggle → Account → 'Create New API Token' → kaggle.json 을 ~/Downloads 에 둔 뒤", file=sys.stderr)
        print(f"  mkdir -p ~/.kaggle && mv ~/Downloads/kaggle.json {KEY_PATH} && chmod 600 {KEY_PATH}", file=sys.stderr)
        raise SystemExit(2)
    mode = KEY_PATH.stat().st_mode & 0o777
    if mode & 0o077:
        print(f"경고: {KEY_PATH} 권한이 {oct(mode)} 이다. chmod 600 을 권한다.", file=sys.stderr)
    creds = json.loads(KEY_PATH.read_text())
    token = base64.b64encode(f"{creds['username']}:{creds['key']}".encode()).decode()
    return f"Basic {token}"


def request(url: str, auth: str, extra: dict | None = None):
    req = urllib.request.Request(url, headers={"Authorization": auth, **(extra or {})})
    try:
        return urllib.request.urlopen(req, timeout=60)
    except urllib.error.HTTPError as e:
        if e.code in (401, 403):
            print(f"인증 실패({e.code}) — 컴페티션 Rules를 수락했는지 확인하라", file=sys.stderr)
        raise


def list_files(auth: str) -> list[dict]:
    with request(f"{API}/competitions/data/list/{COMPETITION}", auth) as r:
        return json.loads(r.read())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default="personalization/data")
    ap.add_argument("--download", action="store_true", help="실제로 받는다(기본은 크기 확인만)")
    args = ap.parse_args()

    auth = load_auth()
    try:
        files = list_files(auth)
    except Exception as e:
        print(f"파일 목록을 못 가져왔다: {e}", file=sys.stderr)
        return 1

    print(f"컴페티션 {COMPETITION} 파일 목록:")
    total = 0
    for f in files:
        size = int(f.get("totalBytes") or f.get("size") or 0)
        total += size
        print(f"  {f.get('name', '?'):45s} {size/1e9:>8.2f} GB")
    print(f"  {'합계':45s} {total/1e9:>8.2f} GB")

    if not args.download:
        print("\n크기 확인만 했다. 받으려면 --download")
        return 0

    root = Path(args.data_dir) / "hm" / "raw"
    root.mkdir(parents=True, exist_ok=True)
    for f in files:
        name = f.get("name", "")
        if not name.endswith(".zip"):
            continue
        dest = root / name
        size = int(f.get("totalBytes") or f.get("size") or 0)
        if dest.exists() and dest.stat().st_size == size:
            print(f"  건너뜀(이미 있음) {name}")
            continue
        print(f"  받는 중 {name} ({size/1e9:.2f} GB)")
        tmp = dest.with_suffix(dest.suffix + ".part")
        with request(f"{API}/competitions/data/download/{COMPETITION}/{name}", auth) as r, open(tmp, "wb") as out:
            done = 0
            while chunk := r.read(1 << 20):
                out.write(chunk)
                done += len(chunk)
                if size:
                    print(f"\r    {done/size*100:5.1f}% ({done/1e9:.2f}/{size/1e9:.2f} GB)", end="", flush=True)
        print()
        tmp.replace(dest)

    # 이미지 압축 해제(멱등: 이미 있는 파일은 건너뛴다)
    images = Path(args.data_dir) / "hm" / "images"
    images.mkdir(parents=True, exist_ok=True)
    for z in root.glob("*.zip"):
        print(f"  푸는 중 {z.name}")
        with zipfile.ZipFile(z) as zf:
            for member in zf.namelist():
                if not member.lower().endswith(".jpg"):
                    continue
                target = images / Path(member).name
                if target.exists():
                    continue
                with zf.open(member) as src, open(target, "wb") as out:
                    out.write(src.read())
    print(f"이미지 {len(list(images.glob('*.jpg'))):,}장 → {images}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
