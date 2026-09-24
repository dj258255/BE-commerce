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


FULL_COLUMNS = ("product_id", "name", "product_type", "description", "category_code", "subcategory_code",
                "colour_code", "price", "in_stock")


def load_full():
    """필터 필드까지(#244). 재고는 stock 에 행이 없으면 있음으로 본다(앱과 같은 규칙)."""
    sql = ("SELECT p.product_id, p.name, p.product_type, p.description, p.category_code, p.subcategory_code, "
           "p.colour_code, p.price, COALESCE(s.quantity, 1) > 0 FROM products p LEFT JOIN stock s ON s.product_id = p.product_id")
    out = subprocess.run(
        ["docker", "exec", CONTAINER, "sh", "-c",
         f'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -B -N becommerce -e "{sql}"'],
        check=True, capture_output=True, text=True).stdout
    rows = []
    for line in out.split("\n"):
        if not line:
            continue
        v = [_unescape(x) for x in line.split("\t")]
        rows.append({"product_id": int(v[0]), "name": v[1], "product_type": v[2], "description": v[3],
                     "category_code": v[4] or None, "subcategory_code": v[5] or None, "colour_code": v[6] or None,
                     "price": int(v[7]), "in_stock": v[8] == "1"})
    return rows


def replicate(rows, times):
    """규모 실험용 합성 카탈로그: 실제 행을 times 번 복제하고 id 만 바꾼다(내용 분포는 그대로). 제너레이터다."""
    for k in range(times):
        for r in rows:
            yield {**r, "product_id": r["product_id"] + k * 10_000_000_000}


def write_tsv(rows, path):
    """Lucene 벤치마크(Java)가 읽는 형식. 탭·줄바꿈은 공백으로 바꾼다."""
    with open(path, "w") as f:
        for r in rows:
            vals = [r["product_id"], r["name"], r["product_type"], r["description"], r["category_code"] or "",
                    r["subcategory_code"] or "", r["colour_code"] or "", r["price"], 1 if r["in_stock"] else 0]
            f.write("\t".join(str(v).replace("\t", " ").replace("\n", " ") for v in vals) + "\n")
