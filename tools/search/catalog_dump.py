"""카탈로그를 MySQL 컨테이너에서 읽는다(#236). 색인과 평가가 같은 원본을 보게 한 곳에 둔다.

mysql -B 는 탭·줄바꿈·역슬래시를 이스케이프해 내보낸다. 그대로 쓰면 설명 안의 줄바꿈이 행을 쪼갠다.
"""
import os
import subprocess

CONTAINER = os.environ.get("MYSQL_CONTAINER", "pay-mysql-1")
COLUMNS = ("product_id", "name", "product_type", "description")


def _unescape(v: str) -> str:
    if v == "NULL":
        return ""
    return (v.replace("\\t", "\t").replace("\\n", "\n").replace("\\0", "\0").replace("\\\\", "\\"))


def load_products():
    sql = "SELECT " + ", ".join(COLUMNS) + " FROM products"
    out = subprocess.run(
        ["docker", "exec", CONTAINER, "sh", "-c",
         f'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -B -N becommerce -e "{sql}"'],
        check=True, capture_output=True, text=True).stdout
    rows = []
    for line in out.split("\n"):
        if not line:
            continue
        parts = line.split("\t")
        if len(parts) != len(COLUMNS):
            raise SystemExit(f"열 수가 다르다({len(parts)}): {line[:80]}")
        rows.append({"product_id": int(parts[0]), "name": _unescape(parts[1]),
                     "product_type": _unescape(parts[2]), "description": _unescape(parts[3])})
    return rows
